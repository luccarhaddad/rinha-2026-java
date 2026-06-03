# Rinha de Backend 2026 — Go Migration Plan

> From Java/GraalVM (`v11 = 2450`) to Go with ultra-tuning. Target: **5500–5900**, stretch **6000**.

---

## 1. Goal & Target

| Metric | Java v11 baseline | Go v1 target | Go v2 stretch |
|---|---|---|---|
| `final_score` | 2450 | **5500–5800** | **5900–6000** |
| `p99` | 319 ms | **< 5 ms** | **< 1 ms** |
| `detection_score` | 1955 | **2900–3000** | **3000** |
| `p99_score` | 496 | **2400–2700** | **3000** |
| `failure_rate` | 0.15 % | **< 0.1 %** | **0** |
| Image size | 236 MB | **< 60 MB** | **< 50 MB** |
| Startup | ~200 ms | **< 50 ms** | **< 20 ms** |

The cap is `3000 + 3000 = 6000`. Reaching it requires:
- **Perfect detection** (TP=23942, FN=0, FP=0) — exact-or-near-exact NN search.
- **p99 < ~1 ms** — sub-millisecond search + network roundtrip.

Both are achievable, demonstrated by 13 leaderboard entries already at 6000 (Rust/Go/.NET/C++/ASM). Best Java sits at 5806 (rank 30), suggesting Go has marginal edge.

---

## 2. Constraints (rinha 2026)

- **Total budget**: 1 CPU + 350 MB RAM across all services.
- **Topology**: ≥1 load balancer + ≥2 API instances, `bridge` network only.
- **Platform**: `linux/amd64` (Haswell-equivalent VM — AVX2 baseline guaranteed).
- **Image**: must be public on Docker Hub or similar.
- **License**: MIT.
- **No `privileged`, no `host` network mode.**
- **LB**: cannot inspect payload or apply business logic.

Implication for resource split (proposed):
- HAProxy: **0.05 CPU / 15 MB**
- 3× API instance: **0.317 CPU / 110 MB each**

---

## 3. Architecture Overview

```
┌──────────────┐  HTTP :9999
│    k6 load   │ ──────────────┐
└──────────────┘               │
                               ▼
                    ┌────────────────────┐
                    │  HAProxy (mode tcp)│  0.05 CPU / 15 MB
                    └─────────┬──────────┘
            ┌─────────────────┼─────────────────┐
            │                 │                 │
            ▼ UDS             ▼ UDS             ▼ UDS
       ┌─────────┐       ┌─────────┐       ┌─────────┐
       │  api-1  │       │  api-2  │       │  api-3  │
       │  Go     │       │  Go     │       │  Go     │
       │  bin    │       │  bin    │       │  bin    │
       │ 8 MB    │       │ 8 MB    │       │ 8 MB    │
       │ +mmap   │       │ +mmap   │       │ +mmap   │
       │  45 MB  │       │  45 MB  │       │  45 MB  │
       └─────────┘       └─────────┘       └─────────┘
        0.317 CPU         0.317 CPU         0.317 CPU
        110 MB ea         110 MB ea         110 MB ea
```

Each API:
- listens on Unix Domain Socket (shared volume),
- mmaps the same `data.bin` (kernel page cache de-duplicates pages across containers if same inode),
- runs single-threaded (`GOMAXPROCS=1`) with one goroutine for the I/O loop.

---

## 4. Project Layout

