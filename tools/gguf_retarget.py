#!/usr/bin/env python3
"""Targeted GGUF requantisation: rewrite specific tensors of one shard.

Why this and not another whole-model quant: any general re-quant widens the
34 GB Q2_0 expert pool, and the measured page-cache fit (99.8% hit rate,
~1130 faults/token) depends on that pool staying where it is. Only a few dense
tensor groups are worth changing, so this rewrites exactly those and copies
every other tensor as raw bytes.

    python3 gguf_retarget.py --in S1.gguf --out S1-new.gguf --recipe bf16-to-q8 --dry-run
    python3 gguf_retarget.py --in S1.gguf --out S1-new.gguf --recipe bf16-to-q8

Recipes:
    bf16-to-q8   BF16 dense tensors -> Q8_0   (gguf-py supports both)
    list         print every tensor, its type and byte size, change nothing

Tensor shape note: gguf-py wants the blocked/contiguous axis LAST, so a GGUF
tensor with dims [2560, 248320] is passed to quantize() as (248320, 2560).
"""
import argparse
import os
import sys
import time
import numpy as np

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                                "upstream-llama", "gguf-py"))
from gguf import GGUFReader, GGUFWriter, GGMLQuantizationType as T  # noqa: E402
from gguf.quants import dequantize, quantize                        # noqa: E402
from gguf.constants import GGML_QUANT_SIZES                          # noqa: E402


def type_bytes_per_weight(t: T) -> float:
    from gguf.constants import GGML_QUANT_SIZES  # noqa: E402
    blk, size = GGML_QUANT_SIZES[t]
    return size / blk


def is_protected(name: str, n_elem: int) -> bool:
    """Tensors that must keep their original precision.

    - lookup tables: token_embd is read one row per token, ple_* are lazy ranges
    - ROUTERS (ffn_gate_inp*): they pick 10 of 512 experts, and experts #10/#11
      often differ by <0.002 in logit. Perturbing them flips *discrete* routing
      choices that compound across 48 layers, for a total saving of ~126 MB
      (~4 ms/token). Never worth it.
    - norms / 1D tensors / anything tiny: rounding error there is
      disproportionate, and the bytes saved are negligible.
    """
    if ("token_embd" in name) or ("ple_" in name):
        return True
    if "gate_inp" in name:                 # the 48 MoE routers
        return True
    if name.endswith("_norm.weight") or name.endswith(".bias"):
        return True
    if n_elem < 1 << 16:                   # < 65k elements: not worth the risk
        return True
    return False


def plan(reader, recipe):
    """Return {tensor_name: (src_type, dst_type)} for the tensors to change."""
    changes = {}
    if recipe == "bf16-to-q8":
        for t in reader.tensors:
            if t.tensor_type != T.BF16:
                continue
            if is_protected(t.name, int(np.prod(t.shape))):
                continue
            changes[t.name] = (t.tensor_type, T.Q8_0)
    return changes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", required=True)
    ap.add_argument("--out", dest="dst", required=True)
    ap.add_argument("--recipe", default="bf16-to-q8")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--limit", type=int, default=0, help="change at most N tensors (testing)")
    args = ap.parse_args()

    t0 = time.time()
    reader = GGUFReader(args.src)
    fs = os.path.getsize(args.src)
    print(f"read {args.src} ({fs/1e9:.2f} GB, {len(reader.tensors)} tensors) in {time.time()-t0:.1f}s")

    if args.recipe == "list":
        for t in reader.tensors:
            n = int(np.prod(t.shape))
            bpw = type_bytes_per_weight(t.tensor_type)
            print(f"  {t.name:44s} {t.tensor_type.name:6s} {bpw:6.3f} B/w  {n*bpw/1e6:9.1f} MB")
        return

    changes = plan(reader, args.recipe)
    if args.limit:
        changes = dict(list(changes.items())[:args.limit])
    tot_before = sum(int(np.prod(t.shape)) * type_bytes_per_weight(t.tensor_type)
                     for t in reader.tensors if t.name in changes)
    tot_after = sum(int(np.prod(t.shape)) * type_bytes_per_weight(changes[t.name][1])
                    for t in reader.tensors if t.name in changes)
    print(f"recipe {args.recipe}: {len(changes)} tensors, "
          f"{tot_before/1e6:.1f} MB -> {tot_after/1e6:.1f} MB "
          f"(-{(tot_before-tot_after)/1e6:.1f} MB/token)")
    if args.dry_run:
        for name in list(changes)[:8]:
            print(f"    would rewrite: {name}")
        print("dry run: nothing written")
        return

    writer = GGUFWriter(args.dst, arch=reader.fields["general.architecture"].contents())
    # copy metadata verbatim, then all tensors (raw bytes except the changed ones)
    for key, field in reader.fields.items():
        if key.startswith("GGUF.") or key.startswith("general.architecture"):
            continue
        try:
            val = field.contents()
        except Exception:
            continue
        writer.add_key_value(key, val, field.types[0] if field.types else None)

    n_changed = 0
    cos_min = 1.0
    for idx, t in enumerate(reader.tensors):
        raw_shape = tuple(int(x) for x in t.shape)
        if t.name in changes:
            src_type, dst_type = changes[t.name]
            # gguf-py convention: blocked axis last
            f32 = dequantize(t.data, src_type).astype(np.float32)
            q = quantize(f32, dst_type)
            # Gate 0: catch orientation/stride/scale bugs per tensor, before any
            # bytes are written. A transposed or mis-strided tensor drops the
            # row cosine to <0.2 immediately; a correct one stays >0.999.
            blk, bsz = GGML_QUANT_SIZES[dst_type]
            expect = (f32.size // blk) * bsz
            if q.nbytes != expect:
                sys.exit(f"FATAL {t.name}: packed {q.nbytes} bytes, expected {expect}")
            back = dequantize(q, dst_type).astype(np.float32)
            a = f32.reshape(-1, f32.shape[-1])
            b = back.reshape(-1, back.shape[-1])
            cs = []
            for r in (0, a.shape[0] - 1):
                d = np.linalg.norm(a[r]) * np.linalg.norm(b[r])
                cs.append(float(np.dot(a[r], b[r]) / d) if d > 0 else 1.0)
            lo = min(cs)
            if lo < 0.999:
                sys.exit(f"FATAL {t.name}: row cosine {lo:.4f} < 0.999 - wrong layout?")
            cos_min = min(cos_min, lo)
            del f32, back, a, b
            writer.add_tensor(t.name, q, raw_shape=raw_shape, raw_dtype=dst_type)
            n_changed += 1
        else:
            writer.add_tensor(t.name, t.data, raw_shape=raw_shape, raw_dtype=t.tensor_type)
        if (idx + 1) % 200 == 0:
            print(f"  {idx+1}/{len(reader.tensors)} tensors, {n_changed} requantised")

    writer.write_header_to_file()
    writer.write_kv_data_to_file()
    writer.write_tensors_to_file()
    writer.close()
    print(f"gate0: worst per-tensor row cosine {cos_min:.5f}")
    print(f"wrote {args.dst}: {os.path.getsize(args.dst)/1e9:.2f} GB, {n_changed} tensors requantised")


if __name__ == "__main__":
    main()
