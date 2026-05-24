package com.runespeak;

public enum Language {
    ENGLISH("eng", "English", "eng_Latn"),
    SPANISH("spa", "Spanish", "spa_Latn"),
    FRENCH("fra", "French", "fra_Latn"),
    GERMAN("deu", "German", "deu_Latn"),
    ITALIAN("ita", "Italian", "ita_Latn"),
    PORTUGUESE("por", "Portuguese", "por_Latn"),
    DUTCH("nld", "Dutch", "nld_Latn"),
    POLISH("pol", "Polish", "pol_Latn"),
    ROMANIAN("ron", "Romanian", "ron_Latn"),
    RUSSIAN("rus", "Russian", "rus_Cyrl"),
    JAPANESE("jpn", "Japanese", "jpn_Jpan"),
    KOREAN("kor", "Korean", "kor_Hang"),
    CHINESE_SIMPLIFIED("zho", "Chinese (Simplified)", "zho_Hans"),
    CHINESE_TRADITIONAL("zho", "Chinese (Traditional)", "zho_Hant"),
    ARABIC("ara", "Arabic", "ara_Arab"),
    HINDI("hin", "Hindi", "hin_Deva"),
    TURKISH("tur", "Turkish", "tur_Latn"),
    VIETNAMESE("vie", "Vietnamese", "vie_Latn"),
    THAI("tha", "Thai", "tha_Thai"),
    INDONESIAN("ind", "Indonesian", "ind_Latn"),
    SWEDISH("swe", "Swedish", "swe_Latn"),
    NORWEGIAN("nor", "Norwegian", "nob_Latn"),
    DANISH("dan", "Danish", "dan_Latn"),
    FINNISH("fin", "Finnish", "fin_Latn"),
    GREEK("ell", "Greek", "ell_Grek"),
    CZECH("ces", "Czech", "ces_Latn"),
    HUNGARIAN("hun", "Hungarian", "hun_Latn"),
    UKRAINIAN("ukr", "Ukrainian", "ukr_Cyrl");

    private final String isoCode;
    private final String displayName;
    private final String floresCode;

    Language(String isoCode, String displayName, String floresCode) {
        this.isoCode = isoCode;
        this.displayName = displayName;
        this.floresCode = floresCode;
    }

    public String getIsoCode() {
        return isoCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFloresCode() {
        return floresCode;
    }
}
