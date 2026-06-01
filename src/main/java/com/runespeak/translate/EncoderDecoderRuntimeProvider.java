package com.runespeak.translate;

import com.google.gson.Gson;

public class EncoderDecoderRuntimeProvider implements ModelRuntimeProvider {
    @Override
    public boolean supports(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return lower.contains("opus-mt") || lower.contains("helsinki") || lower.contains("xenova");
    }

    @Override
    public ModelRuntime create(String modelId, Gson gson) {
        return new JavaEncoderDecoderRuntime(modelId, gson);
    }
}
