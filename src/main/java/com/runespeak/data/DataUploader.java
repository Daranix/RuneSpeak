package com.runespeak.data;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Singleton
public class DataUploader {
    private static final MediaType BINARY = MediaType.get("application/octet-stream");

    private final TranslationDatabase database;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    @Inject
    public DataUploader(TranslationDatabase database, OkHttpClient httpClient) {
        this.database = database;
        this.httpClient = httpClient;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runespeak-uploader");
            t.setDaemon(true);
            return t;
        });
    }

    public void start(String serverUrl, int intervalMinutes) {
        if (task != null && !task.isCancelled()) {
            task.cancel(false);
        }

        if (serverUrl == null || serverUrl.isBlank()) {
            log.info("Data upload disabled: no server URL configured");
            return;
        }

        task = scheduler.scheduleWithFixedDelay(
                () -> uploadPending(serverUrl),
                intervalMinutes, intervalMinutes, TimeUnit.MINUTES
        );
        log.info("Data uploader started — every {} min to {}", intervalMinutes, serverUrl);
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        scheduler.shutdownNow();
    }

    public void uploadNow(String serverUrl) {
        uploadPending(serverUrl);
    }

    private void uploadPending(String serverUrl) {
        try {
            int count = database.getUnsentCount();
            if (count == 0) return;

            log.info("Uploading {} unsent translations...", count);

            Path snapshot = database.exportForUpload();

            RequestBody body = RequestBody.create(BINARY, snapshot.toFile());
            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/octet-stream")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    deleteSnapshot(snapshot);
                    log.warn("Upload failed (will retry later): {}", e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful()) {
                            database.purgeAfterUpload();
                            log.info("Uploaded — database purged for next cycle");
                        } else {
                            log.warn("Upload returned {} — will retry later", response.code());
                        }
                    } finally {
                        deleteSnapshot(snapshot);
                    }
                }
            });

        } catch (Exception e) {
            log.warn("Upload cycle failed (will retry later): {}", e.getMessage());
        }
    }

    private static void deleteSnapshot(Path snapshot) {
        try {
            Files.deleteIfExists(snapshot);
        } catch (IOException e) {
            log.warn("Failed to delete temp snapshot: {}", e.getMessage());
        }
    }
}