```
rinha-2026-go/
├── cmd/
│   ├── server/
│   │   └── main.go              ← entrypoint
│   └── preprocess/              ← optional: replace scripts/preprocess_faiss.py
│       └── main.go
├── internal/
│   ├── server/
│   │   ├── reactor.go           ← I/O loop (fasthttp → custom epoll if needed)
│   │   ├── parser.go            ← zero-alloc JSON parser
│   │   └── response.go          ← 6 pre-computed response byte[]
│   ├── vector/
│   │   ├── vectorize.go         ← 14-feature extractor (port of Vectorizer.java)
│   │   ├── normalize.go         ← Norm constants
│   │   ├── mcc.go               ← MCC → risk lookup table
│   │   └── date.go              ← parse ISO-8601 timestamp without alloc
│   ├── index/
│   │   ├── ivf.go               ← IVF metadata + top-level search orchestration
│   │   ├── scan.go              ← scalar fallback + Go-level loop driver
│   │   ├── scan_amd64.s         ← AVX2 inner loop (Plan 9 syntax)
│   │   ├── scan_amd64.go        ← assembly function declaration (//go:noescape)
│   │   ├── topk.go              ← fixed-size top-5 min-heap
│   │   └── format.go            ← binary index layout (encode/decode)
│   └── data/
│       ├── mmap.go              ← syscall.Mmap wrapper + madvise hints
│       └── load.go              ← load index + labels at startup
├── data/                         ← built artifacts (gitignored, baked in image)
│   ├── data.bin                 ← IVF index in custom Go-friendly layout
│   └── labels.bin               ← 3M × 1 byte (0=legit, 1=fraud)
├── scripts/
│   ├── preprocess.py            ← reuse + minor adjust to emit data.bin
│   └── bench-local.sh           ← k6 wrapper
├── go.mod
├── go.sum
├── Dockerfile                   ← multi-stage: golang → scratch
├── docker-compose.yml
├── haproxy.cfg
├── info.json                    ← rinha submission info
└── README.md
```

---

## 5. Day-by-Day Plan

### Day 1: Scaffold + Index Loader

**Goals**: Compiling Go module, mmap loader, distance function with unit tests.

Tasks:
1. `go mod init github.com/luccarhaddad/rinha-2026-go`
2. Create directory skeleton above.
3. Define binary index format in `internal/index/format.go`:
   ```
   Magic     [4]byte = "RNH1"
   Version   uint32  = 1
   Dims      uint32  = 14
   NList     uint32
   NTotal    uint32
   NProbe    uint32   (default; overridable via env)
   reserved  [12]byte (padding to 32B header)
   Centroids [NList][Dims]float32        // ~230 KB for nlist=4096
   CellOff   [NList+1]uint32             // offsets into Vecs/Labels (prefix sum)
   Vecs      [NTotal][Dims]int8          // SQ8 quantized, cell-clustered order
   ```
   Cell-clustered order = vectors belonging to cell `c` live at `Vecs[CellOff[c]..CellOff[c+1]]`.
   Labels follow the same permutation.

4. Port the Python preprocess to emit this format:
   - Keep `faiss.index_factory("IVF4096,SQ8", METRIC_L2)` for training/clustering.
   - After `index.add()`, extract centroids + per-cell vector lists + labels.
   - Write `data.bin` + `labels.bin` in cell-clustered order.

5. `internal/data/mmap.go`:
   ```go
   func Open(path string) (*Index, error) {
       f, _ := os.Open(path)
       st, _ := f.Stat()
       b, _ := syscall.Mmap(int(f.Fd()), 0, int(st.Size()),
                            syscall.PROT_READ, syscall.MAP_SHARED)
       // advise the kernel: random access on the vector region
       _ = syscall.Madvise(b[vecOffset:], syscall.MADV_RANDOM)
       return parseHeader(b)
   }
   ```
6. Distance: scalar Go impl `l2sqScalar(q, v []int8) int32` with unit tests against Java reference outputs.

**Done criteria**: `go test ./...` green; index file ~45 MB; load time < 50 ms.

### Day 2: HTTP Server + JSON Parser + Vectorizer

**Goals**: HTTP 200 on `/fraud-score` end-to-end with mocked search.

Tasks:
1. Choose HTTP server (see §6.1). Start with **fasthttp**; fall back to custom epoll only if profiler shows it's >300 µs per req.
2. Wire UDS listener:
   ```go
   ln, _ := net.Listen("unix", os.Getenv("SOCK_PATH"))
   _ = os.Chmod(os.Getenv("SOCK_PATH"), 0666)  // HAProxy in another container
   s := &fasthttp.Server{Handler: handle, ReadBufferSize: 8192}
   s.Serve(ln)
   ```
3. Port `JsonParser.java` to `internal/server/parser.go` — same single-pass byte walk, dispatch by first character of key, offset-based string handling.
4. Port `Vectorizer.java` (14 features) + `Norm` constants + `MccRisk` table.
5. Pre-compute 6 response variants (3 fraud_score buckets × {approved, denied}) as `[]byte` constants. Same logic as `FraudHandler.RESPONSES` in Java.
6. Smoke test: `curl POST /fraud-score` with example payload returns HTTP 200 with valid JSON.

**Done criteria**: end-to-end request handled, returns mock score; no allocations in hot path (verified with `go test -bench -benchmem`, target `0 B/op`).

