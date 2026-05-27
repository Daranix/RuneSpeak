package com.runespeak.translate;

import ai.onnxruntime.OrtEnvironment;
import org.junit.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class GemmaBenchmarkTest {

    @Test
    public void testGemmaSpeed() throws Exception {
        String modelId = "onnx-community/gemma-4-E2B-it-ONNX";
        ModelRuntime runtime = ModelRuntime.forModelId(modelId);
        
        String cacheRoot = System.getProperty("runespeak.cache",
                Paths.get(System.getProperty("user.home"), ".runespeak").toString());
        Path modelsDir = Paths.get(cacheRoot, "models");
        Path modelPath = modelsDir.resolve(modelId.replace('/', '_').replaceAll("[^a-zA-Z0-9_-]", "_"));
        
        System.out.println("Gemma 2B Benchmark:");
        System.out.println("Model Path: " + modelPath.toAbsolutePath());
        
        // Check if model files exist before running benchmark
        boolean filesPresent = true;
        for (DownloadFile file : runtime.getRequiredFiles()) {
            Path filePath = modelPath.resolve(file.getLocalFilename());
            if (!Files.exists(filePath) || Files.size(filePath) < 1000) {
                filesPresent = false;
                break;
            }
        }
        
        if (!filesPresent) {
            System.out.println("Gemma 2B model files not fully cached. Skipping speed test.");
            System.out.println("To run this benchmark, make sure you download the model files to: " + modelPath.toAbsolutePath());
            return;
        }
        
        OrtEnvironment env = null;
        try {
            env = OrtEnvironment.getEnvironment();
        } catch (UnsatisfiedLinkError e) {
            System.out.println("Skipping benchmark: ONNX native libraries not loaded.");
            return;
        }
        
        long t0 = System.currentTimeMillis();
        runtime.load(modelPath, env);
        long loadTime = System.currentTimeMillis() - t0;
        System.out.println("Gemma 2B Load Time: " + loadTime + " ms");
        
        String[] prompts = {
            "Cancel",
            "Walk here",
            "Talk-to Banker"
        };
        
        for (String prompt : prompts) {
            long t1 = System.nanoTime();
            String translation = runtime.translate(prompt, "eng_Latn", "spa_Latn").get(30, TimeUnit.SECONDS);
            long duration = (System.nanoTime() - t1) / 1_000_000;
            System.out.println("Prompt: '" + prompt + "' -> '" + translation + "' (" + duration + " ms)");
            assertNotNull(translation);
        }
        
        runtime.shutdown();
    }
}
