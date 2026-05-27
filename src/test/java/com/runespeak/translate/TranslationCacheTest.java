package com.runespeak.translate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

public class TranslationCacheTest {

    private Path tempDir;
    private TranslationCache cache;

    @Before
    public void setUp() throws IOException {
        tempDir = Files.createTempDirectory("runespeak-cache-test-");
        cache = new TranslationCache(tempDir, 100);
    }

    @After
    public void tearDown() {
        cache.shutdown();
        try {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException ignored) {}
    }

    @Test
    public void putAndGet() {
        cache.put("Hello", "Hola", "eng_Latn", "spa_Latn");
        assertEquals("Hola", cache.get("Hello", "eng_Latn", "spa_Latn"));
    }

    @Test
    public void getMissing() {
        assertNull(cache.get("Hello", "eng_Latn", "spa_Latn"));
    }

    @Test
    public void keyIncludesSourceLang() {
        cache.put("Hello", "Hola", "eng_Latn", "spa_Latn");
        assertNull(cache.get("Hello", "fra_Latn", "spa_Latn"));
    }

    @Test
    public void keyIncludesTargetLang() {
        cache.put("Hello", "Hola", "eng_Latn", "spa_Latn");
        assertNull(cache.get("Hello", "eng_Latn", "fra_Latn"));
    }

    @Test
    public void keyIncludesText() {
        cache.put("Hello", "Hola", "eng_Latn", "spa_Latn");
        assertNull(cache.get("Hi", "eng_Latn", "spa_Latn"));
    }

    @Test
    public void differentLanguagesSameText() {
        cache.put("Hello", "Hola", "eng_Latn", "spa_Latn");
        cache.put("Hello", "Bonjour", "eng_Latn", "fra_Latn");
        assertEquals("Hola", cache.get("Hello", "eng_Latn", "spa_Latn"));
        assertEquals("Bonjour", cache.get("Hello", "eng_Latn", "fra_Latn"));
    }

    @Test
    public void putNullOriginalTextDoesNothing() {
        cache.put(null, "Hola", "eng_Latn", "spa_Latn");
        assertNull(cache.get("Hello", "eng_Latn", "spa_Latn"));
    }

    @Test
    public void putNullTranslationDoesNothing() {
        cache.put("Hello", null, "eng_Latn", "spa_Latn");
        assertNull(cache.get("Hello", "eng_Latn", "spa_Latn"));
    }

    @Test
    public void evictionWhenExceedingMaxSize() {
        cache.setMaxSize(3);
        cache.put("A", "a", "en", "es");
        cache.put("B", "b", "en", "es");
        cache.put("C", "c", "en", "es");
        cache.put("D", "d", "en", "es");
        assertNull(cache.get("A", "en", "es"));
        assertEquals("b", cache.get("B", "en", "es"));
        assertEquals("c", cache.get("C", "en", "es"));
        assertEquals("d", cache.get("D", "en", "es"));
    }

    @Test
    public void accessPreventsEviction() {
        cache.setMaxSize(3);
        cache.put("A", "a", "en", "es");
        cache.put("B", "b", "en", "es");
        cache.put("C", "c", "en", "es");
        cache.get("A", "en", "es");
        cache.put("D", "d", "en", "es");
        assertNotNull(cache.get("A", "en", "es"));
        assertNull(cache.get("B", "en", "es"));
    }

    @Test
    public void setMaxSizeEvictsEntries() {
        for (int i = 0; i < 10; i++) {
            cache.put("K" + i, "V" + i, "en", "es");
        }
        assertEquals(10, cache.size());
        cache.setMaxSize(5);
        assertEquals(5, cache.size());
    }

    @Test
    public void clearRemovesAll() {
        cache.put("Hello", "Hola", "en", "es");
        cache.put("Goodbye", "Adiós", "en", "es");
        assertEquals(2, cache.size());
        cache.clear();
        assertEquals(0, cache.size());
        assertNull(cache.get("Hello", "en", "es"));
    }

    @Test
    public void size() {
        assertEquals(0, cache.size());
        cache.put("A", "a", "en", "es");
        assertEquals(1, cache.size());
        cache.put("B", "b", "en", "es");
        assertEquals(2, cache.size());
    }

    @Test
    public void overwriteExistingKey() {
        cache.put("Hello", "Hola", "en", "es");
        cache.put("Hello", "¡Hola!", "en", "es");
        assertEquals("¡Hola!", cache.get("Hello", "en", "es"));
    }

    @Test
    public void flushAndReload() {
        cache.put("Hello", "Hola", "en", "es");
        cache.put("Goodbye", "Adiós", "en", "es");
        cache.flushNow();

        TranslationCache reloaded = new TranslationCache(tempDir, 100);
        assertEquals("Hola", reloaded.get("Hello", "en", "es"));
        assertEquals("Adiós", reloaded.get("Goodbye", "en", "es"));
        reloaded.shutdown();
    }

    @Test
    public void specialCharactersSurviveRoundTrip() {
        String original = "He said \"Hello\" & goodbye\nNew line\tTab\\Backslash\0NUL=equals";
        String translated = "Ella dijo: «¡Hola!»\nNueva línea";
        cache.put(original, translated, "en", "es");
        cache.flushNow();

        TranslationCache reloaded = new TranslationCache(tempDir, 100);
        assertEquals(translated, reloaded.get(original, "en", "es"));
        reloaded.shutdown();
    }

    @Test
    public void emptyText() {
        cache.put("", "vacío", "en", "es");
        assertEquals("vacío", cache.get("", "en", "es"));
    }

    @Test
    public void getMaxSize() {
        assertEquals(100, cache.getMaxSize());
        cache.setMaxSize(50);
        assertEquals(50, cache.getMaxSize());
    }

    @Test
    public void flushWithoutDirtyDoesNotWrite() {
        cache.flushNow();
        cache.put("Hello", "Hola", "en", "es");
        cache.flushNow();
        assertEquals("Hola", cache.get("Hello", "en", "es"));
    }

    @Test
    public void cacheDirCreatedOnFlush() throws IOException {
        Path nestedDir = tempDir.resolve("sub").resolve("nested");
        TranslationCache nestedCache = new TranslationCache(nestedDir, 100);
        nestedCache.put("Hello", "Hola", "en", "es");
        nestedCache.flushNow();
        assertTrue(Files.exists(nestedDir.resolve("translation_cache.properties")));
        nestedCache.shutdown();
    }
}
