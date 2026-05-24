package com.runespeak.translate;

import ai.djl.engine.Engine;
import ai.djl.huggingface.translator.HuggingFaceEncoderDecoderTranslator;
import ai.djl.huggingface.translator.HuggingFaceEncoderDecoderTranslatorFactory;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.training.util.ProgressBar;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class ModelManager {
    static final String DEFAULT_MODEL = "facebook/nllb-200-distilled-600M";

    @Getter
    private ZooModel<String, String> model;
    @Getter
    private Predictor<String, String> predictor;
    private final Path cacheDir;

    @Getter
    private volatile boolean loaded = false;
    @Getter
    private volatile boolean loading = false;
    @Getter
    private String currentModelId;

    private String sourceLang = "eng_Latn";
    private String targetLang = "spa_Latn";

    private static final Set<String> SUPPORTED_ENGINES = new HashSet<>();

    static {
        SUPPORTED_ENGINES.add("PyTorch");
        SUPPORTED_ENGINES.add("OnnxRuntime");
    }

    public ModelManager(Path cacheDir) {
        this.cacheDir = cacheDir;
        this.currentModelId = DEFAULT_MODEL;
    }

    public void setLanguages(String sourceFloresCode, String targetFloresCode) {
        this.sourceLang = sourceFloresCode;
        this.targetLang = targetFloresCode;
        if (loaded) {
            predictor.close();
            model.close();
            loaded = false;
        }
    }

    public synchronized void loadModel(String modelId) throws IOException {
        if (loaded && currentModelId.equals(modelId)) return;
        if (loading) return;

        loading = true;
        currentModelId = modelId;

        try {
            if (model != null) {
                model.close();
                model = null;
                predictor = null;
            }

            Files.createDirectories(cacheDir);
            String engine = resolveEngine();

            log.info("Loading translation model: {} via {}", modelId, engine);
            log.info("Source: {}  Target: {}", sourceLang, targetLang);

            HuggingFaceEncoderDecoderTranslator translator =
                    HuggingFaceEncoderDecoderTranslator.builder()
                            .setSrcLang(sourceLang)
                            .setTgtLang(targetLang)
                            .build();

            String modelUrl = "https://huggingface.co/" + modelId;

            Criteria<String, String> criteria = Criteria.builder()
                    .setTypes(String.class, String.class)
                    .optModelUrls(modelUrl)
                    .optEngine(engine)
                    .optTranslator(translator)
                    .optProgress(new ProgressBar())
                    .optOption("cache_dir", cacheDir.toAbsolutePath().toString())
                    .build();

            model = ModelZoo.loadModel(criteria);
            predictor = model.newPredictor();
            loaded = true;

            log.info("Model loaded: {}", modelId);
        } catch (Exception e) {
            loaded = false;
            log.error("Failed to load model {}: {}", modelId, e.getMessage());
            throw new IOException("Failed to load model: " + modelId, e);
        } finally {
            loading = false;
        }
    }

    private String resolveEngine() {
        for (String e : SUPPORTED_ENGINES) {
            if (Engine.hasEngine(e)) {
                return e;
            }
        }
        return "PyTorch";
    }

    public boolean isModelAvailable() {
        if (loaded) return true;
        Path modelDir = cacheDir.resolve(currentModelId.replace('/', '_'));
        return Files.exists(modelDir) && hasModelFiles(modelDir);
    }

    private boolean hasModelFiles(Path dir) {
        try {
            return Files.walk(dir, 2)
                    .anyMatch(p -> {
                        String name = p.toString().toLowerCase();
                        return name.endsWith(".pt") || name.endsWith(".onnx")
                                || name.endsWith(".bin") || name.endsWith(".json");
                    });
        } catch (IOException e) {
            return false;
        }
    }

    public void shutdown() {
        try {
            if (predictor != null) predictor.close();
            if (model != null) model.close();
        } catch (Exception e) {
            log.error("Error closing model: {}", e.getMessage());
        }
        loaded = false;
    }
}
