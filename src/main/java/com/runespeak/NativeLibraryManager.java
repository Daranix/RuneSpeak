package com.runespeak;

import lombok.extern.slf4j.Slf4j;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
public class NativeLibraryManager {

    private static final String ORT_MAVEN_URL =
            "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.17.1/onnxruntime-1.17.1.jar";
    private static final String TOKENIZERS_MAVEN_URL =
            "https://repo1.maven.org/maven2/ai/djl/huggingface/tokenizers/0.36.0/tokenizers-0.36.0.jar";

    private static final long ORT_JAR_EXPECTED = 87_521_508;
    private static final long TOKENIZERS_JAR_EXPECTED = 18_716_955;

    private final Path nativesDir;
    private volatile boolean nativesReady = false;

    public NativeLibraryManager(Path runespeakDir) {
        this.nativesDir = runespeakDir.resolve("natives");
    }

    public boolean ensure() {
        if (nativesReady) return true;

        try {
            Files.createDirectories(nativesDir);

            String platform = detectPlatform();
            log.debug("Detected platform: {}", platform);

            String[] ortFiles = ortLibraryFiles(platform);
            String tokenizerFile = tokenizerLibraryFile(platform);
            String ortNativeDir = ortNativeDir(platform);
            String tokenizerJarDir = tokenizerJarDir(platform);

            if (ortFiles == null || tokenizerFile == null) {
                log.warn("Unsupported platform: {}, natives will not be loaded", platform);
                return false;
            }

            for (String file : ortFiles) {
                Path libPath = nativesDir.resolve(file);
                if (!Files.exists(libPath)) {
                    log.info("ONNX Runtime native not found ({}), downloading from Maven...", file);
                    downloadJarAndExtract(ORT_MAVEN_URL, "onnxruntime-1.17.1.jar",
                            ORT_JAR_EXPECTED, "ai/onnxruntime/native/" + ortNativeDir + "/", file);
                }
            }

            {
                Path libPath = nativesDir.resolve(tokenizerFile);
                if (!Files.exists(libPath)) {
                    log.info("Tokenizer native not found, downloading from Maven...");
                    downloadJarAndExtract(TOKENIZERS_MAVEN_URL, "tokenizers-0.36.0.jar",
                            TOKENIZERS_JAR_EXPECTED, "native/lib/" + tokenizerJarDir + "/cpu/", tokenizerFile);
                }
            }

            // Load ORT core first (dependency of the JNI bridge), then the JNI bridge
            for (String file : ortFiles) {
                Path libPath = nativesDir.resolve(file);
                System.load(libPath.toAbsolutePath().toString());
                log.debug("Loaded {}", libPath.getFileName());
            }

            {
                Path libPath = nativesDir.resolve(tokenizerFile);
                System.load(libPath.toAbsolutePath().toString());
                log.debug("Loaded {}", libPath.getFileName());
            }

            nativesReady = true;
            log.info("Native libraries loaded successfully");
            return true;
        } catch (Exception e) {
            log.error("Failed to load native libraries: {}", e.getMessage(), e);
            return false;
        }
    }

    private void downloadJarAndExtract(String url, String jarName, long expectedSize,
                                       String jarPrefix, String targetFile) throws IOException {
        Path cachedJar = nativesDir.resolve(jarName);
        if (!Files.exists(cachedJar) || Files.size(cachedJar) != expectedSize) {
            log.info("Downloading {} from Maven Central...", jarName);
            Path tmp = cachedJar.resolveSibling(cachedJar.getFileName() + ".tmp");
            try (ReadableByteChannel in = Channels.newChannel(new URL(url).openStream());
                 FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                out.getChannel().transferFrom(in, 0, Long.MAX_VALUE);
            }
            Files.move(tmp, cachedJar, StandardCopyOption.REPLACE_EXISTING);
            log.info("Downloaded {} ({})", jarName, humanSize(Files.size(cachedJar)));
        } else {
            log.debug("Using cached {}", jarName);
        }

        try (ZipFile zf = new ZipFile(cachedJar.toFile())) {
            String targetEntryPath = jarPrefix + targetFile;
            ZipEntry entry = zf.getEntry(targetEntryPath);
            if (entry == null) {
                log.warn("Could not find '{}' in {} (looking for {})", targetEntryPath, jarName, targetFile);
                return;
            }
            Path target = nativesDir.resolve(targetFile);
            if (!Files.exists(target)) {
                try (InputStream is = zf.getInputStream(entry)) {
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
                log.debug("Extracted {} -> {}", targetEntryPath, target);
            }
        }
    }

    static String detectPlatform() {
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch").toLowerCase();

        if (os.contains("linux")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? "linux-aarch64" : "linux-x64";
        }
        if (os.contains("windows")) {
            return "win-x64";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return arch.contains("aarch64") || arch.contains("arm64") ? "osx-aarch64" : "osx-x64";
        }
        return "unknown";
    }

    static String ortNativeDir(String platform) {
        return platform;
    }

    static String[] ortLibraryFiles(String platform) {
        switch (platform) {
            case "linux-x64":
            case "linux-aarch64":
                return new String[]{"libonnxruntime.so", "libonnxruntime4j_jni.so"};
            case "win-x64":
                return new String[]{"onnxruntime.dll", "onnxruntime4j_jni.dll"};
            case "osx-x64":
            case "osx-aarch64":
                return new String[]{"libonnxruntime.dylib", "libonnxruntime4j_jni.dylib"};
            default:
                return null;
        }
    }

    static String tokenizerJarDir(String platform) {
        switch (platform) {
            case "linux-x64":
                return "linux-x86_64";
            case "linux-aarch64":
                return "linux-aarch64";
            case "win-x64":
                return "win-x86_64";
            case "osx-x64":
            case "osx-aarch64":
                return "osx-aarch64";
            default:
                return null;
        }
    }

    static String tokenizerLibraryFile(String platform) {
        switch (platform) {
            case "linux-x64":
            case "linux-aarch64":
                return "libtokenizers.so";
            case "win-x64":
                return "tokenizers.dll";
            case "osx-x64":
            case "osx-aarch64":
                return "libtokenizers.dylib";
            default:
                return null;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
