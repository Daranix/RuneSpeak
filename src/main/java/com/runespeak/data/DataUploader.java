package com.runespeak.data;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Singleton
public class DataUploader {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int BATCH_SIZE = 1000;

    private final TranslationDatabase database;
    private final Gson gson;
    private final OkHttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> task;

    @Inject
    public DataUploader(TranslationDatabase database, Gson gson, OkHttpClient httpClient) {
        this.database = database;
        this.gson = gson;
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

            List<TranslationEntry> batch = database.getUnsent(BATCH_SIZE);
            if (batch.isEmpty()) return;

            List<UploadPayload> payload = batch.stream()
                    .map(e -> new UploadPayload(e.getSourceText(), e.getTranslatedText(), e.getSourceLang(), e.getTargetLang()))
                    .collect(Collectors.toList());

            String json = gson.toJson(payload);

            RequestBody body = RequestBody.create(JSON, json);
            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    log.warn("Upload failed (will retry later): {}", e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try (response) {
                        if (response.isSuccessful()) {
                            List<Long> ids = batch.stream()
                                    .map(TranslationEntry::getId)
                                    .collect(Collectors.toList());
                            database.markAsSent(ids);
                            log.info("Uploaded and acknowledged {} translations", ids.size());
                        } else {
                            log.warn("Upload returned {} — will retry later", response.code());
                        }
                    }
                }
            });

        } catch (Exception e) {
            log.warn("Upload cycle failed (will retry later): {}", e.getMessage());
        }
    }

    private static class UploadPayload {
        private final String s;
        private final String t;
        private final String sl;
        private final String tl;

        UploadPayload(String source, String translation, String sourceLang, String targetLang) {
            this.s = source;
            this.t = translation;
            this.sl = sourceLang;
            this.tl = targetLang;
        }
    }
}
