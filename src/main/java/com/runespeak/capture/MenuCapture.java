package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class MenuCapture {
    /** Matches a leading colour tag, e.g. <col=ffff00> */
    private static final Pattern LEADING_COL_TAG = Pattern.compile("^(<col=[0-9a-fA-F]+>)(.+?)(?:</col>)?$", Pattern.DOTALL);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

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
            // --- Option (action label, e.g. "Talk-to") ---
            String option = entry.getOption();
            if (option != null && !option.isEmpty()) {
                String cleanOption = stripTags(option);
                if (!cleanOption.isEmpty()) {
                    String cached = translator.getCache().get(cleanOption, srcCode, tgtCode);
                    if (cached != null) {
                        entry.setOption(cached);
                    } else if (pending.add("option:" + cleanOption)) {
                        final String key = cleanOption;
                        translator.translateAsync(cleanOption).thenAccept(translated -> {
                            if (translated != null && !translated.startsWith("⏳") && !translated.equals(key)) {
                                translator.getCache().put(key, translated, srcCode, tgtCode);
                            }
                        });
                    }
                }
            }

            // --- Target (object/NPC name, e.g. "<col=ffff00>Survival Expert") ---
            String targetText = entry.getTarget();
            if (targetText != null && !targetText.isEmpty()) {
                // Separate the leading colour tag from the plain name so the
                // translation model only sees natural language text.
                Matcher m = LEADING_COL_TAG.matcher(targetText);
                String colTag;
                String plainTarget;
                if (m.matches()) {
                    colTag = m.group(1);
                    plainTarget = m.group(2);
                } else {
                    colTag = "";
                    plainTarget = stripTags(targetText);
                }

                if (!plainTarget.isEmpty()) {
                    String cached = translator.getCache().get(plainTarget, srcCode, tgtCode);
                    if (cached != null) {
                        entry.setTarget(colTag + cached);
                    } else if (pending.add("target:" + plainTarget)) {
                        final String key = plainTarget;
                        final String tag = colTag;
                        translator.translateAsync(plainTarget).thenAccept(translated -> {
                            if (translated != null && !translated.startsWith("⏳") && !translated.equals(key)) {
                                translator.getCache().put(key, translated, srcCode, tgtCode);
                            }
                        });
                    }
                }
            }
        }
    }

    /**
     * Fires on every hover (before right-click). Only applies CACHED translations
     * so the hover tooltip shows translated text immediately. Cache is warmed by
     * handleOpenedMenu when the full right-click menu is opened.
     */
    public void handleMenuEntryAdded(MenuEntryAdded event) {
        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        String srcCode = source.getFloresCode();
        String tgtCode = target.getFloresCode();

        MenuEntry entry = event.getMenuEntry();

        String option = entry.getOption();
        if (option != null && !option.isEmpty()) {
            String cleanOption = stripTags(option);
            String cached = translator.getCache().get(cleanOption, srcCode, tgtCode);
            if (cached != null) {
                entry.setOption(cached);
            }
        }

        String targetText = entry.getTarget();
        if (targetText != null && !targetText.isEmpty()) {
            Matcher m = LEADING_COL_TAG.matcher(targetText);
            String colTag;
            String plainTarget;
            if (m.matches()) {
                colTag = m.group(1);
                plainTarget = m.group(2);
            } else {
                colTag = "";
                plainTarget = stripTags(targetText);
            }
            if (!plainTarget.isEmpty()) {
                String cached = translator.getCache().get(plainTarget, srcCode, tgtCode);
                if (cached != null) {
                    entry.setTarget(colTag + cached);
                }
            }
        }
    }

    public void clear() {
        pending.clear();
    }

    private static String stripTags(String text) {
        if (text == null) return "";
        return ANY_TAG.matcher(text).replaceAll("").trim();
    }
}