### Day 3: IVF Search Integration

**Goals**: real top-5 NN search; score correctness validated against Java.

Tasks:
1. `internal/index/ivf.go`:
   - Quantize query (`[14]float32 → [14]int8`) using the same SQ8 scheme as the index.
   - Compute distance to each centroid (4096 × 14 floats — scalar is fine here, only ~50 µs).
   - Partial-sort to top-`nprobe` cells.
   - For each cell, scan `Vecs[CellOff[c]..CellOff[c+1]]` with scalar distance, push into top-5 heap.
2. `internal/index/topk.go`:
   ```go
   type Top5 struct {
       dist [5]int32  // sorted ascending or as max-heap
       idx  [5]uint32
       n    int
       max  int32     // current worst (cached)
   }
   func (t *Top5) tryInsert(d int32, i uint32) {
       if t.n < 5 { /* push + sift up */ }
       else if d < t.max { /* replace top + sift down */ }
   }
   ```
   Zero alloc, ~20 instructions per insert in the common case.
3. Label fold:
   ```go
   var frauds uint8
   for k := 0; k < 5; k++ { frauds += labels[t.idx[k]] }
   // map (frauds, edge-case rules) → one of the 6 precomputed responses
   ```
4. Cross-validate: run the Java app and Go app against the same 1000 sample payloads (`api/src/test/resources/example-payloads.json`), assert byte-identical responses for ≥98% (allowing for IVF tie-breaking variance).

**Done criteria**: local k6 against single Go instance → `final_score ≥ 3000`.

### Day 4: SIMD Hot Loop (AVX2 Assembly)

**Goals**: scan loop saturating memory bandwidth; p99 < 5 ms local.

Tasks:
1. Pick implementation path:
   - **A. Hand-written `.s` file** (max control, ~30 lines for our inner loop)
   - **B. `avo` library** (Go DSL that emits `.s` — typesafe, easier maintenance)
   Recommend **B** for the first cut; switch to A if hand-tuning reveals avo missing an instruction.

2. Inner loop spec:
   ```
   func l2sq14_avx2(q *int8, vecs *int8, n int32, out *int32)
   // Compute L2 squared distance from q[0..14] to each of `vecs[i*14..(i+1)*14]`
   // for i in [0, n). Output goes to out[i].
   ```
   AVX2 strategy:
   - Load `q` into a 256-bit register (zero-padded from 14 to 16/32 bytes — choose layout that lets `PMADDUBSW`+`PMADDWD` work).
   - Loop processes K vectors per iteration (K=4 or 8 — measure).
   - Use `VPSUBSB` for signed subtraction, `VPMADDWD` for square-and-accumulate.
   - Reduce per-vector with `VPHADDW` / horizontal add.
   - Store results to `out[i]`.

3. Padding: since 14 ≠ 16, decide:
   - **Pad each stored vector to 16 bytes** (waste 12% memory — 48 MB instead of 42 MB; still fits budget) — simpler SIMD, no scatter.
   - OR keep packed 14 — fastest scan needs careful shuffle handling.
   Recommend **pad to 16** for first version; revisit if memory becomes tight.

4. Validate output bit-identical to scalar Go for 100k random inputs.

5. Benchmark:
   ```bash
   go test -bench=BenchmarkScan -benchmem -cpu=1
   ```
   Target: scan 5800 vectors in < 200 µs (≈ 30 GB/s effective).

**Done criteria**: scan ASM benchmarks at ≥ 3× scalar Go speed; e2e p99 < 5 ms local.

### Day 5: Docker + Compose + Cloud Bench

**Goals**: production image, 3-instance compose, first cloud benchmark.

Tasks:
1. Multi-stage `Dockerfile` (see §6.2). Final image on `scratch`, ~50 MB.
2. `docker-compose.yml` with HAProxy + 3 API instances (see §6.3).
3. `haproxy.cfg` in `mode tcp`, round-robin over 3 UDS backends.
4. Build, push to Docker Hub: `luccarhaddad/rinha2026-go:v1`.
5. Pull on cloud VM (`rinha-bench` GCP) — native amd64 build, no qemu.
6. Run `k6 run rinha-de-backend-2026/test/test.js`, capture results.
7. Submit oficial.

**Done criteria**: cloud bench `final_score ≥ 5000`, oficial submission queued.

