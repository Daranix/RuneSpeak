package com.runespeak.translate;

import org.junit.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.junit.Assert.*;

public class JavaEncoderDecoderRuntimeTest {

    private static final String TEST_MODEL = "onnx-community/opus-mt-en-es";
    private static final Path CACHE_DIR = Paths.get(
            System.getProperty("runespeak.cache", "F:\\runespeak_cache")
    ).resolve("models");

    @Test
    public void downloadAndTranslate() throws Exception {
        String modelId = TEST_MODEL;
        String sanitized = modelId.replace('/', '_').replaceAll("[^a-zA-Z0-9_-]", "_");
        Path modelPath = CACHE_DIR.resolve(sanitized);

        System.out.println("Model cache path: " + modelPath);

        JavaEncoderDecoderRuntime runtime = new JavaEncoderDecoderRuntime(modelId);

        for (DownloadFile file : runtime.getRequiredFiles()) {
            downloadIfMissing(modelId, modelPath, file);
        }

        System.out.println("All files downloaded. Loading model...");
        runtime.load(modelPath);

        System.out.println("Model loaded. Running translations...");
        assertTrue("Model should be loaded", runtime.isLoaded());

        String[][] cases = {
            {"Hello, how are you?", "Hola, \u00BFc\u00F3mo est\u00E1s?"},
            {"Good morning", "Buenos d\u00EDas."},
            {"Thank you very much", "Muchas gracias."},
            {"Where is the bathroom?", "\u00BFD\u00F3nde est\u00E1 el ba\u00F1o?"},
            {"I love programming", "Me encanta la programaci\u00F3n"},
            {"The weather is nice today", "El tiempo es agradable hoy"},
            {"One, two, three", "Uno, dos, tres"},
            {"My name is John", "Mi nombre es John."},
            {"How much does this cost?", "\u00BFCu\u00E1nto cuesta esto?"},
            {"See you tomorrow", "Hasta ma\u00F1ana."},
        };

        for (String[] pair : cases) {
            String input = pair[0];
            String expected = pair[1];
            String result = runtime.translate(input, "eng", "spa").get();
            System.out.println("  \"" + input + "\" -> \"" + result + "\"");
            assertNotNull("Translation should not be null for: " + input, result);
            assertFalse("Translation should not be blank for: " + input, result.isBlank());
            assertFalse("Translation should not be the hourglass for: " + input, result.startsWith("\u23F3"));
            assertFalse("Translation should differ from input: " + input, result.equals(input));
            assertEquals("Translation mismatch for: " + input, expected, result);
        }

        System.out.println("SUCCESS: All " + cases.length + " translations correct");

        runtime.shutdown();
    }

    private void downloadIfMissing(String modelId, Path modelPath, DownloadFile file) throws IOException {
        Files.createDirectories(modelPath);
        Path filePath = modelPath.resolve(file.getLocalFilename());
        if (Files.exists(filePath) && Files.size(filePath) > 1000) {
            System.out.println("Already cached: " + file.getLocalFilename() + " (" + Files.size(filePath) + " bytes)");
            return;
        }

        String url = file.getDownloadUrl(modelId);
        System.out.println("Downloading " + file.getLocalFilename() + " from " + url + " ...");
        long t0 = System.currentTimeMillis();
        Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try (ReadableByteChannel in = Channels.newChannel(new URL(url).openStream());
             FileOutputStream out = new FileOutputStream(tmp.toFile())) {
            out.getChannel().transferFrom(in, 0, Long.MAX_VALUE);
        }
        Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
        long elapsed = System.currentTimeMillis() - t0;
        System.out.println("Downloaded " + file.getLocalFilename() + " (" + Files.size(filePath) + " bytes) in " + elapsed + "ms");
    }
}
