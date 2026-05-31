#!/usr/bin/env bash
# Profile the FAISS stack under the official k6 load and produce a flame graph.
# Captures CPU samples of the Java process inside api-1 during the official
# rinha test ramp (1 → 900 rps over 120s).
#
# Output: ~/profile/cpu.html (SVG flame graph) + ~/profile/results.json + ~/profile/k6.log
#
# Usage:
#   bash scripts/profile-load.sh           # default: profile during full 120s test
#   bash scripts/profile-load.sh 60        # custom: profile last 60s (covers peak)
set -euo pipefail

# --- knobs ---
PROFILE_DURATION=${1:-120}           # seconds to sample
SAMPLE_INTERVAL_MS=${SAMPLE_INTERVAL_MS:-10}   # default: 10ms (low overhead)
DELAY_BEFORE_PROFILE=${DELAY_BEFORE_PROFILE:-0}  # seconds to wait after k6 start before profiling
# Profile event: cpu (needs perf_events; may fail in containers without CAP_SYS_ADMIN)
#                wall (wall-clock sampling; always works; less precise for CPU-bound code)
#                itimer (fallback signal-based; works without perf)
PROFILE_EVENT=${PROFILE_EVENT:-wall}

# --- paths ---
HOME_DIR=${HOME}
WORK="$HOME_DIR/rinha-bench"
REPO_DIR="$WORK/rinha-2026-java"
RINHA_DIR="$WORK/rinha-de-backend-2026"
OUT_DIR="$HOME_DIR/profile"
AP_VERSION="3.0"
AP_DIR="$HOME_DIR/async-profiler-${AP_VERSION}-linux-x64"
AP="$AP_DIR/bin/asprof"

mkdir -p "$OUT_DIR"

log() { echo "[$(date +%T)] $*" >&2; }

# --- 1) async-profiler ---
if [ ! -x "$AP" ]; then
  log "installing async-profiler ${AP_VERSION}..."
  cd "$HOME_DIR"
  wget -q "https://github.com/async-profiler/async-profiler/releases/download/v${AP_VERSION}/async-profiler-${AP_VERSION}-linux-x64.tar.gz"
  tar xzf "async-profiler-${AP_VERSION}-linux-x64.tar.gz"
  rm -f "async-profiler-${AP_VERSION}-linux-x64.tar.gz"
fi
[ -x "$AP" ] || { log "asprof missing at $AP"; exit 1; }

# --- 2) kernel knobs for perf_events (needs sudo, one-time per boot) ---
log "relaxing kernel perf restrictions (sudo)..."
sudo sysctl -wq kernel.perf_event_paranoid=1
sudo sysctl -wq kernel.kptr_restrict=0

