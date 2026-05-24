package com.runespeak.overlay;

import com.runespeak.RuneSpeakConfig;
import com.runespeak.capture.DialogCapture;
import com.runespeak.capture.MenuCapture;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class TranslationOverlay extends Overlay {
    private static final Pattern COLOR_TAG_PATTERN = Pattern.compile("<col=[^>]+>|</col>");
    private static final Pattern ICON_TAG_PATTERN = Pattern.compile("<img=\\d+>");

    private final Client client;
    private final RuneSpeakConfig config;
    private final MenuCapture menuCapture;
    private final DialogCapture dialogCapture;
    private final LocalTranslator translator;

    @Inject
    public TranslationOverlay(Client client, RuneSpeakConfig config, MenuCapture menuCapture, DialogCapture dialogCapture, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.menuCapture = menuCapture;
        this.dialogCapture = dialogCapture;
        this.translator = translator;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!translator.isReady()) return null;

        renderDialogTranslation(graphics);
        renderMenuHoverTranslation(graphics);

        return null;
    }

    private void renderDialogTranslation(Graphics2D graphics) {
        if (!dialogCapture.isDialogActive()) return;
        if (!config.translateNpcDialogue()) return;

        Widget npcDialog = client.getWidget(WidgetInfo.DIALOG_NPC_TEXT);
        Widget dialogWidget = npcDialog != null && !npcDialog.isHidden() ? npcDialog : null;

        if (dialogWidget == null) {
            Widget playerDialog = client.getWidget(WidgetInfo.DIALOG_PLAYER_TEXT);
            dialogWidget = playerDialog != null && !playerDialog.isHidden() ? playerDialog : null;
        }

        if (dialogWidget == null) return;

        String translation = dialogCapture.getCurrentDialogTranslation();
        if (translation == null || translation.isEmpty()) return;
        if (translation.equals(dialogCapture.getCurrentDialogOriginal()) && !config.showOriginal()) return;

        Rectangle bounds = dialogWidget.getBounds();
        if (bounds.isEmpty()) return;

        Font originalFont = graphics.getFont();
        graphics.setFont(new Font("Dialog", Font.PLAIN, 14));

        String textToRender = translation;
        textToRender = COLOR_TAG_PATTERN.matcher(textToRender).replaceAll("");
        textToRender = ICON_TAG_PATTERN.matcher(textToRender).replaceAll("");

        int textX = (int) bounds.getX();
        int textY = (int) bounds.getY() + bounds.height + 20;

        graphics.setColor(new Color(0, 0, 0, 180));
        graphics.fillRect(textX - 2, textY - 14, graphics.getFontMetrics().stringWidth(textToRender) + 4, 18);

        Color textColor = parseColor(config.getTranslationColor());
        graphics.setColor(textColor);
        graphics.drawString(textToRender, textX, textY);

        String prefix = "[AI] ";
        graphics.setColor(new Color(0, 255, 100, 120));
        graphics.setFont(new Font("Dialog", Font.BOLD, 10));
        graphics.drawString(prefix, textX, textY - 2);

        graphics.setFont(originalFont);
    }

    private void renderMenuHoverTranslation(Graphics2D graphics) {
        if (!config.translateMenuEntries()) return;

        MenuEntry[] entries = client.getMenuEntries();
        if (entries == null || entries.length == 0) return;

        for (MenuEntry entry : entries) {
            String option = entry.getOption();
            if (option == null || option.isEmpty()) continue;

            String translation = menuCapture.getTranslation("option:" + option);
            if (translation == null || translation.equals(option)) continue;

            String target = entry.getTarget();
            String targetTranslation = null;
            if (target != null && !target.isEmpty() && !target.equals(option)) {
                targetTranslation = menuCapture.getTranslation("target:" + target);
            }
        }
    }

    private Color parseColor(String hex) {
        try {
            return Color.decode(hex);
        } catch (NumberFormatException e) {
            return Color.GREEN;
        }
    }
}
