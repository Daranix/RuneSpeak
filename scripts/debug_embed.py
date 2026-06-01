"""Debug embed+pos computation."""
import json, os
import onnx
import onnxruntime as ort
import numpy as np

cache = os.environ.get("RUNESPEAK_CACHE", "F:/runespeak_cache")
snap = "b5eba94a023a1954c90401b43537f479f962981d"
base = f"{cache}/models--onnx-community--opus-mt-en-es/snapshots/{snap}/onnx"

with open(f"{cache}/models--onnx-community--opus-mt-en-es/snapshots/{snap}/config.json") as f:
    config = json.load(f)
print(f"Config sinusoidal_pos_embeddings: {config.get('sinusoidal_pos_embeddings', 'not set')}")
print(f"Config scale_embedding: {config.get('scale_embedding', 'not set')}")
print(f"Config normalize_before: {config.get('normalize_before', 'not set')}")
print(f"Config static_position_embeddings: {config.get('static_position_embeddings', 'not set')}")

m = onnx.load(f"{base}/decoder_model.onnx")
init_map = {}
for init in m.graph.initializer:
    init_map[init.name] = onnx.numpy_helper.to_array(init)

pe = init_map["model.decoder.embed_positions.weight"]
print(f"\nposEmbed shape: {pe.shape}")
print(f"posEmbed row 0 [0:10]: {pe[0,:10].tolist()}")
print(f"posEmbed row 0 [255:265]: {pe[0,255:265].tolist()}")

sw = init_map["model.shared.weight"]
print(f"\nshared.weight shape: {sw.shape}")
print(f"shared.weight[65000][0:10]: {sw[65000,:10].tolist()}")
print(f"shared.weight[65000] norm: {np.linalg.norm(sw[65000]):.4f}")

SCALE = np.sqrt(512)
token_idx = 65000
pos_idx = 0
manual_embed = sw[token_idx] * SCALE
manual_pos = pe[pos_idx]
manual_result = manual_embed + manual_pos
print(f"\nManual embed+pos[0:10]: {manual_result[:10].tolist()}")
print(f"Manual embed+pos norm: {np.linalg.norm(manual_result):.4f}")
print(f"scaled_embed norm={np.linalg.norm(manual_embed):.4f}")
print(f"pos_enc norm={np.linalg.norm(manual_pos):.4f}")
