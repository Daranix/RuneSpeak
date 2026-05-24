package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuOpened;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

@Slf4j
@Singleton
public class MenuCapture {
    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    private final Map<String, String> optionTranslations = new ConcurrentHashMap<>();

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

        MenuEntry[] entries = client.getMenuEntries();
        for (MenuEntry entry : entries) {
            String option = entry.getOption();
            String target_text = entry.getTarget();

            if (option != null && !option.isEmpty() && !optionTranslations.containsKey("option:" + option)) {
                String translated = translator.translateSync(option);
                if (!translated.equals(option)) {
                    optionTranslations.put("option:" + option, translated);
                    entry.setOption(translated);
                }
            }
            if (target_text != null && !target_text.isEmpty() && !target_text.equals(option)
                    && !optionTranslations.containsKey("target:" + target_text)) {
                String translated = translator.translateSync(target_text);
                if (!translated.equals(target_text)) {
                    optionTranslations.put("target:" + target_text, translated);
                    entry.setTarget(translated);
                }
            }
        }
    }

    public String getTranslation(String cacheKey) {
        return optionTranslations.get(cacheKey);
    }

    public void clear() {
        optionTranslations.clear();
    }
}