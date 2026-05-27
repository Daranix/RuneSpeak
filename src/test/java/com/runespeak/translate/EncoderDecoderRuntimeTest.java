package com.runespeak.translate;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class EncoderDecoderRuntimeTest {

    @Test
    public void getModelId() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("onnx-community/opus-mt-en-es");
        assertEquals("onnx-community/opus-mt-en-es", runtime.getModelId());
    }

    @Test
    public void getRequiredFilesWithOnnxSubdir() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("onnx-community/opus-mt-en-es");
        List<DownloadFile> files = runtime.getRequiredFiles();
        assertEquals(4, files.size());

        List<String> remotePaths = files.stream()
                .map(f -> f.getDownloadUrl("onnx-community/opus-mt-en-es"))
                .collect(Collectors.toList());

        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/opus-mt-en-es/resolve/main/onnx/encoder_model.onnx"));
        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/opus-mt-en-es/resolve/main/onnx/decoder_model.onnx"));
        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/opus-mt-en-es/resolve/main/tokenizer.json"));
        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/opus-mt-en-es/resolve/main/config.json"));
    }

    @Test
    public void initiallyNotLoaded() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("onnx-community/opus-mt-en-es");
        assertFalse(runtime.isLoaded());
        assertFalse(runtime.isLoading());
    }
}
