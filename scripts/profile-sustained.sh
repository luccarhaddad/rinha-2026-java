#!/usr/bin/env bash
# Profile the FAISS stack under a SUSTAINABLE constant-rate load.
# Avoids the saturation/OOM cascade of the official ramping test.
# Use this for "where does the CPU go?" investigation.
#
# Output: ~/profile/cpu.html (flame graph) + ~/profile/k6.log
#
# Usage:
#   bash scripts/profile-sustained.sh           # default 300 rps × 60s
#   bash scripts/profile-sustained.sh 200 90    # 200 rps × 90s
set -euo pipefail

# --- knobs ---
RATE=${1:-300}                       # constant req/s (well below saturation)
DURATION=${2:-60}                    # seconds of load
SAMPLE_INTERVAL_MS=${SAMPLE_INTERVAL_MS:-50}   # profiler sample interval (50ms = low overhead)
PROFILE_EVENT=${PROFILE_EVENT:-wall}            # wall | itimer (both work in containers)

# --- paths ---
WORK="$HOME/rinha-bench"
REPO_DIR="$WORK/rinha-2026-java"
RINHA_DIR="$WORK/rinha-de-backend-2026"
OUT_DIR="$HOME/profile"
AP_VERSION="3.0"
AP_DIR="$HOME/async-profiler-${AP_VERSION}-linux-x64"
AP="$AP_DIR/bin/asprof"

mkdir -p "$OUT_DIR"
log() { echo "[$(date +%T)] $*" >&2; }

# --- 1) async-profiler ---
if [ ! -x "$AP" ]; then
  log "installing async-profiler..."
  cd "$HOME"
  wget -q "https://github.com/async-profiler/async-profiler/releases/download/v${AP_VERSION}/async-profiler-${AP_VERSION}-linux-x64.tar.gz"
  tar xzf "async-profiler-${AP_VERSION}-linux-x64.tar.gz"
  rm -f "async-profiler-${AP_VERSION}-linux-x64.tar.gz"
fi

# --- 2) fresh stack ---
cd "$REPO_DIR"
log "tearing down + fresh up..."
docker compose down >/dev/null 2>&1 || true
docker compose up -d
for i in $(seq 1 90); do
  curl -s -m 2 -o /dev/null -w "%{http_code}" http://localhost:9999/ready 2>/dev/null | grep -q 200 && { log "ready after ${i}s"; break; }
  [ "$i" = "90" ] && { log "ERROR: never ready"; exit 1; }
  sleep 1
done

# --- 3) copy profiler ---
CID=$(docker compose ps -q api-1)
docker exec "$CID" sh -c 'rm -rf /profiler' >/dev/null 2>&1 || true
docker cp "$AP_DIR" "$CID:/profiler" >/dev/null
log "profiler copied into api-1"

# --- 4) build inline k6 script for constant rate ---
K6_SCRIPT=$(mktemp /tmp/k6sust.XXXXXX.js)
cat > "$K6_SCRIPT" <<EOF
import http from "k6/http";
import { SharedArray } from "k6/data";
import { Counter } from "k6/metrics";
import exec from "k6/execution";
const DATA = "$RINHA_DIR/test/test-data.json";
const testData = new SharedArray("d", () => JSON.parse(open(DATA)).entries);
const err = new Counter("err"), ok = new Counter("ok");
export const options = {
  summaryTrendStats: ["p(50)","p(95)","p(99)"],
  scenarios: { f: {
    executor: "constant-arrival-rate",
    rate: $RATE, timeUnit: "1s", duration: "${DURATION}s",
    preAllocatedVUs: 200, maxVUs: 400
  } }
};
export default function() {
  const e = testData[exec.scenario.iterationInTest % testData.length];
  const r = http.post("http://localhost:9999/fraud-score", JSON.stringify(e.request),
    { headers: { "Content-Type": "application/json" }, timeout: "2001ms" });
  if (r.status === 200) ok.add(1); else err.add(1);
}
export function handleSummary(d) {
  const x = d.metrics.http_req_duration.values;
  return { stdout: JSON.stringify({
    rate_target: $RATE, duration: "${DURATION}s",
    p50_ms: +x["p(50)"].toFixed(2),
    p95_ms: +x["p(95)"].toFixed(2),
    p99_ms: +x["p(99)"].toFixed(2),
    ok: d.metrics.ok ? d.metrics.ok.values.count : 0,
    err: d.metrics.err ? d.metrics.err.values.count : 0
  }, null, 2) + "\n" };
}
EOF

# --- 5) launch k6 + profiler synchronously ---
PROF_LOG="$OUT_DIR/asprof.log"
K6_LOG="$OUT_DIR/k6.log"
FLAME="$OUT_DIR/cpu.html"

log "launching k6 @ ${RATE} rps for ${DURATION}s..."
K6_NO_USAGE_REPORT=true k6 run --quiet "$K6_SCRIPT" > "$K6_LOG" 2>&1 &
K6_PID=$!

# small warmup window before profiling (avoid JIT-noisy first seconds)
sleep 5

log "launching profiler: ${DURATION}s @ ${SAMPLE_INTERVAL_MS}ms, mode=$PROFILE_EVENT..."
PROFILE_TIME=$(( DURATION - 5 ))
docker exec "$CID" /profiler/bin/asprof \
  -d "$PROFILE_TIME" \
  -e "$PROFILE_EVENT" \
  -i "${SAMPLE_INTERVAL_MS}ms" \
  -f /tmp/cpu.html \
  1 \
  > "$PROF_LOG" 2>&1 &
PROF_PID=$!

# wait both
wait $PROF_PID || log "(profiler exited non-zero — see $PROF_LOG)"
log "profiler done"
wait $K6_PID || true
log "k6 done"

# --- 6) collect artifacts ---
docker cp "$CID:/tmp/cpu.html" "$FLAME" 2>/dev/null || log "no /tmp/cpu.html"

# --- 7) verify containers survived ---
log ""
log "============== CONTAINER STATE =============="
for c in $(cd "$REPO_DIR" && docker compose ps -aq); do
  name=$(docker inspect -f '{{.Name}}' "$c" | sed 's|^/||')
  state=$(docker inspect -f 'oom={{.State.OOMKilled}} exit={{.State.ExitCode}} status={{.State.Status}}' "$c")
  echo "  $name: $state"
done

log ""
log "============== K6 SUMMARY =============="
cat "$K6_LOG"

log ""
log "============== ARTIFACTS =============="
ls -lh "$OUT_DIR"
log ""
log "FLAME GRAPH:  $FLAME"
log "Download to laptop:"
log "  gcloud compute scp rinha-bench:$FLAME ~/Downloads/cpu.html --zone=us-central1-a"
log "  open ~/Downloads/cpu.html"
