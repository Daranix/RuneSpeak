"""Compare Java ONNX Runtime output with ONNX Runtime."""
import onnxruntime as ort
import numpy as np
import json, os, tempfile
import onnx
from onnx import helper

os.environ["HF_HUB_DISABLE_SYMLINKS_WARNING"] = "1"

cache = os.environ.get("RUNESPEAK_CACHE", "F:/runespeak_cache")
snap = "b5eba94a023a1954c90401b43537f479f962981d"
base = f"{cache}/models--onnx-community--opus-mt-en-es/snapshots/{snap}/onnx"

from transformers import AutoTokenizer
tok = AutoTokenizer.from_pretrained("onnx-community/opus-mt-en-es", cache_dir=cache)

with open(f"{cache}/models--onnx-community--opus-mt-en-es/snapshots/{snap}/config.json") as f:
    config = json.load(f)

start_id = config.get("decoder_start_token_id", 65000)
eos_id = config.get("eos_token_id", 0)
print(f"start={start_id} eos={eos_id}", flush=True)

enc_session = ort.InferenceSession(f"{base}/encoder_model.onnx")

def create_debug_session(model_path, extra_outputs):
    """Create a session with extra intermediate outputs added to the graph."""
    model = onnx.load(model_path)
    # Collect all tensor names in the graph
    all_tensors = set()
    for node in model.graph.node:
        for o in node.output:
            all_tensors.add(o)
    existing_names = {o.name for o in model.graph.output}
    for tensor_name in extra_outputs:
        if tensor_name not in existing_names and tensor_name in all_tensors:
            vi = helper.make_tensor_value_info(tensor_name, onnx.TensorProto.FLOAT, None)
            model.graph.output.append(vi)
        elif tensor_name not in all_tensors:
            print(f"  [WARN] tensor {tensor_name} not found in graph, skipping", flush=True)
    tmp_dir = tempfile.mkdtemp()
    tmp_path = os.path.join(tmp_dir, "debug.onnx")
    onnx.save(model, tmp_path)
    sess = ort.InferenceSession(tmp_path)
    return sess

# Decoder per-layer output tensor names
layer_outputs = []
for i in range(6):
    layer_outputs.append(f"/model/decoder/layers.{i}/Add_2_output_0")
    layer_outputs.append(f"/model/decoder/layers.{i}/final_layer_norm/Add_1_output_0")
layer_outputs.append("/model/decoder/Add_2_output_0")  # embed+pos input

dec_session = create_debug_session(f"{base}/decoder_model.onnx", layer_outputs)
dec_with_past = create_debug_session(f"{base}/decoder_with_past_model.onnx", layer_outputs)

text = "Hello, how are you?"
inputs = tok(text, return_tensors="np", padding=True, truncation=True)
input_ids = inputs["input_ids"]
attn_mask = np.ones_like(input_ids, dtype=np.int64)
print(f"Input: {text!r}  IDs: {input_ids.tolist()}", flush=True)
print(f"Input tokens: {[tok.decode([t]) for t in input_ids[0]]}", flush=True)

# Run encoder
enc_out = enc_session.run(None, {
    "input_ids": input_ids,
    "attention_mask": attn_mask,
})
encoder_hidden = enc_out[0]
print(f"\nEncoder output shape: {encoder_hidden.shape}", flush=True)
per_pos = " ".join(f"{np.linalg.norm(encoder_hidden[0,p]):.0f}" for p in range(encoder_hidden.shape[1]))
print(f"Encoder per-pos norms: {per_pos}", flush=True)
print(f"Encoder first 5: {encoder_hidden[0,0,:5].tolist()}", flush=True)

# Step 0: use decoder_model.onnx with full input
print(f"\n--- Step 0: full decoder ---", flush=True)
feed = {
    "input_ids": np.array([[start_id]], dtype=np.int64),
    "encoder_hidden_states": encoder_hidden,
    "encoder_attention_mask": attn_mask,
}

# Run with per-layer output extraction
all_output_tensors = layer_outputs + [o.name for o in dec_session.get_outputs()]
results_dict = {}
outputs_list = dec_session.run(all_output_tensors, feed)
for name, arr in zip(all_output_tensors, outputs_list):
    results_dict[name] = arr

logits = results_dict["logits"]

# Print per-layer states
print("=== Step 0 per-layer decoder hidden states (first 5 elements) ===")
# Embed+pos input
emb = results_dict["/model/decoder/Add_2_output_0"][0, 0, :]
norm = float(np.linalg.norm(emb))
print(f"  embed+pos: [{', '.join(f'{v:.9f}' for v in emb[:5].tolist())}]  norm={norm:.1f}")

for i in range(6):
    out = results_dict[f"/model/decoder/layers.{i}/Add_2_output_0"][0, 0, :]
    fn = results_dict[f"/model/decoder/layers.{i}/final_layer_norm/Add_1_output_0"][0, 0, :]
    out_norm = float(np.linalg.norm(out))
    fn_norm = float(np.linalg.norm(fn))
    print(f"  L{i} out: [{', '.join(f'{v:.9f}' for v in out[:5].tolist())}]  norm={out_norm:.1f}")
    print(f"  L{i} fn_ln: [{', '.join(f'{v:.9f}' for v in fn[:5].tolist())}]  norm={fn_norm:.1f}")
print()

# Extract present KV for step 0
present_kv = {}
output_names = [o.name for o in dec_session.get_outputs()]
for i, name in enumerate(output_names):
    if name.startswith("present"):
        present_kv[name.replace("present", "past_key_values")] = results_dict[name]

print(f"Captured {len(present_kv)} KV pairs", flush=True)

next_token = int(np.argmax(logits[0, -1, :]))
top5 = np.argsort(logits[0, -1, :])[-5:][::-1]
top5_vals = [float(logits[0, -1, t]) for t in top5]
tok_str = tok.decode([next_token])
print(f"step=0 tok={next_token} ({tok_str!r}) top5={top5.tolist()} "
      f"top5_val={[f'{v:.2f}' for v in top5_vals]}", flush=True)

output_ids = [start_id, next_token]

# Steps 1+: use decoder_with_past_model.onnx (standard outputs only)
for step in range(1, 30):
    if next_token == eos_id:
        break

    feed = {
        "input_ids": np.array([[next_token]], dtype=np.int64),
        "encoder_attention_mask": attn_mask,
    }
    for name, arr in present_kv.items():
        feed[name] = arr

    standard_outputs = [o.name for o in dec_with_past.get_outputs()]
    outputs_list = dec_with_past.run(standard_outputs, feed)
    results_dict = {}
    for name, arr in zip(standard_outputs, outputs_list):
        results_dict[name] = arr

    logits = results_dict["logits"]

    for name in standard_outputs:
        if name.startswith("present") and ".decoder." in name:
            present_kv[name.replace("present", "past_key_values")] = results_dict[name]

    next_token = int(np.argmax(logits[0, -1, :]))
    top5 = np.argsort(logits[0, -1, :])[-5:][::-1]
    top5_vals = [float(logits[0, -1, t]) for t in top5]
    tok_str = tok.decode([next_token])
    print(f"step={step} tok={next_token} ({tok_str!r}) top5={top5.tolist()} "
          f"top5_val={[f'{v:.2f}' for v in top5_vals]}", flush=True)

    output_ids.append(next_token)

result = tok.decode(output_ids[1:], skip_special_tokens=True)
print(f"\nTranslation: {result!r}", flush=True)
print(f"Output tokens: {output_ids}", flush=True)
