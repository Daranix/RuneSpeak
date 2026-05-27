package com.runespeak.translate;

import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public class EncoderDecoderRuntimeTest {

    @Test
    public void getModelId() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("onnx-community/t5-small-ONNX");
        assertEquals("onnx-community/t5-small-ONNX", runtime.getModelId());
    }

    @Test
    public void getModelIdWithEmptySubdir() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("test/model");
        assertEquals("test/model", runtime.getModelId());
    }

    @Test
    public void getRequiredFilesWithOnnxSubdir() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("onnx-community/t5-small-ONNX");
        List<DownloadFile> files = runtime.getRequiredFiles();
        assertEquals(4, files.size());

        List<String> remotePaths = files.stream()
                .map(f -> f.getDownloadUrl("onnx-community/t5-small-ONNX"))
                .collect(Collectors.toList());

        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/t5-small-ONNX/resolve/main/onnx/encoder_model.onnx"));
        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/t5-small-ONNX/resolve/main/onnx/decoder_model.onnx"));
        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/t5-small-ONNX/resolve/main/tokenizer.json"));
        assertTrue(remotePaths.contains(
                "https://huggingface.co/onnx-community/t5-small-ONNX/resolve/main/config.json"));
    }

    @Test
    public void getRequiredFilesWithoutOnnxSubdir() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("test/model");
        List<DownloadFile> files = runtime.getRequiredFiles();
        assertEquals(4, files.size());

        List<String> localNames = files.stream()
                .map(DownloadFile::getLocalFilename)
                .collect(Collectors.toList());

        assertTrue(localNames.contains("encoder_model.onnx"));
        assertTrue(localNames.contains("decoder_model.onnx"));
        assertTrue(localNames.contains("tokenizer.json"));
        assertTrue(localNames.contains("config.json"));
    }

    @Test
    public void initiallyNotLoaded() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("test/model");
        assertFalse(runtime.isLoaded());
        assertFalse(runtime.isLoading());
    }

    @Test
    public void nullSubdirTreatedAsEmpty() {
        EncoderDecoderRuntime runtime = new EncoderDecoderRuntime("test/model");
        List<DownloadFile> files = runtime.getRequiredFiles();
        String url = files.get(0).getDownloadUrl("test/model");
        assertEquals("https://huggingface.co/test/model/resolve/main/encoder_model.onnx", url);
    }

    @Test
    public void getRequiredFilesForHelsinkiOnnx() {
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
}
