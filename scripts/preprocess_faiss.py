#!/usr/bin/env python3
"""Build-time preprocessor for the FAISS path.

Reads references.json.gz and writes two artifacts that get baked into the
Docker image:
  - data.faiss  : FAISS IVF4096_HNSW32,SQ8 index (METRIC_L2), ~66 MB
  - labels.bin  : N bytes (0 = legit, 1 = fraud), ordinals matching FAISS ids

Why IVF_HNSW: HNSW is used as the coarse quantizer over the 4096 IVF
centroids (NOT the 3M dataset). This makes the "which cells to probe"
lookup O(log 4096) instead of O(4096) linear scan. With finer cells
(4096 vs 1024) we can use lower nprobe (~4 vs 8) for same recall —
the dominant scan cost drops ~7x.

Run:
  pip install faiss-cpu numpy
  python scripts/preprocess_faiss.py <references.json.gz> <out_dir>

The output dir typically is api/src/main/resources/ so the image's COPY picks
them up.
"""
import faiss
import gzip
import json
import numpy as np
import os
import sys
import time


DIMS = 14
NLIST = 4096  # IVF cells; ~3M/4096 ≈ 733 vec/cell. 4x finer than v9's 1024 cells.
HNSW_M = 32   # HNSW coarse quantizer connectivity (operates over the NLIST
              # centroids only — NOT the 3M dataset vectors).
FACTORY = f"IVF{NLIST}_HNSW{HNSW_M},SQ8"


def load(gz_path: str):
    print(f"[1/3] loading {gz_path}...", flush=True)
    t0 = time.time()
    with gzip.open(gz_path) as f:
        data = json.load(f)
    vectors = np.array([d["vector"] for d in data], dtype=np.float32)
    labels = np.array(
        [1 if d["label"] == "fraud" else 0 for d in data], dtype=np.uint8
    )
    print(
        f"  N={len(vectors):,} D={vectors.shape[1]} "
        f"fraud={labels.mean()*100:.2f}%  load={time.time()-t0:.1f}s",
        flush=True,
    )
    if vectors.shape[1] != DIMS:
        sys.exit(f"unexpected dim {vectors.shape[1]} (want {DIMS})")
    return vectors, labels


def build_index(vectors: np.ndarray) -> faiss.Index:
    print(f"[2/3] building {FACTORY} index...", flush=True)
    t0 = time.time()
    index = faiss.index_factory(DIMS, FACTORY, faiss.METRIC_L2)

    # Train on a sample (256*nlist points is the FAISS rule of thumb).
    train_n = min(256 * NLIST, len(vectors))
    train_sample = vectors[
        np.random.RandomState(0).choice(len(vectors), train_n, replace=False)
    ]
    index.train(train_sample)
    print(f"  trained on {train_n:,} samples in {time.time()-t0:.1f}s", flush=True)

    t0 = time.time()
    index.add(vectors)
    print(
        f"  added {index.ntotal:,} vectors in {time.time()-t0:.1f}s",
        flush=True,
    )
    return index


def write_outputs(index: faiss.Index, labels: np.ndarray, out_dir: str):
    print(f"[3/3] writing artifacts to {out_dir}/...", flush=True)
    os.makedirs(out_dir, exist_ok=True)
    bin_path = os.path.join(out_dir, "data.faiss")
    labels_path = os.path.join(out_dir, "labels.bin")
    faiss.write_index(index, bin_path)
    with open(labels_path, "wb") as f:
        f.write(labels.tobytes())
    print(f"  {bin_path}: {os.path.getsize(bin_path)/1e6:.1f} MB")
    print(f"  {labels_path}: {os.path.getsize(labels_path):,} bytes")


def main(argv):
    if len(argv) != 3:
        sys.exit(f"usage: {argv[0]} <references.json.gz> <out_dir>")
    vectors, labels = load(argv[1])
    index = build_index(vectors)
    write_outputs(index, labels, argv[2])
    print("ok.")


if __name__ == "__main__":
    main(sys.argv)
