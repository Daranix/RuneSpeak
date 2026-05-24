"""Create tokenizer.json for MarianMT models for use with DJL HuggingFaceTokenizer."""
import json
import sys
from pathlib import Path

from tokenizers import Tokenizer, models, normalizers, pre_tokenizers, decoders, processors


def create_tokenizer_json(model_dir: str):
    model_path = Path(model_dir)

    with open(model_path / "vocab.json") as f:
        vocab = json.load(f)

    with open(model_path / "config.json") as f:
        cfg = json.load(f)

    vocab_list = list(vocab.items())
    unigram = models.Unigram(vocab_list)
    tokenizer = Tokenizer(unigram)

    unk_id = cfg.get("unk_token_id", 0)
    tokenizer.add_special_tokens(["<unk>", "<s>", "</s>"])
    tokenizer.unk_token = "<unk>"

    tokenizer.normalizer = normalizers.Replace("▁", " ")

    tokenizer.pre_tokenizer = pre_tokenizers.Metaspace(
        replacement="\u2581", prepend_scheme="always", split=True
    )

    tokenizer.decoder = decoders.Metaspace()

    tokenizer.post_processor = processors.TemplateProcessing(
        single="$A", pair="$A $B", special_tokens=[]
    )

    tokenizer.save(str(model_path / "tokenizer.json"))
    print(f"tokenizer.json saved to {model_path / 'tokenizer.json'}")

    output = tokenizer.encode("Hello world")
    print(f"Test: '{tokenizer.decode(output.ids)}'")
    print(f"  ids: {output.ids[:10]}...")
    print(f"  attention_mask: {output.attention_mask[:10]}...")
    print("OK")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python scripts/create_tokenizer_json.py <model_dir>")
        sys.exit(1)
    create_tokenizer_json(sys.argv[1])
