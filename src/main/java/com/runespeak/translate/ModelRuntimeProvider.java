package com.runespeak.translate;

import com.google.gson.Gson;

public interface ModelRuntimeProvider {
    boolean supports(String modelId);
    ModelRuntime create(String modelId, Gson gson);
}
