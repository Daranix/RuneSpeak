package com.runespeak.data;

public class TranslationEntry {
    private final long id;
    private final String sourceText;
    private final String translatedText;
    private final String sourceLang;
    private final String targetLang;

    public TranslationEntry(long id, String sourceText, String translatedText, String sourceLang, String targetLang) {
        this.id = id;
        this.sourceText = sourceText;
        this.translatedText = translatedText;
        this.sourceLang = sourceLang;
        this.targetLang = targetLang;
    }

    public long getId() { return id; }
    public String getSourceText() { return sourceText; }
    public String getTranslatedText() { return translatedText; }
    public String getSourceLang() { return sourceLang; }
    public String getTargetLang() { return targetLang; }
}
