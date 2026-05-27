package com.runespeak.translate;

import org.junit.Test;

import static org.junit.Assert.*;

public class DownloadFileTest {

    @Test
    public void urlWithRemoteSubdirectory() {
        DownloadFile file = new DownloadFile("onnx", "encoder_model.onnx");
        String url = file.getDownloadUrl("onnx-community/t5-small-ONNX");
        assertEquals(
                "https://huggingface.co/onnx-community/t5-small-ONNX/resolve/main/onnx/encoder_model.onnx",
                url);
    }

    @Test
    public void urlWithEmptyRemotePath() {
        DownloadFile file = new DownloadFile("", "tokenizer.json");
        String url = file.getDownloadUrl("onnx-community/t5-small-ONNX");
        assertEquals(
                "https://huggingface.co/onnx-community/t5-small-ONNX/resolve/main/tokenizer.json",
                url);
    }

    @Test
    public void urlWithMultiplePathSegments() {
        DownloadFile file = new DownloadFile("onnx/quantized", "model_q4f16.onnx");
        String url = file.getDownloadUrl("org/model");
        assertEquals(
                "https://huggingface.co/org/model/resolve/main/onnx/quantized/model_q4f16.onnx",
                url);
    }

    @Test
    public void getLocalFilename() {
        DownloadFile file = new DownloadFile("onnx", "encoder_model.onnx");
        assertEquals("encoder_model.onnx", file.getLocalFilename());
    }

    @Test
    public void getRemotePath() {
        DownloadFile file = new DownloadFile("onnx", "encoder_model.onnx");
        assertEquals("onnx", file.getRemotePath());
    }

    @Test
    public void emptyRemotePath() {
        DownloadFile file = new DownloadFile("", "tokenizer.json");
        assertEquals("", file.getRemotePath());
    }

    @Test
    public void modelIdWithOrgAndModel() {
        DownloadFile file = new DownloadFile("", "config.json");
        String url = file.getDownloadUrl("onnx-community/Qwen2.5-0.5B-Instruct-ONNX");
        assertEquals(
                "https://huggingface.co/onnx-community/Qwen2.5-0.5B-Instruct-ONNX/resolve/main/config.json",
                url);
    }
}
