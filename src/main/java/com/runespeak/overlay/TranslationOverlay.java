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

/**
 * Translation overlay — kept in place for future use but currently
 * all in-game text is replaced directly inside the widgets by DialogCapture
 * and MenuCapture. The floating green text overlay has been removed to avoid
 * visual clutter and conflicts with the "Show Original" toggle.
 */
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
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.HIGH);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        // No floating overlay text — all translations are applied directly to widgets.
        return null;
    }
}
