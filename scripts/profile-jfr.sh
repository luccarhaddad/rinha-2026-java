#!/usr/bin/env bash
# Profile via Java Flight Recorder (JFR — built into the JVM, no native agent).
# Safe: no signal-based sampling, no native stack unwinding, won't crash the JVM.
#
# Flow: bring up stack with JFR enabled via JAVA_TOOL_OPTIONS → run k6 at a
# sustainable rate → copy profile.jfr out → convert to flamegraph + text dumps.
#
# Usage: bash scripts/profile-jfr.sh           # default 200 rps, 60s record
#        bash scripts/profile-jfr.sh 300 90    # 300 rps, 90s record
set -euo pipefail

# --- knobs ---
RATE=${1:-200}
DURATION=${2:-60}
JFR_DELAY=${JFR_DELAY:-20}   # seconds after JVM start before JFR begins recording
JFR_SETTINGS=${JFR_SETTINGS:-profile}  # "profile" = method-level sampling; "default" = lighter
K6_BUFFER=10

# --- paths ---
WORK="$HOME/rinha-bench"
REPO_DIR="$WORK/rinha-2026-java"
RINHA_DIR="$WORK/rinha-de-backend-2026"
OUT_DIR="$HOME/profile"
AP_VERSION="3.0"
AP_DIR="$HOME/async-profiler-${AP_VERSION}-linux-x64"
AP="$AP_DIR/bin/asprof"
COMPOSE_OVERRIDE="/tmp/docker-compose.jfr.yml"

mkdir -p "$OUT_DIR"
log() { echo "[$(date +%T)] $*" >&2; }

# --- ensure async-profiler is around (only for JFR → flamegraph conversion) ---
if [ ! -x "$AP" ]; then
  log "installing async-profiler (used only as a JFR converter)..."
  cd "$HOME"
  wget -q "https://github.com/async-profiler/async-profiler/releases/download/v${AP_VERSION}/async-profiler-${AP_VERSION}-linux-x64.tar.gz"
  tar xzf "async-profiler-${AP_VERSION}-linux-x64.tar.gz"
  rm -f "async-profiler-${AP_VERSION}-linux-x64.tar.gz"
fi

# --- generate compose override that injects JFR start flag into api-1 only ---
# delay=${JFR_DELAY}s: wait for the warmup phase (~13s) plus a few seconds before recording
# duration=${DURATION}s: how long to record
# settings=profile: deep method profiling
# filename: written inside the container, copied out after
cat > "$COMPOSE_OVERRIDE" <<EOF
services:
  api-1:
    environment:
      JAVA_TOOL_OPTIONS: "-XX:StartFlightRecording=filename=/tmp/profile.jfr,duration=${DURATION}s,settings=${JFR_SETTINGS},delay=${JFR_DELAY}s"
EOF
log "compose override at $COMPOSE_OVERRIDE"

# --- fresh stack with override ---
cd "$REPO_DIR"
log "tearing down + up with JFR enabled on api-1..."
docker compose down >/dev/null 2>&1 || true
docker compose -f docker-compose.yml -f "$COMPOSE_OVERRIDE" up -d

for i in $(seq 1 90); do
  curl -s -m 2 -o /dev/null -w "%{http_code}" http://localhost:9999/ready 2>/dev/null | grep -q 200 && { log "ready after ${i}s"; break; }
  [ "$i" = "90" ] && { log "ERROR: never ready"; exit 1; }
  sleep 1
done

# Confirm JFR options are active
CID=$(docker compose ps -q api-1)
log "verifying JFR is configured on api-1..."
docker logs "$CID" 2>&1 | grep -iE 'flight|recording|JFR' | head -5 || log "(no JFR-related log lines yet — may appear after delay)"

# Sanity
SANITY=$(curl -s -m 5 -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}')
log "sanity: $SANITY"
[[ "$SANITY" == *approved* ]] || { log "ERROR sanity failed"; exit 1; }

# --- inline k6 at sustainable rate ---
K6_TOTAL=$(( JFR_DELAY + DURATION + K6_BUFFER ))
K6_SCRIPT=$(mktemp /tmp/k6jfr.XXXXXX.js)
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
    rate: $RATE, timeUnit: "1s", duration: "${K6_TOTAL}s",
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
    rate: $RATE, total_duration: "${K6_TOTAL}s",
    p50: +x["p(50)"].toFixed(2), p95: +x["p(95)"].toFixed(2), p99: +x["p(99)"].toFixed(2),
    ok: d.metrics.ok ? d.metrics.ok.values.count : 0,
    err: d.metrics.err ? d.metrics.err.values.count : 0
  }) + "\n" };
}
EOF

