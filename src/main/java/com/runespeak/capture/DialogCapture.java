package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Singleton
public class DialogCapture {
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

    /** Tracks original clean text → translation for conversation option widgets. */
    private final ConcurrentHashMap<String, String> optionTranslations = new ConcurrentHashMap<>();

    @Inject
    public DialogCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void checkAndTranslateDialog() {
        if (!config.translateNpcDialogue()) return;

        Widget npcDialog    = client.getWidget(InterfaceID.ChatLeft.TEXT);
        Widget playerDialog = client.getWidget(InterfaceID.ChatRight.TEXT);
        Widget spriteDialog = client.getWidget(InterfaceID.Objectbox.TEXT);

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
        } else {
            if (dialogActive) {
                dialogActive = false;
                currentDialogTranslation = "";
                currentDialogOriginal = "";
            }
        }

        // Dialog options ("Select an Option" box) use a SEPARATE interface (219) that is
        // shown INSTEAD of the NPC text widget — it must be checked unconditionally.
        translateDialogOptions();
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
            // If "Show Original" was toggled ON and widget shows translated → restore original
            if (config.showOriginal() && !currentDialogOriginal.isEmpty() && !cleanText.equals(cleanOriginal)) {
                dialogWidget.setText(currentDialogOriginal);
            }
            // If "Show Original" was toggled OFF and widget shows original → apply translation
            if (!config.showOriginal() && !currentDialogTranslation.isEmpty() && cleanText.equals(cleanOriginal)) {
                dialogWidget.setText(currentDialogTranslation);
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
        // The "Select an Option" conversation choices live inside interface 219.
        // Component 1 holds the scrollable list; its dynamic children are the individual options.
        Widget optionGroup = client.getWidget(InterfaceID.Chatmenu.OPTIONS);
        if (optionGroup == null || optionGroup.isHidden()) {
            // Option dialog went away — clear per-widget translation cache
            if (!optionTranslations.isEmpty()) {
                optionTranslations.clear();
            }
            return;
        }

        // Try dynamic children first; fall back to static children (depends on OSRS version)
        Widget[] children = optionGroup.getDynamicChildren();
        if (children == null || children.length == 0) {
            children = optionGroup.getStaticChildren();
        }
        if (children == null || children.length == 0) return;

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        String srcCode = source.getFloresCode();
        String tgtCode = target.getFloresCode();

        for (Widget option : children) {
            String optText = option.getText();
            if (optText == null || optText.isEmpty()) continue;

            String clean = stripTags(optText);
            if (clean.isEmpty()) continue;

            // If the current text IS a known translation, skip it — we already applied it.
            // This prevents re-translation when the game resets the widget text.
            String knownTranslation = optionTranslations.get(clean);
            if (knownTranslation != null) {
                // Re-apply the translation every tick in case the game reset the widget
                if (!clean.equals(knownTranslation)) {
                    option.setText(knownTranslation);
                }
                continue;
            }

            // Guard: if this text is already a translation value (i.e. some other option's
            // original maps to this text), skip — we don't want to translate a translation.
            if (optionTranslations.containsValue(clean)) continue;

            // Check cache first for an immediate synchronous update
            String cached = translator.getCache().get(clean, srcCode, tgtCode);
            if (cached != null && !cached.equals(clean)) {
                optionTranslations.put(clean, cached);
                option.setText(cached);
                continue;
            }

            // Mark as "pending" with a self-mapping to prevent duplicate async requests
            optionTranslations.put(clean, clean);

            final Widget capturedOption = option;
            final String capturedClean  = clean;
            translator.translateAsync(clean).thenAccept(translated -> {
                if (!translated.startsWith("\u23F3") && !translated.equals(capturedClean)) {
                    optionTranslations.put(capturedClean, translated);
                    translator.getCache().put(capturedClean, translated, srcCode, tgtCode);
                    capturedOption.setText(translated);
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
        optionTranslations.clear();
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