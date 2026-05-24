#!/usr/bin/env python3
"""
RuneSpeak translation worker.
Loads a HuggingFace model and listens for JSON requests on stdin.
Communication protocol: one JSON object per line.

Request:  {"id": 1, "text": "hello", "src": "eng_Latn", "tgt": "spa_Latn"}
Response: {"id": 1, "text": "hola", "ok": true}
Error:    {"id": 1, "error": "message", "ok": false}
Control:  {"cmd": "shutdown"}
Ping:     {"cmd": "ping"}
Pong:     {"id": 0, "ok": true, "event": "ping", "python": "3.10.0",
           "torch": true, "transformers": true, "sentencepiece": true, "cuda": false}
"""

WORKER_VERSION = "1.0.0"

import json
import sys
import logging
import os

logging.basicConfig(
    level=logging.INFO,
    format="%(levelname)s: %(message)s",
    stream=sys.stderr,
)
log = logging.getLogger("runespeak-worker")


def check_torch_device():
    """Check if CUDA or MPS is available and return device info."""
    try:
        import torch
        if torch.cuda.is_available():
            return "cuda", torch.cuda.get_device_name(0)
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            return "mps", "Apple Silicon"
        return "cpu", None
    except ImportError:
        return None, None


def load_model(model_id: str, cache_dir: str):
    import torch
    from transformers import (
        AutoTokenizer,
        AutoModelForSeq2SeqLM,
    )

    device, device_name = check_torch_device()

    log.info("Loading tokenizer: %s", model_id)
    tokenizer = AutoTokenizer.from_pretrained(
        model_id,
        cache_dir=cache_dir,
        local_files_only=False,
    )

    log.info("Loading model: %s (device: %s)", model_id, device or "cpu")
    torch_dtype = torch.float16 if device == "cuda" else None
    model = AutoModelForSeq2SeqLM.from_pretrained(
        model_id,
        cache_dir=cache_dir,
        local_files_only=False,
        torch_dtype=torch_dtype,
    )

    if device:
        model = model.to(device)

    model.eval()
    log.info("Model loaded successfully — %s", model_id)
    return tokenizer, model, device


def translate(tokenizer, model, text: str, src_lang: str, tgt_lang: str,
              device: str | None = None) -> str:
    tokenizer.src_lang = src_lang
    inputs = tokenizer(text, return_tensors="pt", truncation=True, max_length=512)
    if device:
        inputs = {k: v.to(device) for k, v in inputs.items()}

    forced_bos_token_id = tokenizer.lang_code_to_id.get(tgt_lang)
    if forced_bos_token_id is None:
        forced_bos_token_id = tokenizer.convert_tokens_to_ids(tgt_lang)

    with torch.no_grad():
        generated = model.generate(
            **inputs,
            forced_bos_token_id=forced_bos_token_id,
            max_length=512,
            num_beams=4,
        )
    return tokenizer.decode(generated[0], skip_special_tokens=True)


def handle_ping():
    """Respond with Python version and available dependencies."""
    info = {
        "python": f"{sys.version_info.major}.{sys.version_info.minor}.{sys.version_info.micro}",
        "torch": False,
        "transformers": False,
        "sentencepiece": False,
        "cuda": False,
        "device": "cpu",
    }
    try:
        import torch
        info["torch"] = True
        info["cuda"] = torch.cuda.is_available()
        info["device"] = "cuda" if info["cuda"] else "cpu"
        if hasattr(torch.backends, "mps") and torch.backends.mps.is_available():
            info["device"] = "mps"
    except ImportError:
        pass
    try:
        import transformers
        info["transformers"] = True
    except ImportError:
        pass
    try:
        import sentencepiece
        info["sentencepiece"] = True
    except ImportError:
        pass
    return info


def main():
    model_id = os.environ.get("RUNESPEAK_MODEL", "facebook/nllb-200-distilled-600M")
    cache_dir = os.environ.get("HF_HOME", os.path.expanduser("~/.cache/huggingface"))

    log.info("Starting RuneSpeak worker v%s — model: %s", WORKER_VERSION, model_id)
    log.info("Cache dir: %s", cache_dir)

    print(json.dumps({"ok": True, "event": "starting", "model": model_id,
                       "worker_version": WORKER_VERSION}), flush=True)

    tokenizer = None
    model = None
    device = None

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue

        try:
            req = json.loads(line)
        except json.JSONDecodeError as e:
            log.warning("Invalid JSON: %s", e)
            continue

        req_id = req.get("id", 0)

        if "cmd" in req:
            cmd = req["cmd"]
            if cmd == "shutdown":
                print(json.dumps({"id": req_id, "ok": True, "event": "shutdown"}),
                       flush=True)
                break
            elif cmd == "ping":
                info = handle_ping()
                print(json.dumps({"id": req_id, "ok": True, "event": "ping",
                                   "worker_version": WORKER_VERSION, **info}),
                       flush=True)
                continue
            elif cmd == "load_model":
                model_id = req.get("model", model_id)
                try:
                    tokenizer, model, device = load_model(model_id, cache_dir)
                    print(json.dumps({"id": req_id, "ok": True, "event": "ready",
                                       "model": model_id}),
                           flush=True)
                except Exception as e:
                    log.error("Failed to load model: %s", e)
                    print(json.dumps({"id": req_id, "ok": False, "event": "error",
                                       "error": str(e)}),
                           flush=True)
                continue
            continue

        if model is None:
            log.warning("Model not loaded, loading default: %s", model_id)
            try:
                tokenizer, model, device = load_model(model_id, cache_dir)
                print(json.dumps({"id": 0, "ok": True, "event": "ready",
                                   "model": model_id}), flush=True)
            except Exception as e:
                log.error("Failed to load model: %s", e)
                print(json.dumps({"id": req_id, "ok": False, "error": str(e)}),
                       flush=True)
                continue

        text = req.get("text", "")
        src = req.get("src", "eng_Latn")
        tgt = req.get("tgt", "spa_Latn")

        if not text:
            print(json.dumps({"id": req_id, "ok": False, "error": "empty text"}),
                   flush=True)
            continue

        try:
            result = translate(tokenizer, model, text, src, tgt, device)
            print(json.dumps({"id": req_id, "ok": True, "text": result}),
                   flush=True)
        except Exception as e:
            log.error("Translation failed: %s", e)
            print(json.dumps({"id": req_id, "ok": False, "error": str(e)}),
                   flush=True)

    log.info("Worker shutting down")


if __name__ == "__main__":
    main()
