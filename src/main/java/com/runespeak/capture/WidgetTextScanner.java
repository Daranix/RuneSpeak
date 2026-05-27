package com.runespeak.capture;

import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Slf4j
@Singleton
public class WidgetTextScanner {

    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    private final List<TextSource> sources = new ArrayList<>();
    private final Set<Integer> registeredIds = new HashSet<>();
    private final Map<Integer, SourceState> states = new ConcurrentHashMap<>();

    @Inject
    public WidgetTextScanner(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void register(int componentId, String name, Predicate<RuneSpeakConfig> isEnabled) {
        if (registeredIds.add(componentId)) {
            sources.add(new TextSource(componentId, name, isEnabled));
        }
    }

    public void onGameTick() {
        for (TextSource source : sources) {
            if (!source.isEnabled.test(config)) continue;
            checkAndTranslate(source);
        }
    }

    private void checkAndTranslate(TextSource source) {
        Widget widget = client.getWidget(source.componentId);
        if (widget == null || widget.isHidden()) {
            SourceState prev = states.remove(source.componentId);
            if (prev != null && prev.lastOriginal != null && !prev.lastOriginal.isEmpty()) {
                log.debug("{}: dialog closed", source.name);
            }
            return;
        }

        String text = widget.getText();
        if (text == null || text.isEmpty()) {
            states.remove(source.componentId);
            return;
        }

        SourceState state = states.computeIfAbsent(source.componentId, k -> new SourceState());

        // Widget shows our cached translation — nothing to do
        if (text.equals(state.lastTranslation)) return;

        // Widget shows our cached original
        if (text.equals(state.lastOriginal)) {
            // showOriginal toggled OFF, we have a translation, widget shows original → apply
            if (!config.showOriginal() && state.lastTranslation != null && !state.lastTranslation.isEmpty()) {
                widget.setText(state.lastTranslation);
                state.translationApplied = true;
                return;
            }
            // showOriginal toggled ON, widget already shows original — nothing to do
            return;
        }

        // Widget shows different text from both original and translation → new text arrived
        // OR widget was reset by the game to its original text
        // Restore original if showOriginal is ON and we had applied a translation
        if (config.showOriginal() && state.translationApplied && state.lastOriginal != null && !state.lastOriginal.isEmpty()) {
            widget.setText(state.lastOriginal);
            return;
        }

        // This is genuinely new text — translate it
        state.lastOriginal = text;
        state.lastTranslation = null;
        state.translationApplied = false;

        log.info("{}: '{}'", source.name, text);

        translator.translateAsync(text).thenAccept(translated -> {
            if (translated.startsWith("\u23F3")) return;

            Widget w = client.getWidget(source.componentId);
            if (w == null || w.isHidden() || !text.equals(state.lastOriginal)) return;

            state.lastTranslation = translated;

            if (!translated.equals(text)) {
                log.info("{}: '{}' -> '{}'", source.name, text, translated);
            }

            if (!config.showOriginal()) {
                w.setText(translated);
                state.translationApplied = true;
            }
        });
    }

    public void clear() {
        states.clear();
    }

    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            log.debug("WidgetScanner: LOGGED_IN — resetting all state");
            states.clear();
        }
    }

    private static class TextSource {
        final int componentId;
        final String name;
        final Predicate<RuneSpeakConfig> isEnabled;

        TextSource(int componentId, String name, Predicate<RuneSpeakConfig> isEnabled) {
            this.componentId = componentId;
            this.name = name;
            this.isEnabled = isEnabled;
        }
    }

    private static class SourceState {
        volatile String lastOriginal = "";
        volatile String lastTranslation = null;
        volatile boolean translationApplied = false;
    }
}
