# MonsterQwenDroid

An Android app that runs a **~176B-parameter hybrid MoE** (Qwen3.8-Flash-Next: 512 experts,
top-10 routed, ~6B active per token) **entirely on-device** — no server, no network, CPU only —
straight from a 66 GB mixed-quant GGUF.

**Status: 0.1.0-alpha.** It works and it is usable, but expect rough edges.

Measured on a RedMagic 10S Pro (Snapdragon 8 Elite, 24 GB RAM, 1 TB UFS):

| | |
|---|---:|
| Decode | **4.4 t/s** at full quality |
| Prefill | ~2.9 t/s |
| Weights | 66.4 GB, 2 shards, demand-paged from UFS |
| RAM to fit | 24 GB phone; ~15.5 GB page cache holds the hot expert working set |

## Requirements

- A phone with **~24 GB RAM** and **~70 GB free storage** (the model does not fit in RAM; it is
  memory-mapped and streamed, and the design depends on the page cache holding the hot set).
- The model file, obtained by you from its publisher. **Weights are not bundled** — they carry
  their own license. You need the Qwen3.8-Flash-Next GGUF split into the two shards the app
  expects (`...-00001-of-00002.gguf`, `...-00002-of-00002.gguf`) in the custom mixed quant the app
  documents, plus matching SHA-256 entries in `ModelCatalog.kt`.
- To build: Android SDK, NDK 28.x, CMake 3.31+, and a clean llama.cpp checkout **at revision
  `1b70350`** with `patches/llama.cpp-moedroid.patch` applied (see `patches/README.md`).

## How it works

The app is a thin Kotlin/Compose shell around a **patched llama.cpp**. The engine changes are the
interesting part and they live in `patches/llama.cpp-moedroid.patch`:

| change | effect |
|---|---|
| **BF16 dot product had no ARM SIMD path** (x86-only upstream) — added NEON `vshll` and hardware `vbfdotq_f32` | **+40% decode, +55% prefill** |
| **Cluster pinning** — `--cpu-mask 0x3f --cpu-strict 1` keeps all workers in one L2 cluster | **+26% decode**, quality-neutral |
| **`GGML_OP_MOE_SUM`** — one-pass expert reduction instead of 9 views+adds per layer | −30% graph nodes, bit-identical output |
| Optional expert cap (`BONSAI_MOE_TOPK`) and expert-slice prefetch (`BONSAI_MOE_PREFETCH`) | opt-in, off by default |

Why cluster pinning matters: Snapdragon 8 Elite has **no L3**, so its two Oryon clusters each have
a private 12 MB L2. Left unpinned, the scheduler spreads workers across both, and every graph
node's barrier line and activations pay a coherency round-trip — which makes *small* thread counts
look optimal when they are just hiding the problem. Pinned, 6 workers on the 6-core cluster beat
2 unpinned workers by 26%.

## Honest results, including the negative ones

Several plausible optimisations were measured and **rejected**. They are documented in
`docs/PERFORMANCE.md` because they save other people the experiment:

- **Speculative decoding (MTP head) is 4x SLOWER here, not neutral.** Acceptance is excellent
  (77.5%), but the 2.8 GB sidecar evicts the expert working set past the page-cache cliff: major
  faults rise 5x to ~6,100/token, which fully accounts for the regression. Ships disabled.
- **Uniform expert cap of 6** gives +27% decode for **+11.3% perplexity** (8-chunk). k=8 gives +8%
  for +3.3%. Opt-in behind the SPEED profile only.
- **Removing 30% of the decode graph's nodes changed nothing** — dispatch is not the wall.
- **A 1.52x faster Q2_0 kernel measured zero end-to-end change**, at two different thread counts.
  The expert path is limited by address translation / memory-level parallelism, not ALU.
- **Converting the experts to Q4_0 would be worse, not better**: doubling the expert pool pushes
  the working set past the same page-cache cliff.

## The app

- Load/unload the model, chat UI, thinking-effort control.
- Engine profiles (QUALITY / BALANCED / SPEED / THERMAL_SAVER) with measured settings.
- A harness tab for running reproducible A/B comparisons on the device.

## License

MIT (see `LICENSE`). llama.cpp/ggml are MIT. Model weights are **not** included and are licensed
separately by their publisher.
