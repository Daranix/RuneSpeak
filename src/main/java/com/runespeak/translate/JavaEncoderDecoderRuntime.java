package com.runespeak.translate;

import com.google.gson.Gson;
import com.runespeak.translate.tensor.Tensor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class JavaEncoderDecoderRuntime implements ModelRuntime {

    private final String modelId;
    private final Gson gson;
    @Getter
    private volatile boolean loaded = false;
    @Getter
    private volatile boolean isLoading = false;
    @Getter
    private String currentModelId;

    private BpeTokenizer tokenizer;
    private Map<String, Tensor> encoderWeights;
    private Map<String, Tensor> decoderWeights;

    private static final boolean VERBOSE = "true".equals(System.getProperty("runespeak.verbose"));

    private void vlog(String format, Object... args) {
        if (VERBOSE) log.info(format, args);
    }

    private int decoderStartTokenId;
    private int eosTokenId;
    private int maxLength = 128;

    // Cached transposed LM head (transpose once, not every step)
    private Tensor lmHeadWeightT;

    // Stored prev hidden state for step-over-step cosine similarity
    private Tensor prevHiddenState;

    // KV cache for autoregressive decode
    private static class KvCache {
        Tensor[] selfK, selfV, crossK, crossV;
        Tensor encoderTensor;
        int encLen;
        int step;
        KvCache() {
            selfK = new Tensor[NUM_LAYERS];
            selfV = new Tensor[NUM_LAYERS];
            crossK = new Tensor[NUM_LAYERS];
            crossV = new Tensor[NUM_LAYERS];
        }
    }

    private KvCache kvCache;

    private static final int D_MODEL = 512;
    private static final int NUM_HEADS = 8;
    private static final int HEAD_DIM = D_MODEL / NUM_HEADS;
    private static final int FFN_DIM = 2048;
    private static final int NUM_LAYERS = 6;
    private static final float SCALE = (float) Math.sqrt(D_MODEL);
    private static final int MAX_INPUT_TOKENS = 128;
    private static final float REPETITION_PENALTY = 1.2f;

    public JavaEncoderDecoderRuntime(String modelId) {
        this(modelId, new Gson());
    }

    public JavaEncoderDecoderRuntime(String modelId, Gson gson) {
        this.modelId = modelId;
        this.gson = gson;
    }

    @Override
    public String getModelId() { return modelId; }

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
    public void load(Path modelPath) throws IOException {
        this.isLoading = true;
        this.currentModelId = modelId;

        try {
            log.info("Loading encoder weights from {}/onnx/encoder_model.onnx", modelPath);
            Path encoderPath = modelPath.resolve("encoder_model.onnx");
            if (!encoderPath.toFile().exists()) {
                encoderPath = modelPath.resolve("onnx").resolve("encoder_model.onnx");
            }
            this.encoderWeights = new OnnxWeightReader(encoderPath).getAll();
            log.debug("Encoder weights: {}", encoderWeights.keySet());

            log.info("Loading decoder weights from {}/onnx/decoder_model.onnx", modelPath);
            Path decoderPath = modelPath.resolve("decoder_model.onnx");
            if (!decoderPath.toFile().exists()) {
                decoderPath = modelPath.resolve("onnx").resolve("decoder_model.onnx");
            }
            this.decoderWeights = new OnnxWeightReader(decoderPath).getAll();
            log.debug("Decoder weights: {}", decoderWeights.keySet());

            Path tokenizerPath = modelPath.resolve("tokenizer.json");
            this.tokenizer = new BpeTokenizer(gson, tokenizerPath);
            log.info("Tokenizer loaded, vocab size: {}", tokenizer.getVocabSize());

            Path configPath = modelPath.resolve("config.json");
            if (configPath.toFile().exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(configPath));
                decoderStartTokenId = extractInt(content, "\"decoder_start_token_id\"", 0);
                eosTokenId = extractInt(content, "\"eos_token_id\"", 1);
                maxLength = extractInt(content, "\"max_length\"", 30);
            } else {
                decoderStartTokenId = 0;
                eosTokenId = 1;
                maxLength = 30;
            }
            if (maxLength > 200) maxLength = 200;

            // Patch zero embeddings: if decoder_start_token_id row is all zeros, copy EOS embedding
            patchZeroEmbeddings(decoderStartTokenId, eosTokenId);

            // Cache transposed LM head (avoids 133MB copy per decoder step)
            Tensor lmHead = findWeightAny(decoderWeights, "lm_head.weight", "model.lm_head.weight", "model.shared.weight");
            if (lmHead == null) {
                lmHead = findWeightAny(encoderWeights, "shared.weight", "model.encoder.embed_tokens.weight");
            }
            if (lmHead == null) {
                lmHead = findWeightAny(decoderWeights, "shared.weight", "model.decoder.embed_tokens.weight");
            }
            lmHeadWeightT = lmHead != null ? lmHead.transpose() : null;

            this.loaded = true;
            log.info("JavaEncoderDecoder loaded: {} (start={}, eos={}, max={})",
                    modelId, decoderStartTokenId, eosTokenId, maxLength);
        } catch (Exception e) {
            loaded = false;
            log.error("Failed to load model {}: {}", modelId, e.getMessage(), e);
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
            String result = translateSync(text);
            log.info("JavaEncoderDecoder translate {}->{} '{}' -> '{}' in {}ms",
                    srcLang, tgtLang, truncate(text, 30), truncate(result, 30),
                    System.currentTimeMillis() - t0);
            return CompletableFuture.completedFuture(result);
        } catch (Exception e) {
            log.error("JavaEncoderDecoder translation failed: {}", e.getMessage());
            return CompletableFuture.completedFuture(text);
        }
    }

    private String translateSync(String text) {
        int[] inputIds = tokenizer.encode(text);
        if (inputIds.length == 0) return text;

        // Truncate very long inputs to prevent quality degradation and excessive latency
        if (inputIds.length > MAX_INPUT_TOKENS) {
            int[] truncated = new int[MAX_INPUT_TOKENS];
            System.arraycopy(inputIds, 0, truncated, 0, MAX_INPUT_TOKENS);
            inputIds = truncated;
            log.debug("Truncated input from {} to {} tokens", inputIds.length > MAX_INPUT_TOKENS ? "?" : "", MAX_INPUT_TOKENS);
        }

        // Append EOS token (</s>) to encoder input, matching HuggingFace convention
        long[] inputLongs = new long[inputIds.length + 1];
        for (int i = 0; i < inputIds.length; i++) inputLongs[i] = inputIds[i];
        inputLongs[inputIds.length] = eosTokenId;

        float[] encoderHidden = encoderForward(inputLongs);
        kvCache = null; // Reset KV cache for new translation
        prevHiddenState = null;

        List<Long> outputTokenIds = new ArrayList<>();
        outputTokenIds.add((long) decoderStartTokenId);

        for (int i = 0; i < maxLength; i++) {
            long[] decoderInput = outputTokenIds.stream().mapToLong(l -> l).toArray();
            float[] logits = decoderForward(decoderInput, encoderHidden);

            // Apply repetition penalty: discourage the model from reusing previously generated tokens
            if (REPETITION_PENALTY != 1.0f && !outputTokenIds.isEmpty()) {
                for (long prevId : outputTokenIds) {
                    int idx = (int) prevId;
                    if (idx >= 0 && idx < logits.length) {
                        logits[idx] /= REPETITION_PENALTY;
                    }
                }
            }

            int nextToken = argmax(logits);
            if (i < 5 || nextToken == eosTokenId) {
                log.debug("decoder step {}: generated token {} (eos={})", i, nextToken, eosTokenId);
            }
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

    private float[] encoderForward(long[] inputIds) {
        int seqLen = inputIds.length;
        Tensor embedding = embed(inputIds, "shared.weight", "model.encoder.embed_tokens.weight", "encoder.embed_tokens.weight", "embed_tokens.weight");

        Tensor posEmbed = findWeightAny(encoderWeights,
                "model.encoder.embed_positions.weight",
                "encoder.embed_positions.weight",
                "embedded_positions.weight");
        if (posEmbed == null) {
            posEmbed = findWeightAny(decoderWeights,
                    "model.decoder.embed_positions.weight",
                    "decoder.embed_positions.weight");
        }

        Tensor hidden = addEmbeddingAndPosition(embedding, posEmbed, seqLen);

        float[] encInputNormByPos = new float[seqLen];
        for (int p = 0; p < seqLen; p++) {
            float pn = 0;
            for (int j = 0; j < D_MODEL; j++) pn += hidden.get(p, j) * hidden.get(p, j);
            encInputNormByPos[p] = (float)Math.sqrt(pn);
        }

        for (int layer = 0; layer < NUM_LAYERS; layer++) {
            float[] hdB = hidden.data();
            float nB = 0; for (float v : hdB) nB += v * v;
            hidden = encoderLayer(hidden, layer);
            float[] hdA = hidden.data();
            float nA = 0; for (float v : hdA) nA += v * v;
            vlog("  encoder layer {}: ||hidden|| {} -> {}", layer,
                String.format("%.0f", Math.sqrt(nB / seqLen)), String.format("%.0f", Math.sqrt(nA / seqLen)));
        }

        // Log per-position norms of encoder output and input for comparison
        float[] encOut = hidden.data();
        StringBuilder eoInfo = new StringBuilder("  encoder per-pos ||output||:");
        for (int p = 0; p < seqLen; p++) {
            float pn = 0;
            for (int j = 0; j < D_MODEL; j++) pn += encOut[p * D_MODEL + j] * encOut[p * D_MODEL + j];
            eoInfo.append(String.format(" %.0f", Math.sqrt(pn)));
        }
        vlog(eoInfo.toString());
        StringBuilder eiInfo = new StringBuilder("  encoder per-pos ||input||:");
        for (int p = 0; p < seqLen; p++) {
            eiInfo.append(String.format(" %.0f", encInputNormByPos[p]));
        }
        vlog(eiInfo.toString());

        float[] out = new float[seqLen * D_MODEL];
        for (int i = 0; i < seqLen; i++) {
            for (int j = 0; j < D_MODEL; j++) {
                out[i * D_MODEL + j] = hidden.get(i, j);
            }
        }
        return out;
    }

    private Tensor encoderLayer(Tensor hidden, int layerIdx) {
        String prefix = "model.encoder.layers." + layerIdx + ".self_attn.";
        String prefixAlt = "encoder.layers." + layerIdx + ".self_attn.";

        // Post-norm: LayerNorm AFTER self-attention residual
        String ln1Prefix = "model.encoder.layers." + layerIdx + ".self_attn_layer_norm.";
        String ln1Alt = "encoder.layers." + layerIdx + ".self_attn_layer_norm.";
        String ln1Alt2 = "model.encoder.layers." + layerIdx + ".final_layer_norm.";

        Tensor residual = hidden.copy();

        Tensor q = linear(hidden, prefix + "q_proj", prefixAlt + "q_proj");
        Tensor k = linear(hidden, prefix + "k_proj", prefixAlt + "k_proj");
        Tensor v = linear(hidden, prefix + "v_proj", prefixAlt + "v_proj");

        int seqLen = hidden.shape()[0];
        Tensor attnOutput = attention(q, k, v, seqLen, false);
        Tensor attnProj = linear(attnOutput, prefix + "out_proj", prefixAlt + "out_proj");
        hidden = layerNorm(residual.add(attnProj), ln1Prefix, ln1Alt, ln1Alt2);

        // Post-norm: LayerNorm AFTER FFN residual
        String ln2Prefix = "model.encoder.layers." + layerIdx + ".final_layer_norm.";
        String ln2Alt = "encoder.layers." + layerIdx + ".final_layer_norm.";
        String ln2Alt2 = "model.encoder.layers." + layerIdx + ".self_attn_layer_norm.";

        residual = hidden.copy();

        Tensor fc1 = linear(hidden,
                "model.encoder.layers." + layerIdx + ".fc1",
                "encoder.layers." + layerIdx + ".fc1");
        Tensor activated = swish(fc1);

        Tensor fc2 = linear(activated,
                "model.encoder.layers." + layerIdx + ".fc2",
                "encoder.layers." + layerIdx + ".fc2");
        hidden = layerNorm(residual.add(fc2), ln2Prefix, ln2Alt, ln2Alt2);

        return hidden;
    }

    private void initKvCache(float[] encoderHidden, int encLen) {
        kvCache = new KvCache();
        kvCache.encLen = encLen;
        kvCache.step = 0;
        float[] encData = new float[encLen * D_MODEL];
        System.arraycopy(encoderHidden, 0, encData, 0, encLen * D_MODEL);
        kvCache.encoderTensor = new Tensor(encData, new int[]{encLen, D_MODEL});
        // Pre-compute cross-attention K/V for all layers
        for (int layer = 0; layer < NUM_LAYERS; layer++) {
            String crossPrefix = "model.decoder.layers." + layer + ".encoder_attn.";
            String crossAlt = "decoder.layers." + layer + ".encoder_attn.";
            kvCache.crossK[layer] = linear(kvCache.encoderTensor, crossPrefix + "k_proj", crossAlt + "k_proj");
            kvCache.crossV[layer] = linear(kvCache.encoderTensor, crossPrefix + "v_proj", crossAlt + "v_proj");
        }
    }

    private float[] decoderForward(long[] inputIds, float[] encoderHidden) {
        int seqLen = inputIds.length;
        int encLen = encoderHidden.length / D_MODEL;

        // Initialize KV cache on first call
        if (kvCache == null || kvCache.encLen != encLen) {
            initKvCache(encoderHidden, encLen);
        }

        // In autoregressive mode, only process the new token
        boolean incremental = kvCache.step > 0 && seqLen == kvCache.step + 1;
        long[] tokenIds;
        int posOffset;
        if (incremental) {
            tokenIds = new long[]{inputIds[seqLen - 1]};
            posOffset = kvCache.step;
        } else {
            tokenIds = inputIds;
            posOffset = 0;
            kvCache.step = 0;
            for (int l = 0; l < NUM_LAYERS; l++) {
                kvCache.selfK[l] = null;
                kvCache.selfV[l] = null;
            }
        }

        Tensor embedding = embed(tokenIds, "shared.weight", "model.decoder.embed_tokens.weight", "decoder.embed_tokens.weight", "embed_tokens.weight", "model.shared.weight");

        Tensor posEmbed = findWeightAny(decoderWeights,
                "model.decoder.embed_positions.weight",
                "decoder.embed_positions.weight");
        if (posEmbed == null) {
            posEmbed = findWeightAny(encoderWeights,
                    "model.encoder.embed_positions.weight",
                    "encoder.embed_positions.weight");
        }

        Tensor hidden = addEmbeddingAndPosition(embedding, posEmbed, tokenIds.length, posOffset);

        if (kvCache.step <= 2) {
            float[] hd0 = hidden.data();
            float n0 = 0;
            for (float v : hd0) n0 += v * v;
            vlog(String.format("  step=%d INPUT before layers: ||H||=%.1f H[0..4]=[%.2f,%.2f,%.2f,%.2f,%.2f]",
                kvCache.step, (float)Math.sqrt(n0), hd0[0], hd0[1], hd0[2], hd0[3], hd0[4]));
        }

        if (!incremental) {
            // Log position embedding diagnostics
            if (posEmbed != null) {
                float peNorm=0, peFirst=posEmbed.get(0,0);
                for (float v : posEmbed.data()) peNorm += v*v;
                peNorm = (float)Math.sqrt(peNorm);
                vlog("  posEmbed shape=[{},{}] ||W||_F={} pe[0][0]={} pe[1][0]={}",
                    posEmbed.shape()[0], posEmbed.shape()[1], String.format("%.1f", peNorm),
                    String.format("%.3f", peFirst), posEmbed.shape()[0] > 1 ? String.format("%.3f", posEmbed.get(1,0)) : "N/A");
            } else {
                vlog("  posEmbed is NULL");
            }
            // Log embedding row norms for various tokens
            Tensor sw = findWeightAny("model.shared.weight", "shared.weight");
            if (sw != null) {
                StringBuilder eb = new StringBuilder("  embed row L2 norms:");
                for (int tid : new int[]{0, 1, 2, 100, 1000, 21693, 65000, (int)tokenIds[0]}) {
                    if (tid < sw.shape()[0]) {
                        float rn = 0; for (int j = 0; j < sw.shape()[1]; j++) rn += sw.get(tid, j) * sw.get(tid, j);
                        eb.append(String.format(" [%d]=%.4f", tid, (float)Math.sqrt(rn)));
                    }
                }
                vlog(eb.toString());
                // Check if shared.weight row count actually includes 65000
                vlog("  shared.weight shape=[{},{}], first val={}, last val={}",
                    sw.shape()[0], sw.shape()[1], sw.get(0,0), sw.get(sw.shape()[0]-1, 0));
            }
            // Log encoder output norm
            float[] encD = kvCache.encoderTensor.data();
            float eNorm = 0; for (float v : encD) eNorm += v * v;
            eNorm = (float)Math.sqrt(eNorm);
            // Log cross V norm for layer 0
            float[] vd0 = kvCache.crossV[0].data();
            float vNorm0 = 0; for (float v : vd0) vNorm0 += v * v;
            vNorm0 = (float)Math.sqrt(vNorm0);
            // Per-position encoder output and crossV norms
            int dModel = hidden.shape()[1];
            StringBuilder encInfo = new StringBuilder("  encoder per-pos ||hidden||:");
            StringBuilder vInfo = new StringBuilder("  crossV[0] per-pos ||V||:");
            for (int p = 0; p < encLen; p++) {
                float ePosNorm = 0, vPosNorm = 0;
                for (int j = 0; j < dModel; j++) {
                    float ev = encD[p * dModel + j]; ePosNorm += ev * ev;
                    float vv = vd0[p * dModel + j]; vPosNorm += vv * vv;
                }
                encInfo.append(String.format(" %.0f", Math.sqrt(ePosNorm)));
                vInfo.append(String.format(" %.0f", Math.sqrt(vPosNorm)));
            }
            vlog(encInfo.toString());
            // Encoder cosine similarity: compare position 0 vs others
            StringBuilder encSim = new StringBuilder("  encoder per-pos E[0]·E[p] sim:");
            float e0n = 0; for (int j = 0; j < dModel; j++) e0n += encD[j] * encD[j];
            e0n = (float)Math.sqrt(e0n);
            for (int p = 1; p < encLen; p++) {
                float dot = 0, epn = 0;
                for (int j = 0; j < dModel; j++) {
                    dot += encD[j] * encD[p * dModel + j];
                    epn += encD[p * dModel + j] * encD[p * dModel + j];
                }
                epn = (float)Math.sqrt(epn);
                encSim.append(String.format(" %.2f", dot / (e0n * epn)));
            }
            vlog(encSim.toString());
            vlog(vInfo.toString());
            // CrossV cosine similarity: compare position 0 vs others
            StringBuilder simInfo = new StringBuilder("  crossV[0] V[0]·V[p] sim:");
            float[] cV0 = kvCache.crossV[0].data();
            float v0n0 = 0; for (int j = 0; j < dModel; j++) v0n0 += cV0[j] * cV0[j];
            v0n0 = (float)Math.sqrt(v0n0);
            for (int p = 1; p < encLen; p++) {
                float dot = 0, vpn = 0;
                for (int j = 0; j < dModel; j++) {
                    dot += cV0[j] * cV0[p * dModel + j];
                    vpn += cV0[p * dModel + j] * cV0[p * dModel + j];
                }
                vpn = (float)Math.sqrt(vpn);
                simInfo.append(String.format(" %.3f", dot / (v0n0 * vpn)));
            }
            vlog(simInfo.toString());
            // Log v_proj weight Frobenius norm for cross-attention layer 0
            String vpKey = "model.decoder.layers.0.encoder_attn.v_proj.weight";
            Tensor vpW = decoderWeights.get(vpKey);
            if (vpW == null) {
                vpKey = "decoder.layers.0.encoder_attn.v_proj.weight";
                vpW = decoderWeights.get(vpKey);
            }
            if (vpW == null) {
                vpKey = "layers.0.encoder_attn.v_proj.weight";
                vpW = decoderWeights.get(vpKey);
            }
            if (vpW != null) {
                int[] vpShape = vpW.shape();
                float[] vpD = vpW.data();
                float fn = 0;
                for (float v : vpD) fn += v * v;
                fn = (float)Math.sqrt(fn);
                // Also compute how much of the weight comes from top direction
                // Project encoder pos 0 through V weight manually for one component
                int dM = hidden.shape()[1];
                int encPos0Idx = 0;
                float signalBefore = 0;
                for (int j = 0; j < dM; j++) signalBefore += encD[j] * encD[j];
                signalBefore = (float)Math.sqrt(signalBefore);
                // Compute V[0] = enc @ v_proj, already computed as crossV[0][0]
                float[] cV0data = kvCache.crossV[0].data();
                float v0norm = 0; for (int j = 0; j < dM; j++) v0norm += cV0data[j] * cV0data[j];
                v0norm = (float)Math.sqrt(v0norm);
                vlog("  v_proj weight shape=[{},{}] ||W||_F={} ||enc[0]||={} -> ||V[0]||={}",
                    vpShape[0], vpShape[1], String.format("%.0f", fn), String.format("%.0f", signalBefore), String.format("%.0f", v0norm));
            } else {
                vlog("  v_proj weight NOT FOUND");
            }
        }
        if (!incremental) {
            // Log all decoder LN weights for diagnostic
            for (int l = 0; l < NUM_LAYERS; l++) {
                String[][] lnTypes = {
                    {"model.decoder.layers." + l + ".self_attn_layer_norm.weight", "decoder.layers." + l + ".self_attn_layer_norm.weight"},
                    {"model.decoder.layers." + l + ".encoder_attn_layer_norm.weight", "decoder.layers." + l + ".encoder_attn_layer_norm.weight"},
                    {"model.decoder.layers." + l + ".final_layer_norm.weight", "decoder.layers." + l + ".final_layer_norm.weight"}
                };
                for (String[] lnName : lnTypes) {
                    Tensor lnW = findWeightAny(lnName);
                    if (lnW != null) {
                        float avg = 0, min = Float.MAX_VALUE, max = 0;
                        for (float vv : lnW.data()) { float av = Math.abs(vv); avg += av; if (av < min) min = av; if (av > max) max = av; }
                        avg /= lnW.data().length;
                        vlog(String.format("  LN L%d: avg=%.2f [%.2f-%.2f] %s", l, avg, min, max, lnName[0].replace(".weight","")));
                    }
                }
            }
            // Log decoder-wide final layer_norm weight (after all layers, before LM head)
            String[][] globalLnNames = {
                {"model.decoder.layer_norm.weight", "decoder.layer_norm.weight"},
                {"model.encoder.layer_norm.weight", "encoder.layer_norm.weight"}
            };
            for (String[] lnCandidates : globalLnNames) {
                Tensor lnW = findWeightAny(lnCandidates);
                if (lnW != null) {
                    float avg = 0, min = Float.MAX_VALUE, max = 0;
                    for (float vv : lnW.data()) { float av = Math.abs(vv); avg += av; if (av < min) min = av; if (av > max) max = av; }
                    avg /= lnW.data().length;
                    vlog(String.format("  global LN: avg=%.2f [%.2f-%.2f] %s", avg, min, max, lnCandidates[0].replace(".weight","")));
                } else {
                    vlog("  global LN not found: {}", lnCandidates[0]);
                }
            }
        }
        for (int layer = 0; layer < NUM_LAYERS; layer++) {
            hidden = decoderLayerCached(hidden, layer, incremental);
            if (kvCache.step <= 2) {
                float[] hd = hidden.data();
                float hn = 0;
                for (float v : hd) hn += v * v;
                vlog(String.format("  step=%d L%d: ||H||=%.1f H[0..4]=[%.2f,%.2f,%.2f,%.2f,%.2f]",
                    kvCache.step, layer, (float)Math.sqrt(hn),
                    hd[0], hd[1], hd[2], hd[3], hd[4]));
            }
        }

        // LM head — uses cached transposed weight (transposed once during model loading)
        if (kvCache.step <= 1) {
            String lmSrc;
            if (decoderWeights.containsKey("lm_head.weight")) lmSrc = "decoder.lm_head";
            else if (decoderWeights.containsKey("model.shared.weight")) lmSrc = "decoder.model.shared";
            else if (encoderWeights.containsKey("shared.weight")) lmSrc = "encoder.shared";
            else lmSrc = "unknown";
            int[] ls = lmHeadWeightT.shape();
            vlog("  LM head source: {} shape=[{},{}]", lmSrc, ls[1], ls[0]);
        }

        Tensor logits = hidden.matmul(lmHeadWeightT);
        // Apply final_logits_bias if present (HuggingFace convention)
        Tensor logitsBias = findWeightAny("final_logits_bias", "model.final_logits_bias");
        if (logitsBias != null && kvCache.step <= 1) {
            int nnz = 0; for (float v : logitsBias.data()) if (Math.abs(v) > 1e-6) nnz++;
            vlog("  final_logits_bias shape=[{},{}], first={}, last={}, nnz={}",
                logitsBias.shape().length > 0 ? logitsBias.shape()[0] : 0,
                logitsBias.shape().length > 1 ? logitsBias.shape()[1] : 0,
                logitsBias.getFlat(0), logitsBias.getFlat(logitsBias.data().length-1), nnz);
        }
        int vocSize = logits.shape()[1];
        float[] result = new float[vocSize];
        for (int i = 0; i < vocSize; i++) {
            float val = logits.get(0, i);
            if (logitsBias != null && i < logitsBias.data().length) val += logitsBias.getFlat(i);
            result[i] = val;
        }

        // Per-step logging: top-3 tokens, hidden norm, input norm, cosine sim with prev step
        {
            int best = argmax(result);
            int[] top3 = topK(result, 3);
            StringBuilder topStr = new StringBuilder();
            for (int ti = 0; ti < 3 && ti < top3.length && top3[ti] >= 0; ti++) {
                topStr.append(String.format(" %d=%.2f", top3[ti], result[top3[ti]]));
            }
            float hNorm = 0; for (float v : hidden.data()) hNorm += v * v;
            hNorm = (float) Math.sqrt(hNorm);
            float[] emData = embedding.data();
            float eNorm = 0; for (float v : emData) eNorm += v * v;
            eNorm = (float) Math.sqrt(eNorm);
            // Cosine sim with previous step's hidden
            String cosStr = "";
            if (prevHiddenState != null) {
                float dot = 0, pn = 0;
                for (int j = 0; j < D_MODEL; j++) {
                    float a = hidden.get(0, j), b = prevHiddenState.get(0, j);
                    dot += a * b; pn += b * b;
                }
                float pNorm = (float) Math.sqrt(pn);
                cosStr = String.format(" cos(prev)=%.3f", dot / (hNorm * pNorm));
            }
            // Cosine sim with lm_head[best]
            float embDot = 0, embNorm = 0;
            if (best >= 0 && best < lmHeadWeightT.shape()[1]) {
                for (int j = 0; j < D_MODEL; j++) {
                    float hv = hidden.get(0, j), lv = lmHeadWeightT.get(j, best);
                    embDot += hv * lv; embNorm += lv * lv;
                }
                embNorm = (float) Math.sqrt(embNorm);
            }
            String cosEmb = (embNorm > 0) ? String.format(" cos(h,emb[%d])=%.3f", best, embDot / (hNorm * embNorm)) : "";
            vlog(String.format("  step=%d best=%d%s ||H||=%.1f ||E||=%.1f%s%s",
                kvCache.step, best, topStr, hNorm, eNorm, cosStr, cosEmb));
        }
        prevHiddenState = hidden.copy();

        kvCache.step = seqLen;
        return result;
    }

    private Tensor decoderLayerCached(Tensor hidden, int layerIdx, boolean incremental) {
        String prefix = "model.decoder.layers." + layerIdx + ".self_attn.";
        String prefixAlt = "decoder.layers." + layerIdx + ".self_attn.";

        // Post-norm: LayerNorm AFTER self-attention residual
        String ln1Prefix = "model.decoder.layers." + layerIdx + ".self_attn_layer_norm.";
        String ln1Alt = "decoder.layers." + layerIdx + ".self_attn_layer_norm.";

        Tensor residual = hidden.copy();

        Tensor q = linear(hidden, prefix + "q_proj", prefixAlt + "q_proj");
        Tensor k = linear(hidden, prefix + "k_proj", prefixAlt + "k_proj");
        Tensor v = linear(hidden, prefix + "v_proj", prefixAlt + "v_proj");

        // Update self-attention KV cache
        if (kvCache.selfK[layerIdx] == null || !incremental) {
            kvCache.selfK[layerIdx] = k;
            kvCache.selfV[layerIdx] = v;
        } else {
            kvCache.selfK[layerIdx] = concat(kvCache.selfK[layerIdx], k);
            kvCache.selfV[layerIdx] = concat(kvCache.selfV[layerIdx], v);
        }

        int kvLen = kvCache.selfK[layerIdx].shape()[0];
        Tensor attnOutput = attention(q, kvCache.selfK[layerIdx], kvCache.selfV[layerIdx], kvLen, true);
        Tensor attnProj = linear(attnOutput, prefix + "out_proj", prefixAlt + "out_proj");
        if (!incremental) {
            float[] qd = q.data(); float qn = 0; for (float vv : qd) qn += vv*vv;
            float[] kd = k.data(); float kn = 0; for (float vv : kd) kn += vv*vv;
            float[] vd = v.data(); float vn = 0; for (float vv : vd) vn += vv*vv;
            float[] ad = attnOutput.data(); float an = 0; for (float vv : ad) an += vv*vv;
            float[] pd = attnProj.data(); float pn = 0; for (float vv : pd) pn += vv*vv;
            String lnName = "model.decoder.layers." + layerIdx + ".final_layer_norm.weight";
            Tensor lnW = findWeightAny(lnName, "decoder.layers." + layerIdx + ".final_layer_norm.weight");
            float lnAvg = 0;
            float lnMax = 0, lnMin = Float.MAX_VALUE;
            if (lnW != null) {
                for (float vv : lnW.data()) { float av = Math.abs(vv); lnAvg += av; if (av > lnMax) lnMax = av; if (av < lnMin) lnMin = av; }
                lnAvg /= lnW.data().length;
            }
            vlog(String.format("  L%d attn: ||q||=%.1f ||k||=%.1f ||v||=%.1f ||attnOut||=%.1f ||proj||=%.1f LNw=%.2f[%.2f-%.2f]",
                layerIdx, (float)Math.sqrt(qn), (float)Math.sqrt(kn),
                (float)Math.sqrt(vn), (float)Math.sqrt(an), (float)Math.sqrt(pn), lnAvg, lnMin, lnMax));
            if (layerIdx == 5 && lnW != null) {
                float[] wd = lnW.data();
                vlog(String.format("  L5 LNw first5: %.3f %.3f %.3f %.3f %.3f  last5: %.3f %.3f %.3f %.3f %.3f",
                    wd[0], wd[1], wd[2], wd[3], wd[4],
                    wd[wd.length-5], wd[wd.length-4], wd[wd.length-3], wd[wd.length-2], wd[wd.length-1]));
            }
        }
        hidden = layerNorm(residual.add(attnProj), ln1Prefix, ln1Alt);
        if (!incremental) {
            float[] hdSA = hidden.data(); float hnSA = 0; for (float vv : hdSA) hnSA += vv*vv;
            vlog(String.format("  L%d after self-attn LN: ||hidden||=%.1f",
                layerIdx, (float)Math.sqrt(hnSA)));
        }

        // Post-norm: LayerNorm AFTER cross-attention residual
        String ln2Prefix = "model.decoder.layers." + layerIdx + ".encoder_attn_layer_norm.";
        String ln2Alt = "decoder.layers." + layerIdx + ".encoder_attn_layer_norm.";

        residual = hidden.copy();

        String crossPrefix = "model.decoder.layers." + layerIdx + ".encoder_attn.";
        String crossAlt = "decoder.layers." + layerIdx + ".encoder_attn.";

        Tensor crossQ = linear(hidden, crossPrefix + "q_proj", crossAlt + "q_proj");
        Tensor crossOutput = attention(crossQ, kvCache.crossK[layerIdx], kvCache.crossV[layerIdx], kvCache.encLen, false);
        if (!incremental) {
            float[] hdBA = hidden.data(); float hnBA = 0; for (float vv : hdBA) hnBA += vv*vv;
            float[] cd = crossOutput.data(); float cn = 0; for (float vv : cd) cn += vv*vv;
            vlog(String.format("  L%d cross: ||hidden||=%.1f -> ||crossOut||=%.1f",
                layerIdx, (float)Math.sqrt(hnBA), (float)Math.sqrt(cn)));
            // Log cross-attention softmax weights for layer 0, first position
            if (layerIdx == 0 && kvCache.step == 0) {
                int encLen = kvCache.encLen;
                StringBuilder wInfo = new StringBuilder("  L0 cross-attn softmax (pos0, all heads):");
                for (int h = 0; h < NUM_HEADS; h++) {
                    float[] scores = new float[encLen];
                    float maxScore = Float.NEGATIVE_INFINITY;
                    for (int j = 0; j < encLen; j++) {
                        float s = 0;
                        for (int d = 0; d < HEAD_DIM; d++) {
                            s += crossQ.get(0, h * HEAD_DIM + d) * kvCache.crossK[layerIdx].get(j, h * HEAD_DIM + d);
                        }
                        scores[j] = s / (float)Math.sqrt(HEAD_DIM);
                        if (scores[j] > maxScore) maxScore = scores[j];
                    }
                    float sumExp = 0;
                    for (int j = 0; j < encLen; j++) { scores[j] = (float)Math.exp(scores[j] - maxScore); sumExp += scores[j]; }
                    wInfo.append(String.format("  h%d:", h));
                    for (int j = 0; j < encLen; j++) wInfo.append(String.format(" %.2f", scores[j] / sumExp));
                }
                vlog(wInfo.toString());
            }
        }
        Tensor crossProj = linear(crossOutput, crossPrefix + "out_proj", crossAlt + "out_proj");
        if (!incremental) {
            float[] cpd = crossProj.data(); float cpn = 0; for (float vv : cpd) cpn += vv*vv;
            float[] hdCA = residual.data(); float hnCA = 0; for (float vv : hdCA) hnCA += vv*vv;
            vlog(String.format("  L%d cross: ||crossProj||=%.1f (resid=%.1f)",
                layerIdx, (float)Math.sqrt(cpn), (float)Math.sqrt(hnCA)));
        }
        hidden = layerNorm(residual.add(crossProj), ln2Prefix, ln2Alt);
        if (!incremental) {
            float[] hdCA = hidden.data(); float hnCA = 0; for (float vv : hdCA) hnCA += vv*vv;
            vlog(String.format("  L%d after cross-attn LN: ||hidden||=%.1f",
                layerIdx, (float)Math.sqrt(hnCA)));
        }

        // Post-norm: LayerNorm AFTER FFN residual
        String ln3Prefix = "model.decoder.layers." + layerIdx + ".final_layer_norm.";
        String ln3Alt = "decoder.layers." + layerIdx + ".final_layer_norm.";

        residual = hidden.copy();

        Tensor fc1 = linear(hidden,
                "model.decoder.layers." + layerIdx + ".fc1",
                "decoder.layers." + layerIdx + ".fc1");
        Tensor activated = swish(fc1);
        Tensor fc2 = linear(activated,
                "model.decoder.layers." + layerIdx + ".fc2",
                "decoder.layers." + layerIdx + ".fc2");
        if (!incremental) {
            float[] f1d = fc1.data(); float f1n = 0; for (float vv : f1d) f1n += vv*vv;
            float[] f2d = fc2.data(); float f2n = 0; for (float vv : f2d) f2n += vv*vv;
            float[] hdBN = residual.data(); float hnBN = 0; for (float vv : hdBN) hnBN += vv*vv;
            vlog(String.format("  L%d ffn: resid=%.1f fc1=%.1f fc2=%.1f",
                layerIdx, (float)Math.sqrt(hnBN), (float)Math.sqrt(f1n), (float)Math.sqrt(f2n)));
        }
        hidden = layerNorm(residual.add(fc2), ln3Prefix, ln3Alt);
        if (!incremental) {
            float[] hdFFN = hidden.data(); float hnFFN = 0; for (float vv : hdFFN) hnFFN += vv*vv;
            // Log average LN weight for this layer
            String[] lnWNames = new String[]{
                "model.decoder.layers." + layerIdx + ".final_layer_norm.weight",
                "decoder.layers." + layerIdx + ".final_layer_norm.weight"
            };
            Tensor lnW = findWeightAny(lnWNames);
            float lnAvg = 0;
            if (lnW != null) { for (float vv : lnW.data()) lnAvg += Math.abs(vv); lnAvg /= lnW.data().length; }
            vlog(String.format("  L%d after FFN LN: ||hidden||=%.1f LNw=%.2f",
                layerIdx, (float)Math.sqrt(hnFFN), lnAvg));
        }

        return hidden;
    }

    private Tensor concat(Tensor a, Tensor b) {
        int rowsA = a.shape()[0], cols = a.shape()[1];
        int rowsB = b.shape()[0];
        float[] result = new float[(rowsA + rowsB) * cols];
        System.arraycopy(a.data(), 0, result, 0, a.data().length);
        System.arraycopy(b.data(), 0, result, a.data().length, b.data().length);
        return new Tensor(result, new int[]{rowsA + rowsB, cols});
    }

    private void patchZeroEmbeddings(int startTokId, int fallbackTokId) {
        Tensor sw = findWeightAny("model.shared.weight", "shared.weight");
        if (sw == null) return;
        int d = sw.shape()[1];
        if (startTokId >= sw.shape()[0] || fallbackTokId >= sw.shape()[0]) return;
        // Check start token row
        boolean zeroRow = true;
        for (int j = 0; j < d; j++) {
            if (Math.abs(sw.get(startTokId, j)) > 1e-6f) { zeroRow = false; break; }
        }
        if (!zeroRow) return;
        log.warn("embedding row {} (decoder_start_token_id) is ALL ZEROS — copying from token {}", startTokId, fallbackTokId);
        for (int j = 0; j < d; j++) {
            sw.set(sw.get(fallbackTokId, j), startTokId, j);
        }
    }

    private Tensor embed(long[] ids, String... weightNames) {
        Tensor w = findWeightAny(encoderWeights, weightNames);
        if (w == null) {
            w = findWeightAny(decoderWeights, weightNames);
        }
        if (w == null) {
            w = ensureWeight(weightNames[0]);
        }
        int vocabSize = w.shape()[0];
        int dModel = w.shape()[1];
        float[] result = new float[ids.length * dModel];
        for (int i = 0; i < ids.length; i++) {
            int idx = (int) ids[i];
            if (idx < 0 || idx >= vocabSize) idx = 0;
            for (int j = 0; j < dModel; j++) {
                result[i * dModel + j] = w.get(idx, j) * SCALE;
            }
        }
        return new Tensor(result, new int[]{ids.length, dModel});
    }

    private Tensor addEmbeddingAndPosition(Tensor embedding, Tensor posEmbed, int seqLen) {
        return addEmbeddingAndPosition(embedding, posEmbed, seqLen, 0);
    }

    private Tensor addEmbeddingAndPosition(Tensor embedding, Tensor posEmbed, int seqLen, int offset) {
        if (posEmbed == null) return embedding;
        int maxPos = posEmbed.shape()[0];
        int dModel = posEmbed.shape()[1];
        float[] result = embedding.data().clone();
        for (int i = 0; i < seqLen && (i + offset) < maxPos; i++) {
            for (int j = 0; j < dModel; j++) {
                result[i * dModel + j] += posEmbed.get(i + offset, j);
            }
        }
        return new Tensor(result, embedding.shape());
    }

    private Tensor linear(Tensor input, String... weightNames) {
        Tensor w = findWeightAny(weightNames);
        if (w == null) {
            w = ensureWeight(weightNames[0]);
        }
        // ONNX MatMul stores weight as right-hand operand: hidden @ weight
        // Weight shape is [input_dim, output_dim] (no transpose needed)
        if (input.shape()[1] != w.shape()[0]) {
            log.debug("linear {}: input {} w {}", weightNames[0],
                java.util.Arrays.toString(input.shape()),
                java.util.Arrays.toString(w.shape()));
        }
        Tensor result = input.matmul(w);

        String biasName = weightNames[0] + ".bias";
        if (weightNames[0].endsWith(".weight")) {
            biasName = weightNames[0].substring(0, weightNames[0].length() - 7) + ".bias";
        }
        Tensor bias = findWeightAny(biasName);
        if (bias != null && bias.shape()[0] == result.shape()[1]) {
            float[] r = result.data().clone();
            int cols = result.shape()[1];
            for (int i = 0; i < result.shape()[0]; i++) {
                for (int j = 0; j < cols; j++) {
                    r[i * cols + j] += bias.getFlat(j);
                }
            }
            return new Tensor(r, result.shape());
        }
        return result;
    }

    private Tensor attention(Tensor q, Tensor k, Tensor v, int kvLen, boolean causal) {
        int seqLen = q.shape()[0];
        int dModel = q.shape()[1];
        // When using KV cache, q starts at (kvLen - seqLen) in absolute position
        int qOffset = kvLen - seqLen;

        float[] qData = q.data();
        float[] kData = k.data();
        float[] vData = v.data();

        float[] attnScores = new float[NUM_HEADS * seqLen * kvLen];

        for (int h = 0; h < NUM_HEADS; h++) {
            int hOff = h * HEAD_DIM;
            for (int i = 0; i < seqLen; i++) {
                int absI = i + qOffset;
                for (int j = 0; j < kvLen; j++) {
                    float sum = 0;
                    for (int d = 0; d < HEAD_DIM; d++) {
                        sum += qData[i * dModel + hOff + d] * kData[j * dModel + hOff + d];
                    }
                    int idx = h * seqLen * kvLen + i * kvLen + j;
                    attnScores[idx] = sum / (float) Math.sqrt(HEAD_DIM);
                    if (causal && j > absI) {
                        attnScores[idx] = -1e9f;
                    }
                }
            }
        }

        for (int h = 0; h < NUM_HEADS; h++) {
            for (int i = 0; i < seqLen; i++) {
                int base = h * seqLen * kvLen + i * kvLen;
                float max = Float.NEGATIVE_INFINITY;
                for (int j = 0; j < kvLen; j++) {
                    if (attnScores[base + j] > max) max = attnScores[base + j];
                }
                float sum = 0;
                for (int j = 0; j < kvLen; j++) {
                    float val = (float) Math.exp(attnScores[base + j] - max);
                    attnScores[base + j] = val;
                    sum += val;
                }
                for (int j = 0; j < kvLen; j++) {
                    attnScores[base + j] /= sum;
                }
            }
        }

        float[] output = new float[seqLen * dModel];
        for (int h = 0; h < NUM_HEADS; h++) {
            int hOff = h * HEAD_DIM;
            for (int i = 0; i < seqLen; i++) {
                for (int d = 0; d < HEAD_DIM; d++) {
                    float sum = 0;
                    for (int j = 0; j < kvLen; j++) {
                        int attnIdx = h * seqLen * kvLen + i * kvLen + j;
                        sum += attnScores[attnIdx] * vData[j * dModel + hOff + d];
                    }
                    output[i * dModel + hOff + d] = sum;
                }
            }
        }

        return new Tensor(output, new int[]{seqLen, dModel});
    }

    private Tensor layerNorm(Tensor input, String... normNames) {
        String cleanName = normNames[0];
        while (cleanName.endsWith(".")) cleanName = cleanName.substring(0, cleanName.length() - 1);

        Tensor weight = findWeightAny(cleanName + ".weight");
        boolean hasWeight = (weight != null);
        if (weight == null) weight = ensureWeight(cleanName + ".weight");
        Tensor bias = findWeightAny(cleanName + ".bias");
        if (bias == null) bias = new Tensor(new int[]{D_MODEL});

        int rows = input.shape()[0];
        int cols = input.shape()[1];
        float[] result = new float[input.data().length];

        for (int i = 0; i < rows; i++) {
            int base = i * cols;
            float mean = 0;
            for (int j = 0; j < cols; j++) mean += input.data()[base + j];
            mean /= cols;
            float var = 0;
            for (int j = 0; j < cols; j++) {
                float diff = input.data()[base + j] - mean;
                var += diff * diff;
            }
            var /= cols;
            float std = (float) Math.sqrt(var + 1e-5);

            for (int j = 0; j < cols; j++) {
                float val = (input.data()[base + j] - mean) / std;
                if (hasWeight) {
                    val = val * weight.getFlat(j) + bias.getFlat(j);
                }
                result[base + j] = val;
            }
        }
        return new Tensor(result, input.shape());
    }

    private Tensor swish(Tensor input) {
        float[] r = new float[input.data().length];
        for (int i = 0; i < input.data().length; i++) {
            float x = input.data()[i];
            r[i] = x / (1 + (float) Math.exp(-x));
        }
        return new Tensor(r, input.shape());
    }

    private Tensor findWeightFuzzy(Map<String, Tensor> weights, String name) {
        if (weights == null || name == null) return null;

        // Try exact match
        Tensor t = weights.get(name);
        if (t != null) return t;

        // Try stripping prefix that matches this model's naming convention
        // CRITICAL: only strip prefixes belonging to the model being searched,
        // to avoid cross-model false matches (e.g. decoder finding encoder "layers.X.*" weights)
        String stripped = name;
        if (weights == encoderWeights) {
            if (stripped.startsWith("model.encoder.")) {
                stripped = stripped.substring("model.encoder.".length());
            } else if (stripped.startsWith("model.")) {
                stripped = stripped.substring("model.".length());
            }
        } else if (weights == decoderWeights) {
            if (stripped.startsWith("model.decoder.")) {
                stripped = stripped.substring("model.decoder.".length());
            } else if (stripped.startsWith("model.")) {
                stripped = stripped.substring("model.".length());
            }
        }
        if (stripped != name) {
            t = findWeightFuzzy(weights, stripped);
            if (t != null) return t;
        }

        // Try with .weight suffix (for weight matrices)
        if (!name.endsWith(".weight") && !name.endsWith(".bias")) {
            String withWeight = name + ".weight";
            while (withWeight.contains("..")) withWeight = withWeight.replace("..", ".");
            t = weights.get(withWeight);
            if (t != null) return t;
        }

        // Try with .bias suffix
        if (!name.endsWith(".bias")) {
            String withBias = name + ".bias";
            while (withBias.contains("..")) withBias = withBias.replace("..", ".");
            t = weights.get(withBias);
            if (t != null) return t;
        }

        // Final fallback: try adding .weight to the stripped name
        if (stripped != name) {
            String strippedWeight = stripped + ".weight";
            while (strippedWeight.contains("..")) strippedWeight = strippedWeight.replace("..", ".");
            t = weights.get(strippedWeight);
            if (t != null) return t;
            String strippedBias = stripped + ".bias";
            while (strippedBias.contains("..")) strippedBias = strippedBias.replace("..", ".");
            t = weights.get(strippedBias);
            if (t != null) return t;
        }

        return null;
    }

    private Tensor findWeightAny(Map<String, Tensor> weights, String... candidates) {
        for (String name : candidates) {
            Tensor t = findWeightFuzzy(weights, name);
            if (t != null) return t;
        }
        return null;
    }

    private Tensor findWeightAny(String... candidates) {
        for (String name : candidates) {
            // Try encoder first, then decoder
            Tensor t = findWeightFuzzy(encoderWeights, name);
            if (t != null) return t;
            t = findWeightFuzzy(decoderWeights, name);
            if (t != null) return t;
        }
        return null;
    }

    private Tensor ensureWeight(String name) {
        // Try both maps with fuzzy lookup
        Tensor t = findWeightFuzzy(encoderWeights, name);
        if (t == null) t = findWeightFuzzy(decoderWeights, name);
        if (t == null) {
            log.warn("Missing weight: {} — using zeros", name);
            return new Tensor(new int[]{D_MODEL, D_MODEL});
        }
        return t;
    }

    private static int argmax(float[] logits) {
        int best = 0;
        for (int i = 1; i < logits.length; i++) {
            if (logits[i] > logits[best]) best = i;
        }
        return best;
    }

    private static int[] topK(float[] arr, int k) {
        int[] idx = new int[k];
        float[] vals = new float[k];
        for (int i = 0; i < k; i++) { vals[i] = Float.NEGATIVE_INFINITY; idx[i] = -1; }
        for (int i = 0; i < arr.length; i++) {
            float v = arr[i];
            for (int j = 0; j < k; j++) {
                if (v > vals[j]) {
                    for (int m = k - 1; m > j; m--) { vals[m] = vals[m-1]; idx[m] = idx[m-1]; }
                    vals[j] = v; idx[j] = i;
                    break;
                }
            }
        }
        return idx;
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
            if (c == '-' || Character.isDigit(c)) { sb.append(c); started = true; }
            else if (started) { if (c == ',' || c == '}' || c == ']' || c == '\n') break; }
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : def;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : s;
    }

    @Override
    public void shutdown() {
        loaded = false;
        tokenizer = null;
        encoderWeights = null;
        decoderWeights = null;
    }
}
