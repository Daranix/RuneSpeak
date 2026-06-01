package com.runespeak.translate;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Slf4j
public class BpeTokenizer {
    private static final String SPACE_MARKER = "\u2581";

    private final Map<String, Integer> vocab;
    private final List<Merge> merges;
    private final Map<String, Float> tokenScores;
    private final boolean isUnigram;
    private final boolean addBosToken;
    private final boolean addEosToken;
    private final int bosId;
    private final int eosId;
    private final int padId;
    private final int unkId;
    private final String unkToken;
    private final int vocabSize;
    private final boolean prependScheme;

    private static class Merge implements Comparable<Merge> {
        final String left, right;
        final int rank;

        Merge(String left, String right, int rank) {
            this.left = left;
            this.right = right;
            this.rank = rank;
        }

        public int compareTo(Merge o) {
            return Integer.compare(this.rank, o.rank);
        }
    }

    public BpeTokenizer(Path tokenizerJsonPath) throws IOException {
        this(Files.readString(tokenizerJsonPath, StandardCharsets.UTF_8));
    }

    public BpeTokenizer(String jsonContent) {
        Gson gson = new Gson();
        JsonObject root = gson.fromJson(jsonContent, JsonObject.class);

        this.vocab = new HashMap<>();
        this.merges = new ArrayList<>();
        this.tokenScores = new HashMap<>();

        JsonObject model = root.getAsJsonObject("model");
        if (model == null) throw new IllegalArgumentException("No 'model' in tokenizer.json");

        String type = getString(model, "type");
        boolean isBpe = "BPE".equals(type);
        this.isUnigram = "Unigram".equals(type);

        if (isBpe) {
            JsonObject vocabObj = model.getAsJsonObject("vocab");
            if (vocabObj != null) {
                for (String key : vocabObj.keySet()) {
                    vocab.put(key, vocabObj.get(key).getAsInt());
                }
            }
            JsonArray mergesArr = model.getAsJsonArray("merges");
            if (mergesArr != null) {
                for (int i = 0; i < mergesArr.size(); i++) {
                    String line = mergesArr.get(i).getAsString();
                    int space = line.lastIndexOf(' ');
                    if (space > 0) {
                        String left = line.substring(0, space);
                        String right = line.substring(space + 1);
                        merges.add(new Merge(left, right, i));
                    }
                }
            }
            Collections.sort(merges);
        } else if (isUnigram) {
            JsonElement vocabEl = model.get("vocab");
            if (vocabEl != null) {
                if (vocabEl.isJsonObject()) {
                    int idx = 0;
                    for (String key : vocabEl.getAsJsonObject().keySet()) {
                        vocab.put(key, idx++);
                    }
                } else if (vocabEl.isJsonArray()) {
                    JsonArray arr = vocabEl.getAsJsonArray();
                    for (int i = 0; i < arr.size(); i++) {
                        JsonElement entry = arr.get(i);
                        if (entry.isJsonArray()) {
                            // Format: [["token", score], ["token2", score2], ...]
                            JsonArray pair = entry.getAsJsonArray();
                            if (pair.size() >= 1 && pair.get(0).isJsonPrimitive()) {
                                String token = pair.get(0).getAsString();
                                int id = i;
                                vocab.put(token, id);
                                if (pair.size() >= 2) {
                                    tokenScores.put(token, pair.get(1).getAsFloat());
                                }
                            }
                        } else if (entry.isJsonObject() && entry.getAsJsonObject().has("content")) {
                            String token = entry.getAsJsonObject().get("content").getAsString();
                            int id = vocab.size();
                            vocab.put(token, id);
                        } else if (entry.isJsonPrimitive()) {
                            // skip
                        }
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported tokenizer type: " + type);
        }

        this.vocabSize = vocab.size();

        JsonObject addPrefixSpace = root.getAsJsonObject("add_prefix_space");
        this.prependScheme = addPrefixSpace != null && addPrefixSpace.get("type") != null;

        JsonObject normalizer = root.getAsJsonObject("normalizer");
        if (normalizer != null && !normalizer.isJsonNull()) {
            sanitizeNormalizer(normalizer);
        }

        this.unkToken = extractToken(root, "unk_token", "<unk>");
        this.unkId = vocab.getOrDefault(unkToken, 0);
        this.bosId = extractTokenId(root, "bos_token", vocab);
        this.eosId = extractTokenId(root, "eos_token", vocab);
        this.padId = extractTokenId(root, "pad_token", vocab);
        this.addBosToken = bosId >= 0;
        this.addEosToken = eosId >= 0;

        log.debug("BPE tokenizer loaded: vocab={}, merges={}, unk={}, bos={}, eos={}, pad={}",
                vocabSize, merges.size(), unkId, bosId, eosId, padId);
    }

    private static void sanitizeNormalizer(JsonObject normalizer) {
        if (!normalizer.has("type")) return;
        String type = normalizer.get("type").getAsString();
        if ("Sequence".equals(type) && normalizer.has("normalizers")) {
            JsonArray normalizers = normalizer.getAsJsonArray("normalizers");
            JsonArray cleaned = new JsonArray();
            for (JsonElement elem : normalizers) {
                if (elem.isJsonObject()) {
                    JsonObject n = elem.getAsJsonObject();
                    if (n.has("type") && "Precompiled".equals(n.get("type").getAsString())) {
                        if (!n.has("precompiled_charsmap") || n.get("precompiled_charsmap").isJsonNull()) {
                            continue;
                        }
                    }
                }
                cleaned.add(elem);
            }
            normalizer.add("normalizers", cleaned);
        }
    }

    public int[] encode(String text) {
        if (isUnigram) {
            return encodeUnigram(text);
        }

        String processed = preTokenize(text);

        if (merges.isEmpty()) {
            return charTokenize(processed);
        }

        String[] words = processed.split(" ");
        List<Integer> tokenIds = new ArrayList<>();

        for (String word : words) {
            if (word.isEmpty()) continue;
            String withSpace = word + "</w>";
            List<String> symbols = new ArrayList<>();
            for (int i = 0; i < word.length(); i++) {
                symbols.add(String.valueOf(word.charAt(i)));
            }
            if (!symbols.isEmpty()) {
                symbols.set(symbols.size() - 1, symbols.get(symbols.size() - 1) + "</w>");
            }

            boolean changed = true;
            while (changed) {
                changed = false;
                int bestRank = Integer.MAX_VALUE;
                int bestIdx = -1;
                for (int i = 0; i < symbols.size() - 1; i++) {
                    String pair = symbols.get(i) + symbols.get(i + 1);
                    int rank = findMergeRank(pair);
                    if (rank >= 0 && rank < bestRank) {
                        bestRank = rank;
                        bestIdx = i;
                    }
                }
                if (bestIdx >= 0) {
                    String merged = symbols.get(bestIdx) + symbols.get(bestIdx + 1).replace("</w>", "");
                    if (symbols.get(bestIdx + 1).endsWith("</w>")) {
                        merged += "</w>";
                    }
                    symbols.set(bestIdx, merged);
                    symbols.remove(bestIdx + 1);
                    changed = true;
                }
            }

            for (String symbol : symbols) {
                Integer id = vocab.get(symbol);
                if (id != null) {
                    tokenIds.add(id);
                } else {
                    tokenIds.add(unkId);
                }
            }
        }

        int[] result = new int[tokenIds.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = tokenIds.get(i);
        }
        return result;
    }

    public long[] encodeLong(String text) {
        int[] ids = encode(text);
        long[] result = new long[ids.length];
        for (int i = 0; i < ids.length; i++) {
            result[i] = ids[i];
        }
        return result;
    }

    public long[][] encodeBatch(String[] texts) {
        long[][] batch = new long[texts.length][];
        int maxLen = 0;
        for (int i = 0; i < texts.length; i++) {
            batch[i] = encodeLong(texts[i]);
            if (batch[i].length > maxLen) maxLen = batch[i].length;
        }
        return batch;
    }

    private int[] encodeUnigram(String text) {
        String[] words = preTokenizeUnigram(text);
        List<Integer> tokenIds = new ArrayList<>();

        for (String word : words) {
            List<String> tokens = viterbiSegment(word);
            for (String token : tokens) {
                Integer id = vocab.get(token);
                if (id != null) {
                    tokenIds.add(id);
                } else {
                    tokenIds.add(unkId);
                }
            }
        }

        int[] result = new int[tokenIds.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = tokenIds.get(i);
        }
        return result;
    }

    private String[] preTokenizeUnigram(String text) {
        if (text == null || text.isEmpty()) return new String[0];
        // Metaspace with add_prefix_space=true: prefix space before splitting
        String withPrefix = " " + text;
        String[] raw = withPrefix.split("\\s+");
        List<String> words = new ArrayList<>();
        for (String w : raw) {
            if (!w.isEmpty()) {
                words.add(SPACE_MARKER + w);
            }
        }
        return words.toArray(new String[0]);
    }

    private static final int MAX_UNIGRAM_LENGTH = 20;

    private List<String> viterbiSegment(String word) {
        int len = word.length();
        float[] bestScore = new float[len + 1];
        int[] backtrace = new int[len + 1];
        java.util.Arrays.fill(bestScore, Float.NEGATIVE_INFINITY);
        bestScore[0] = 0;

        for (int end = 1; end <= len; end++) {
            int start = Math.max(0, end - MAX_UNIGRAM_LENGTH);
            for (; start < end; start++) {
                String token = word.substring(start, end);
                Float score = tokenScores.get(token);
                if (score != null) {
                    float total = bestScore[start] + score;
                    if (total > bestScore[end]) {
                        bestScore[end] = total;
                        backtrace[end] = start;
                    }
                }
            }
        }

        List<String> tokens = new ArrayList<>();
        if (bestScore[len] == Float.NEGATIVE_INFINITY) {
            tokens.add(word);
            return tokens;
        }
        for (int end = len; end > 0; ) {
            int start = backtrace[end];
            tokens.add(word.substring(start, end));
            end = start;
        }
        java.util.Collections.reverse(tokens);
        return tokens;
    }

    private int[] charTokenize(String text) {
        int[] ids = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            ids[i] = vocab.getOrDefault(ch, unkId);
        }
        return ids;
    }

    private int findMergeRank(String pair) {
        for (Merge m : merges) {
            String mpair = m.left + m.right;
            if (mpair.equals(pair)) return m.rank;
        }
        return -1;
    }

    public String decode(long[] tokenIds) {
        if (isUnigram) {
            return decodeUnigram(tokenIds);
        }
        StringBuilder sb = new StringBuilder();
        for (long id : tokenIds) {
            String token = idToToken((int) id);
            if (token != null) {
                if (token.endsWith("</w>")) {
                    sb.append(token, 0, token.length() - 4);
                    sb.append(' ');
                } else {
                    sb.append(token);
                }
            }
        }
        String result = sb.toString().trim();
        if (result.startsWith("<")) {
            int tagEnd = result.indexOf('>');
            if (tagEnd >= 0 && tagEnd < 10) {
                result = result.substring(tagEnd + 1).trim();
            }
        }
        return result;
    }

    private String decodeUnigram(long[] tokenIds) {
        StringBuilder sb = new StringBuilder();
        for (long id : tokenIds) {
            String token = idToToken((int) id);
            if (token == null) continue;
            if (token.equals("</s>") || token.equals("<unk>")) continue;
            if (token.startsWith(SPACE_MARKER)) {
                sb.append(' ');
                sb.append(token.substring(SPACE_MARKER.length()));
            } else {
                sb.append(token);
            }
        }
        return sb.toString().trim();
    }

    private String idToToken(int id) {
        for (Map.Entry<String, Integer> e : vocab.entrySet()) {
            if (e.getValue() == id) return e.getKey();
        }
        return null;
    }

    private String preTokenize(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        boolean lastWasSpace = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                lastWasSpace = true;
                continue;
            }
            if (shouldSplit(c)) {
                if (!lastWasSpace) sb.append(' ');
                sb.append(c);
                sb.append(' ');
                lastWasSpace = true;
            } else {
                if (!lastWasSpace && !isWordChar(sb.charAt(sb.length() - 1)) && !isWordChar(c)) {
                    sb.append(' ');
                }
                sb.append(c);
                lastWasSpace = false;
            }
        }
        String result = sb.toString().toLowerCase(java.util.Locale.ROOT);
        while (result.contains("  ")) result = result.replace("  ", " ");
        return result.trim();
    }

