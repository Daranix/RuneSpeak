package com.runespeak.translate;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class OnnxTranslationEngine {

    private static final String HF_BASE = "https://huggingface.co/%s/resolve/main/%s";
    private static final String[] REQUIRED_FILES = {
            "encoder_model.onnx", "decoder_model.onnx", "tokenizer.json"
    };

    @Getter
    private volatile boolean loaded = false;
    @Getter
    private volatile boolean loading = false;
    @Getter
    private String currentModelId;

    private OrtEnvironment ortEnv;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private HuggingFaceTokenizer tokenizer;

    private int decoderStartTokenId;
    private int eosTokenId;
    private int maxLength = 128;

    private final Path modelsDir;

    public OnnxTranslationEngine(Path runespeakDir) {
        this.modelsDir = runespeakDir.resolve("models");
    }

    public synchronized void loadModel(String modelId) throws IOException {
        log.info("Loading model: {}", modelId);
        if (loaded && currentModelId != null && currentModelId.equals(modelId)) {
            log.info("Model {} already loaded, skipping", modelId);
            return;
        }
        if (loading) {
            log.info("Model {} already loading, skipping", modelId);
            return;
        }

        log.info("Shutting down previous model (if any)");
        shutdown();
        loading = true;
        currentModelId = modelId;
        log.info("Set loading=true, currentModelId={}", modelId);

        try {
            log.info("Creating models directory: {}", modelsDir);
            Files.createDirectories(modelsDir);
            Path modelPath = modelsDir.resolve(sanitizeModelId(modelId));
            log.info("Model path: {}", modelPath);

            log.info("Downloading encoder_model.onnx");
            downloadIfMissing(modelId, modelPath, "encoder_model.onnx");
            log.info("Downloading decoder_model.onnx");
            downloadIfMissing(modelId, modelPath, "decoder_model.onnx");
            log.info("Downloading tokenizer.json");
            downloadIfMissing(modelId, modelPath, "tokenizer.json");

            log.info("Creating OrtEnvironment");
            ortEnv = OrtEnvironment.getEnvironment();
            log.info("OrtEnvironment created: {}", ortEnv);

            log.info("Creating session options");
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(2);
            opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);
            log.info("Session options created");

            log.info("Creating encoder session from: {}", modelPath.resolve("encoder_model.onnx"));
            encoderSession = ortEnv.createSession(
                    modelPath.resolve("encoder_model.onnx").toString(), opts);
            log.info("Encoder session created: {}", encoderSession);

            log.info("Creating decoder session from: {}", modelPath.resolve("decoder_model.onnx"));
            decoderSession = ortEnv.createSession(
                    modelPath.resolve("decoder_model.onnx").toString(), opts);
            log.info("Decoder session created: {}", decoderSession);

            log.info("Logging encoder inputs: {}", encoderSession.getInputInfo().keySet());
            log.info("Logging decoder inputs: {}", decoderSession.getInputInfo().keySet());

            Map<String, String> tokOpts = new HashMap<>();
            tokOpts.put("addSpecialTokens", "false");
            log.info("Creating HuggingFaceTokenizer from: {}", modelPath.resolve("tokenizer.json"));
            tokenizer = HuggingFaceTokenizer.newInstance(
                    modelPath.resolve("tokenizer.json"), tokOpts);
            log.info("Tokenizer created: {}", tokenizer);

            log.info("Checking for config.json");
            downloadIfMissing(modelId, modelPath, "config.json");
            Path configPath = modelPath.resolve("config.json");
            if (Files.exists(configPath)) {
                log.info("Reading config from: {}", configPath);
                readConfig(configPath);
            } else {
                log.info("No config.json found, using defaults");
                decoderStartTokenId = 0;
                eosTokenId = 1;
            }

            loaded = true;
            log.info("ONNX model loaded successfully: {} (start={}, eos={}, max={})",
                    modelId, decoderStartTokenId, eosTokenId, maxLength);
        } catch (Exception e) {
            loaded = false;
            log.error("Failed to load model {}: {}", modelId, e.getMessage(), e);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to load model: " + modelId, e);
        } finally {
            loading = false;
            log.info("Set loading=false for model {}", modelId);
        }
    }

    private void downloadIfMissing(String modelId, Path modelPath, String filename)
            throws IOException {
        Files.createDirectories(modelPath);
        Path filePath = modelPath.resolve(filename);
        if (Files.exists(filePath) && Files.size(filePath) > 1000) return;

        String url = String.format(HF_BASE, modelId, filename);
        log.info("Downloading {} from {}", filename, url);
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try (ReadableByteChannel in = Channels.newChannel(new URL(url).openStream());
             FileOutputStream out = new FileOutputStream(tmp.toFile())) {
            out.getChannel().transferFrom(in, 0, Long.MAX_VALUE);
        }
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Downloaded {} ({})", filename, humanSize(Files.size(filePath)));
    }

    private void readConfig(Path configPath) throws IOException {
        String content = new String(Files.readAllBytes(configPath));
        decoderStartTokenId = extractInt(content, "\"decoder_start_token_id\"", 0);
        eosTokenId = extractInt(content, "\"eos_token_id\"", 1);
        maxLength = extractInt(content, "\"max_length\"", 128);
    }

    private static int extractInt(String json, String key, int def) {
        int idx = json.indexOf(key);
        if (idx < 0) return def;
        idx = json.indexOf(':', idx + key.length());
        if (idx < 0) return def;
        StringBuilder sb = new StringBuilder();
        for (int i = idx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == ',' || c == '}' || c == ']' || c == '\n' || c == ' ') break;
            if (Character.isDigit(c) || c == '-') sb.append(c);
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : def;
    }

    public CompletableFuture<String> translate(String text, String srcLang, String tgtLang) {
        CompletableFuture<String> future = new CompletableFuture<>();
        if (text == null || text.isBlank()) {
            future.complete(text);
            return future;
        }
        if (!loaded) {
            future.complete("\u23F3 " + text);
            return future;
        }
        long t0 = System.currentTimeMillis();
        try {
            String result = translateSync(text, srcLang, tgtLang);
            log.info("translate {}->{} '{}' -> '{}' in {}ms",
                    srcLang, tgtLang, truncate(text, 30), truncate(result, 30),
                    System.currentTimeMillis() - t0);
            future.complete(result);
        } catch (Exception e) {
            log.error("Translation failed: {}", e.getMessage());
            future.complete(text);
        }
        return future;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String translateSync(String text, String srcLang, String tgtLang) throws Exception {
        String langMap = srcLang + " to " + tgtLang;
        String prefix = "translate " + langMap + ": ";
        String inputText = prefix + text;

        long t0 = System.currentTimeMillis();
        Encoding encoding = tokenizer.encode(inputText);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        log.info("Tokenized '{}' -> {} tokens", truncate(inputText, 50), inputIds.length);

        if (inputIds.length == 0) return text;

        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, new long[][]{inputIds});
        OnnxTensor maskTensor = OnnxTensor.createTensor(ortEnv, new long[][]{attentionMask});

        Map<String, OnnxTensor> encoderInputs = new LinkedHashMap<>();
        encoderInputs.put("input_ids", inputTensor);
        encoderInputs.put("attention_mask", maskTensor);

        OrtSession.Result encoderOutput = encoderSession.run(encoderInputs);
        OnnxTensor encoderHiddenState = (OnnxTensor) encoderOutput.get("last_hidden_state").get();

        List<Long> outputTokenIds = new ArrayList<>();
        outputTokenIds.add((long) decoderStartTokenId);

        for (int i = 0; i < maxLength; i++) {
            long[][] decoderInputIds = new long[][]{
                    outputTokenIds.stream().mapToLong(l -> l).toArray()
            };
            OnnxTensor decoderInput = OnnxTensor.createTensor(ortEnv, decoderInputIds);
            OnnxTensor decMaskTensor = OnnxTensor.createTensor(ortEnv, new long[][]{attentionMask});

            Map<String, OnnxTensor> decoderInputs = new LinkedHashMap<>();
            decoderInputs.put("input_ids", decoderInput);
            decoderInputs.put("encoder_hidden_states", encoderHiddenState);
            decoderInputs.put("encoder_attention_mask", decMaskTensor);

            OrtSession.Result decoderOutput = decoderSession.run(decoderInputs);
            OnnxTensor logitsTensor = (OnnxTensor) decoderOutput.get("logits").get();

            float[][][] logitsData = (float[][][]) logitsTensor.getValue();
            float[] lastLogits = logitsData[0][logitsData[0].length - 1];

            int nextToken = argmax(lastLogits);
            if (nextToken == eosTokenId) break;
            outputTokenIds.add((long) nextToken);
        }

        long[] resultIds = outputTokenIds.stream()
                .skip(1)
                .mapToLong(l -> l)
                .toArray();
        String result = tokenizer.decode(resultIds);
        return result != null && !result.isBlank() ? result.trim() : text;
    }

    private static int argmax(float[] logits) {
        int best = 0;
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > logits[best]) best = i;
        }
        return best;
    }

    private static String sanitizeModelId(String modelId) {
        return modelId.replace('/', '_').replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public void shutdown() {
        loaded = false;
        try { if (encoderSession != null) encoderSession.close(); } catch (Exception ignored) {}
        try { if (decoderSession != null) decoderSession.close(); } catch (Exception ignored) {}
        encoderSession = null;
        decoderSession = null;
        tokenizer = null;
    }
}