log "starting k6: $RATE rps × ${K6_TOTAL}s (covers JFR window of ${JFR_DELAY}s + ${DURATION}s + buffer)..."
K6_NO_USAGE_REPORT=true k6 run --quiet "$K6_SCRIPT" > "$OUT_DIR/k6.log" 2>&1 &
K6_PID=$!

# JFR runs autonomously inside the JVM. Wait for delay+duration+couple seconds.
WAIT=$(( JFR_DELAY + DURATION + 5 ))
log "waiting ${WAIT}s for JFR window to complete..."
sleep "$WAIT"

# --- copy profile.jfr out of container ---
log "copying /tmp/profile.jfr out of api-1..."
docker cp "$CID:/tmp/profile.jfr" "$OUT_DIR/profile.jfr" 2>&1 || { log "WARN: jfr file not found yet (recording may still be in progress)"; sleep 5; docker cp "$CID:/tmp/profile.jfr" "$OUT_DIR/profile.jfr" 2>&1 || log "ERROR: still no jfr file"; }
ls -lh "$OUT_DIR/profile.jfr" 2>/dev/null || log "no JFR file present"

# wait for k6 to finish
wait $K6_PID || true

# --- convert JFR with async-profiler ---
if [ -f "$OUT_DIR/profile.jfr" ]; then
  log "converting JFR to flamegraph + collapsed stacks..."
  "$AP" convert -o flamegraph "$OUT_DIR/profile.jfr" "$OUT_DIR/cpu.html" 2>&1 | head -5 || log "flamegraph convert failed (see above)"
  "$AP" convert -o collapsed "$OUT_DIR/profile.jfr" "$OUT_DIR/cpu.collapsed" 2>&1 | head -5 || log "collapsed convert failed"
fi

# --- print artifacts + summaries ---
echo ""
echo "============== CONTAINER STATE =============="
for c in $(cd "$REPO_DIR" && docker compose ps -aq); do
  name=$(docker inspect -f '{{.Name}}' "$c" | sed 's|^/||')
  docker inspect -f "$name: status={{.State.Status}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}" "$c"
done

echo ""
echo "============== K6 SUMMARY =============="
grep -oE '\{"rate":[^}]*\}' "$OUT_DIR/k6.log" | tail -1 || tail -10 "$OUT_DIR/k6.log"

echo ""
echo "============== ARTIFACTS =============="
ls -lh "$OUT_DIR"/

if [ -f "$OUT_DIR/cpu.collapsed" ] && [ -s "$OUT_DIR/cpu.collapsed" ]; then
  echo ""
  echo "============== TOP 25 LEAF METHODS (by samples — where the time actually goes) =============="
  awk '{
    n=$NF; sub(/ *[0-9]+$/, "")
    sub(/.*;/, "")    # last frame
    counts[$0] += n
  } END { for (k in counts) print counts[k], k }' \
    "$OUT_DIR/cpu.collapsed" | sort -nr | head -25

  echo ""
  echo "============== TOP 15 PACKAGES (aggregated) =============="
  awk '{
    n=$NF; sub(/ *[0-9]+$/, "")
    sub(/.*;/, "")
    split($0, parts, ".")
    pkg=parts[1]
    if (parts[2] != "") pkg = pkg "." parts[2]
    if (parts[3] != "") pkg = pkg "." parts[3]
    counts[pkg] += n
  } END { for (k in counts) print counts[k], k }' \
    "$OUT_DIR/cpu.collapsed" | sort -nr | head -15

  echo ""
  echo "============== TOP 15 STACK FAMILIES (entry frame → leaf chain) =============="
  awk '{
    n=$NF; sub(/ *[0-9]+$/, "")
    counts[$0] += n
  } END { for (k in counts) print counts[k], k }' \
    "$OUT_DIR/cpu.collapsed" | sort -nr | head -15 | sed 's|;|\n    → |g'
fi

echo ""
echo "Flame graph SVG: $OUT_DIR/cpu.html"
echo "Raw JFR file:    $OUT_DIR/profile.jfr"
echo "To download to your Mac:"
echo "  gcloud compute scp rinha-bench:$OUT_DIR/cpu.html ~/Downloads/cpu.html --zone=us-central1-a"
