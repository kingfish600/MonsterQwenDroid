# Measured performance and methodology

All numbers from a RedMagic 10S Pro (Snapdragon 8 Elite: 2 Oryon prime + 6 perf, 24 GB LPDDR5X,
1 TB UFS, no L3). CPU only. Prompt "Write one paragraph about the sea.", greedy, 64-token runs
unless noted.

## The measurement discipline that made these numbers trustworthy

Three traps produced *fake* results during development; anyone repeating this should guard
against them:

1. **Run arms mirrored (A/B/B/A), not sequentially.** Sequential runs let the first arm warm the
   page cache and hand the second a free win — this manufactured a fake +70% speedup once.
2. **Verify the clock regime before believing any number.** The vendor thermal daemon caps
   `scaling_max_freq` to ~1/3 of hardware maximum unless the app is foregrounded in the game-boost
   profile; a locked phone additionally parks cores in doze. The same configuration measured
   0.1 t/s and 4.4 t/s depending purely on this.
3. **Gate correctness separately from speed.** For kernel changes, require token-identical output
   (the arithmetic is unchanged). For *requantisation*, token identity is the wrong gate — a 0.38%
   weight perturbation legitimately diverges greedy output after a few tokens.

## Configuration sweep (decode t/s)

| configuration | decode |
|---|---:|
| stock, `-t 6`, no pinning, default poll | ~1.0 |
| + `--poll 100` | 1.6 |
| + BF16 `vbfdot` kernel | 2.3 |
| + `-t 2` (unpinned — looked optimal, was an artifact) | 3.4 |
| **+ `--cpu-mask 0x3f --cpu-strict 1`, `-t 6`** | **4.4** |
| `-t 4` pinned / `-t 5` pinned | 4.0 / 4.1 |
| `-t 2` pinned to prime cluster | 3.7 |

Context size (512-8192) had no measurable effect. `--poll` is worth ~3% once pinned (it was worth
everything unpinned).

## Where a decode token goes (per-op, differenced per-graph profile)

| op | ms/token |
|---|---:|
| `MUL_MAT` (dense, 785 matmuls) | 116 |
| `MUL_MAT_ID` (experts, 144 matmuls) | 93 |
| everything else | 23 |

Dense streams at ~29 GB/s. The expert path manages **~7 GB/s** on the same cores. That gap is the
main open problem; see below.

## Rejected optimisations, with evidence

- **MTP / speculative decoding:** 3.94-4.05 t/s without, **0.95 t/s with** (`draft-n-max 1`, 77.5%
  acceptance, mean 1.77 tokens/pass). `majflt` rises from ~1,130 to ~6,100 per token: the 2.8 GB
  sidecar pushes the expert working set past the page-cache cliff. Not a CPU problem.
- **MOE_SUM (single-pass expert reduction):** `ADD` nodes fell from 584 to 152 per token (~30% of
  the graph), output bit-identical, decode unchanged (3.1 vs 3.1-3.2).
- **Q2_0 kernel unrolling:** 1.52x in a single-threaded microbenchmark (`tools/q2_0_bench.c`),
  **zero** end-to-end change at unpinned `-t 2` and again at pinned `-t 6`. At two or more threads
  the second worker's chain already hides the latency the unroll targets.
- **Q4_0 experts:** doubling the 34 GB expert pool to 68 GB would take the active working set from
  ~12-15 GB (which fits the ~15.5 GB cache — hence 99.8% hit rate) to ~24 GB, past the cliff.
- **Expert-slice prefetch (`POSIX_MADV_WILLNEED`):** implemented and verified (hints fire,
  bit-identical output), neutral — and the fault measurements say why: the expert path is not
  storage-bound (~1 MB/token of major faults against 664 MB of slices).

## Where the remaining headroom is

1. **Expert-path memory behaviour (~7 GB/s vs 29 GB/s).** ~166,000 distinct 4 KB pages are touched
   per token from a 34 GB virtual range, against an L2 TLB covering only ~8 MB at 4 KB pages —
   while all workers currently swarm one expert in small work-stealing chunks. Distributing whole
   experts per thread (they are independent, disjoint outputs) plus page-ahead prefetch is the next
   experiment.
2. **Dense bytes: 3.35 GB/token**, of which BF16 is 1,462 MB (43.7%) and one Q5_K tensor
   (`output.weight`, the 248k-vocab LM head) is 437 MB, read every token. `tools/gguf_retarget.py`
   does targeted tensor requantisation without touching the size-critical expert pool.
3. **Router-aware expert budgeting** (skip only experts whose weight is <10% of the top expert on
   middle layers) as a middle ground between full quality and the uniform cap.

## Prior art

No published result runs a ~176B-class MoE above ~4 t/s fully offline on an unrooted phone.
Comparable mobile work (PowerInfer-2, LLM-in-a-Flash, PhoneLM) targets 7B-47B models at 2-4 t/s
with custom pipelines.
