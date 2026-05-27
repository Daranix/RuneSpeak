package com.runespeak;

import com.google.inject.Provides;
import com.runespeak.capture.ChatCapture;
import com.runespeak.capture.DialogCapture;
import com.runespeak.capture.MenuCapture;
import com.runespeak.overlay.TranslationOverlay;
import com.runespeak.panel.RuneSpeakPanel;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.OverheadTextChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

import javax.inject.Inject;
import java.awt.image.BufferedImage;

@Slf4j
@PluginDescriptor(
        name = "RuneSpeak",
        description = "Local AI translation plugin for OSRS using HuggingFace models.",
        tags = {"translation", "ai", "local", "offline", "huggingface"}
)
public class RuneSpeakPlugin extends Plugin {
    @Inject
    @Getter
    private Client client;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    @Getter
    private RuneSpeakConfig config;

    @Inject
    private LocalTranslator translator;

    @Inject
    private MenuCapture menuCapture;

    @Inject
    private DialogCapture dialogCapture;

    @Inject
    private ChatCapture chatCapture;

    @Inject
    private TranslationOverlay translationOverlay;

    @Inject
    private ConfigManager configManager;

    private RuneSpeakPanel panel;
    private NavigationButton navButton;
    @Override
    protected void startUp() {
        log.info("RuneSpeak starting up...");

        translator.applyConfig();

        overlayManager.add(translationOverlay);
        initPanel();

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        translator.setLanguages(source, target);

        startModelLoading();

        log.info("RuneSpeak started!");
    }

    @Override
    protected void shutDown() {
        log.info("RuneSpeak shutting down...");

        overlayManager.remove(translationOverlay);

        if (navButton != null) {
            clientToolbar.removeNavigation(navButton);
        }

        if (panel != null) {
            panel.shutdown();
        }

        translator.shutdown();

        log.info("RuneSpeak stopped!");
    }

    @Provides
    RuneSpeakConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(RuneSpeakConfig.class);
    }

    @Subscribe
    public void onGameTick(GameTick tick) {
        if (client.getGameState() != GameState.LOGGED_IN) return;

        if (config.translateNpcDialogue()) {
            dialogCapture.checkAndTranslateDialog();
        }
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event) {
        // Fires on hover (before right-click) — apply cached translations so the
        // hover tooltip shows translated text without needing a full right-click.
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (config.translateMenuEntries()) {
            menuCapture.handleMenuEntryAdded(event);
        }
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        if (config.translateMenuEntries()) {
            menuCapture.handleOpenedMenu(event);
        }
    }

    @Subscribe
    public void onChatMessage(ChatMessage event) {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        chatCapture.handleChatMessage(event);
    }

    @Subscribe
    public void onOverheadTextChanged(OverheadTextChanged event) {
        if (!config.translateOverhead()) return;
        if (client.getGameState() != GameState.LOGGED_IN) return;

        String text = event.getOverheadText();
        translator.translateAsync(text).thenAccept(translated -> {
            if (!translated.equals(text) && !translated.startsWith("⏳")) {
                event.getActor().setOverheadText(translated);
                log.info("Overhead: {} -> {}", text, translated);
            }
        });
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if (!"runespeak".equals(event.getGroup())) return;

        switch (event.getKey()) {
            case "targetLanguage":
            case "sourceLanguage": {
                Language source = config.getSourceLanguage();
                Language target = config.getTargetLanguage();
                translator.setLanguages(source, target);
                log.info("Languages updated: {} → {}", source.getDisplayName(), target.getDisplayName());

                // Clear stale translations for the old language pair
                translator.getCache().clear();
                dialogCapture.clear();
                menuCapture.clear();
                log.info("Translation cache and dialog state cleared for new language pair.");

                if (source != target) {
                    String modelId = "onnx-community/opus-mt-" + source.getTwoLetterCode() + "-" + target.getTwoLetterCode();
                    translator.initialize(modelId);
                }
                break;
            }
        }
    }

    private static final java.util.Set<String> OBSOLETE_MODELS = java.util.Set.of(
            "google-t5/t5-small",
            "google-t5/t5-base",
            "facebook/nllb-200-distilled-600M",
            "Helsinki-NLP/opus-mt-en-es",
            "Helsinki-NLP/opus-mt-en-fr",
            "Helsinki-NLP/opus-mt-en-de"
    );

    private static final String DEFAULT_MODEL = "onnx-community/opus-mt-en-es";

    private void startModelLoading() {
        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        if (source != target) {
            String modelId = "onnx-community/opus-mt-" + source.getTwoLetterCode() + "-" + target.getTwoLetterCode();
            log.info("startModelLoading: Loading model: {}", modelId);
            translator.initialize(modelId);
        }
        log.info("startModelLoading: Initialization submitted to executor");
    }

    private void initPanel() {
        panel = injector.getInstance(RuneSpeakPanel.class);

        final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");

        navButton = NavigationButton.builder()
                .tooltip("RuneSpeak")
                .icon(icon)
                .priority(6)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);
    }
}
