package com.runespeak.translate;

public class EncoderDecoderRuntimeProvider implements ModelRuntimeProvider {
    @Override
    public boolean supports(String modelId) {
        ModelStrategy strategy = ModelStrategy.forModelId(modelId);
        return !strategy.isDecoderOnly();
    }

    @Override
    public ModelRuntime create(String modelId) {
        return new EncoderDecoderRuntime(modelId);
    }
}
