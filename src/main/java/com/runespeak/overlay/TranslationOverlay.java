package com.runespeak.overlay;

import com.runespeak.RuneSpeakConfig;
import com.runespeak.capture.DialogCapture;
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
    private final LocalTranslator translator;

    @Inject
    public TranslationOverlay(Client client, RuneSpeakConfig config, DialogCapture dialogCapture, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.dialogCapture = dialogCapture;
        this.translator = translator;
        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!config.showOriginal()) return null;

        String original = dialogCapture.getCurrentDialogOriginal();
        if (original == null || original.isEmpty()) return null;

        String clean = stripTags(original);
        if (clean.isEmpty()) return null;

        FontMetrics fm = graphics.getFontMetrics();

        String label = "Original: " + clean;
        int textWidth = fm.stringWidth(label);
        int textHeight = fm.getHeight();

        int pad = 4;
        int x = 5;
        int y = 5 + textHeight;

        graphics.setColor(new Color(0, 0, 0, 180));
        graphics.fillRect(x - pad, y - textHeight - pad,
                textWidth + pad * 2, textHeight + pad * 2);

        graphics.setColor(Color.GREEN);
        graphics.drawString(label, x, y);

        return new Dimension(textWidth + pad * 2, textHeight + pad * 2);
    }

    private static String stripTags(String text) {
        if (text == null) return "";
        return text.replaceAll("<[^>]+>", "").trim();
    }
}
