package com.runespeak.translate;

import org.junit.Test;

import static org.junit.Assert.*;

public class ModelRuntimeTest {

    @Test
    public void t5ReturnsEncoderDecoderRuntime() {
        ModelRuntime runtime = ModelRuntime.forModelId("onnx-community/t5-small-ONNX");
        assertTrue("Expected EncoderDecoderRuntime for T5",
                runtime instanceof EncoderDecoderRuntime);
    }

    @Test
    public void echarlaixT5ReturnsEncoderDecoderRuntime() {
        ModelRuntime runtime = ModelRuntime.forModelId("echarlaix/t5-small-onnx");
        assertTrue("Expected EncoderDecoderRuntime for echarlaix T5",
                runtime instanceof EncoderDecoderRuntime);
    }

    @Test
    public void helsinkiReturnsEncoderDecoderRuntime() {
        ModelRuntime runtime = ModelRuntime.forModelId("onnx-community/opus-mt-en-es");
        assertTrue("Expected EncoderDecoderRuntime for Helsinki-NLP",
                runtime instanceof EncoderDecoderRuntime);
    }

    @Test
    public void unknownModelFallsBackToEncoderDecoderRuntime() {
        ModelRuntime runtime = ModelRuntime.forModelId("some/unknown-model");
        assertTrue("Expected EncoderDecoderRuntime for unknown model (fallback)",
                runtime instanceof EncoderDecoderRuntime);
    }

    @Test(expected = IllegalArgumentException.class)
    public void nullModelIdThrowsException() {
        ModelRuntime.forModelId(null);
    }

    @Test
    public void emptyModelIdFallsBackToEncoderDecoder() {
        ModelRuntime runtime = ModelRuntime.forModelId("");
        assertTrue("Expected EncoderDecoderRuntime for empty modelId (no provider matches)",
                runtime instanceof EncoderDecoderRuntime);
    }

    @Test
    public void helsinkiModelIdIsStored() {
        ModelRuntime runtime = ModelRuntime.forModelId("onnx-community/opus-mt-en-es");
        assertEquals("onnx-community/opus-mt-en-es", runtime.getModelId());
    }

    @Test
    public void t5ModelIdIsStored() {
        ModelRuntime runtime = ModelRuntime.forModelId("onnx-community/t5-small-ONNX");
        assertEquals("onnx-community/t5-small-ONNX", runtime.getModelId());
    }
}
