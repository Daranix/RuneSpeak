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

    public String getTwoLetterCode() {
        switch (this) {
            case ENGLISH: return "en";
            case SPANISH: return "es";
            case FRENCH: return "fr";
            case GERMAN: return "de";
            case ITALIAN: return "it";
            case PORTUGUESE: return "pt";
            case DUTCH: return "nl";
            case POLISH: return "pl";
            case ROMANIAN: return "ro";
            case RUSSIAN: return "ru";
            case JAPANESE: return "ja";
            case KOREAN: return "ko";
            case CHINESE_SIMPLIFIED:
            case CHINESE_TRADITIONAL: return "zh";
            case ARABIC: return "ar";
            case HINDI: return "hi";
            case TURKISH: return "tr";
            case VIETNAMESE: return "vi";
            case THAI: return "th";
            case INDONESIAN: return "id";
            case SWEDISH: return "sv";
            case NORWEGIAN: return "no";
            case DANISH: return "da";
            case FINNISH: return "fi";
            case GREEK: return "el";
            case CZECH: return "cs";
            case HUNGARIAN: return "hu";
            case UKRAINIAN: return "uk";
            default: return "en";
        }
    }
}
