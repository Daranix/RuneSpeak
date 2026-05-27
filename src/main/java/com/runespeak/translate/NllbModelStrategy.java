package com.runespeak.translate;

import java.util.List;

public class NllbModelStrategy implements ModelStrategy {
    @Override
    public boolean supports(String modelId) {
        return modelId.toLowerCase().contains("nllb");
    }

    @Override
    public PromptStrategy getPromptStrategy(String modelId) {
        return new NllbPromptStrategy();
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
        return "onnx";
    }

    @Override
    public String getEncoderFilename(String modelId) {
        return "encoder_model_fp16.onnx";
    }

    @Override
    public String getDecoderFilename(String modelId) {
        return "decoder_model_fp16.onnx";
    }

    @Override
    public boolean isDecoderOnly() {
        return false;
    }
}
