package com.runespeak.translate;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;

public interface PromptStrategy {
    String format(String text, String srcFloresCode, String tgtFloresCode);

    default int overrideDecoderStartTokenId(String tgtLang, int defaultId, HuggingFaceTokenizer tokenizer) {
        return defaultId;
    }

    default int overrideForcedBosTokenId(String tgtLang, HuggingFaceTokenizer tokenizer) {
        return -1;
    }

    default int tokensToSkip() {
        return 1;
    }

    default boolean addSpecialTokens() {
        return false;
    }
}
