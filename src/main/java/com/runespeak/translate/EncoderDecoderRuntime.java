package com.runespeak.translate;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.*;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
public class EncoderDecoderRuntime implements ModelRuntime {

    private final String modelId;

    @Getter
    private volatile boolean loaded = false;
    @Getter
    private volatile boolean isLoading = false;
    @Getter
    private String currentModelId;

    private OrtEnvironment ortEnv;
    private OrtSession encoderSession;
    private OrtSession decoderSession;
    private HuggingFaceTokenizer tokenizer;

    private int decoderStartTokenId;
    private int eosTokenId;
    private int maxLength = 128;

    public EncoderDecoderRuntime(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public String getModelId() {
        return modelId;
    }

    @Override
    public List<DownloadFile> getRequiredFiles() {
        return List.of(
                new DownloadFile("onnx", "encoder_model.onnx"),
                new DownloadFile("onnx", "decoder_model.onnx"),
                new DownloadFile("", "tokenizer.json"),
                new DownloadFile("", "config.json")
        );
    }

    @Override
    public void load(Path modelPath, OrtEnvironment ortEnv) throws IOException {
        this.ortEnv = ortEnv;
        isLoading = true;
        currentModelId = modelId;

        try {
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            int threads = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            opts.setIntraOpNumThreads(threads);
            opts.setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_WARNING);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.EXTENDED_OPT);

            boolean gpuEnabled = false;
            try {
                opts.addCUDA(0);
                gpuEnabled = true;
                log.info("CUDA execution provider enabled");
            } catch (OrtException e) {
                log.info("CUDA EP not available: {}", e.getMessage());
            }
            if (!gpuEnabled) {
                try {
                    opts.addDirectML(0);
                    gpuEnabled = true;
                    log.info("DirectML execution provider enabled");
                } catch (OrtException e) {
                    log.info("DirectML EP not available: {}", e.getMessage());
                }
            }
            log.info("Creating ORT encoder/decoder sessions with {} threads{}",
                    threads, gpuEnabled ? " + GPU" : "");

            encoderSession = ortEnv.createSession(
                    modelPath.resolve("encoder_model.onnx").toString(), opts);
            decoderSession = ortEnv.createSession(
                    modelPath.resolve("decoder_model.onnx").toString(), opts);

            log.debug("Encoder Inputs: {}", encoderSession.getInputNames());
            log.debug("Encoder Outputs: {}", encoderSession.getOutputNames());
            log.debug("Decoder Inputs: {}", decoderSession.getInputNames());
            log.debug("Decoder Outputs: {}", decoderSession.getOutputNames());

            Map<String, String> tokOpts = new HashMap<>();
            tokOpts.put("addSpecialTokens", "true");
            tokenizer = HuggingFaceTokenizer.newInstance(
                    modelPath.resolve("tokenizer.json"), tokOpts);

            Path configPath = modelPath.resolve("config.json");
            if (Files.exists(configPath)) {
                readConfig(configPath);
            } else {
                decoderStartTokenId = 0;
                eosTokenId = 1;
            }

            loaded = true;
            log.info("EncoderDecoder model loaded: {} (start={}, eos={}, max={})",
                    modelId, decoderStartTokenId, eosTokenId, maxLength);
        } catch (Exception e) {
            loaded = false;
            log.error("Failed to load EncoderDecoder model {}: {}", modelId, e.getMessage(), e);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Failed to load model: " + modelId, e);
        } finally {
            isLoading = false;
        }
    }

    @Override
    public CompletableFuture<String> translate(String text, String srcLang, String tgtLang) {
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        if (!loaded) {
            return CompletableFuture.completedFuture("\u23F3 " + text);
        }

        long t0 = System.currentTimeMillis();
        try {
            String result = translateSync(text, srcLang, tgtLang);
            log.info("EncoderDecoder translate {}->{} '{}' -> '{}' in {}ms",
                    srcLang, tgtLang, truncate(text, 30), truncate(result, 30),
                    System.currentTimeMillis() - t0);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("EncoderDecoder translation failed: {}", e.getMessage());
            return CompletableFuture.completedFuture(text);
        }
    }

    private String translateSync(String text, String srcLang, String tgtLang) throws Exception {
        String inputText = text;

        Encoding encoding = tokenizer.encode(inputText);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        if (inputIds.length == 0) return text;

        int actualDecoderStart = decoderStartTokenId;
        List<Long> outputTokenIds = new ArrayList<>();
        outputTokenIds.add((long) actualDecoderStart);

        try (OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, new long[][]{inputIds});
             OnnxTensor maskTensor = OnnxTensor.createTensor(ortEnv, new long[][]{attentionMask});
             OrtSession.Result encoderOutput = encoderSession.run(Map.of("input_ids", inputTensor, "attention_mask", maskTensor))) {

            OnnxTensor encoderHiddenState = (OnnxTensor) encoderOutput.get("last_hidden_state").get();

            for (int i = 0; i < maxLength && outputTokenIds.size() < maxLength + 1; i++) {
                long[][] decoderInputIds = new long[][]{
                        outputTokenIds.stream().mapToLong(l -> l).toArray()
                };
                try (OnnxTensor decoderInput = OnnxTensor.createTensor(ortEnv, decoderInputIds);
                     OnnxTensor decMaskTensor = OnnxTensor.createTensor(ortEnv, new long[][]{attentionMask});
                     OrtSession.Result decoderOutput = decoderSession.run(Map.of(
                             "input_ids", decoderInput,
                             "encoder_hidden_states", encoderHiddenState,
                             "encoder_attention_mask", decMaskTensor
                     ))) {

                    OnnxTensor logitsTensor = (OnnxTensor) decoderOutput.get("logits").get();
                    float[][][] logitsData = (float[][][]) logitsTensor.getValue();
                    float[] lastLogits = logitsData[0][logitsData[0].length - 1];

                    int nextToken = argmax(lastLogits);
                    if (nextToken == eosTokenId) break;
                    outputTokenIds.add((long) nextToken);
                }
            }
        }

        long[] resultIds = outputTokenIds.stream()
                .skip(1)
                .mapToLong(l -> l)
                .toArray();
        String result = tokenizer.decode(resultIds);
        return result != null && !result.isBlank() ? result.trim() : text;
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
        boolean started = false;
        for (int i = idx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '-' || Character.isDigit(c)) {
                sb.append(c);
                started = true;
            } else if (started) {
                if (c == ',' || c == '}' || c == ']' || c == '\n') break;
            }
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : def;
    }

    private static int argmax(float[] logits) {
        int best = 0;
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > logits[best]) best = i;
        }
        return best;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Override
    public void shutdown() {
        loaded = false;
        try { if (encoderSession != null) encoderSession.close(); } catch (Exception ignored) {}
        try { if (decoderSession != null) decoderSession.close(); } catch (Exception ignored) {}
        encoderSession = null;
        decoderSession = null;
        tokenizer = null;
    }
}
