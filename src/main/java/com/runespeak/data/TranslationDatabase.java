package com.runespeak.data;

import com.runespeak.RuneSpeakConfig;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Singleton
public class TranslationDatabase {
    private volatile Connection connection;
    private Path dbPath;
    private String dbUrl;

    @Inject
    public TranslationDatabase() {
    }

    public synchronized void init(RuneSpeakConfig config) {
        try {
            String custom = config.getModelCacheDir();
            Path baseDir = custom != null && !custom.isBlank()
                    ? Path.of(custom)
                    : new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "runespeak").toPath();
            dbPath = baseDir.resolve("data/translations");
            dbUrl = "jdbc:h2:file:" + dbPath.toAbsolutePath().toString().replace("\\", "/")
                    + ";DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE;MODE=PostgreSQL";

            Files.createDirectories(dbPath.getParent());
            openConnection();
            ensureSchema();
        } catch (Exception e) {
            log.error("Failed to initialize translation database: {}", e.getMessage(), e);
        }
    }

    private void openConnection() throws SQLException {
        connection = DriverManager.getConnection(dbUrl, "sa", "");
    }

    private void ensureSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS translations (" +
                    "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  source_hash VARCHAR(64) NOT NULL," +
                    "  source_text CLOB NOT NULL," +
                    "  translated_text CLOB NOT NULL," +
                    "  source_lang VARCHAR(10) NOT NULL," +
                    "  target_lang VARCHAR(10) NOT NULL," +
                    "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  UNIQUE (source_hash, source_lang, target_lang)" +
                    ")");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_unsent ON translations(source_hash, source_lang, target_lang)");
        }
    }

    public synchronized void store(String sourceText, String translatedText, String sourceLang, String targetLang) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "MERGE INTO translations (source_hash, source_text, translated_text, source_lang, target_lang) " +
                        "KEY (source_hash, source_lang, target_lang) " +
                        "VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, sha256(sourceText));
            ps.setString(2, sourceText);
            ps.setString(3, translatedText);
            ps.setString(4, sourceLang);
            ps.setString(5, targetLang);
            ps.executeUpdate();
        } catch (Exception e) {
            log.warn("Failed to store translation: {}", e.getMessage());
        }
    }

    public synchronized int getUnsentCount() {
        if (connection == null) return 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM translations")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.warn("Failed to count translations: {}", e.getMessage());
            return 0;
        }
    }

    public synchronized Path exportForUpload() throws IOException {
        if (connection == null) throw new IOException("Database not initialized");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CHECKPOINT");
        } catch (SQLException e) {
            throw new IOException("CHECKPOINT failed", e);
        }

        Path sourceFile = dbPath.resolveSibling(dbPath.getFileName() + ".mv.db");
        Path tempFile = Files.createTempFile("runespeak-upload-", ".mv.db");
        Files.copy(sourceFile, tempFile, StandardCopyOption.REPLACE_EXISTING);
        log.debug("Exported DB snapshot to {} ({} bytes)", tempFile, Files.size(tempFile));
        return tempFile;
    }

    public synchronized void purgeAfterUpload() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException e) {
            log.warn("Error closing database for purge: {}", e.getMessage());
        }
        connection = null;

        try {
            Path mvFile = dbPath.resolveSibling(dbPath.getFileName() + ".mv.db");
            Path traceFile = dbPath.resolveSibling(dbPath.getFileName() + ".trace.db");
            Files.deleteIfExists(mvFile);
            Files.deleteIfExists(traceFile);

            openConnection();
            ensureSchema();
            log.info("Database purged — fresh file created");
        } catch (Exception e) {
            log.warn("Failed to purge database: {}", e.getMessage());
        }
    }

    public synchronized void shutdown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                log.warn("Error closing database: {}", e.getMessage());
            }
            connection = null;
        }
    }

    private static String sha256(String text) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
