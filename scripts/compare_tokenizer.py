import json

# Load HuggingFace tokenizer
import sys
sys.stdout.reconfigure(encoding='utf-8')

# HuggingFace tokenizer
from transformers import AutoTokenizer
hf = AutoTokenizer.from_pretrained(
    'F:/runespeak_cache/models--onnx-community--opus-mt-en-es/snapshots/b5eba94a023a1954c90401b43537f479f962981d',
    use_fast=False)
tokens = hf.encode('Hello, how are you?')
print(f'HuggingFace tokens: {tokens}')
print(f'HuggingFace token strings: {hf.convert_ids_to_tokens(tokens)}')

# Simulate Java's Unigram tokenizer
p = 'F:/runespeak_cache/models--onnx-community--opus-mt-en-es/snapshots/b5eba94a023a1954c90401b43537f479f962981d/tokenizer.json'
with open(p, encoding='utf-8') as f:
    tok = json.load(f)

model = tok['model']
vocab_list = model['vocab']  # [token, score] entries
vocab = {entry[0]: i for i, entry in enumerate(vocab_list)}
scores = {entry[0]: entry[1] for entry in vocab_list if len(entry) >= 2}
SPACE_MARKER = '\u2581'

# Java's preTokenizerUnigram: add_prefix_space + WhitespaceSplit + Metaspace
def pre_tokenize(text):
    with_prefix = ' ' + text
    raw = with_prefix.split()
    words = []
    for w in raw:
        if w:
            words.append(SPACE_MARKER + w)
    return words

# Viterbi
def viterbi(word):
    n = len(word)
    max_len = 20
    best = [float('-inf')] * (n + 1)
    bt = [0] * (n + 1)
    best[0] = 0
    for end in range(1, n + 1):
        start = max(0, end - max_len)
        for s in range(start, end):
            token = word[s:end]
            score = scores.get(token)
            if score is not None:
                total = best[s] + score
                if total > best[end]:
                    best[end] = total
                    bt[end] = s
    pieces = []
    if best[n] == float('-inf'):
        return [word]
    end = n
    while end > 0:
        start = bt[end]
        pieces.append(word[start:end])
        end = start
    pieces.reverse()
    return pieces

text = 'Hello, how are you?'
words = pre_tokenize(text)
print(f'Pre-tokenized words: {words}')
all_tokens = []
for w in words:
    pieces = viterbi(w)
    print(f'  {repr(w)} -> {pieces}')
    ids = [vocab.get(p, 1) for p in pieces]
    all_tokens.extend(ids)

print(f'Java-simulated token IDs: {all_tokens}')
print(f'Java token strings: {[vocab_list[i][0] if i < len(vocab_list) else "?" for i in all_tokens]}')
