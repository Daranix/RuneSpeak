#!/usr/bin/env python3
"""Export HuggingFace seq2seq models to ONNX for use with ONNX Runtime Java.

Usage:
  .venv\Scripts\python scripts\export_to_onnx.py Helsinki-NLP/opus-mt-en-es models/opus-mt-en-es-onnx
  .venv\Scripts\python scripts\export_to_onnx.py facebook/nllb-200-distilled-600M models/nllb-200-600M-onnx
"""

import sys
import os
from pathlib import Path

import torch
from transformers import (
    AutoTokenizer,
    AutoConfig,
    AutoModelForSeq2SeqLM,
)


def export_model(model_id: str, output_dir: str):
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)

    print(f"Loading model: {model_id}")
    config = AutoConfig.from_pretrained(model_id)
    tokenizer = AutoTokenizer.from_pretrained(model_id)
    model = AutoModelForSeq2SeqLM.from_pretrained(model_id, torch_dtype=torch.float32)
    model.eval()

    # Save model config and tokenizer files
    config.save_pretrained(str(output_path))
    tokenizer.save_pretrained(str(output_path))
    # Save tokenizer in tokenizers JSON format (for DJL/Rust tokenizers)
    try:
        from tokenizers import Tokenizer
        tok = Tokenizer.from_pretrained(model_id)
        tok.save(str(output_path / "tokenizer.json"))
        print(f"  tokenizer.json saved")
    except Exception as e:
        print(f"  (tokenizer.json not saved: {e})")
    print(f"Config + tokenizer saved to {output_path}")

    d_model = getattr(config, "d_model", getattr(config, "hidden_size", 512))
    print(f"d_model={d_model}")

    # Create dummy inputs for tracing
    dummy_input = tokenizer("Hello world", return_tensors="pt")
    seq_len = dummy_input["input_ids"].shape[1]

    # ── Export encoder ──────────────────────────────────────────────
    print("Exporting encoder...")
    encoder = model.get_encoder()

    class EncoderWrapper(torch.nn.Module):
        def __init__(self, enc):
            super().__init__()
            self.enc = enc

        def forward(self, input_ids, attention_mask):
            out = self.enc(input_ids=input_ids, attention_mask=attention_mask)
            return {"last_hidden_state": out[0]}

    enc_wrapper = EncoderWrapper(encoder)

    import os as _os
    _os.environ["PYTHONIOENCODING"] = "utf-8"
    torch.onnx.export(
        enc_wrapper,
        (dummy_input["input_ids"], dummy_input["attention_mask"]),
        str(output_path / "encoder_model.onnx"),
        input_names=["input_ids", "attention_mask"],
        output_names=["last_hidden_state"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "sequence"},
            "attention_mask": {0: "batch", 1: "sequence"},
            "last_hidden_state": {0: "batch", 1: "sequence"},
        },
        opset_version=18,
    )
    enc_size = os.path.getsize(output_path / "encoder_model.onnx")
    print(f"  → encoder_model.onnx ({enc_size / 1024 / 1024:.1f} MB)")

    # ── Export decoder + lm_head ────────────────────────────────────
    print("Exporting decoder + lm_head...")
    decoder = model.get_decoder()
    lm_head = model.lm_head

    class DecoderWithLMHead(torch.nn.Module):
        def __init__(self, dec, lm):
            super().__init__()
            self.dec = dec
            self.lm = lm

        def forward(self, input_ids, encoder_hidden_states):
            dec_out = self.dec(
                input_ids=input_ids,
                encoder_hidden_states=encoder_hidden_states,
            )
            logits = self.lm(dec_out[0])
            return {"logits": logits}

    dec_wrapper = DecoderWithLMHead(decoder, lm_head)
    dummy_enc_out = torch.randn(1, seq_len, d_model)

    torch.onnx.export(
        dec_wrapper,
        (dummy_input["input_ids"], dummy_enc_out),
        str(output_path / "decoder_model.onnx"),
        input_names=["input_ids", "encoder_hidden_states"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch", 1: "sequence"},
            "encoder_hidden_states": {0: "batch", 1: "sequence"},
            "logits": {0: "batch", 1: "sequence"},
        },
        opset_version=18,
        dynamo=False,
    )
    dec_size = os.path.getsize(output_path / "decoder_model.onnx")
    print(f"  → decoder_model.onnx ({dec_size / 1024 / 1024:.1f} MB)")

    # ── Write model metadata ────────────────────────────────────────
    import json
    meta = {
        "model_id": model_id,
        "model_type": config.model_type,
        "d_model": d_model,
        "vocab_size": config.vocab_size,
        "decoder_start_token_id": getattr(config, "decoder_start_token_id", None),
        "eos_token_id": config.eos_token_id,
        "pad_token_id": config.pad_token_id,
        "max_length": getattr(config, "max_length", 512),
        "architectures": config.architectures,
    }
    with open(output_path / "model_metadata.json", "w") as f:
        json.dump(meta, f, indent=2)

    print(f"\n✓ Export complete: {output_path}")
    print(f"  Files: {sorted(f.name for f in output_path.iterdir())}")

    # ── Quick verification ──────────────────────────────────────────
    print("\nVerifying with ONNX Runtime...")
    try:
        import onnxruntime as ort
        enc_session = ort.InferenceSession(str(output_path / "encoder_model.onnx"))
        dec_session = ort.InferenceSession(str(output_path / "decoder_model.onnx"))

        enc_out = enc_session.run(None, {
            "input_ids": dummy_input["input_ids"].numpy(),
            "attention_mask": dummy_input["attention_mask"].numpy(),
        })
        print(f"  Encoder output shape: {enc_out[0].shape}")

        dec_out = dec_session.run(None, {
            "input_ids": dummy_input["input_ids"].numpy(),
            "encoder_hidden_states": enc_out[0],
        })
        print(f"  Decoder output shape: {dec_out[0].shape}")
        print("✓ ONNX verification passed!")
    except ImportError:
        print("  (skip verification: onnxruntime not installed)")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python scripts/export_to_onnx.py <model_id> [output_dir]")
        print()
        print("Examples:")
        print("  .venv\\Scripts\\python scripts\\export_to_onnx.py Helsinki-NLP/opus-mt-en-es models/opus-mt-onnx")
        print("  .venv\\Scripts\\python scripts\\export_to_onnx.py facebook/nllb-200-distilled-600M models/nllb-onnx")
        sys.exit(1)

    model_id = sys.argv[1]
    output_dir = sys.argv[2] if len(sys.argv) > 2 else f"models/{model_id.replace('/', '-')}-onnx"
    export_model(model_id, output_dir)
