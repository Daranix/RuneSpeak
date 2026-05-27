package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Singleton
public class DialogCapture {
    private static final int DIALOG_OPTION_WIDGET_ID = net.runelite.api.widgets.WidgetID.DIALOG_OPTION_GROUP_ID;

    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    @Getter
    private volatile String currentDialogTranslation = "";
    @Getter
    private volatile String currentDialogOriginal = "";
    @Getter
    private volatile boolean dialogActive = false;

    /** Log of recent NPC dialog translations shown in the side panel. */
    @Getter
    private final List<DialogLogEntry> dialogLog = new CopyOnWriteArrayList<>();

    /** Tracks which option widget IDs have already been translated to avoid re-translation. */
    private final ConcurrentHashMap<Integer, String> translatedOptions = new ConcurrentHashMap<>();

    @Inject
    public DialogCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void checkAndTranslateDialog() {
        if (!config.translateNpcDialogue()) return;

        Widget npcDialog    = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
        Widget playerDialog = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
        Widget spriteDialog = client.getWidget(WidgetInfo.DIALOG_SPRITE_TEXT);

        Widget activeDialog = null;
        if (npcDialog != null && !npcDialog.isHidden()) {
            activeDialog = npcDialog;
        } else if (playerDialog != null && !playerDialog.isHidden()) {
            activeDialog = playerDialog;
        } else if (spriteDialog != null && !spriteDialog.isHidden()) {
            activeDialog = spriteDialog;
        }

        if (activeDialog != null) {
            translateMainDialog(activeDialog);
            translateDialogOptions();
        } else {
            if (dialogActive) {
                dialogActive = false;
                currentDialogTranslation = "";
                currentDialogOriginal = "";
                translatedOptions.clear();
            }
        }
    }

    private void translateMainDialog(Widget dialogWidget) {
        String text = dialogWidget.getText();
        if (text == null || text.isEmpty()) return;

        // Strip colour tags to compare cleanly
        String cleanText = stripTags(text);
        String cleanOriginal = stripTags(currentDialogOriginal);
        String cleanTranslation = stripTags(currentDialogTranslation);

        // The widget already shows the original text we are tracking → nothing to do.
        // The widget already shows the translation we produced → don't re-translate.
        // Both guards are needed: on each game tick the widget returns whatever text
        // is currently displayed, which may be the translation we already set.
        if (cleanText.equals(cleanOriginal) || (!cleanTranslation.isEmpty() && cleanText.equals(cleanTranslation))) {
            // If "Show Original" was toggled on, restore the raw original text
            if (config.showOriginal() && !currentDialogOriginal.isEmpty() && !cleanText.equals(cleanOriginal)) {
                dialogWidget.setText(currentDialogOriginal);
            }
            return;
        }

        dialogActive = true;
        currentDialogOriginal = text;
        currentDialogTranslation = "";

        // Restore original immediately while waiting for translation
        if (config.showOriginal()) {
            dialogWidget.setText(text);
        }

        final Widget capturedWidget = dialogWidget;
        translator.translateAsync(cleanText).thenAccept(translated -> {
            if (translated.startsWith("⏳")) return;               // model not ready yet
            if (!currentDialogOriginal.equals(capturedWidget.getText())
                    && !currentDialogOriginal.equals(text)) return; // dialog changed

            currentDialogTranslation = translated;

            if (!translated.equals(cleanText)) {
                addToLog(cleanText, translated);
            }

            if (!config.showOriginal()) {
                capturedWidget.setText(translated);
            }
        });
    }

    private void translateDialogOptions() {
        // Dialog option list (NPC conversation choices): component 1 inside the options group
        Widget optionGroup = client.getWidget(DIALOG_OPTION_WIDGET_ID, 1);
        if (optionGroup == null || optionGroup.isHidden()) return;

        Widget[] children = optionGroup.getDynamicChildren();
        if (children == null) return;

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        String srcCode = source.getFloresCode();
        String tgtCode = target.getFloresCode();

        for (Widget option : children) {
            String optText = option.getText();
            if (optText == null || optText.isEmpty()) continue;

            String clean = stripTags(optText);
            if (clean.isEmpty()) continue;

            // Already translated this exact widget content
            String alreadyTranslated = translatedOptions.get(option.getId());
            if (clean.equals(alreadyTranslated)) continue;

            // Check cache first
            String cached = translator.getCache().get(clean, srcCode, tgtCode);
            if (cached != null && !cached.equals(clean)) {
                option.setText(cached);
                translatedOptions.put(option.getId(), cached);
                continue;
            }

            // Async translate
            final Widget capturedOption = option;
            final String capturedText   = optText;
            final String capturedClean  = clean;
            translator.translateAsync(clean).thenAccept(translated -> {
                if (!translated.startsWith("⏳") && !translated.equals(capturedClean)) {
                    // Make sure the widget still has the original text before overwriting
                    if (capturedText.equals(capturedOption.getText())) {
                        capturedOption.setText(translated);
                    }
                    translatedOptions.put(capturedOption.getId(), translated);
                    translator.getCache().put(capturedClean, translated, srcCode, tgtCode);
                }
            });
        }
    }

    private void addToLog(String original, String translation) {
        dialogLog.add(new DialogLogEntry(original, translation, System.currentTimeMillis()));
        if (dialogLog.size() > 100) {
            dialogLog.remove(0);
        }
    }

    private static String stripTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }

    public void clear() {
        dialogActive = false;
        currentDialogTranslation = "";
        currentDialogOriginal = "";
        translatedOptions.clear();
        dialogLog.clear();
    }

    @Getter
    public static class DialogLogEntry {
        private final String original;
        private final String translation;
        private final long timestamp;

        public DialogLogEntry(String original, String translation, long timestamp) {
            this.original    = original;
            this.translation = translation;
            this.timestamp   = timestamp;
        }
    }
}