### Day 6: PGO + Profile-Driven Tuning

**Goals**: extract another 10–15% from hot path.

Tasks:
1. Build with profiling on:
   ```go
   import _ "net/http/pprof"
   go func() { http.ListenAndServe(":6060", nil) }()
   ```
2. Drive load via k6 for 60 s, capture profile:
   ```bash
   curl http://localhost:6060/debug/pprof/profile?seconds=60 > cpu.pprof
   ```
3. Rebuild with PGO:
   ```bash
   go build -pgo=cpu.pprof -ldflags="-s -w" -o server ./cmd/server
   ```
4. Inspect `pprof` for unexpected hotspots:
   - GC overhead → tune `GOGC`, `MEMORY_LIMIT`.
   - Map lookups in MCC table → switch to flat array indexed by MCC code.
   - Syscall hot → consider io_uring (deferred unless needed).
5. Re-bench, iterate.

**Done criteria**: cloud bench `final_score ≥ 5500`.

### Day 7: Polish + Stretch

**Goals**: push toward 5800–6000.

Candidates by ROI:

1. **Drop HAProxy → custom fd-passing** — saves ~50 µs/req. Implementation: SO_REUSEPORT on multiple listeners, no proxy. Or use `socat` / `nginx mode tcp` to compare.
2. **Drop fasthttp → custom epoll reactor** — saves ~100-300 µs/req in tail. Implementation: ~200 lines using `golang.org/x/sys/unix` directly. Cost: more bug surface; benefit: full control.
3. **Tighten MMAP advise** — `MADV_WILLNEED` on hot cells after coarse step.
4. **Reorder index** so MOST-PROBED cells live in same hugepage (requires usage profile).
5. **Quantize queries to int4 + int8 indexes** — reduces query dispatch cost (negligible win).
6. **Optimize JSON parser** — measure per-char dispatch costs, table-driven if branch predictor misses.

Each item is ~half a day; stop when score plateaus.

**Done criteria**: `final_score ≥ 5800`.

---

## 6. Subsystem Specs

### 6.1 HTTP Server Decision Matrix

| Approach | Per-req overhead | Effort | Recommendation |
|---|---|---|---|
| `net/http` (stdlib) | ~400–800 µs | low | ❌ too slow for top tier |
| **fasthttp** | ~50–150 µs | low | ✅ **start here** |
| Custom epoll + parser | ~10–50 µs | high (2-3 days) | ⚠️ only if fasthttp plateau |

**fasthttp pros**:
- Zero-alloc request/response by design.
- `RequestCtx` reuses byte buffers.
- UDS support via `ListenAndServeUNIX` or via `ServeConn` on a custom listener.
- Battle-tested in high-perf services (Cloudflare workers, etc.).

**fasthttp cons**:
- Different API from stdlib (`ctx.Request.Body()` returns `[]byte` not `io.Reader`).
- Doesn't fit middleware-heavy patterns (irrelevant for us).

### 6.2 Dockerfile

```dockerfile
# syntax=docker/dockerfile:1.7
# Stage 1: build (uses golang image with full toolchain)
FROM golang:1.23-alpine AS build
RUN apk add --no-cache build-base
WORKDIR /src

# Cache go.mod separately for faster rebuilds
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Optional: ship the cpu.pprof from a previous run for PGO.
# If data/cpu.pprof exists, enable PGO; otherwise plain build.
ARG PGO=auto
RUN CGO_ENABLED=0 GOOS=linux GOARCH=amd64 \
    go build \
      -pgo=${PGO} \
      -ldflags="-s -w -buildid=" \
      -trimpath \
      -o /server \
      ./cmd/server && \
    ls -lh /server

# Stage 2: production image — scratch base, no libc, no shell
FROM scratch
COPY --from=build /server /server
COPY data/data.bin   /data/data.bin
COPY data/labels.bin /data/labels.bin

ENV IVF_NPROBE=8 \
    DATA_PATH=/data/data.bin \
    LABELS_PATH=/data/labels.bin
# SOCK_PATH is set per-instance by docker-compose

ENTRYPOINT ["/server"]
```

Notes:
- `scratch` base → no shell, no `ldd`. Static-linked Go binary stands alone.
- `-ldflags="-s -w"` strips debug symbols (~30% smaller binary).
- `-trimpath` removes filesystem paths from binary (privacy + smaller).
- `-buildid=` makes build reproducible.
- Final size: binary ~8–12 MB + data ~45 MB → ~55 MB total.

