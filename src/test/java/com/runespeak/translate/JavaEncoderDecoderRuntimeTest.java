package com.runespeak.translate;

import com.google.gson.Gson;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class JavaEncoderDecoderRuntimeTest {

    private static final String TEST_MODEL = "onnx-community/opus-mt-en-es";
    private static final Path CACHE_DIR = Paths.get(
            System.getProperty("runespeak.cache", "F:\\runespeak_cache")
    ).resolve("models");

    @Test
    public void downloadAndTranslate() throws Exception {
        testLanguage(TEST_MODEL, "eng", "spa", new String[][]{
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
        });
    }

    @Test
    public void downloadAndTranslateEnFr() throws Exception {
        testLanguage("onnx-community/opus-mt-en-fr", "eng", "fra", new String[][]{
            {"Good morning", "Bonjour."},
            {"Thank you very much", "Merci beaucoup."},
            {"Where is the bathroom?", "O\u00F9 sont les toilettes ?"},
            {"My name is John", "Mon nom est John."},
            {"See you tomorrow", "A demain."},
        });
    }

    @Test
    public void downloadAndTranslateEnDe() throws Exception {
        testLanguage("onnx-community/opus-mt-en-de", "eng", "deu", new String[][]{
            {"Good morning", "Guten Morgen."},
            {"Thank you very much", "Vielen Dank."},
            {"Where is the bathroom?", "Wo ist das Badezimmer?"},
            {"My name is John", "Mein Name ist John."},
            {"See you tomorrow", "Bis morgen."},
        });
    }

    @Test
    public void downloadAndTranslateEnNl() throws Exception {
        testLanguage("onnx-community/opus-mt-en-nl", "eng", "nld", new String[][]{
            {"Good morning", "Goedemorgen."},
            {"Thank you very much", "Hartelijk dank."},
            {"Where is the bathroom?", "Waar is de badkamer?"},
            {"My name is John", "Mijn naam is John."},
            {"See you tomorrow", "Tot morgen."},
        });
    }

    @Test
    public void downloadAndTranslateEnRu() throws Exception {
        testLanguage("onnx-community/opus-mt-en-ru", "eng", "rus", new String[][]{
            {"Good morning", "\u0414\u043E\u0431\u0440\u043E\u0435 \u0443\u0442\u0440\u043E."},
            {"Thank you very much", "\u0411\u043E\u043B\u044C\u0448\u043E\u0435 \u0441\u043F\u0430\u0441\u0438\u0431\u043E."},
            {"Where is the bathroom?", "\u0413\u0434\u0435 \u0432\u0430\u043D\u043D\u0430\u044F?"},
            {"My name is John", "\u041C\u0435\u043D\u044F \u0437\u043E\u0432\u0443\u0442 \u0414\u0436\u043E\u043D."},
            {"See you tomorrow", "\u0414\u043E \u0437\u0430\u0432\u0442\u0440\u0430."},
        });
    }

    @Test
    public void downloadAndTranslateEnZh() throws Exception {
        testLanguage("onnx-community/opus-mt-en-zh", "eng", "zho", new String[][]{
            {"Good morning", "\u65E9\u4E0A\u597D\u002C\u65E9"},
            {"Thank you very much", "\u975E\u5E38\u611F\u8C22"},
            {"My name is John", "\u6211\u53EB\u7EA6\u7FF0"},
            {"See you tomorrow", "\u660E\u5929\u89C1"},
        });
    }

    @Test
    public void downloadAndTranslateEnAr() throws Exception {
        testLanguage("onnx-community/opus-mt-en-ar", "eng", "ara", new String[][]{
            {"Good morning", "\u0633\u064F\u0646\u0651 \u0627\u0644\u062E\u064A\u0631"},
            {"Thank you very much", "\u0634\u0643\u0631\u0627 \u062C\u0632\u064A\u0644\u0627"},
            {"My name is John", "\u0627\u0633\u0645\u064A \u0647\u0648 (\u062C\u0648\u0646)"},
            {"See you tomorrow", "\u0623\u0631\u0627\u0643 \u063A\u062F\u0627\u064B"},
        });
    }

    @Test
    public void downloadAndTranslateEnIt() throws Exception {
        testLanguage("Xenova/opus-mt-en-it", "eng", "ita", new String[][]{
            {"Good morning", "Buongiorno."},
            {"Thank you very much", "Grazie mille."},
            {"Where is the bathroom?", "Dov'\u00E8 il bagno?"},
            {"My name is John", "Mi chiamo John."},
            {"See you tomorrow", "A domani."},
        });
    }

    private void testLanguage(String modelId, String srcLang, String tgtLang, String[][] cases) throws Exception {
        String sanitized = modelId.replace('/', '_').replaceAll("[^a-zA-Z0-9_-]", "_");
        Path modelPath = CACHE_DIR.resolve(sanitized);

        System.out.println("Model cache path: " + modelPath);

        JavaEncoderDecoderRuntime runtime = new JavaEncoderDecoderRuntime(modelId, new Gson());

        for (DownloadFile file : runtime.getRequiredFiles()) {
            downloadIfMissing(modelId, modelPath, file);
        }

        System.out.println("All files downloaded. Loading model...");
        runtime.load(modelPath);

        System.out.println("Model loaded. Running translations...");
        assertTrue("Model should be loaded", runtime.isLoaded());

        List<String> errors = new ArrayList<>();
        for (String[] pair : cases) {
            String input = pair[0];
            String expected = pair[1];
            String result = runtime.translate(input, "eng", tgtLang).get();
            System.out.println("  \"" + input + "\" -> \"" + result + "\"");
            try {
                assertNotNull("Translation should not be null for: " + input, result);
                assertFalse("Translation should not be blank for: " + input, result.isBlank());
                assertFalse("Translation should not be the hourglass for: " + input, result.startsWith("\u23F3"));
                assertFalse("Translation should differ from input: " + input, result.equals(input));
                assertEquals("Translation mismatch for: " + input, expected, result);
            } catch (AssertionError e) {
                errors.add(input + " -> expected=[" + expected + "] actual=[" + result + "]");
            }
        }

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder(errors.size() + " translation(s) incorrect:\n");
            for (String err : errors) sb.append("  ").append(err).append("\n");
            System.err.print(sb.toString());
            fail(sb.toString().trim());
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
