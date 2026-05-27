package com.runespeak.translate;

import java.util.List;

public interface ModelStrategy {
    List<ModelStrategy> STRATEGIES = List.of(
            new T5ModelStrategy(),
            new NllbModelStrategy(),
            new HelsinkiModelStrategy(),
            new DefaultModelStrategy()
    );

    static ModelStrategy forModelId(String modelId) {
        if (modelId == null) {
            return new DefaultModelStrategy();
        }
        for (ModelStrategy strategy : STRATEGIES) {
            if (strategy.supports(modelId)) {
                return strategy;
            }
        }
        return new DefaultModelStrategy();
    }

    boolean supports(String modelId);
    
    PromptStrategy getPromptStrategy(String modelId);
    
    List<DownloadFile> getRequiredFiles(String modelId);
    
    String getOnnxSubdir(String modelId);
    String getDecoderFilename(String modelId);
    
    default String getEncoderFilename(String modelId) {
        return "encoder_model.onnx";
    }

    default boolean isDecoderOnly() {
        return true;
    }
}
