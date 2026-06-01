"""Verify expected translations for test cases"""
import onnxruntime as ort
import json

BASE = 'F:/runespeak_cache/models--onnx-community--opus-mt-en-es/snapshots/b5eba94a023a1954c90401b43537f479f962981d/onnx/'

# Load tokenizer manually to get vocab
import sys
sys.path.insert(0, 'G:/repositories/runespeak/runespeak-plugin')
from tokenizers import Tokenizer

tok = Tokenizer.from_file(BASE.replace('/onnx/', '/tokenizer.json').replace('\\', '/'))
tok.enable_truncation(max_length=30)
tok.enable_padding(pad_id=0, pad_token='</s>', length=None)

enc_sess = ort.InferenceSession(BASE + 'encoder_model.onnx')
dec_sess = ort.InferenceSession(BASE + 'decoder_model.onnx')

def translate(text):
    # Tokenize with HuggingFace-style: add_prefix_space=True
    text = ' ' + text if not text.startswith(' ') else text
    encoded = tok.encode(text)
    ids = encoded.ids
    # Add EOS
    input_ids = ids + [0]
    input_arr = [input_ids]
    
    enc_out = enc_sess.run(None, {
        enc_sess.get_inputs()[0].name: input_arr,
        enc_sess.get_inputs()[1].name: [[1]*len(input_ids)]
    })[0]
    
    # Autoregressive decode
    dec_input = [[65000]]  # decoder_start_token_id
    output_ids = []
    for step in range(30):
        logits, = dec_sess.run(['logits'], {
            'input_ids': dec_input,
            'encoder_attention_mask': [[1]*len(input_ids)],
            'encoder_hidden_states': enc_out,
        })
        next_id = logits[0, -1].argmax()
        if next_id == 0:  # EOS
            break
        output_ids.append(next_id)
        dec_input = [[65000] + output_ids]
    
    return tok.decode(output_ids, skip_special_tokens=True)

test_cases = [
    "Hello, how are you?",
    "Good morning",
    "Thank you very much",
    "Where is the bathroom?",
    "I love programming",
    "The weather is nice today",
    "One, two, three",
    "My name is John",
    "How much does this cost?",
    "See you tomorrow",
]

for text in test_cases:
    result = translate(text)
    print(f"  \"{text}\" -> \"{result}\"")
    import time
    time.sleep(0.1)
