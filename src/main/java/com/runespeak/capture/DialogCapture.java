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

    @Inject
    public DialogCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void checkAndTranslateDialog() {
        if (!config.translateNpcDialogue()) return;

        Widget npcDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
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
            String text = activeDialog.getText();
            if (text != null && !text.isEmpty() && !text.equals(currentDialogOriginal)) {
                dialogActive = true;
                currentDialogOriginal = text;

                Language source = config.getSourceLanguage();
                Language target = config.getTargetLanguage();
                translator.setLanguages(source, target);

Widget dialogWidget = activeDialog;
                translator.translateAsync(text).thenAccept(translated -> {
                    if (!translated.startsWith("\u23F3") && text.equals(currentDialogOriginal)) {
                        currentDialogTranslation = translated;
                        dialogWidget.setText(translated);
                    } else {
                        currentDialogTranslation = text;
                    }
                });
            }
        } else {
            dialogActive = false;
            currentDialogTranslation = "";
            currentDialogOriginal = "";
        }
    }

    public void clear() {
        dialogActive = false;
        currentDialogTranslation = "";
        currentDialogOriginal = "";
    }
}