### 6.3 docker-compose.yml

```yaml
services:
  haproxy:
    image: haproxy:2.9-alpine
    volumes:
      - ./haproxy.cfg:/usr/local/etc/haproxy/haproxy.cfg:ro
      - sockets:/sock
    ports:
      - "9999:9999"
    depends_on: [api-1, api-2, api-3]
    networks: [app]
    deploy:
      resources:
        limits:
          cpus: "0.05"
          memory: "15MB"

  api-1: &api
    image: luccarhaddad/rinha2026-go:v1
    environment:
      SOCK_PATH: /sock/api-1.sock
      IVF_NPROBE: "8"
      GOGC: "200"
      GOMEMLIMIT: "80MiB"
      GOMAXPROCS: "1"
    volumes:
      - sockets:/sock
    networks: [app]
    deploy:
      resources:
        limits:
          cpus: "0.317"
          memory: "110MB"

  api-2:
    <<: *api
    environment:
      SOCK_PATH: /sock/api-2.sock
      IVF_NPROBE: "8"
      GOGC: "200"
      GOMEMLIMIT: "80MiB"
      GOMAXPROCS: "1"

  api-3:
    <<: *api
    environment:
      SOCK_PATH: /sock/api-3.sock
      IVF_NPROBE: "8"
      GOGC: "200"
      GOMEMLIMIT: "80MiB"
      GOMAXPROCS: "1"

networks:
  app:
    driver: bridge

volumes:
  sockets:
```

Budget check:
- 0.05 + 3 × 0.317 = 1.001 (round 0.316 each for exact 1.0)
- 15 + 3 × 110 = 345 (5 MB slack)

### 6.4 haproxy.cfg

```haproxy
global
  daemon
  maxconn 8192
  nbthread 1

defaults
  mode tcp
  timeout connect 1s
  timeout client 30s
  timeout server 30s
  log /dev/null local0

frontend in
  bind :9999
  default_backend apis

backend apis
  balance roundrobin
  option tcp-check
  server api1 /sock/api-1.sock check inter 1s
  server api2 /sock/api-2.sock check inter 1s
  server api3 /sock/api-3.sock check inter 1s
```

Notes:
- `mode tcp` → no HTTP parsing in HAProxy (saves CPU).
- `nbthread 1` → match the single-CPU budget.
- No keepalive needed in TCP mode; client connection map is 1:1 to backend.

If `tcp-check` adds non-trivial cost, drop it; the apps recover via HAProxy's transient backend retries.

### 6.5 Index Binary Format

Layout (little-endian throughout, naturally aligned):

```
Offset  Size   Field
0       4      Magic      = "RNH1"
4       4      Version    = 1
8       4      Dims       = 14
12      4      NList      (e.g. 4096)
16      4      NTotal     (3_000_000)
20      4      NProbeDef  (e.g. 8)
24      8      Reserved
32      Centroids:  NList × Dims × float32     ≈ 230 KB
…       CellOff:    (NList+1) × uint32          ≈ 16 KB
…       Vecs:       NTotal × 16 × int8 (padded) ≈ 48 MB
…       (labels in separate file labels.bin    = NTotal × uint8 ≈ 3 MB)
```

Why **padded to 16 bytes per vector**:
- Native AVX2 load (`VMOVDQU`) on 16-byte boundary, no shuffle.
- Memory waste: 2 bytes × 3M = 6 MB (acceptable in 110 MB budget).
- Each 16-byte slot has 14 active bytes + 2 zero bytes (zeros contribute 0 to L2² distance, harmless).

Why **cell-clustered storage**:
- Sequential memory access within a scanned cell → prefetcher-friendly.
- Single `MADV_SEQUENTIAL` would be too coarse; we use `MADV_RANDOM` at file level but bursts within a cell are still sequential.

### 6.6 SIMD Scan Specification

`l2sq14_avx2(q *int8, vecs *int8, n int32, out *int32)`:

```
Input:
  q     — pointer to query (14 bytes + 2 zero pad, total 16 B)
  vecs  — pointer to N × 16-byte vectors (contiguous)
  n     — number of vectors to compare
  out   — pointer to N × int32 distance output

Pseudo-code:
  ymm0 = load q (16 B replicated to upper lane, or just zero-extend)
  for i in 0..n step 4:
    ymm1 = load vecs[i*16  ..i*16+16]     (vector 0, 16 bytes in lower xmm)
    ymm2 = load vecs[i*16+16..i*16+32]    (vector 1)
    ymm3 = load vecs[i*16+32..i*16+48]    (vector 2)
    ymm4 = load vecs[i*16+48..i*16+64]    (vector 3)

    diff1 = VPSUBSB(q, ymm1)              ; signed sat sub
    diff2 = VPSUBSB(q, ymm2)
    diff3 = VPSUBSB(q, ymm3)
    diff4 = VPSUBSB(q, ymm4)

    ; square via VPMADDUBSW + VPMADDWD pipeline
    sq1   = VPMADDWD(VPMOVSXBW(diff1), VPMOVSXBW(diff1))
    ; ...sq2, sq3, sq4 likewise

    ; horizontal reduce each to single int32
    out[i+0] = phadd(sq1)
    out[i+1] = phadd(sq2)
    out[i+2] = phadd(sq3)
    out[i+3] = phadd(sq4)
```

Expected throughput: ~4 vectors per ~8 cycles = ~1.5 GFLOPs effective, easily saturating memory bandwidth at typical L1/L2 hit rates.

If `avo`: the same logic in Go DSL, ~80 lines. Manually written `.s`: ~50 lines plus header.

### 6.7 Zero-Alloc Hot Path Checklist

The request handler must NOT allocate. Verification:

```go
func BenchmarkHandle(b *testing.B) {
    body := loadExamplePayload()
    ctx := acquireMockCtx(body)
    b.ResetTimer()
    b.ReportAllocs()
    for i := 0; i < b.N; i++ {
        Handle(ctx)
    }
}
// Expected: 0 B/op, 0 allocs/op
```

Common alloc pitfalls in Go:
- **String/byte conversions**: `string(b)` allocates. Use `unsafe.String` (Go 1.20+) when read-only.
- **fmt.Sprintf**: always allocates. Pre-compute responses.
- **map iteration order**: no alloc, but ordering varies — avoid in hot path.
- **append to nil slice**: allocates. Use `make` with capacity up front.
- **defer in hot path**: each defer adds ~50 ns + alloc on some Go versions. Avoid in inner loops.
- **interface boxing**: `var x interface{} = 42` allocates. Stay concrete-typed.

Use `go test -gcflags="-m"` to check escape analysis for hot functions.

---

## 7. Build & Deployment Pipeline

### 7.1 Local build

```bash
make build       # CGO_ENABLED=0 go build ./cmd/server
make test        # go test ./...
make bench       # go test -bench=. -benchmem
make docker      # docker build -t rinha2026-go:dev .
make smoke       # docker compose up + curl test
```

### 7.2 PGO workflow

```bash
# 1. Build initial binary
make build

# 2. Run under load with pprof endpoint enabled
PGO_RUN=1 docker compose up &
sleep 5
curl -s "http://localhost:6060/debug/pprof/profile?seconds=60" > data/cpu.pprof
docker compose down

# 3. Rebuild with PGO enabled
docker build --build-arg PGO=data/cpu.pprof -t rinha2026-go:pgo .

# 4. Verify PGO took effect
go tool pprof -text -nodecount=10 data/cpu.pprof
```

### 7.3 CI (optional, day 6+)

```yaml
# .github/workflows/build.yml
on: [push, pull_request]
jobs:
  test:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with: { go-version: '1.23' }
      - run: go test ./... -race
      - run: go build ./cmd/server
```

### 7.4 Submission

```bash
# After cloud bench validates score
docker tag luccarhaddad/rinha2026-go:v1 luccarhaddad/rinha2026-go:latest
docker push luccarhaddad/rinha2026-go:latest

# Update submission branch
git checkout submission
sed -i 's|rinha2026-java|rinha2026-go|g' docker-compose.yml
git add -A && git commit -m "submission: switch to Go" && git push

# Trigger oficial test
gh issue create --repo zanfranceschi/rinha-de-backend-2026 \
  --title "rinha/test luccarhaddad-go" \
  --body "rinha/test luccarhaddad-go"
```

---

## 8. Testing Strategy

### 8.1 Unit tests (`go test ./...`)
- `internal/server/parser_test.go` — JSON parse correctness vs example-payloads.json.
- `internal/vector/vectorize_test.go` — feature values byte-identical to Java reference.
- `internal/index/topk_test.go` — heap correctness, ordering invariants.
- `internal/index/scan_test.go` — ASM output equals scalar for 100k random inputs.

