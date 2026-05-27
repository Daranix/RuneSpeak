package com.runespeak.translate;

import java.util.List;

public class HelsinkiModelStrategy implements ModelStrategy {
    @Override
    public boolean supports(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return lower.contains("helsinki") || lower.contains("opus-mt");
    }

    @Override
    public PromptStrategy getPromptStrategy(String modelId) {
        return new PassthroughPromptStrategy();
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
        return modelId.contains("onnx-community") || modelId.contains("xenova") || modelId.contains("echarlaix") ? "onnx" : "";
    }

    @Override
    public String getDecoderFilename(String modelId) {
        // Many ONNX exporter variants use decoder_model.onnx, but Transformers.js might merge them.
        // We fallback to decoder_model.onnx unless specified.
        if (modelId.toLowerCase().contains("xenova") && !modelId.toLowerCase().contains("opus-mt")) {
            return "decoder_model_merged.onnx";
        }
        return "decoder_model.onnx";
    }

    @Override
    public boolean isDecoderOnly() {
        return false;
    }
}