# --- 3) fresh stack ---
cd "$REPO_DIR"
log "tearing down any existing stack..."
docker compose down >/dev/null 2>&1 || true
log "starting fresh stack..."
docker compose up -d
log "waiting for /ready..."
for i in $(seq 1 90); do
  code=$(curl -s -m 2 -o /dev/null -w "%{http_code}" http://localhost:9999/ready 2>/dev/null || echo 000)
  if [ "$code" = "200" ]; then log "ready after ${i}s"; break; fi
  if [ "$i" = "90" ]; then log "ERROR: /ready never returned 200"; docker compose logs api-1 | tail -30; exit 1; fi
  sleep 1
done

# sanity request before profiling (also catches obvious breakage)
log "sanity request via :9999..."
RESP=$(curl -s -m 5 -X POST http://localhost:9999/fraud-score \
  -H 'Content-Type: application/json' \
  -d '{"id":"tx-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}')
log "sanity response: $RESP"
[[ "$RESP" == *approved* ]] || { log "ERROR: sanity failed"; exit 1; }

# --- 4) copy profiler INTO api-1 container ---
CID=$(docker compose ps -q api-1)
[ -n "$CID" ] || { log "ERROR: api-1 container id not found"; exit 1; }
log "copying profiler into container $CID..."
docker exec "$CID" sh -c 'rm -rf /profiler' >/dev/null 2>&1 || true
docker cp "$AP_DIR" "$CID:/profiler" >/dev/null

# cleanup trap (always copies artifacts back, even on partial failure)
PROF_LOG="$OUT_DIR/asprof.log"
K6_LOG="$OUT_DIR/k6.log"
FLAME="$OUT_DIR/cpu.html"
cleanup() {
  log "(cleanup) collecting artifacts..."
  docker cp "$CID:/tmp/cpu.html" "$FLAME" 2>/dev/null || log "no /tmp/cpu.html inside container yet"
  cp -f "$RINHA_DIR/test/results.json" "$OUT_DIR/results.json" 2>/dev/null || true
}
trap cleanup EXIT

# --- 5) start k6 + profiler in synchronized fashion ---
# k6 runs full test (~120s). profiler captures DURATION seconds starting
# DELAY_BEFORE_PROFILE seconds after k6 begins.
log "starting k6 ($(date +%T))..."
cd "$RINHA_DIR"
K6_NO_USAGE_REPORT=true k6 run test/test.js > "$K6_LOG" 2>&1 &
K6_PID=$!
log "k6 pid=$K6_PID"

if [ "$DELAY_BEFORE_PROFILE" -gt 0 ]; then
  log "waiting ${DELAY_BEFORE_PROFILE}s before starting profiler..."
  sleep "$DELAY_BEFORE_PROFILE"
fi

log "starting async-profiler: ${PROFILE_DURATION}s @ ${SAMPLE_INTERVAL_MS}ms interval, mode=$PROFILE_EVENT..."
docker exec "$CID" /profiler/bin/asprof \
  -d "$PROFILE_DURATION" \
  -e "$PROFILE_EVENT" \
  -i "${SAMPLE_INTERVAL_MS}ms" \
  -f /tmp/cpu.html \
  1 \
  > "$PROF_LOG" 2>&1 &
PROF_PID=$!
log "asprof pid=$PROF_PID"

# --- 6) wait for both ---
log "waiting for profiler ($PROFILE_DURATION s) to finish..."
wait $PROF_PID || log "(profiler exited non-zero — see $PROF_LOG)"
log "profiler done."
log "waiting for k6 to finish..."
wait $K6_PID || log "(k6 exited non-zero — but that's normal under load)"
log "k6 done."

# --- 7) collect ---
cleanup
trap - EXIT

log "============== ARTIFACTS =============="
ls -lh "$OUT_DIR"
log ""
log "PROFILER OUTPUT:"
cat "$PROF_LOG"
log ""
log "RESULTS.JSON SUMMARY:"
if [ -f "$OUT_DIR/results.json" ]; then
  python3 -c "
import json
with open('$OUT_DIR/results.json') as f: d = json.load(f)
s = d.get('test-results', {}).get('scoring', {})
br = s.get('breakdown', {})
print(f'  p99:           {d[\"test-results\"].get(\"p99\")}')
print(f'  TP/TN/FP/FN:   {br.get(\"true_positive_detections\")}/{br.get(\"true_negative_detections\")}/{br.get(\"false_positive_detections\")}/{br.get(\"false_negative_detections\")}')
print(f'  HTTP errors:   {br.get(\"http_errors\")}')
print(f'  failure_rate:  {s.get(\"failure_rate\")}')
print(f'  p99_score:     {s.get(\"p99_score\",{}).get(\"value\")}')
print(f'  det_score:     {s.get(\"detection_score\",{}).get(\"value\")}')
print(f'  FINAL:         {s.get(\"final_score\")}')
"
else
  log "no results.json found"
fi
log ""
log "FLAME GRAPH:  $FLAME"
log "K6 LOG:       $K6_LOG"
log "ASPROF LOG:   $PROF_LOG"
log ""
log "To download flame graph to your laptop, on your Mac run:"
log "  gcloud compute scp rinha-bench:$FLAME ~/Downloads/cpu.html --zone=us-central1-a"
log "  open ~/Downloads/cpu.html"