### 8.2 Differential testing vs Java
For each example payload, run both stacks and assert response equality. Allow tiny numeric jitter on edge cases (IVF tie-breaking).

```bash
go test -tags differential ./internal/...
```

### 8.3 Load testing
```bash
# local
docker compose up -d
k6 run test/test.js
docker compose down -v

# cloud (after push to Docker Hub)
gcloud compute ssh rinha-bench --command="
  cd rinha-2026-go && \
  docker compose down -v && \
  docker compose pull && \
  docker compose up -d && \
  sleep 5 && \
  k6 run ~/rinha-bench/rinha-de-backend-2026/test/test.js
"
```

### 8.4 Memory profiling
```bash
docker stats --no-stream      # peak resident
docker exec api-1 cat /proc/1/status | grep -E 'Vm(Peak|Size|RSS)'
```
Verify each api ≤ 110 MB and HAProxy ≤ 15 MB throughout the run.

---

## 9. Optimization Checklist (sorted by expected ROI)

| # | Optimization | Expected gain | Effort | Day |
|---|---|---|---|---|
| 1 | AVX2 scan loop (avo or .s) | **+1500–2500 pts** | 1 day | 4 |
| 2 | IVF nlist=4096 + nprobe tuning | +400-800 | 0.5 day | 3 |
| 3 | fasthttp + UDS | +300-600 | 0.5 day | 2 |
| 4 | 3 instances + HAProxy | +200-400 | 0.5 day | 5 |
| 5 | Zero-alloc parser + responses | +200-400 | 0.5 day | 2 |
| 6 | mmap with MADV hints | +50-150 | 0.5 hour | 1 |
| 7 | Cell-clustered vector layout | +100-200 | included in day 1 | 1 |
| 8 | PGO recompile | +100-300 | 0.5 day | 6 |
| 9 | GOMAXPROCS=1 + LockOSThread | +50-150 | 1 hour | 2 |
| 10 | Drop fasthttp → custom epoll | +100-200 | 1-2 days | 7 |
| 11 | Tighter MCC lookup (flat array) | +20-50 | 1 hour | profile-driven |
| 12 | Vector ID quantize (uint32 → uint24) | +0-30 | 2 hours | optional |

### Anti-patterns to avoid

- **CGO calls in hot path** — each cgo crossover is ~150 ns minimum. We don't need CGO for our case.
- **Reflection** — `reflect.*` allocates and breaks escape analysis. Stay typed.
- **Channels in inner loop** — channel send/recv is ~100-200 ns. Use direct calls.
- **`time.Now()` per request** — syscall overhead. If needed, use a TSC-driven cache.

---

## 10. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| fasthttp adds >300 µs/req → can't crack 1ms | Medium | High | Day 7 fallback: custom epoll reactor (~200 LoC) |
| ASM scan has bug | Medium | Medium | Differential test vs scalar; keep scalar as build-tagged fallback |
| HAProxy `tcp-check` adds latency | Low | Low | Drop `check`, accept that bad instance lingers |
| Go GC pause during bench | Low | Medium | `GOMEMLIMIT=80MiB`, `GOGC=200`, zero-alloc hot path |
| `scratch` image misses some required file | Low | High | Test docker image standalone before compose |
| mmap fails inside containerized scratch | Low | High | Verify with `strace -f -e mmap` during local bring-up |
| Page cache NOT shared across containers | Medium | Medium | Use named volume mount (single inode); test with /proc/meminfo Cached |
| Recall regression vs FAISS at same nprobe | Medium | Low | Differential test against Java reference for 1000 samples |
| Submission branch out of sync | Low | High | Automate via Makefile target `make submit` |

---

## 11. Fallback Paths

If by Day 5 the score plateaus below 5000:

1. **Brute force instead of IVF** — scan all 3M every query. Memory-bandwidth bound at ~1.5-2 ms. Eliminates IVF training error, perfect recall.
2. **HNSW custom impl** — match top Java entries (rank 30) at ~5800 with hand-rolled HNSW. More code (~500 LoC), higher risk.
3. **Skip Go altogether, optimize v11 Java further** — accept ~3500 ceiling.

If by Day 7 the score plateaus below 5500:

