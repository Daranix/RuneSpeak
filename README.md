<p align="center">
  <img src="src/main/resources/com/runespeak/icon.png" alt="RuneSpeak Logo" width="80" />
</p>

<h1 align="center">RuneSpeak</h1>

<p align="center">
  <strong>A <a href="https://runelite.net">RuneLite</a> plugin for local real-time AI translation of Old School RuneScape text using ONNX models.</strong>
</p>

<p align="center">
  Translates in-game text in real time — NPC dialogues, right-click menus, chat messages, overhead text, dynamic widgets, and more — <strong>all running locally on your machine with CPU</strong>.
</p>

<p align="center">
  <a href="#how-it-works">How it works</a> •
  <a href="#supported-languages">Supported languages</a> •
  <a href="#requirements">Requirements</a> •
  <a href="#build">Build</a> •
  <a href="#run">Run</a> •
  <a href="#configuration">Configuration</a> •
  <a href="#limitations">Limitations</a>
</p>

---

## How it works

RuneSpeak downloads `opus-mt` (MarianMT) translation models from HuggingFace and runs them locally with a pure-Java inference engine — no native libraries, no Python, no external runtime. The plugin detects in-game text, sends it to the translation model, and displays the result as an overlay on screen or by replacing the original widget text.

### Architecture

```
Widget/Text detected → Capturer (DialogCapture, MenuCapture, etc.)
                    → LocalTranslator
                        → TranslationCache (LRU cache)
                        → OnnxTranslationEngine
                            → JavaEncoderDecoderRuntime (pure-Java inference)
                                → ONNX model weights
                    → Overlay / Widget replacement
```

The `JavaEncoderDecoderRuntime` is a hand-written encoder-decoder inference loop (MarianMT architecture: 6 layers, 512-dim model, 8 attention heads). It reads ONNX weight files directly and runs inference entirely in pure Java — no native libraries, no ONNX Runtime, no Python, no external dependencies. Key optimizations include:

- **Repetition penalty** (1.2×) to prevent models from looping or copying input
- **LM head weight transpose caching** — the 133 MB `lm_head.weight` matrix is transposed once at load time, avoiding an expensive matrix transpose on every decoder step
- **i-k-j loop ordering** in `Tensor.matmul()` for sequential memory access patterns
- **Dynamic max length** — capped at 200 tokens to prevent runaway generation on corrupted input

### Capturers

| Capturer | What it translates |
|---|---|
| `DialogCapture` | NPC, player, and sprite dialogues |
| `MenuCapture` | Right-click context menu entries |
| `ChatCapture` | Player chat messages |
| `OverlayTextCapture` | System messages, tutorial instructions, notifications |
| `WidgetTextScanner` | Generic widget scanner — auto-discovers any widget with text without manual registration |
| Overhead text | Floating text above NPCs and players |

## Supported languages

28 languages are defined in the enum (Spanish, French, German, Italian, Portuguese, Dutch, Polish, Romanian, Russian, Japanese, Korean, Chinese Simplified/Traditional, Arabic, Hindi, Turkish, Vietnamese, Thai, Indonesian, Swedish, Norwegian, Danish, Finnish, Greek, Czech, Hungarian, Ukrainian).

**ONNX models currently available** (confirmed tested):

| Model | Language pair |
|---|---|
| `onnx-community/opus-mt-en-es` | English → Spanish |
| `onnx-community/opus-mt-en-fr` | English → French |
| `onnx-community/opus-mt-en-de` | English → German |
| `onnx-community/opus-mt-en-nl` | English → Dutch |
| `onnx-community/opus-mt-en-ru` | English → Russian |
| `onnx-community/opus-mt-en-zh` | English → Chinese (Simplified) |
| `onnx-community/opus-mt-en-ar` | English → Arabic |
| `Xenova/opus-mt-en-it` | English → Italian |

Models are downloaded automatically based on the selected language pair. The plugin constructs the model ID from the source-target two-letter codes (`onnx-community/opus-mt-{src}-{tgt}`). If no ONNX variant exists for a pair, the plugin falls back to the default Spanish model.

## Requirements

