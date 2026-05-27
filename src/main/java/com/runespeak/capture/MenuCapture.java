package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

@Slf4j
@Singleton
public class MenuCapture {
    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    private final Set<String> pending = ConcurrentHashMap.newKeySet();

    @Inject
    public MenuCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void handleOpenedMenu(MenuOpened event) {
        if (!config.translateMenuEntries()) return;

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        translator.setLanguages(source, target);

        String srcCode = source.getFloresCode();
        String tgtCode = target.getFloresCode();

        MenuEntry[] entries = client.getMenuEntries();
        for (MenuEntry entry : entries) {
            String option = entry.getOption();
            if (option != null && !option.isEmpty()) {
                String cached = translator.getCache().get(option, srcCode, tgtCode);
                if (cached != null) {
                    entry.setOption(cached);
                } else if (pending.add("option:" + option)) {
                    translator.translateAsync(option).thenAccept(translated -> {
                        if (translated != null && !translated.equals(option)) {
                            translator.getCache().put(option, translated, srcCode, tgtCode);
                        }
                    });
                }
            }
            String targetText = entry.getTarget();
            if (targetText != null && !targetText.isEmpty() && !targetText.equals(option)) {
                String cached = translator.getCache().get(targetText, srcCode, tgtCode);
                if (cached != null) {
                    entry.setTarget(cached);
                } else if (pending.add("target:" + targetText)) {
                    translator.translateAsync(targetText).thenAccept(translated -> {
                        if (translated != null && !translated.equals(targetText)) {
                            translator.getCache().put(targetText, translated, srcCode, tgtCode);
                        }
                    });
                }
            }
        }
    }

    public void clear() {
        pending.clear();
    }
}
