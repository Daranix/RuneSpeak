package com.runespeak.translate;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ModelRuntime {
    String getModelId();
    List<DownloadFile> getRequiredFiles();

    void load(Path modelPath) throws IOException;
    CompletableFuture<String> translate(String text, String srcLang, String tgtLang);
    void shutdown();

    boolean isLoaded();
    boolean isLoading();
    String getCurrentModelId();

    static ModelRuntime forModelId(String modelId, Gson gson) {
        if (modelId == null) {
            throw new IllegalArgumentException("Model ID must not be null");
        }
        for (ModelRuntimeProvider provider : PROVIDERS) {
            if (provider.supports(modelId)) {
                return provider.create(modelId, gson);
            }
        }
        throw new IllegalArgumentException("No runtime provider for model: " + modelId);
    }

    List<ModelRuntimeProvider> PROVIDERS = List.of(
            new EncoderDecoderRuntimeProvider()
    );
}
