package com.runespeak.overlay;

import com.runespeak.RuneSpeakConfig;
import com.runespeak.capture.DialogCapture;
import com.runespeak.capture.OverlayTextCapture;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.*;

@Slf4j
@Singleton
public class TranslationOverlay extends Overlay {

    private final Client client;
    private final RuneSpeakConfig config;
    private final DialogCapture dialogCapture;
    private final OverlayTextCapture overlayTextCapture;
    private final LocalTranslator translator;

    @Inject
    public TranslationOverlay(Client client, RuneSpeakConfig config, DialogCapture dialogCapture, OverlayTextCapture overlayTextCapture, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.dialogCapture = dialogCapture;
        this.overlayTextCapture = overlayTextCapture;
        this.translator = translator;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOriginal()) return null;

        int y = 5;
        FontMetrics fm = graphics.getFontMetrics();
        int pad = 4;

        // NPC dialog original text
        String dialogOriginal = dialogCapture.getCurrentDialogOriginal();
        if (dialogOriginal != null && !dialogOriginal.isEmpty()) {
            String clean = stripTags(dialogOriginal);
            if (!clean.isEmpty()) {
                String label = "NPC: " + clean;
                int textWidth = fm.stringWidth(label);
                int textHeight = fm.getHeight();

                graphics.setColor(new Color(0, 0, 0, 180));
                graphics.fillRect(5 - pad, y + textHeight - textHeight - pad,
                        textWidth + pad * 2, textHeight + pad * 2);

                graphics.setColor(Color.GREEN);
                graphics.drawString(label, 5, y + textHeight);
                y += textHeight + pad * 2 + 4;
            }
        }

        // Overlay text original (tutorial, notifications, etc.)
        String overlayOriginal = overlayTextCapture.getCurrentOriginal();
        if (overlayOriginal != null && !overlayOriginal.isEmpty()) {
            String clean = stripTags(overlayOriginal);
            if (!clean.isEmpty()) {
                String label = "Overlay: " + clean;
                int textWidth = fm.stringWidth(label);
                int textHeight = fm.getHeight();

                graphics.setColor(new Color(0, 0, 0, 180));
                graphics.fillRect(5 - pad, y + textHeight - textHeight - pad,
                        textWidth + pad * 2, textHeight + pad * 2);

                graphics.setColor(Color.GREEN);
                graphics.drawString(label, 5, y + textHeight);
                y += textHeight + pad * 2 + 4;
            }
        }

        return new Dimension(
                5 + pad * 2,
                y - 5
        );
    }

    private static String stripTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
