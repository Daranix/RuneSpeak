import json
import sys

sys.stdout.reconfigure(encoding='utf-8')

p = 'F:/runespeak_cache/models--onnx-community--opus-mt-en-es/snapshots/b5eba94a023a1954c90401b43537f479f962981d/tokenizer.json'
with open(p, encoding='utf-8') as f:
    tok = json.load(f)

model = tok['model']
print('Type:', model['type'])
vocab = model['vocab']
print(f'Vocab entries: {len(vocab)}')
entries = [(repr(tok), scr) for tok, scr in vocab[:5]]
print(f'First 5 entries: {entries}')

for token, score in vocab[:50]:
    r = repr(token)
    if '\\u2581' in r or '\u2581' in token:
        print(f'Found prefix: {r} score={score}')
        break

s = [v[1] for v in vocab]
print(f'Scores: min={min(s):.6f} max={max(s):.4f}')
print('pre_tokenizer:', json.dumps(tok.get('pre_tokenizer', {}), indent=2))
norm = tok.get('normalizer', {})
print('normalizer type:', norm.get('type', 'none'))