- RuneLite
- Java 11
- Internet connection for initial model download (~130 MB per model)
- ~200 MB disk space per model (models are cached locally at `.runelite/runespeak/` or a custom path)
- CPU with AVX support recommended for performance

## Build

```bash
./gradlew build
```

## Test

```bash
# Run all translation inference tests (downloads models on first run)
./gradlew test --tests "com.runespeak.translate.JavaEncoderDecoderRuntimeTest"
```

Tests verify translation output for all 8 available models (43 test cases). Models are cached locally — subsequent runs skip downloads.

## Benchmark

```bash
# Run speed benchmarks across all available models
./gradlew runBenchmark -Prunespeak.cache=/path/to/cache
```

## Run

```bash
# With RuneLite in developer mode
./gradlew runRuneLite
```

The plugin appears in the RuneLite side panel with a rune icon, showing model status, translation log, and cache management.

## Configuration

### Translation
- **Source Language** — game language (English by default)
- **Target Language** — language to translate to
- **Model Cache Directory** — custom directory for models and cache (defaults to `.runelite/runespeak/`)

### Display
- **NPC Dialogue** — non-player character dialogues (enabled by default)
- **Right-click Menus** — context menu options (enabled by default)
- **Game Messages** — system messages and tutorial instructions (enabled by default)
- **Chat Messages** — player chat messages (**disabled by default**)
- **Overhead Text** — floating text above NPCs/players (enabled by default)
- **Overlay Text Instructions** — tutorial instructions, XP drops, notifications (enabled by default)
- **Item/NPC/Object Names** — item, NPC, and object name translations (enabled by default)
- **Show Original** — display original text alongside translation (enabled by default)
- **Translation Color** — overlay text color (default: `#00FF00`)

### Cache
- **Unlimited Cache** — prevents automatic cache eviction (enabled by default)
- **Max Cache Size** — number of cache entries, only applies when Unlimited Cache is off (default: 5000)

## Supported engines

RuneSpeak runs translation models entirely in pure Java on CPU — no native code, no external runtimes, no Python.

- **JavaEncoderDecoderRuntime** — a from-scratch encoder-decoder inference engine for MarianMT (`opus-mt`) models. Handles tokenization (BPE), embedding, sinusoidal positional encoding, 6-layer transformer encoder/decoder with multi-head self-attention and cross-attention, feed-forward blocks, layer normalization, autoregressive decoding with repetition penalty, and detokenization — all in pure Java.
- Models are provided via `EncoderDecoderRuntimeProvider` (matches `opus-mt`, `Helsinki-NLP`, or `Xenova` model IDs).
- The interface is extensible — custom `ModelRuntimeProvider` implementations can support other architectures (T5, NLLB, etc.).

**There is no ONNX Runtime dependency.** The engine reads ONNX weight files (which are just serialized tensors) and performs all matrix operations with its own `Tensor` class — hand-written matmul, softmax, layer norm, transpose, and element-wise ops.

> **Note on translation quality**: RuneSpeak uses AI models for translation, not a dictionary. Translations are generated by a machine learning model and may contain errors, awkward phrasing, or mistranslations — especially for slang, abbreviations, or Old School RuneScape-specific terminology. Quality varies by language pair.

## Limitations

- **Model availability**: Only 8 language pairs have ONNX-converted `opus-mt` models available. Other languages defined in the plugin will fall back to the default English→Spanish model until ONNX versions are published.
- **First load** can take several seconds while downloading the ONNX model (~130 MB)
- **Translation latency** — ~100–500ms per sentence on CPU
- **Memory usage** — ~200–800 MB depending on the model
- **Not all game text can be translated** — the plugin respects permanent UI interfaces (inventory, prayers, spells, etc.)
- **Translation quality** varies by language pair and may produce awkward phrasing for OSRS-specific terminology

## Roadmap

The long-term goal is to build a **crowdsourced translation improvement server**:

- Players' generated translations are anonymously submitted to a central server
- Submissions are aggregated and ranked by frequency — the most common translation for a given text wins
- High-quality translations are fed back to improve future model fine-tuning
- Over time, the community converges on accurate, context-aware translations for OSRS-specific terminology

This turns RuneSpeak from a solo tool into a collaborative system where the player base collectively refines the translation quality.

## License

BSD-2-Clause
