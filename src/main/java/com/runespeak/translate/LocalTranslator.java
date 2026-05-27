package com.runespeak.translate;

import com.runespeak.Language;
import lombok.extern.slf4j.Slf4j;
import com.runespeak.RuneSpeakConfig;

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
    private final OnnxTranslationEngine engine;
    private final TranslationCache cache;
    private final ExecutorService executor;
    private final ExecutorService loadExecutor;
    private final RuneSpeakConfig config;

    private Language currentSource = Language.ENGLISH;
    private Language currentTarget = Language.SPANISH;

    @Inject
    public LocalTranslator(RuneSpeakConfig config) {
        this.config = config;

        Path baseDir = resolveCacheDir();

        this.engine = new OnnxTranslationEngine(baseDir);
        this.cache = new TranslationCache(baseDir.resolve("cache"),
                config.unlimitedCache() ? Integer.MAX_VALUE : config.getCacheSize());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "runespeak-translate");
            t.setDaemon(true);
            return t;
        });
        this.loadExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "runespeak-model-load");
            t.setDaemon(true);
            return t;
        });
    }

    public void initialize(String modelId) {
        Path baseDir = resolveCacheDir();
        engine.setBaseDir(baseDir);

        loadExecutor.submit(() -> {
            log.info("Loading model: {}", modelId);
            try {
                engine.loadModel(modelId);
                log.info("Model loaded: {}", modelId);
            } catch (Exception e) {
                log.error("Failed to load model: {}", e.getMessage(), e);
            }
        });
    }

    public void applyConfig() {
        int maxSize = config.unlimitedCache() ? Integer.MAX_VALUE : config.getCacheSize();
        cache.setMaxSize(maxSize);
        log.info("Config applied — max entries: {}", config.unlimitedCache() ? "unlimited" : String.valueOf(maxSize));
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

        if (!engine.isLoaded()) {
            return "\u23F3 " + text;
        }

        try {
            String result = CompletableFuture.supplyAsync(() -> {
                        try {
                            return engine.translate(text, srcCode, tgtCode).get(60, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            log.error("Translation failed for '{}': {}", truncate(text, 30), e.getMessage());
                            return text;
                        }
                    }, executor)
                    .get(60, TimeUnit.SECONDS);
            if (!result.equals(text)) {
                cache.put(text, result, srcCode, tgtCode);
            }
            return result;
        } catch (Exception e) {
            log.error("translateSync failed: {}", e.getMessage());
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

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Wait for model to finish loading (up to 60s) instead of
                // dropping the request — this handles early-startup messages
                // (tutorial, login) that fire before the model is ready.
                if (!waitForModel()) {
                    return "\u23F3 " + text;
                }

                String result = engine.translate(text, srcCode, tgtCode).get(60, TimeUnit.SECONDS);
                cache.put(text, result, srcCode, tgtCode);
                return result;
            } catch (Exception e) {
                log.error("Async translation failed for '{}': {}", truncate(text, 30), e.getMessage());
                return text;
            }
        }, executor);
    }

    private boolean waitForModel() {
        long deadline = System.currentTimeMillis() + 60_000;
        while (!engine.isLoaded() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return engine.isLoaded();
    }

    public boolean isReady() {
        return engine.isLoaded();
    }

    public boolean isLoading() {
        return engine.isLoading();
    }

    public Path getCacheDir() {
        return resolveCacheDir();
    }

    public TranslationCache getCache() {
        return cache;
    }

    public OnnxTranslationEngine getEngine() {
        return engine;
    }

    public void shutdown() {
        engine.shutdown();
        cache.shutdown();
        loadExecutor.shutdown();
        executor.shutdown();
    }

    private Path resolveCacheDir() {
        String custom = config.getModelCacheDir();
        if (custom != null && !custom.isBlank()) {
            return Path.of(custom);
        }
        return new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "runespeak").toPath();
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
