# Rinha de Backend 2026 — Java (Fraud Detection / Vector Search)

Fraud scoring backend for the [Rinha de Backend 2026](https://github.com/zanfranceschi/rinha-de-backend-2026).
Each `POST /fraud-score` normalizes a transaction into a 14-dimension vector and
finds the **k=5 nearest** reference vectors among **3,000,000** labeled vectors by
euclidean distance; `fraud_score = frauds/5`, `approved = fraud_score < 0.6`.

## Architecture

```
client → nginx :9999 (round-robin, keepalive upstream)
           ├─ api-1  (Helidon Níma SE, virtual threads)
           └─ api-2  (Helidon Níma SE, virtual threads)
```

Per request, inside an API instance:
`bodyBytes → JsonParser (zero-alloc) → Vectorizer (int8) → KnnSearch (SoA tiled, Vector API) → TopK → byte response`

No database, no hot-path I/O. The dataset is static and `mmap`'d read-only.

### Key decisions

- **int8 quantization.** Every dimension maps `[-1, 1] → [0, 255]` (`q = round((x+1)/2·255)`).
  The `-1` sentinel (dims 5/6 when `last_transaction` is null) lands naturally on `0`.
  Distance is integer `Σ(qa−qb)²` (int32, no `sqrt`). This shrinks the dataset from
  ~168 MB (float32) to **42 MB**, fitting the 160 MB/instance limit, and quarters the
  memory bandwidth that dominates a 3M-vector scan. An end-to-end test confirms int8
  causes **zero decision flips** vs exact float64 on the example set.
- **SoA + tiling, brute force.** Vectors are stored dim-major (Structure-of-Arrays).
  The kernel processes records in tiles of 4096, accumulating squared differences into
  a small int32 block in L1, then merges into the top-5. This gives vertical SIMD
  accumulation (no per-record horizontal reduction) and tiny per-request memory.
  Brute force is exact (matches how the test labels were generated) and has **near-zero
  latency variance** — which the p99-based score rewards.
- **AVX2 target.** The official test host is a Mac Mini Late 2014 (Haswell): AVX2 + FMA3,
  **no AVX-512**. The kernel uses `IntVector.SPECIES_256` (256-bit / 8 lanes) — optimal
  on Haswell.
- **Stack:** JDK 25 + Generational ZGC, `jdk.incubator.vector`, Helidon SE WebServer 4.1.6
  on virtual threads, nginx round-robin, Jib + `eclipse-temurin:25-jre` (linux/amd64).

### The 14 dimensions

See `docs/superpowers/specs/2026-05-26-rinha-2026-fraud-vector-search-design.md` for the
full table and the canonical formulas (dims 5/6 compute `minutes_since_last_tx` from the
timestamp difference, with `-1` when there's no prior transaction).

## Build & run

Requires **JDK 25** (`export JAVA_HOME=$(/usr/libexec/java_home -v 25)` on macOS).

```bash
# Compile + run the full test suite (22 tests)
cd api && mvn clean test

# One-time: preprocess the reference dataset → int8 SoA artifacts (~3s)
mvn -q -DskipTests package
java --add-modules=jdk.incubator.vector -cp target/api.jar \
  com.rinha.fraud.preprocess.PreprocessDataset \
  /path/to/rinha-de-backend-2026/resources/references.json.gz \
  src/main/resources/dataset.i8bin src/main/resources/labels.bin
# Expect: records=3000000 ... bin=42000008B labels=3000000B

# Build the container image (linux/amd64) into the local Docker daemon
mvn -q -DskipTests package jib:dockerBuild        # image: <namespace>/rinha2026-java:latest
# Push to a public registry when ready (needs `docker login`):
# mvn -q -DskipTests package jib:build

# Run the full topology (replace the image namespace in docker-compose.yml first)
docker compose up
curl -i localhost:9999/ready
curl -X POST localhost:9999/fraud-score -H 'Content-Type: application/json' -d @payload.json
```

Resource split (`docker-compose.yml`, total ≤ 1 CPU / 350 MB): nginx `0.10`/`30MB`,
api-1 `0.45`/`160MB`, api-2 `0.45`/`160MB`.

## Benchmarks

```bash
# JMH kernel benchmark (scalar vs vectorized)
cd api && mvn -q -Pjmh -DskipTests clean package
java --add-modules=jdk.incubator.vector -cp target/api.jar org.openjdk.jmh.Main KernelBench -f 1 -wi 5 -i 8

# Load test (with `docker compose up` running)
wrk -t2 -c50 -d30s -s scripts/post-fraud.lua http://localhost:9999/fraud-score
```

### Important: ARM dev host vs amd64 target

Development was done on Apple Silicon (ARM64); the deployment target is `linux/amd64`.
The Vector API kernel uses `SPECIES_256`, which is **native on Haswell AVX2** but
**emulated on ARM's 128-bit NEON**, so local latency numbers are *not* representative.
Measured locally (n=3M, single core, JDK 25):

| Path | ARM64 (Apple Silicon) | amd64 Haswell (target) |
|------|----------------------|------------------------|
| `scoreAllScalar` (JIT auto-vectorized to NEON) | ~10.4 ms/op | TBD |
| `scoreAllVector` (`SPECIES_256`, emulated on ARM) | ~286 ms/op | **TBD — expected the fast path** |

The amd64 numbers (and the SIMD assembly check confirming `vpmaddwd`/AVX2 emission) are a
**pending manual step on a real amd64 host** — fill in this table and the p99 from the
official k6 preview run.

## Status / TODO

- [x] Endpoints, int8 pipeline, exact KNN, container image, tests (22 passing).
- [ ] Measure real p99 + SIMD assembly on amd64; record results above.
- [ ] Repo housekeeping: MIT `LICENSE`, `info.json`, `submission` branch, participant PR.
- [ ] (Optional) Exclude the dataset from the shaded jar layer to avoid ~45 MB image duplication.
- [ ] (Phase 2, only if p99 needs it) IVF coarse filter on top of int8 brute force.
