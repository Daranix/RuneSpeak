"""
Compare Java ONNX runtime output with HuggingFace PyTorch model.
Dumps per-layer hidden states, logits, and attention values.

Usage:
    pip install torch transformers numpy
    python scripts/compare_hf_output.py

Output: prints comparison data to stdout, also saves to compare_output.txt
"""

import torch
import numpy as np
import os, sys, json
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, MarianConfig, MarianMTModel

MODEL_ID = "Helsinki-NLP/opus-mt-en-es"
CACHE_DIR = os.environ.get("RUNESPEAK_CACHE", "F:/runespeak_cache")
MODEL_PATH = os.path.join(CACHE_DIR, "models--Helsinki-NLP--opus-mt-en-es", "snapshots")
TEXT = "Hello, how are you?"

def find_snapshot(base):
    if not os.path.isdir(base):
        return None
    snaps = sorted(os.listdir(base))
    return os.path.join(base, snaps[-1]) if snaps else None

def model_dump():
    device = torch.device("cpu")
    model = MarianMTModel.from_pretrained(MODEL_ID, cache_dir=CACHE_DIR)
    model.eval()
    tokenizer = AutoTokenizer.from_pretrained(MODEL_ID, cache_dir=CACHE_DIR)

    inputs = tokenizer(TEXT, return_tensors="pt", padding=True, truncation=True)
    input_ids = inputs["input_ids"]
    print(f"Input text: {TEXT!r}")
    print(f"Input IDs: {input_ids.tolist()}")
    print(f"Input tokens: {[tokenizer.decode([t]) for t in input_ids[0]]}")
    print()

    # ---- ENCODER ----
    encoder = model.model.encoder
    embed_scale = model.model.embed_scale
    print(f"encoder embed_scale: {embed_scale}")
    print(f"scale_embedding in config: {model.config.scale_embedding}")
    print(f"d_model: {model.config.d_model}")
    print(f"normalize_before: {model.config.normalize_before}")
    print(f"decoder_start_token_id: {model.config.decoder_start_token_id}")
    print(f"eos_token_id: {model.config.eos_token_id}")
    print(f"vocab_size: {model.config.vocab_size}")
    print()

    with torch.no_grad():
        encoder_hidden_states = []
        def hook_fn(name):
            def fn(module, input, output):
                encoder_hidden_states.append((name, output.clone()))
            return fn

        hooks = []
        for i, layer in enumerate(encoder.layers):
            hooks.append(layer.register_forward_hook(hook_fn(f"enc_layer_{i}")))

        encoder_outputs = encoder(input_ids, output_hidden_states=True)
        for h in hooks:
            h.remove()

        print("=== ENCODER ===")
        print(f"encoder output shape: {encoder_outputs[0].shape}")
        per_pos_norms = torch.norm(encoder_outputs[0], dim=-1)
        print(f"encoder per-pos ||output||: {' '.join(f'{v:.0f}' for v in per_pos_norms[0].tolist())}")
        print(f"encoder output first 5: {encoder_outputs[0][0,0,:5].tolist()}")
        print(f"final encoder {encoder.layer_norm.weight.shape} first 5: {encoder.layer_norm.weight[:5].tolist()}")
        print(f"final encoder LN bias first 5: {encoder.layer_norm.bias[:5].tolist() if encoder.layer_norm.bias is not None else 'none'}")
        print()

        # ---- DECODER STEP 0 ----
        decoder = model.model.decoder
        print(f"decoder layer_norm weight first 5: {decoder.layer_norm.weight[:5].tolist()}")
        print(f"decoder layer_norm bias first 5: {decoder.layer_norm.bias[:5].tolist() if decoder.layer_norm.bias is not None else 'none'}")
        print(f"MODEL shared weight row 0 first 5: {model.model.shared.weight[0,:5].tolist()}")
        shared_norms = torch.norm(model.model.shared.weight, dim=-1)
        print(f"shared weight row norms [0,1,2,100,1000]: {[f'{shared_norms[i]:.4f}' for i in [0,1,2,100,1000]]}")
        print(f"lm_head weight tied: {model.lm_head.weight is model.model.shared.weight}")
        print()

        # Build decoder input: [decoder_start_token_id]
        dec_start = model.config.decoder_start_token_id
        decoder_input_ids = torch.tensor([[dec_start]])

        dec_hidden_states = []
        def dec_hook_fn(step_name):
            def fn(module, input, output):
                dec_hidden_states.append((step_name, output[0].clone() if isinstance(output, tuple) else output.clone()))
            return fn

        dec_hooks = []
        for i, layer in enumerate(decoder.layers):
            dec_hooks.append(layer.register_forward_hook(dec_hook_fn(f"dec_layer_{i}")))

        # Register hook for decoder layer_norm
        orig_dec_forward = decoder.forward
        def traced_dec_forward(*args, **kwargs):
            output = orig_dec_forward(*args, **kwargs)
            return output

        outputs = model(decoder_input_ids=decoder_input_ids,
                       encoder_outputs=encoder_outputs,
                       output_hidden_states=True,
                       return_dict=True)
        for h in dec_hooks:
            h.remove()

        print("=== DECODER STEP 0 ===")
        decoder_hidden = outputs.decoder_hidden_states
        if decoder_hidden:
            for i, h in enumerate(decoder_hidden):
                norms = torch.norm(h, dim=-1)
                print(f"  decoder hidden layer {i}: shape={h.shape}, norms: {norms[0,:5].tolist()} ... {norms[0,-5:].tolist()}")
        else:
            print("  No decoder hidden states captured")

        logits = outputs.logits
        print(f"logits shape: {logits.shape}")
        top_tokens = torch.topk(logits[0, -1, :], 5)
        print(f"step 0 top-5 tokens: {top_tokens.indices.tolist()} values: {top_tokens.values.tolist()}")

        # Check final_logits_bias
        print(f"final_logits_bias shape: {model.final_logits_bias.shape}")
        nonzero = (model.final_logits_bias.abs() > 1e-6).sum()
        print(f"final_logits_bias nnz: {nonzero}")
        print(f"final_logits_bias [0]: {model.final_logits_bias[0,0].item():.4f}")
        print(f"final_logits_bias [47]: {model.final_logits_bias[0,47].item():.4f}")
        print(f"final_logits_bias [15]: {model.final_logits_bias[0,15].item():.4f}")
        print()

        # ---- Per-layer per-step tracking ----
        # We need to run greedy decoding step by step with cached attention
        full_output_ids = [dec_start]
        hidden_cache = []

        # Re-run with full decoding
        past_key_values = None
        for step in range(10):
            inp = torch.tensor([full_output_ids])
            with torch.no_grad():
                out = model(
                    input_ids=None,
                    decoder_input_ids=inp if step == 0 else torch.tensor([[full_output_ids[-1]]]),
                    encoder_outputs=encoder_outputs,
                    past_key_values=past_key_values,
                    use_cache=True,
                    output_hidden_states=True,
                    return_dict=True,
                )
            past_key_values = out.past_key_values
            logits = out.logits
            next_token = torch.argmax(logits[0, -1, :]).item()

            # Log intermediate hidden states
            hs = out.decoder_hidden_states
            final_h = hs[-1][0, -1, :]
            final_norm = torch.norm(final_h).item()
            cos_sim = torch.nn.functional.cosine_similarity(
                final_h.unsqueeze(0),
                model.model.shared.weight[next_token].unsqueeze(0)
            ).item()

            top5 = torch.topk(logits[0, -1, :], 5)
            tok_str = tokenizer.decode([next_token])
            print(f"  step={step} tok={next_token} ({tok_str!r}) ||H||={final_norm:.1f} cos(h,emb)={cos_sim:.3f} "
                  f"top5={top5.indices.tolist()} top5_val={[f'{v:.2f}' for v in top5.values.tolist()]}")

            full_output_ids.append(next_token)
            if next_token == model.config.eos_token_id:
                break

        print()
        final_text = tokenizer.decode(full_output_ids[1:], skip_special_tokens=True)
        print(f"Full translation: {final_text!r}")

        # ---- Dump all final LN weights ----
        print()
        print("=== DECODER LN WEIGHTS ===")
        for i, layer in enumerate(decoder.layers):
            for name, param in layer.named_parameters():
                if 'norm' in name or 'layer_norm' in name:
                    w = param.data
                    print(f"  L{i} {name}: shape={w.shape} avg={w.abs().mean():.2f} [{w.min():.2f}-{w.max():.2f}] "
                          f"first5={w[:5].tolist()}")

        print(f"  global layer_norm weight: shape={decoder.layer_norm.weight.shape} "
              f"avg={decoder.layer_norm.weight.abs().mean():.2f} "
              f"[{decoder.layer_norm.weight.min():.2f}-{decoder.layer_norm.weight.max():.2f}] "
              f"first5={decoder.layer_norm.weight[:5].tolist()}")

        # ---- Check how final LN affects hidden states ----
        print()
        print("=== FINAL LN EFFECT ===")
        # Get decoder output without final LN by monkey-patching
        with torch.no_grad():
            # Embedding step
            embed = model.model.decoder.embed_tokens(torch.tensor([[dec_start]]))
            embed = embed * embed_scale
            pos = model.model.decoder.embed_positions(torch.tensor([[dec_start]]))
            h0 = embed + pos

            # Through layers
            h = h0
            for i, layer in enumerate(decoder.layers):
                h = layer(h, encoder_hidden_states=encoder_outputs[0])[0]

            h_before_ln = h.clone()
            h_after_ln = decoder.layer_norm(h)
            ln_effect = (h_after_ln - h_before_ln).abs().mean().item()

            print(f"Before decoder LN: ||h||={torch.norm(h_before_ln).item():.1f} "
                  f"first5={h_before_ln[0,:5].tolist()}")
            print(f"After decoder LN:  ||h||={torch.norm(h_after_ln).item():.1f} "
                  f"first5={h_after_ln[0,:5].tolist()}")
            print(f"LN weight first5: {decoder.layer_norm.weight[:5].tolist()}")
            print(f"LN bias first5:   {decoder.layer_norm.bias[:5].tolist()}")
            print(f"Mean abs diff: {ln_effect:.4f}")

            # Compute logits with and without LN
            lm_weight = model.lm_head.weight  # = shared.weight
            bias = model.final_logits_bias

            logits_with_ln = (h_after_ln @ lm_weight.T) + bias
            logits_without_ln = (h_before_ln @ lm_weight.T) + bias

            top5_with = torch.topk(logits_with_ln[0], 5)
            top5_without = torch.topk(logits_without_ln[0], 5)

            print(f"WITH LN:    top5={top5_with.indices.tolist()} vals={[f'{v:.2f}' for v in top5_with.values.tolist()]}")
            print(f"WITHOUT LN: top5={top5_without.indices.tolist()} vals={[f'{v:.2f}' for v in top5_without.values.tolist()]}")

if __name__ == "__main__":
    model_dump()
