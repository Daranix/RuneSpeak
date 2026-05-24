package com.runespeak.translate;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Singleton
public class LocalTranslator {
    private final ModelManager modelManager;
    private final TranslationCache cache;
    private final ExecutorService executor;
    private final RuneSpeakConfig config;

    private Language currentSource = Language.ENGLISH;
    private Language currentTarget = Language.SPANISH;

    @Inject
    public LocalTranslator(RuneSpeakConfig config) {
        this.config = config;

        Path baseDir = resolveCacheDir();
        Path modelDir = baseDir.resolve("models");
        Path cacheDir = baseDir.resolve("cache");

        this.modelManager = new ModelManager(modelDir);
        this.cache = new TranslationCache(cacheDir, config.getCacheSize());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "runespeak-translate");
            t.setDaemon(true);
            return t;
        });
    }

    private Path resolveCacheDir() {
        String custom = config.getModelCacheDir();
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }
        return Path.of(System.getProperty("user.home"), ".runespeak");
    }

    public void initialize(String modelId) {
        executor.submit(() -> {
            try {
                log.info("Loading model: {}", modelId);
                modelManager.setLanguages(
                        currentSource.getFloresCode(),
                        currentTarget.getFloresCode()
                );
                modelManager.loadModel(modelId);
                log.info("Model loaded: {}", modelId);
            } catch (Exception e) {
                log.error("Failed to load model: {}", e.getMessage(), e);
            }
        });
    }

    public void applyConfig() {
        cache.setMaxSize(config.getCacheSize());
        Path newBase = resolveCacheDir();
        log.info("Config applied — cache dir: {}, max entries: {}", newBase, config.getCacheSize());
    }

    public void setLanguages(Language source, Language target) {
        this.currentSource = source;
        this.currentTarget = target;
        modelManager.setLanguages(source.getFloresCode(), target.getFloresCode());
    }

    public Language getCurrentSource() {
        return currentSource;
    }

    public Language getCurrentTarget() {
        return currentTarget;
    }

    public String translateSync(String text) {
        if (text == null || text.isEmpty() || text.isBlank()) return text;
        if (currentSource == currentTarget) return text;

        String cached = cache.get(text, currentSource.getFloresCode(), currentTarget.getFloresCode());
        if (cached != null) return cached;

        if (!modelManager.isLoaded()) {
            return "⏳ " + text;
        }

        try {
            String result = modelManager.getPredictor().predict(text);
            cache.put(text, result, currentSource.getFloresCode(), currentTarget.getFloresCode());
            return result != null ? result : text;
        } catch (Exception e) {
            log.error("Translation failed for '{}': {}", truncate(text, 30), e.getMessage());
            return text;
        }
    }

    public CompletableFuture<String> translateAsync(String text) {
        if (text == null || text.isEmpty() || text.isBlank()) {
            return CompletableFuture.completedFuture(text);
        }
        if (currentSource == currentTarget) {
            return CompletableFuture.completedFuture(text);
        }

        String cached = cache.get(text, currentSource.getFloresCode(), currentTarget.getFloresCode());
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return CompletableFuture.supplyAsync(() -> translateSync(text), executor);
    }

    public boolean isReady() {
        return modelManager.isLoaded();
    }

    public boolean isLoading() {
        return modelManager.isLoading();
    }

    public boolean isModelAvailable() {
        return modelManager.isModelAvailable();
    }

    public Path getCacheDir() {
        return resolveCacheDir();
    }

    public TranslationCache getCache() {
        return cache;
    }

    public void shutdown() {
        modelManager.shutdown();
        cache.shutdown();
        executor.shutdown();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
