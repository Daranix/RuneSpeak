package com.runespeak.translate;

import java.util.List;

public class DefaultModelStrategy implements ModelStrategy {
    @Override
    public boolean supports(String modelId) {
        return true;
    }

    @Override
    public PromptStrategy getPromptStrategy(String modelId) {
        if (modelId != null && modelId.toLowerCase().contains("xenova")) {
            return new PassthroughPromptStrategy();
        }
        return new T5PromptStrategy();
    }

    @Override
    public List<DownloadFile> getRequiredFiles(String modelId) {
        String subdir = getOnnxSubdir(modelId);
        return List.of(
                new DownloadFile(subdir, getEncoderFilename(modelId)),
                new DownloadFile(subdir, getDecoderFilename(modelId)),
                new DownloadFile("", "tokenizer.json"),
                new DownloadFile("", "config.json")
        );
    }

    @Override
    public String getOnnxSubdir(String modelId) {
        return modelId.contains("onnx-community") || modelId.contains("echarlaix") || modelId.contains("xenova") ? "onnx" : "";
    }

    @Override
    public String getDecoderFilename(String modelId) {
        if (modelId.toLowerCase().contains("xenova")) {
            return "decoder_model_merged.onnx";
        }
        return "decoder_model.onnx";
    }

    @Override
    public boolean isDecoderOnly() {
        return false;
    }
}
