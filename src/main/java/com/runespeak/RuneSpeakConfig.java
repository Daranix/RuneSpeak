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

    @ConfigSection(
            name = "Model",
            description = "AI model and cache settings",
            position = 10,
            closedByDefault = false
    )
    String SECTION_MODEL = "model";

    @ConfigSection(
            name = "Display",
            description = "Overlay display settings",
            position = 20,
            closedByDefault = false
    )
    String SECTION_DISPLAY = "display";

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

    @ConfigItem(
            keyName = "modelId",
            name = "HuggingFace Model",
            description = "Model ID from HuggingFace Hub (e.g., facebook/nllb-200-distilled-600M)",
            position = 0,
            section = SECTION_MODEL
    )
    default String getModelId() {
        return "facebook/nllb-200-distilled-600M";
    }

    @ConfigItem(
            keyName = "autoDownload",
            name = "Auto-download Model",
            description = "Automatically download model on first use",
            position = 1,
            section = SECTION_MODEL
    )
    default boolean isAutoDownload() {
        return true;
    }

    @ConfigItem(
            keyName = "cacheSize",
            name = "Max Cache Entries",
            description = "Maximum number of translations to keep in cache (file-persisted)",
            position = 2,
            section = SECTION_MODEL
    )
    default int getCacheSize() {
        return 5000;
    }

    @ConfigItem(
            keyName = "modelCacheDir",
            name = "Cache Directory",
            description = "Directory to store downloaded models and translation cache",
            position = 3,
            section = SECTION_MODEL
    )
    default String getModelCacheDir() {
        return "";
    }

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
            keyName = "translateChat",
            name = "Chat Messages",
            description = "Translate player chat messages",
            position = 2,
            section = SECTION_DISPLAY
    )
    default boolean translateChat() {
        return false;
    }

    @ConfigItem(
            keyName = "translateOverhead",
            name = "Overhead Text",
            description = "Translate overhead text above NPCs/players",
            position = 3,
            section = SECTION_DISPLAY
    )
    default boolean translateOverhead() {
        return true;
    }

    @ConfigItem(
            keyName = "translateItemNames",
            name = "Item/NPC/Object Names",
            description = "Translate item, NPC, and object names",
            position = 4,
            section = SECTION_DISPLAY
    )
    default boolean translateNames() {
        return true;
    }

    @ConfigItem(
            keyName = "showOriginal",
            name = "Show Original Text",
            description = "Show original text alongside translation",
            position = 5,
            section = SECTION_DISPLAY
    )
    default boolean showOriginal() {
        return true;
    }

    @ConfigItem(
            keyName = "translationColor",
            name = "Translation Color",
            description = "Color of translated text overlay (hex, e.g. #00FF00)",
            position = 6,
            section = SECTION_DISPLAY
    )
    default String getTranslationColor() {
        return "#00FF00";
    }
}