    private static boolean shouldSplit(char c) {
        return c == '.' || c == ',' || c == '!' || c == '?' || c == ';' || c == ':'
                || c == '"' || c == '\'' || c == '(' || c == ')' || c == '[' || c == ']'
                || c == '{' || c == '}' || c == '-' || c == '–' || c == '—';
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '\'' || c == '`';
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return el != null && !el.isJsonNull() ? el.getAsString() : null;
    }

    private static int extractTokenId(JsonObject root, String key, Map<String, Integer> vocab) {
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return -1;
        String token;
        if (el.isJsonObject()) {
            JsonElement content = el.getAsJsonObject().get("content");
            token = content != null ? content.getAsString() : null;
        } else {
            token = el.getAsString();
        }
        if (token != null && vocab.containsKey(token)) {
            return vocab.get(token);
        }
        return -1;
    }

    private static String extractToken(JsonObject root, String key, String def) {
        JsonElement el = root.get(key);
        if (el == null || el.isJsonNull()) return def;
        if (el.isJsonObject()) {
            JsonElement content = el.getAsJsonObject().get("content");
            return content != null ? content.getAsString() : def;
        }
        return el.getAsString();
    }

    public int getVocabSize() { return vocabSize; }
    public int getBosId() { return bosId; }
    public int getEosId() { return eosId; }
    public int getPadId() { return padId; }
    public int getUnkId() { return unkId; }
    public boolean isAddBosToken() { return addBosToken; }
    public boolean isAddEosToken() { return addEosToken; }
}
