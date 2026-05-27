package com.runespeak.translate;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NllbPromptStrategy implements PromptStrategy {

    private volatile int tgtTokenId = -1;

    @Override
    public String format(String text, String srcFloresCode, String tgtFloresCode) {
        return srcFloresCode + " " + text;
    }

    @Override
    public int overrideDecoderStartTokenId(String tgtLang, int defaultId, HuggingFaceTokenizer tokenizer) {
        resolveTgtTokenId(tgtLang, tokenizer);
        return defaultId;
    }

    @Override
    public int overrideForcedBosTokenId(String tgtLang, HuggingFaceTokenizer tokenizer) {
        resolveTgtTokenId(tgtLang, tokenizer);
        return tgtTokenId;
    }

    @Override
    public int tokensToSkip() {
        return 2;
    }

    private void resolveTgtTokenId(String tgtLang, HuggingFaceTokenizer tokenizer) {
        if (tgtTokenId >= 0) return;
        Encoding encoding = tokenizer.encode(tgtLang);
        long[] ids = encoding.getIds();
        tgtTokenId = ids.length > 0 ? (int) ids[0] : -1;
        log.info("NLLB: {} token ID = {}", tgtLang, tgtTokenId);
    }

    public int getTgtTokenId() {
        return tgtTokenId;
    }
}