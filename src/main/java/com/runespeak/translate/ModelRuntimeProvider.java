package com.runespeak.translate;

public interface ModelRuntimeProvider {
    boolean supports(String modelId);
    ModelRuntime create(String modelId);
}
