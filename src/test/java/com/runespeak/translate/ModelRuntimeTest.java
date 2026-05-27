package com.runespeak.translate;

import org.junit.Test;

import static org.junit.Assert.*;

public class ModelRuntimeTest {

    @Test
    public void helsinkiReturnsEncoderDecoderRuntime() {
        ModelRuntime runtime = ModelRuntime.forModelId("onnx-community/opus-mt-en-es");
        assertTrue("Expected EncoderDecoderRuntime for Helsinki-NLP",
                runtime instanceof EncoderDecoderRuntime);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullModelIdThrowsException() {
        ModelRuntime.forModelId(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void unsupportedModelThrowsException() {
        ModelRuntime.forModelId("some/unknown-model");
    }

    @Test
    public void helsinkiModelIdIsStored() {
        ModelRuntime runtime = ModelRuntime.forModelId("onnx-community/opus-mt-en-es");
        assertEquals("onnx-community/opus-mt-en-es", runtime.getModelId());
    }
}
