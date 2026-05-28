package com.runespeak.data;

import com.runespeak.RuneSpeakConfig;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Singleton
public class TranslationDatabase {
    private volatile Connection connection;

    @Inject
    public TranslationDatabase() {
    }

    public synchronized void init(RuneSpeakConfig config) {
        try {
            String custom = config.getModelCacheDir();
            Path baseDir = custom != null && !custom.isBlank()
                    ? Path.of(custom)
                    : new java.io.File(net.runelite.client.RuneLite.RUNELITE_DIR, "runespeak").toPath();
            Path dbPath = baseDir.resolve("data/translations");

            java.nio.file.Files.createDirectories(dbPath.getParent());

            String url = "jdbc:h2:file:" + dbPath.toAbsolutePath().toString().replace("\\", "/")
                    + ";DB_CLOSE_DELAY=-1;AUTO_RECONNECT=TRUE;MODE=PostgreSQL";
            connection = DriverManager.getConnection(url, "sa", "");

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS translations (" +
                        "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "  source_hash VARCHAR(64) NOT NULL," +
                        "  source_text CLOB NOT NULL," +
                        "  translated_text CLOB NOT NULL," +
                        "  source_lang VARCHAR(10) NOT NULL," +
                        "  target_lang VARCHAR(10) NOT NULL," +
                        "  sent BOOLEAN DEFAULT FALSE," +
                        "  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                        "  UNIQUE (source_hash, source_lang, target_lang)" +
                        ")");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_unsent ON translations(sent, created_at)");
            }

            log.info("Translation database initialized at {}.mv.db", dbPath.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to initialize translation database: {}", e.getMessage(), e);
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

    public synchronized List<TranslationEntry> getUnsent(int limit) {
        List<TranslationEntry> entries = new ArrayList<>();
        if (connection == null) return entries;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT id, source_text, translated_text, source_lang, target_lang " +
                        "FROM translations WHERE sent = FALSE " +
                        "ORDER BY created_at ASC LIMIT ?")) {
            ps.setInt(1, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new TranslationEntry(
                            rs.getLong("id"),
                            rs.getString("source_text"),
                            rs.getString("translated_text"),
                            rs.getString("source_lang"),
                            rs.getString("target_lang")
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to query unsent translations: {}", e.getMessage());
        }
        return entries;
    }

    public synchronized void markAsSent(List<Long> ids) {
        if (ids.isEmpty() || connection == null) return;
        StringBuilder sql = new StringBuilder("UPDATE translations SET sent = TRUE WHERE id IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(",");
            sql.append("?");
        }
        sql.append(")");
        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setLong(i + 1, ids.get(i));
            }
            ps.executeUpdate();
            log.debug("Marked {} translations as sent", ids.size());
        } catch (Exception e) {
            log.warn("Failed to mark translations as sent: {}", e.getMessage());
        }
    }

    public synchronized int getUnsentCount() {
        if (connection == null) return 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM translations WHERE sent = FALSE")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            log.warn("Failed to count unsent translations: {}", e.getMessage());
            return 0;
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
