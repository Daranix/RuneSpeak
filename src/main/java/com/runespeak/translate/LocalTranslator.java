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
import java.util.concurrent.TimeUnit;

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
        Path cacheDir = baseDir.resolve("cache");

        this.modelManager = new ModelManager(baseDir, config.getPythonPath());
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
                modelManager.loadModel(modelId);
                log.info("Model loaded: {}", modelId);
            } catch (Exception e) {
                log.error("Failed to load model: {}", e.getMessage(), e);
            }
        });
    }

    public ModelManager.DependencyResult checkDependencies() {
        return modelManager.checkDependencies();
    }

    public ModelManager.DependencyStatus getDependencyStatus() {
        return modelManager.getDependencyStatus();
    }

    public void applyConfig() {
        cache.setMaxSize(config.getCacheSize());
        modelManager.setPythonPath(config.getPythonPath());
        Path newBase = resolveCacheDir();
        log.info("Config applied — cache dir: {}, max entries: {}", newBase, config.getCacheSize());
    }

    public void setLanguages(Language source, Language target) {
        this.currentSource = source;
        this.currentTarget = target;
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

        String srcCode = currentSource.getFloresCode();
        String tgtCode = currentTarget.getFloresCode();

        String cached = cache.get(text, srcCode, tgtCode);
        if (cached != null) return cached;

        if (!modelManager.isLoaded()) {
            return "\u23F3 " + text;
        }

        try {
            String result = modelManager.translate(text, srcCode, tgtCode)
                    .get(30, TimeUnit.SECONDS);
            cache.put(text, result, srcCode, tgtCode);
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

        String srcCode = currentSource.getFloresCode();
        String tgtCode = currentTarget.getFloresCode();

        String cached = cache.get(text, srcCode, tgtCode);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        if (!modelManager.isLoaded()) {
            return CompletableFuture.completedFuture("\u23F3 " + text);
        }

        return modelManager.translate(text, srcCode, tgtCode)
                .thenApply(result -> {
                    cache.put(text, result, srcCode, tgtCode);
                    return result;
                })
                .exceptionally(e -> {
                    log.error("Async translation failed: {}", e.getMessage());
                    return text;
                });
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
