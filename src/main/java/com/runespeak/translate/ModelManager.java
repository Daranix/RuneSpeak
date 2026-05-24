package com.runespeak.translate;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class ModelManager {
    static final String DEFAULT_MODEL = "facebook/nllb-200-distilled-600M";
    private static final String WORKER_VERSION = "1.0.0";

    @Getter
    private volatile boolean loaded = false;
    @Getter
    private volatile boolean loading = false;
    @Getter
    private String currentModelId;

    private Process pythonProcess;
    private BufferedReader processReader;
    private BufferedWriter processWriter;
    private final Path cacheDir;
    private final Path runespeakDir;
    private final AtomicInteger requestId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private volatile Thread readerThread;

    private String configuredPythonPath;
    @Getter
    private volatile DependencyStatus dependencyStatus = new DependencyStatus(false, "", "", false);

    public ModelManager(Path runespeakDir, String pythonPath) {
        this.runespeakDir = runespeakDir;
        this.cacheDir = runespeakDir.resolve("models");
        this.currentModelId = DEFAULT_MODEL;
        this.configuredPythonPath = pythonPath;
    }

    public void setPythonPath(String pythonPath) {
        this.configuredPythonPath = pythonPath;
    }

    public synchronized void loadModel(String modelId) throws IOException {
        if (loaded && currentModelId.equals(modelId)) return;
        if (loading) return;

        shutdown();
        loading = true;
        currentModelId = modelId;

        try {
            Files.createDirectories(cacheDir);
            startPythonProcess();
            loaded = true;
            log.info("Model loaded: {}", modelId);
        } catch (Exception e) {
            loaded = false;
            throw new IOException("Failed to start model: " + modelId, e);
        } finally {
            loading = false;
        }
    }

    public synchronized DependencyResult checkDependencies() {
        String py = resolvePython();
        if (py == null) {
            return new DependencyResult(false, "Python 3 not found. Install Python 3 and ensure it's on PATH, or set a custom path in settings.", null, false);
        }

        String version = getPythonVersion(py);
        if (version == null) {
            return new DependencyResult(false, "Could not determine Python version from: " + py, null, false);
        }

        List<String> missing = new ArrayList<>();
        String[] deps = {"torch", "transformers", "sentencepiece"};
        for (String dep : deps) {
            try {
                Process p = new ProcessBuilder(py, "-c", "import " + dep)
                        .redirectErrorStream(true)
                        .start();
                boolean finished = p.waitFor(10, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    missing.add(dep + " (timeout)");
                } else if (p.exitValue() != 0) {
                    missing.add(dep);
                }
            } catch (Exception e) {
                missing.add(dep + " (error: " + e.getMessage() + ")");
            }
        }

        boolean cuda = false;
        if (!missing.contains("torch")) {
            try {
                Process p = new ProcessBuilder(py, "-c",
                        "import torch; print(torch.cuda.is_available())")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                boolean done = p.waitFor(10, TimeUnit.SECONDS);
                if (done && p.exitValue() == 0) {
                    cuda = "True".equals(r.readLine());
                }
            } catch (Exception ignored) {}
        }

        boolean allMet = missing.isEmpty();
        String status = allMet
                ? (cuda ? "All dependencies OK (CUDA available)" : "All dependencies OK (CPU)")
                : "Missing packages: " + String.join(", ", missing);

        dependencyStatus = new DependencyStatus(allMet, version, status, cuda);
        return new DependencyResult(allMet, version, status, cuda);
    }

    private String getPythonVersion(String py) {
        try {
            Process p = new ProcessBuilder(py, "--version")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return null;
            }
            return r.readLine();
        } catch (Exception e) {
            return null;
        }
    }

    private String resolvePython() {
        if (configuredPythonPath != null && !configuredPythonPath.isBlank()) {
            if (isPython3(configuredPythonPath)) {
                return configuredPythonPath;
            }
            log.warn("Configured Python path '{}' is not valid Python 3, falling back to PATH search", configuredPythonPath);
        }
        return findPythonOnPath();
    }

    private boolean isPython3(String candidate) {
        try {
            Process p = new ProcessBuilder(candidate, "--version")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            boolean done = p.waitFor(5, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            String line = r.readLine();
            return line != null && line.toLowerCase().contains("python 3");
        } catch (Exception e) {
            return false;
        }
    }

    private String findPythonOnPath() {
        String[] candidates = {"python3", "python", "py"};
        for (String c : candidates) {
            try {
                Process p = new ProcessBuilder(c, "--version")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                boolean done = p.waitFor(5, TimeUnit.SECONDS);
                if (!done) {
                    p.destroyForcibly();
                    continue;
                }
                String line = r.readLine();
                if (line != null && line.toLowerCase().contains("python 3")) {
                    return c;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    private void startPythonProcess() throws IOException {
        Path workerScript = extractWorkerScript();

        String py = resolvePython();
        if (py == null) {
            throw new IOException("Python 3 not found. Install Python 3 and ensure it's on PATH.");
        }

        ProcessBuilder pb = new ProcessBuilder(
                py, "-u", workerScript.toAbsolutePath().toString()
        );
        pb.environment().put("RUNESPEAK_MODEL", currentModelId);
        pb.environment().put("HF_HOME", cacheDir.toAbsolutePath().toString());
        pb.environment().put("HF_HUB_DISABLE_SYMLINKS_WARNING", "1");
        pb.directory(runespeakDir.toFile());
        pb.redirectErrorStream(false);

        pythonProcess = pb.start();
        processReader = new BufferedReader(new InputStreamReader(pythonProcess.getInputStream(), StandardCharsets.UTF_8));
        processWriter = new BufferedWriter(new OutputStreamWriter(pythonProcess.getOutputStream(), StandardCharsets.UTF_8));

        BufferedReader errorReader = new BufferedReader(new InputStreamReader(pythonProcess.getErrorStream(), StandardCharsets.UTF_8));
        Thread errorThread = new Thread(() -> {
            try {
                String line;
                while ((line = errorReader.readLine()) != null) {
                    log.info("[py-worker] {}", line);
                }
            } catch (IOException ignored) {}
        }, "runespeak-py-stderr");
        errorThread.setDaemon(true);
        errorThread.start();

        readerThread = new Thread(this::readLoop, "runespeak-py-stdout");
        readerThread.setDaemon(true);
        readerThread.start();

        String readyLine = processReader.readLine();
        if (readyLine == null) {
            throw new IOException("Python process exited without response");
        }

        JSONObject ready = new JSONObject(readyLine);
        if (!ready.optBoolean("ok", false)) {
            throw new IOException("Python worker error: " + ready.optString("error", "unknown"));
        }

        // Send ping to check worker health and dependencies
        JSONObject pingCmd = new JSONObject();
        pingCmd.put("cmd", "ping");
        int pingId = requestId.getAndIncrement();
        pingCmd.put("id", pingId);
        CompletableFuture<String> pingFuture = new CompletableFuture<>();
        pending.put(pingId, pingFuture);

        synchronized (processWriter) {
            processWriter.write(pingCmd.toString());
            processWriter.newLine();
            processWriter.flush();
        }

        try {
            String pingResult = pingFuture.get(10, TimeUnit.SECONDS);
            JSONObject pong = new JSONObject(pingResult);
            log.info("Python worker: v{}, torch={}, cuda={}, device={}",
                    pong.optString("worker_version", "?"),
                    pong.optBoolean("torch", false),
                    pong.optBoolean("cuda", false),
                    pong.optString("device", "?"));
            dependencyStatus = new DependencyStatus(
                    true,
                    pong.optString("python", "unknown"),
                    "OK (" + pong.optString("device", "cpu") + ")",
                    pong.optBoolean("cuda", false)
            );
        } catch (Exception e) {
            log.warn("Ping failed (non-fatal): {}", e.getMessage());
        }

        // Send load_model command
        JSONObject loadCmd = new JSONObject();
        loadCmd.put("cmd", "load_model");
        loadCmd.put("model", currentModelId);
        int loadId = requestId.getAndIncrement();
        loadCmd.put("id", loadId);
        CompletableFuture<String> loadFuture = new CompletableFuture<>();
        pending.put(loadId, loadFuture);

        synchronized (processWriter) {
            processWriter.write(loadCmd.toString());
            processWriter.newLine();
            processWriter.flush();
        }

        try {
            String loadResult = loadFuture.get(5, TimeUnit.MINUTES);
            JSONObject loadResp = new JSONObject(loadResult);
            if (!loadResp.optBoolean("ok", false)) {
                throw new IOException("Model load failed: " + loadResp.optString("error", "unknown"));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Model load timed out or failed", e);
        }
    }

    private void readLoop() {
        try {
            String line;
            while (processReader != null && (line = processReader.readLine()) != null) {
                JSONObject resp = new JSONObject(line);
                int id = resp.optInt("id", -1);
                if (id >= 0) {
                    CompletableFuture<String> future = pending.remove(id);
                    if (future != null) {
                        if (resp.optBoolean("ok", false)) {
                            future.complete(line);
                        } else {
                            future.completeExceptionally(
                                    new RuntimeException(resp.optString("error", "translation failed")));
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (loaded) {
                log.warn("Reader thread ended — Python process may have died");
                loaded = false;
                pending.forEach((id, f) ->
                        f.completeExceptionally(new RuntimeException("Python process died")));
                pending.clear();
            }
        }
    }

    public CompletableFuture<String> translate(String text, String srcLang, String tgtLang) {
        CompletableFuture<String> future = new CompletableFuture<>();
        int id = requestId.getAndIncrement();
        pending.put(id, future);

        JSONObject req = new JSONObject();
        req.put("id", id);
        req.put("text", text);
        req.put("src", srcLang);
        req.put("tgt", tgtLang);

        try {
            synchronized (processWriter) {
                processWriter.write(req.toString());
                processWriter.newLine();
                processWriter.flush();
            }
        } catch (IOException e) {
            pending.remove(id);
            future.completeExceptionally(e);
            log.error("Failed to send request: {}", e.getMessage());
            restartProcess();
        }

        return future;
    }

    private void restartProcess() {
        loaded = false;
        String modelId = currentModelId;
        currentModelId = DEFAULT_MODEL;
        try {
            loadModel(modelId);
        } catch (IOException e) {
            log.error("Failed to restart: {}", e.getMessage());
        }
    }

    private Path extractWorkerScript() throws IOException {
        Path scriptsDir = runespeakDir.resolve("python");
        Files.createDirectories(scriptsDir);
        Path target = scriptsDir.resolve("translate_worker.py");
        Path versionFile = scriptsDir.resolve(".worker_version");

        boolean needsExtract = !Files.exists(target);
        if (!needsExtract && Files.exists(versionFile)) {
            String existingVersion = Files.readString(versionFile).trim();
            if (!WORKER_VERSION.equals(existingVersion)) {
                log.info("Worker script version changed ({} -> {}), re-extracting",
                        existingVersion, WORKER_VERSION);
                needsExtract = true;
            }
        } else if (!needsExtract) {
            needsExtract = true;
        }

        if (needsExtract) {
            try (InputStream in = getClass().getResourceAsStream("/com/runespeak/python/translate_worker.py")) {
                if (in == null) {
                    throw new IOException("Worker script not found in resources");
                }
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(versionFile, WORKER_VERSION);
            log.info("Extracted worker script v{} to: {}", WORKER_VERSION, target);
        }

        return target;
    }

    public boolean isModelAvailable() {
        return loaded;
    }

    public void shutdown() {
        if (pythonProcess != null && pythonProcess.isAlive()) {
            try {
                JSONObject cmd = new JSONObject();
                cmd.put("cmd", "shutdown");
                synchronized (processWriter) {
                    processWriter.write(cmd.toString());
                    processWriter.newLine();
                    processWriter.flush();
                }
                pythonProcess.waitFor(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
            pythonProcess.destroyForcibly();
        }
        loaded = false;
        pending.forEach((id, f) -> f.completeExceptionally(new RuntimeException("shutdown")));
        pending.clear();
    }

    @Getter
    public static class DependencyStatus {
        private final boolean allMet;
        private final String pythonVersion;
        private final String statusMessage;
        private final boolean cudaAvailable;

        public DependencyStatus(boolean allMet, String pythonVersion, String statusMessage, boolean cudaAvailable) {
            this.allMet = allMet;
            this.pythonVersion = pythonVersion;
            this.statusMessage = statusMessage;
            this.cudaAvailable = cudaAvailable;
        }
    }

    @Getter
    public static class DependencyResult {
        private final boolean allMet;
        private final String pythonVersion;
        private final String statusMessage;
        private final boolean cudaAvailable;

        public DependencyResult(boolean allMet, String pythonVersion, String statusMessage, boolean cudaAvailable) {
            this.allMet = allMet;
            this.pythonVersion = pythonVersion;
            this.statusMessage = statusMessage;
            this.cudaAvailable = cudaAvailable;
        }
    }
}