1. **Custom epoll reactor** — biggest remaining latency lever in stdlib path.
2. **io_uring** via `github.com/iceber/iouring-go` — saves a syscall per accept/recv/send. Adds complexity but lat budget.
3. **Drop nginx entirely** — fd-passing LB (SO_REUSEPORT on multiple listeners directly bound to :9999).

---

## 12. Done Criteria

The migration is **done** when ALL of:

- ✅ `final_score ≥ 5500` on cloud VM bench (`rinha-bench` GCP)
- ✅ Oficial submission posted and result published on `rinhadebackend.com.br`
- ✅ Docker image public on Docker Hub at `luccarhaddad/rinha2026-go:vN`
- ✅ Submission branch `submission` updated with new compose
- ✅ `failure_rate < 0.5 %`, no `OOMKilled`, no `http_errors > 0`
- ✅ README updated with build/run/bench instructions
- ✅ Memory budget respected (`docker stats` shows headroom > 10%)

Stretch done:
- ⭐ `final_score ≥ 5800` (rank ~25–30 territory)
- ⭐⭐ `final_score = 6000` (cap)

---

## 13. References

### Top-tier leaderboard entries to study

| Rank | Score | p99 | Language | Repo |
|---|---|---|---|---|
| 1 | 6000 | 0.25 ms | Rust | https://github.com/lucasmontano/rinha-backend-2026-detecta-fraude |
| 5 | 6000 | 0.37 ms | C++ | https://github.com/dalvorsn/cpp-rinha-backend-2026 |
| 8 | 6000 | 0.41 ms | ASM | https://github.com/vinicius-piassa/rinha-backend-2026-asm |
| 9 | 6000 | 0.45 ms | **Go** | https://github.com/vinicius-piassa/rinha-backend-2026-go |
| 17 | 5913 | 1.22 ms | Go | https://github.com/muanlartins/muanlartins-go |
| 23 | 5862 | 1.37 ms | Go | https://github.com/fabianoflorentino/golang |
| 30 | 5806 | 1.56 ms | Java | https://github.com/gb/jvmoonshot-xxvi (best Java) |
| 34 | 5739 | 1.82 ms | Java | https://github.com/arthurd3/fraud-detection |

Top entries to clone and read first: **vinicius-piassa/rinha-backend-2026-go** (proven 6000 in Go), then **muanlartins-go** (rank 17, simpler).

### Tooling

- `avo` — Go DSL for AVX2/AVX512 assembly: https://github.com/mmcloughlin/avo
- `fasthttp` — high-performance HTTP server: https://github.com/valyala/fasthttp
- `pprof` — built-in profiler: `go tool pprof`
- `benchstat` — comparing benchmark runs: https://pkg.go.dev/golang.org/x/perf/cmd/benchstat
- `runtime/trace` — execution tracing for goroutine/GC analysis

### Reading

- Go assembly cheat sheet: https://github.com/teh-cmc/go-internals/blob/master/chapter1_assembly_primer/README.md
- Cloudflare's blog on fasthttp: https://blog.cloudflare.com/go-don-t-collect-my-garbage/
- Profile-guided optimization in Go 1.21+: https://go.dev/doc/pgo

### Carry-over from Java repo

These files in the current Java repo map directly:

| Java source | Go destination |
|---|---|
| `api/src/main/java/com/rinha/fraud/http/JsonParser.java` | `internal/server/parser.go` |
| `api/src/main/java/com/rinha/fraud/vec/Vectorizer.java` | `internal/vector/vectorize.go` |
| `api/src/main/java/com/rinha/fraud/data/MccRisk.java` | `internal/vector/mcc.go` |
| `api/src/main/java/com/rinha/fraud/data/Norm.java` | constants in `internal/vector/normalize.go` |
| `api/src/main/java/com/rinha/fraud/util/DateUtil.java` | `internal/vector/date.go` |
| `api/src/main/java/com/rinha/fraud/http/FraudHandler.java` (RESPONSES) | `internal/server/response.go` |
| `scripts/preprocess_faiss.py` | mostly reused; output format swap to Go-native layout |
| `nginx/nginx.conf` | replaced by `haproxy.cfg` |
| `docker-compose.yml` | new compose, 3 instances + HAProxy |

The semantic content (features, normalization constants, MCC risk table, fraud_score formula, response schema) is identical — only the surrounding runtime changes.

---

**Last updated**: 2026-06-02
