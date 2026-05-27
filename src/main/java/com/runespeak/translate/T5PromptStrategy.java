package com.runespeak.translate;

import com.runespeak.Language;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

public class T5PromptStrategy implements PromptStrategy {

    private static final Map<String, String> FLORES_TO_NAME = Collections.unmodifiableMap(
            Arrays.stream(Language.values())
                    .collect(Collectors.toMap(Language::getFloresCode, Language::getDisplayName))
    );

    @Override
    public String format(String text, String srcFloresCode, String tgtFloresCode) {
        String src = FLORES_TO_NAME.getOrDefault(srcFloresCode, srcFloresCode);
        String tgt = FLORES_TO_NAME.getOrDefault(tgtFloresCode, tgtFloresCode);
        return "translate " + src + " to " + tgt + ": " + text;
    }
}
