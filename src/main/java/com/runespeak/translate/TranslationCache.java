package com.runespeak.translate;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class TranslationCache {
    private final LinkedHashMap<String, String> cache;
    private volatile int maxSize;
    private final Path cacheFile;
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final ScheduledExecutorService flushTimer;

    public TranslationCache(Path cacheDir, int maxSize) {
        this.maxSize = maxSize;
        this.cacheFile = cacheDir.resolve("translation_cache.properties");
        this.cache = new LinkedHashMap<String, String>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > TranslationCache.this.maxSize;
            }
        };

        loadFromDisk();

        this.flushTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runespeak-cache-flush");
            t.setDaemon(true);
            return t;
        });
        flushTimer.scheduleAtFixedRate(this::flushToDisk, 30, 30, TimeUnit.SECONDS);
    }

    public synchronized String get(String originalText, String sourceLang, String targetLang) {
        return cache.get(makeKey(originalText, sourceLang, targetLang));
    }

    public synchronized void put(String originalText, String translation, String sourceLang, String targetLang) {
        if (originalText == null || translation == null) return;
        cache.put(makeKey(originalText, sourceLang, targetLang), translation);
        dirty.set(true);
    }

    public synchronized void setMaxSize(int newMax) {
        this.maxSize = newMax;
        while (cache.size() > maxSize) {
            Map.Entry<String, String> eldest = cache.entrySet().iterator().next();
            cache.remove(eldest.getKey());
        }
        dirty.set(true);
    }

    public synchronized int getMaxSize() {
        return maxSize;
    }

    public synchronized int size() {
        return cache.size();
    }

    public synchronized void clear() {
        cache.clear();
        dirty.set(true);
        flushNow();
    }

    private String makeKey(String text, String sourceLang, String targetLang) {
        return sourceLang + "\0" + targetLang + "\0" + text;
    }

    private void loadFromDisk() {
        if (!Files.exists(cacheFile)) return;
        try {
            synchronized (this) {
                cache.clear();
                for (String line : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int sep = findSeparator(line);
                    if (sep < 0) continue;
                    String key = unescape(line.substring(0, sep));
                    String value = unescape(line.substring(sep + 1));
                    cache.put(key, value);
                }
            }
            log.info("Loaded {} cache entries from {}", cache.size(), cacheFile);
        } catch (IOException e) {
            log.warn("Could not load cache from {}: {}", cacheFile, e.getMessage());
        }
    }

    private static int findSeparator(String line) {
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '=') {
                return i;
            }
        }
        return -1;
    }

    public void flushNow() {
        flushToDisk();
    }

    private void flushToDisk() {
        if (!dirty.compareAndSet(true, false)) return;
        try {
            Files.createDirectories(cacheFile.getParent());
            Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            StringBuilder sb = new StringBuilder("# RuneSpeak translation cache\n");
            sb.append("# Format: src_lang\\0tgt_lang\\0original=translation\n");
            synchronized (this) {
                for (Map.Entry<String, String> e : cache.entrySet()) {
                    sb.append(escape(e.getKey()));
                    sb.append('=');
                    sb.append(escape(e.getValue()));
                    sb.append('\n');
                }
            }
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Failed to flush cache: {}", e.getMessage());
            dirty.set(true);
        }
    }

    public void shutdown() {
        flushTimer.shutdown();
        try {
            flushTimer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        flushNow();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\0", "\\0")
                .replace("=", "\\=");
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                switch (n) {
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case '0':
                        out.append('\0');
                        break;
                    default:
                        out.append(n);
                        break;
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
