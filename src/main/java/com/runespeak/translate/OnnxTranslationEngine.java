package com.runespeak.translate;

import ai.onnxruntime.OrtEnvironment;
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
import java.util.concurrent.CompletableFuture;

@Slf4j
public class OnnxTranslationEngine {

    @Getter
    private volatile boolean loaded = false;
    @Getter
    private volatile boolean loading = false;
    @Getter
    private String currentModelId;

    private ModelRuntime runtime;
    private OrtEnvironment ortEnv;
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

            ModelRuntime newRuntime = ModelRuntime.forModelId(modelId);

            for (DownloadFile file : newRuntime.getRequiredFiles()) {
                downloadIfMissing(modelId, modelPath, file);
            }

            log.info("Creating OrtEnvironment");
            ortEnv = OrtEnvironment.getEnvironment();
            log.info("OrtEnvironment created: {}", ortEnv);

            log.info("Loading model via runtime: {}", newRuntime.getClass().getSimpleName());
            newRuntime.load(modelPath, ortEnv);
            log.info("Model loaded via runtime");

            this.runtime = newRuntime;
            loaded = true;
            log.info("ONNX model loaded successfully: {}", modelId);
        } catch (Throwable t) {
            loaded = false;
            log.error("Failed to load model {}: {}", modelId, t.getMessage(), t);
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("Failed to load model: " + modelId, t);
        } finally {
            loading = false;
            log.info("Set loading=false for model {}", modelId);
        }
    }

    private void downloadIfMissing(String modelId, Path modelPath, DownloadFile file)
            throws IOException {
        Files.createDirectories(modelPath);
        Path filePath = modelPath.resolve(file.getLocalFilename());
        if (Files.exists(filePath) && Files.size(filePath) > 1000) return;

        String url = file.getDownloadUrl(modelId);
        log.info("Downloading {} from {}", file.getLocalFilename(), url);
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try (ReadableByteChannel in = Channels.newChannel(new URL(url).openStream());
             FileOutputStream out = new FileOutputStream(tmp.toFile())) {
            out.getChannel().transferFrom(in, 0, Long.MAX_VALUE);
        }
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
        log.info("Downloaded {} ({})", file.getLocalFilename(), humanSize(Files.size(filePath)));
    }

    public CompletableFuture<String> translate(String text, String srcLang, String tgtLang) {
        if (runtime != null) {
            return runtime.translate(text, srcLang, tgtLang);
        }
        if (text == null || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        return CompletableFuture.completedFuture("\u23F3 " + text);
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
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }
}
