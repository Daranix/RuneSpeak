package com.runespeak;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("runespeak")
public interface RuneSpeakConfig extends Config {
    @ConfigSection(
            name = "Translation",
            description = "Language pair settings",
            position = 0,
            closedByDefault = false
    )
    String SECTION_TRANSLATION = "translation";

    @ConfigItem(
            keyName = "targetLanguage",
            name = "Target Language",
            description = "Language to translate in-game text into",
            position = 0,
            section = SECTION_TRANSLATION
    )
    default Language getTargetLanguage() {
        return Language.SPANISH;
    }

    @ConfigItem(
            keyName = "sourceLanguage",
            name = "Source Language",
            description = "Language of the game text (typically English for OSRS)",
            position = 1,
            section = SECTION_TRANSLATION
    )
    default Language getSourceLanguage() {
        return Language.ENGLISH;
    }

    @ConfigSection(
            name = "Display",
            description = "Overlay display settings",
            position = 10,
            closedByDefault = false
    )
    String SECTION_DISPLAY = "display";

    @ConfigItem(
            keyName = "translateNpcDialogue",
            name = "NPC Dialogue",
            description = "Translate NPC dialogue text",
            position = 0,
            section = SECTION_DISPLAY
    )
    default boolean translateNpcDialogue() {
        return true;
    }

    @ConfigItem(
            keyName = "translateMenuEntries",
            name = "Right-click Menus",
            description = "Translate right-click menu entries",
            position = 1,
            section = SECTION_DISPLAY
    )
    default boolean translateMenuEntries() {
        return true;
    }

    @ConfigItem(
            keyName = "translateGameMessages",
            name = "Game Messages",
            description = "Translate game messages (bottom-left text, tutorial instructions, system messages)",
            position = 2,
            section = SECTION_DISPLAY
    )
    default boolean translateGameMessages() {
        return true;
    }

    @ConfigItem(
            keyName = "translateChat",
            name = "Chat Messages",
            description = "Translate player chat messages",
            position = 3,
            section = SECTION_DISPLAY
    )
    default boolean translateChat() {
        return false;
    }

    @ConfigItem(
            keyName = "translateOverhead",
            name = "Overhead Text",
            description = "Translate overhead text above NPCs/players",
            position = 4,
            section = SECTION_DISPLAY
    )
    default boolean translateOverhead() {
        return true;
    }

    @ConfigItem(
            keyName = "translateOverlayText",
            name = "Overlay Text Instructions",
            description = "Translate the overlay text widget (tutorial instructions, xp drops, notifications)",
            position = 5,
            section = SECTION_DISPLAY
    )
    default boolean translateOverlayText() {
        return true;
    }

    @ConfigItem(
            keyName = "translateItemNames",
            name = "Item/NPC/Object Names",
            description = "Translate item, NPC, and object names",
            position = 6,
            section = SECTION_DISPLAY
    )
    default boolean translateNames() {
        return true;
    }

    @ConfigItem(
            keyName = "showOriginal",
            name = "Show Original Text",
            description = "Show original text alongside translation",
            position = 6,
            section = SECTION_DISPLAY
    )
    default boolean showOriginal() {
        return true;
    }

    @ConfigItem(
            keyName = "translationColor",
            name = "Translation Color",
            description = "Color of translated text overlay (hex, e.g. #00FF00)",
            position = 7,
            section = SECTION_DISPLAY
    )
    default String getTranslationColor() {
        return "#00FF00";
    }

    @ConfigItem(
            keyName = "unlimitedCache",
            name = "Unlimited Cache Size",
            description = "Disable the cache size limit — cache will never evict entries",
            position = 7,
            section = SECTION_TRANSLATION
    )
    default boolean unlimitedCache() {
        return true;
    }

    @ConfigItem(
            keyName = "cacheSize",
            name = "Max Cache Size",
            description = "Maximum number of translation entries to keep in cache (only applies when Unlimited Cache is OFF)",
            position = 8,
            section = SECTION_TRANSLATION
    )
    default int getCacheSize() {
        return 5000;
    }

    @ConfigItem(
            keyName = "modelCacheDir",
            name = "Model Cache Directory",
            description = "Custom directory to store downloaded ONNX models and translation cache",
            position = 9,
            section = SECTION_TRANSLATION
    )
    default String getModelCacheDir() {
        return "";
    }

    @ConfigSection(
            name = "Anonymous Data",
            description = "Anonymous translation data collection settings",
            position = 20,
            closedByDefault = true
    )
    String SECTION_DATA = "data";

    @ConfigItem(
            keyName = "anonymousDataSubmission",
            name = "Anonymous Data Submission",
            description = "Submit anonymized translation data to help improve translation quality. This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers.",
            warning = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers",
            position = 0,
            section = SECTION_DATA
    )
    default boolean anonymousDataSubmission() {
        return true;
    }

    @ConfigItem(
            keyName = "dataUploadUrl",
            name = "",
            description = "",
            position = 1,
            section = SECTION_DATA,
            hidden = true
    )
    default String getDataUploadUrl() {
        return "https://runespeak.mpesteban.dev/api/translations";
    }

    @ConfigItem(
            keyName = "dataUploadInterval",
            name = "",
            description = "",
            position = 2,
            section = SECTION_DATA,
            hidden = true
    )
    default int getDataUploadInterval() {
        return 60;
    }
}
