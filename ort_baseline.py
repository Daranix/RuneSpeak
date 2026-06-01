import onnxruntime as ort
import numpy as np
from transformers import AutoTokenizer

model_path = 'F:/runespeak_cache/models--onnx-community--opus-mt-en-es/snapshots/b5eba94a023a1954c90401b43537f479f962981d/onnx'
tokenizer = AutoTokenizer.from_pretrained(model_path.replace('/onnx',''), use_fast=False)

text = 'Hello, how are you?'
inputs = tokenizer(text, return_tensors='np', padding=True)
input_ids = inputs['input_ids']
attn_mask = inputs['attention_mask']
print(f'Input IDs: {input_ids[0].tolist()}')

encoder = ort.InferenceSession(f'{model_path}/encoder_model.onnx')
decoder = ort.InferenceSession(f'{model_path}/decoder_model.onnx')

enc_out = encoder.run(None, {'input_ids': input_ids, 'attention_mask': attn_mask})[0]
per_pos = np.round(np.sqrt(np.sum(enc_out[0]**2, axis=1))).astype(int).tolist()
print(f'Encoder per-pos norms (ORT): {per_pos}')

# Decode
dec_input = np.array([[65000]], dtype=np.int64)
enc_mask = np.ones((1, input_ids.shape[1]), dtype=np.int64)
for step in range(30):
    outputs = decoder.run(None, {
        'input_ids': dec_input,
        'encoder_hidden_states': enc_out,
        'encoder_attention_mask': enc_mask
    })
    logits = outputs[0]
    # Get last_hidden_state (before LM head) if available
    # ORT decoder returns logits directly
    tok = logits[0, -1, :].argmax()
    msg = f'Step {step}: {tok} (logit={logits[0,-1,tok]:.2f})'
    if tok == 0:
        msg += ' [EOS]'
    print(msg)
    if tok == 0:
        break
    dec_input = np.concatenate([dec_input, np.array([[tok]], dtype=np.int64)], axis=1)

result = tokenizer.decode(dec_input[0][1:].tolist())
print(f'ORT Result: {result}')
