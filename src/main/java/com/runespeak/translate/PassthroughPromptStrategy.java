package com.runespeak.translate;

public class PassthroughPromptStrategy implements PromptStrategy {
    @Override
    public String format(String text, String srcFloresCode, String tgtFloresCode) {
        return text;
    }

    @Override
    public boolean addSpecialTokens() {
        return true;
    }
}
