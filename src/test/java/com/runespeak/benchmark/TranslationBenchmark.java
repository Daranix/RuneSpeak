package com.runespeak.benchmark;

import ai.onnxruntime.OrtEnvironment;
import com.runespeak.translate.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class TranslationBenchmark {

    private static final DecimalFormat FMT = new DecimalFormat("#,##0");

    private static final String[][] TEST_PHRASES = {
            {"eng_Latn", "spa_Latn", "Cancel"},
            {"eng_Latn", "spa_Latn", "Door"},
            {"eng_Latn", "spa_Latn", "Walk here"},
    };

    private static final String[] DEFAULT_MODELS = {
            "Xenova/nllb-200-distilled-600M",
    };

    private static final long PER_PHRASE_TIMEOUT_MS = 120_000;
    private static final long MODEL_LOAD_TIMEOUT_MS = 300_000;

    private static Path modelsDir;

    private static OrtEnvironment ortEnv() {
        if (ortEnvField == null) {
            try {
                ortEnvField = OrtEnvironment.getEnvironment();
            } catch (UnsatisfiedLinkError e) {
                System.err.println("WARN: ONNX Runtime native lib unavailable: " + e.getMessage());
            }
        }
        return ortEnvField;
    }
    private static OrtEnvironment ortEnvField;

    static class Result {
        String modelId;
        String phrase;
        String translation;
        long timeMs;
        boolean timedOut;
        boolean failed;
        String failReason;
        List<String> warnings;

        Result(String modelId, String phrase) {
            this.modelId = modelId;
            this.phrase = phrase;
            this.warnings = new ArrayList<>();
        }
    }

    static class ModelReport {
        String modelId;
        String runtimeType;
        long loadTimeMs;
        boolean loadFailed;
        String loadError;
        List<Result> results = new ArrayList<>();
        String sizeInfo;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("onnxruntime.native.path", "C:\\Users\\Daranix\\.gemini\\antigravity-ide\\brain\\e70147b7-9091-4de9-85e9-b4401d7b1bfb\\scratch\\ort_cpu_libs");
        System.out.println("System property onnxruntime.native.path set to: " + System.getProperty("onnxruntime.native.path"));
        System.out.println("=== Running TranslationBenchmark in CPU Mode ===");
        System.out.println("====================================\n");

        String cacheRoot = System.getProperty("runespeak.cache",
                Paths.get(System.getProperty("user.home"), ".runespeak").toString());
        modelsDir = Paths.get(cacheRoot, "models");

        String[] modelsToBench = args.length > 0 ? args : DEFAULT_MODELS;

        System.out.println("=== RuneSpeak Translation Benchmark ===");
        System.out.println("Cache dir: " + modelsDir.toAbsolutePath());
        System.out.println("Models: " + String.join(", ", modelsToBench));
        System.out.println("Phrases: " + TEST_PHRASES.length);
        System.out.println("Per-phrase timeout: " + PER_PHRASE_TIMEOUT_MS + "ms");
        System.out.println();

        List<ModelReport> reports = new ArrayList<>();
        for (String modelId : modelsToBench) {
            ModelReport report = benchmarkModel(modelId);
            reports.add(report);
        }

        printSummary(reports);
        if (ortEnvField != null) ortEnvField.close();
    }



    static ModelReport benchmarkModel(String modelId) {
        System.out.println("━".repeat(70));
        System.out.println("MODEL: " + modelId);
        System.out.println("━".repeat(70));

        ModelReport report = new ModelReport();
        report.modelId = modelId;

        ModelRuntime runtime;
        try {
            runtime = ModelRuntime.forModelId(modelId);
            report.runtimeType = runtime.getClass().getSimpleName();
            System.out.println("Runtime: " + report.runtimeType);

            Path modelPath = modelsDir.resolve(sanitizeModelId(modelId));
            Files.createDirectories(modelPath);

            long totalSize = downloadIfMissing(modelPath, runtime);
            report.sizeInfo = totalSize > 0 ? humanSize(totalSize) : "cached";
            System.out.println("Model size: " + report.sizeInfo);

            long t0 = System.currentTimeMillis();
            runtime.load(modelPath, ortEnv());
            long t1 = System.currentTimeMillis();
            report.loadTimeMs = t1 - t0;
            report.loadFailed = false;
            System.out.println("Load time: " + FMT.format(report.loadTimeMs) + "ms");
            System.out.println();
        } catch (Exception e) {
            report.loadFailed = true;
            report.loadError = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.out.println("LOAD FAILED: " + report.loadError);
            System.out.println();
            return report;
        }

        System.out.printf("%-25s %-40s %8s  %s%n", "PHRASE", "TRANSLATION", "TIME", "CHECKS");
        System.out.println("-".repeat(90));

        for (String[] phrase : TEST_PHRASES) {
            String srcLang = phrase[0];
            String tgtLang = phrase[1];
            String text = phrase[2];

            Result res = new Result(modelId, text);
            long t0 = System.nanoTime();

            try {
                CompletableFuture<String> future = runtime.translate(text, srcLang, tgtLang);
                String result = future.get(PER_PHRASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                long t1 = System.nanoTime();
                res.timeMs = (t1 - t0) / 1_000_000;
                res.translation = result;
                res.timedOut = false;
                res.failed = false;

                checkQuality(res, text, tgtLang);
            } catch (java.util.concurrent.TimeoutException e) {
                res.timedOut = true;
                res.timeMs = PER_PHRASE_TIMEOUT_MS;
                res.translation = "(TIMEOUT)";
            } catch (Exception e) {
                res.failed = true;
                res.failReason = e.getClass().getSimpleName() + ": " + e.getMessage();
                res.translation = "(ERROR)";
            }

            report.results.add(res);
            printResult(res);
        }

        runtime.shutdown();
        System.out.println();
        return report;
    }

    static void checkQuality(Result res, String original, String expectedLang) {
        if (res.translation == null || res.translation.isBlank()) {
            res.warnings.add("EMPTY");
            return;
        }

        String t = res.translation;

        if (t.equals(original)) {
            res.warnings.add("UNCHANGED");
        }

        if (t.contains("<col") || t.contains("</col>") || t.contains("<br")) {
            res.warnings.add("HAS_HTML");
        }

        if (t.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            res.warnings.add("HAS_CHINESE");
        }

        if (t.length() > original.length() * 5 && original.length() > 2) {
            res.warnings.add("TOO_LONG(" + t.length() + " chars)");
        }

        if (t.toLowerCase().contains("note:") || t.toLowerCase().contains("translation:")
                || t.toLowerCase().contains("explanation:") || t.toLowerCase().contains("sure, ")
                || t.toLowerCase().contains("here is")) {
            res.warnings.add("HAS_EXPLANATION");
        }

        if (t.contains("¿Qué es") || t.contains("what is") || t.contains("what does")) {
            res.warnings.add("DEFINITION");
        }
    }

    static void printResult(Result res) {
        String display = res.translation != null
                ? res.translation.replace('\n', ' ').replace('\r', ' ').trim()
                : "(null)";
        if (display.length() > 38) display = display.substring(0, 35) + "...";

        String time = res.timedOut ? " TIMEOUT" : FMT.format(res.timeMs) + "ms";
        String checks = res.warnings.isEmpty() ? "OK" : String.join(", ", res.warnings);

        System.out.printf("%-25s %-40s %8s  %s%n",
                truncate(res.phrase, 24),
                truncate(display, 39),
                time,
                checks);
    }

    static void printSummary(List<ModelReport> reports) {
        System.out.println();
        System.out.println("=".repeat(70));
        System.out.println("SUMMARY");
        System.out.println("=".repeat(70));

        System.out.printf("%-40s %-12s %-10s %-8s %-8s %s%n",
                "MODEL", "RUNTIME", "LOAD", "PASS", "FAIL", "WARN");
        System.out.println("-".repeat(90));

        for (ModelReport r : reports) {
            if (r.loadFailed) {
                System.out.printf("%-40s %-12s %-10s %-8s %-8s %s%n",
                        r.modelId, r.runtimeType, "FAILED", "-", "-", r.loadError);
                continue;
            }

            long pass = r.results.stream().filter(res -> res.warnings.isEmpty() && !res.failed && !res.timedOut).count();
            long fail = r.results.stream().filter(res -> res.failed || res.timedOut).count();
            long warn = r.results.size() - pass - fail;

            System.out.printf("%-40s %-12s %-10s %-8s %-8s %s%n",
                    r.modelId, r.runtimeType, FMT.format(r.loadTimeMs) + "ms",
                    pass + "/" + r.results.size(), fail, warn);
        }

        System.out.println();
        System.out.println("WARNINGS LEGEND:");
        System.out.println("  UNCHANGED     — output equals input (no translation applied)");
        System.out.println("  EMPTY         — output is blank");
        System.out.println("  HAS_HTML      — output contains HTML/color tags");
        System.out.println("  HAS_CHINESE   — output contains Chinese characters");
        System.out.println("  TOO_LONG      — output is disproportionately longer than input");
        System.out.println("  HAS_EXPLANATION — model output explanations instead of translating");
        System.out.println("  DEFINITION    — model asked 'what is X?' instead of translating");
    }

    static long downloadIfMissing(Path modelPath, ModelRuntime runtime) throws IOException {
        long total = 0;
        for (DownloadFile file : runtime.getRequiredFiles()) {
            Files.createDirectories(modelPath);
            Path filePath = modelPath.resolve(file.getLocalFilename());
            if (Files.exists(filePath) && Files.size(filePath) > 1000) {
                total += Files.size(filePath);
                continue;
            }

            String url = file.getDownloadUrl(runtime.getModelId());
            System.out.println("  Downloading " + file.getLocalFilename() + "...");
            Path tmp = filePath.resolveSibling(filePath.getFileName() + ".tmp");
            try (ReadableByteChannel in = Channels.newChannel(new URL(url).openStream());
                 FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.getChannel().transferFrom(in, 0, Long.MAX_VALUE);
            }
            Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            long sz = Files.size(filePath);
            total += sz;
            System.out.println("    " + humanSize(sz) + " — done");
        }
        return total;
    }

    static String sanitizeModelId(String modelId) {
        return modelId.replace('/', '_').replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
