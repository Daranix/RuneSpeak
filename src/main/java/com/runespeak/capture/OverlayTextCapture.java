package com.runespeak.capture;

import com.runespeak.Language;
import com.runespeak.RuneSpeakConfig;
import com.runespeak.translate.LocalTranslator;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Singleton
public class OverlayTextCapture {

    private static final int TEXT_WIDGET = InterfaceID.Mesoverlay.TEXT;
    private static final Pattern TAG_OR_TEXT = Pattern.compile("(<[^>]+>)|([^<]+)");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private final Client client;
    private final RuneSpeakConfig config;
    private final LocalTranslator translator;

    @Getter
    private volatile String currentOriginal = "";

    @Getter
    private volatile String currentTranslation = "";

    @Getter
    private volatile boolean active = false;

    private volatile String lastReadHash = "";
    private volatile String lastWrittenHash = "";

    @Inject
    public OverlayTextCapture(Client client, RuneSpeakConfig config, LocalTranslator translator) {
        this.client = client;
        this.config = config;
        this.translator = translator;
    }

    public void checkAndTranslate() {
        if (!config.translateOverlayText()) {
            if (active) reset();
            return;
        }

        Widget container = client.getWidget(TEXT_WIDGET);
        if (container == null) {
            if (active) reset();
            return;
        }

        Widget[] children = container.getDynamicChildren();
        if (children == null || children.length == 0) {
            if (active) reset();
            return;
        }

        List<Integer> textChildIndices = new ArrayList<>();
        List<List<Segment>> childSegments = new ArrayList<>();
        StringBuilder fullPlain = new StringBuilder();

        for (int ci = 0; ci < children.length; ci++) {
            String html = children[ci].getText();
            if (html == null) continue;

            List<Segment> segs = new ArrayList<>();
            Matcher m = TAG_OR_TEXT.matcher(html);
            while (m.find()) {
                if (m.group(1) != null) {
                    segs.add(new Segment(m.group(1), true));
                } else {
                    String t = m.group(2);
                    if (!t.isEmpty()) {
                        segs.add(new Segment(t, false));
                    }
                }
            }

            if (segs.isEmpty()) continue;

            textChildIndices.add(ci);
            childSegments.add(segs);

            for (Segment seg : segs) {
                if (!seg.isTag) {
                    if (fullPlain.length() > 0) fullPlain.append(' ');
                    fullPlain.append(seg.text);
                }
            }
        }

        String text = fullPlain.toString().trim();
        if (text.isEmpty()) {
            if (active) reset();
            return;
        }

        String currentHash = Integer.toHexString(text.hashCode());
        if (currentHash.equals(lastReadHash) || currentHash.equals(lastWrittenHash)) return;
        lastReadHash = currentHash;

        log.info("Overlay: '{}'", text);

        active = true;
        currentOriginal = text;
        currentTranslation = "";

        Language source = config.getSourceLanguage();
        Language target = config.getTargetLanguage();
        translator.setLanguages(source, target);

        final List<Integer> indices = textChildIndices;

        translator.translateAsync(text).thenAccept(translated -> {
            if (translated.startsWith("\u23F3")) return;

            currentTranslation = translated;

            if (!translated.equals(text)) {
                log.info("Overlay: '{}' -> '{}'", text, translated);

                if (!config.showOriginal()) {
                    String nextRead = distributeTranslation(childSegments, translated);

                    for (int i = 0; i < indices.size() && i < childSegments.size(); i++) {
                        int childIdx = indices.get(i);
                        StringBuilder sb = new StringBuilder();
                        for (Segment seg : childSegments.get(i)) {
                            sb.append(seg.text);
                        }
                        children[childIdx].setText(sb.toString());
                    }

                    lastWrittenHash = Integer.toHexString(nextRead.hashCode());
                }
            }
        });
    }

    private static String distributeTranslation(List<List<Segment>> childSegments, String translated) {
        List<Segment> textSegments = new ArrayList<>();
        for (List<Segment> segs : childSegments) {
            for (Segment seg : segs) {
                if (!seg.isTag) {
                    textSegments.add(seg);
                }
            }
        }

        if (textSegments.isEmpty()) return translated;

        int[] origSentenceCounts = new int[textSegments.size()];
        int totalOrigSentences = 0;
        for (int i = 0; i < textSegments.size(); i++) {
            origSentenceCounts[i] = Math.max(1, countSentences(textSegments.get(i).text));
            totalOrigSentences += origSentenceCounts[i];
        }

        String[] transSentences = splitSentences(translated);
        int tSent = transSentences.length;

        if (tSent >= textSegments.size()) {
            int start = 0;
            for (int i = 0; i < textSegments.size(); i++) {
                int remaining = textSegments.size() - 1 - i;
                double proportion = (double) origSentenceCounts[i] / totalOrigSentences;
                int end = i == textSegments.size() - 1
                    ? tSent
                    : Math.min(start + Math.max(1, (int) Math.round(proportion * tSent)), tSent - remaining);
                if (end <= start) end = start + 1;
                if (end > tSent) end = tSent;

                StringBuilder sb = new StringBuilder();
                for (int j = start; j < end; j++) {
                    if (j > start) sb.append(' ');
                    sb.append(transSentences[j]);
                }
                textSegments.get(i).text = sb.toString();
                start = end;
            }
        } else {
            int totalWords = 0;
            for (Segment seg : textSegments) totalWords += countWords(seg.text);
            if (totalWords == 0) return translated;

            String[] words = translated.split(" ", -1);
            int wordCount = words.length;
            int startIdx = 0;
            for (int i = 0; i < textSegments.size(); i++) {
                int segWords = Math.max(1, countWords(textSegments.get(i).text));
                int endIdx = i == textSegments.size() - 1
                    ? wordCount
                    : Math.min(startIdx + Math.max(1, (int) ((double) segWords / totalWords * wordCount)), wordCount);

                StringBuilder sb = new StringBuilder();
                for (int j = startIdx; j < endIdx; j++) {
                    if (j > startIdx) sb.append(' ');
                    sb.append(words[j]);
                }
                textSegments.get(i).text = sb.toString();
                startIdx = endIdx;
            }
        }

        StringBuilder nextRead = new StringBuilder();
        for (List<Segment> segs : childSegments) {
            for (Segment seg : segs) {
                if (!seg.isTag) {
                    if (nextRead.length() > 0) nextRead.append(' ');
                    nextRead.append(seg.text);
                }
            }
        }
        return nextRead.toString().trim();
    }

    private static String[] splitSentences(String text) {
        if (text == null || text.trim().isEmpty()) return new String[0];
        String[] parts = SENTENCE_END.split(text);
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) result.add(t);
        }
        return result.toArray(new String[0]);
    }

    private static int countSentences(String text) {
        return splitSentences(text).length;
    }

    private static int countWords(String text) {
        if (text == null || text.trim().isEmpty()) return 0;
        return text.trim().split("\\s+").length;
    }

    public void clear() {
        reset();
    }

    public void onGameStateChanged(net.runelite.api.events.GameStateChanged event) {
        if (event.getGameState() == net.runelite.api.GameState.LOGGED_IN) {
            log.debug("Overlay: LOGGED_IN — resetting hashes");
            lastReadHash = "";
            lastWrittenHash = "";
        }
    }

    private void reset() {
        active = false;
        currentOriginal = "";
        currentTranslation = "";
        lastReadHash = "";
        lastWrittenHash = "";
    }

    private static class Segment {
        String text;
        final boolean isTag;

        Segment(String text, boolean isTag) {
            this.text = text;
            this.isTag = isTag;
        }
    }
}
