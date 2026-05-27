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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class MenuCapture {
    private static final Pattern LEADING_COL_TAG = Pattern.compile("^(<col=[0-9a-fA-F]+>)(.+?)(?:</col>)?$", Pattern.DOTALL);
    private static final Pattern ANY_TAG = Pattern.compile("<[^>]+>");

    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    /** Tracks texts with translation in-flight to prevent duplicate requests. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

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
            String targetText = entry.getTarget();
            String colTag = "";
            String plainTarget = "";
            if (targetText != null && !targetText.isEmpty()) {
                Matcher m = LEADING_COL_TAG.matcher(targetText);
                if (m.matches()) {
                    colTag = m.group(1);
                    plainTarget = m.group(2);
                } else {
                    plainTarget = stripTags(targetText);
                }
            }

            String option = entry.getOption();
            if (option != null && !option.isEmpty()) {
                String cleanOption = stripTags(option);
                if (!cleanOption.isEmpty()) {
                    boolean hasContext = !plainTarget.isEmpty() && cleanOption.length() <= 6 && !cleanOption.contains(" ");
                    String contextKey = hasContext ? cleanOption + "|" + plainTarget : cleanOption;

                    String cached = translator.getCache().get(contextKey, srcCode, tgtCode);
                    if (cached != null) {
                        entry.setOption(cached);
                        inFlight.add("option:" + cached);
                    } else if (!inFlight.contains("option:" + contextKey)) {
                        inFlight.add("option:" + contextKey);
                        final String translateText = hasContext ? plainTarget + " - " + cleanOption : cleanOption;
                        final String key = contextKey;
                        translator.translateAsync(translateText).thenAccept(translated -> {
                            if (translated != null && !translated.startsWith("\u23F3") && !translated.equals(key)) {
                                String optionResult = translated;
                                if (hasContext) {
                                    int sep = translated.lastIndexOf(" - ");
                                    if (sep >= 0) {
                                        optionResult = translated.substring(sep + 3).trim();
                                    } else {
                                        sep = translated.indexOf(" - ");
                                        if (sep >= 0) {
                                            optionResult = translated.substring(0, sep).trim();
                                        }
                                    }
                                    if (optionResult.length() > cleanOption.length() * 3) {
                                        optionResult = cleanOption;
                                    }
                                }
                                if (!optionResult.equals(cleanOption)) {
                                    translator.getCache().put(key, optionResult, srcCode, tgtCode);
                                }
                            }
                        });
                    }
                }
            }

            if (!plainTarget.isEmpty()) {
                String cached = translator.getCache().get(plainTarget, srcCode, tgtCode);
                if (cached != null) {
                    entry.setTarget(colTag + cached);
                    inFlight.add("target:" + cached);
                } else if (!inFlight.contains("target:" + plainTarget)) {
                    inFlight.add("target:" + plainTarget);
                    final String key = plainTarget;
                    final String tag = colTag;
                    translator.translateAsync(plainTarget).thenAccept(translated -> {
                        if (translated != null && !translated.startsWith("\u23F3") && !translated.equals(key)) {
                            translator.getCache().put(key, translated, srcCode, tgtCode);
                        }
                    });
                }
            }
        }
    }

    public void clear() {
        inFlight.clear();
    }

    public void handleMenuEntryAdded(MenuEntryAdded event) {
        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        String srcCode = source.getFloresCode();
        String tgtCode = target.getFloresCode();

        MenuEntry entry = event.getMenuEntry();

        String targetText = entry.getTarget();
        String colTag = "";
        String plainTarget = "";
        if (targetText != null && !targetText.isEmpty()) {
            Matcher m = LEADING_COL_TAG.matcher(targetText);
            if (m.matches()) {
                colTag = m.group(1);
                plainTarget = m.group(2);
            } else {
                plainTarget = stripTags(targetText);
            }
        }

        String option = entry.getOption();
        if (option != null && !option.isEmpty()) {
            String cleanOption = stripTags(option);
            if (!cleanOption.isEmpty()) {
                boolean hasContext = !plainTarget.isEmpty() && cleanOption.length() <= 6 && !cleanOption.contains(" ");
                String contextKey = hasContext ? cleanOption + "|" + plainTarget : cleanOption;

                String cached = translator.getCache().get(contextKey, srcCode, tgtCode);
                if (cached != null) {
                    entry.setOption(cached);
                    inFlight.add("option:" + cached);
                } else if (!inFlight.contains("option:" + contextKey)) {
                    inFlight.add("option:" + contextKey);
                    final String translateText = hasContext ? plainTarget + " - " + cleanOption : cleanOption;
                    final String key = contextKey;
                    translator.translateAsync(translateText).thenAccept(translated -> {
                        if (translated != null && !translated.startsWith("\u23F3") && !translated.equals(key)) {
                            String optionResult = translated;
                            if (hasContext) {
                                int sep = translated.lastIndexOf(" - ");
                                if (sep >= 0) {
                                    optionResult = translated.substring(sep + 3).trim();
                                } else {
                                    sep = translated.indexOf(" - ");
                                    if (sep >= 0) {
                                        optionResult = translated.substring(0, sep).trim();
                                    }
                                }
                                if (optionResult.length() > cleanOption.length() * 3) {
                                    optionResult = cleanOption;
                                }
                            }
                            if (!optionResult.equals(cleanOption)) {
                                translator.getCache().put(key, optionResult, srcCode, tgtCode);
                            }
                        }
                    });
                }
            }
        }

        if (!plainTarget.isEmpty()) {
            String cached = translator.getCache().get(plainTarget, srcCode, tgtCode);
            if (cached != null) {
                entry.setTarget(colTag + cached);
                inFlight.add("target:" + cached);
            } else if (!inFlight.contains("target:" + plainTarget)) {
                inFlight.add("target:" + plainTarget);
                final String key = plainTarget;
                translator.translateAsync(plainTarget).thenAccept(translated -> {
                    if (translated != null && !translated.startsWith("\u23F3") && !translated.equals(key)) {
                        translator.getCache().put(key, translated, srcCode, tgtCode);
                    }
                });
            }
        }
    }

    private static String stripTags(String text) {
        if (text == null) return "";
        return ANY_TAG.matcher(text).replaceAll("").trim();
    }
}
