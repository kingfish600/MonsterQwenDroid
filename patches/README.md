# Engine patch

`llama.cpp-moedroid.patch` contains every engine change the app relies on.

**Base revision:** llama.cpp `1b70350` (`1b70350c92563449576bcbbf552994b1d1ed847d`)

Apply from a clean checkout of that revision:

```bash
git clone https://github.com/ggml-org/llama.cpp
cd llama.cpp && git checkout 1b70350c92563449576bcbbf552994b1d1ed847d
git apply /path/to/llama.cpp-moedroid.patch
```

What it changes (8 files, ~620 lines):

| file | change |
|---|---|
| `ggml/src/ggml-cpu/vec.cpp` | BF16 dot product had no ARM SIMD path upstream (AVX512/AVX2/SSE only). Adds a NEON `vshll` path and a hardware `vbfdotq_f32` path. **+40% decode, +55% prefill** measured. |
| `ggml/src/ggml-cpu/ggml-cpu.c` | MOE_SUM dispatch + n_tasks; optional expert-slice prefetch (`BONSAI_MOE_PREFETCH`); optional per-op/per-graph profiler (`BONSAI_PROF`, `BONSAI_PROF_EVERY`). |
| `ggml/include/ggml.h`, `ggml/src/ggml.c`, `ggml/src/ggml-cpu/ops.{h,cpp}` | New `GGML_OP_MOE_SUM`: sums the expert axis in one pass instead of a view per expert plus a chain of adds. Removes ~30% of the decode graph's nodes with bit-identical output. |
| `ggml/src/ggml-cpu/arch/arm/quants.c` | Two-accumulator Q2_0 dot product (`BONSAI_Q20_UNROLL`, default on). 1.52x in a single-threaded microbenchmark; measured neutral end-to-end at >=2 threads. |
| `src/llama-graph.cpp` | Uses `ggml_moe_sum` for expert aggregation; optional expert-count cap `BONSAI_MOE_TOPK`. |

Environment switches (all optional, sane defaults):

| variable | default | effect |
|---|---|---|
| `BONSAI_MOE_TOPK` | 0 (off) | cap routed experts per layer; 8 = +8% decode for +3.3% perplexity, 6 = +27% for +11.3% |
| `BONSAI_Q20_UNROLL` | 1 | 0 restores the stock single-accumulator Q2_0 kernel |
| `BONSAI_MOE_PREFETCH` | 0 | 1/2 issue `POSIX_MADV_WILLNEED` for upcoming expert slices (measured neutral on this device) |
| `BONSAI_PROF` / `BONSAI_PROF_EVERY` | 0 / 8 | per-op profiler; set the interval to 1 to dump every graph so consecutive dumps can be differenced into a per-token budget |

Note: cluster pinning (`--cpu-mask 0x3f --cpu-strict 1`) needed **no** patch - it is a stock
command-line option, and it is worth +26% decode on this SoC because Snapdragon 8 Elite has no L3.
