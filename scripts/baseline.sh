#!/usr/bin/env bash
# Baseline stability test. No profiling. Runs k6 at increasing constant rates
# and reports where the stack starts breaking. Use this to establish what the
# current image/topology can actually sustain before doing anything else.
#
# Usage: bash scripts/baseline.sh
set -euo pipefail

WORK="$HOME/rinha-bench"
REPO_DIR="$WORK/rinha-2026-java"
RINHA_DIR="$WORK/rinha-de-backend-2026"
OUT_DIR="$HOME/baseline"
mkdir -p "$OUT_DIR"

# Rates to sweep (rps) and duration per rate (seconds)
RATES=(100 200 300 400 500)
DUR=30

log() { echo "[$(date +%T)] $*" >&2; }

# Fresh stack
cd "$REPO_DIR"
log "tearing down + fresh up..."
docker compose down >/dev/null 2>&1 || true
docker compose pull >/dev/null
docker compose up -d

for i in $(seq 1 90); do
  curl -s -m 2 -o /dev/null -w "%{http_code}" http://localhost:9999/ready 2>/dev/null | grep -q 200 && { log "ready after ${i}s"; break; }
  [ "$i" = "90" ] && { log "ERROR: never ready"; exit 1; }
  sleep 1
done

# Sanity
SANITY=$(curl -s -m 5 -X POST http://localhost:9999/fraud-score -H 'Content-Type: application/json' \
  -d '{"id":"tx-1","transaction":{"amount":41.12,"installments":2,"requested_at":"2026-03-11T18:45:53Z"},"customer":{"avg_amount":82.24,"tx_count_24h":3,"known_merchants":["MERC-016"]},"merchant":{"id":"MERC-016","mcc":"5411","avg_amount":60.25},"terminal":{"is_online":false,"card_present":true,"km_from_home":29.23},"last_transaction":null}')
log "sanity: $SANITY"
[[ "$SANITY" == *approved* ]] || { log "ERROR sanity failed"; exit 1; }

# Inline k6 script generator
gen_k6() {
  local rate=$1 dur=$2 out=$3
  cat > "$out" <<EOF
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
    rate: $rate, timeUnit: "1s", duration: "${dur}s",
    preAllocatedVUs: 300, maxVUs: 600
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
    rate: $rate,
    p50: +x["p(50)"].toFixed(1),
    p95: +x["p(95)"].toFixed(1),
    p99: +x["p(99)"].toFixed(1),
    ok: d.metrics.ok ? d.metrics.ok.values.count : 0,
    err: d.metrics.err ? d.metrics.err.values.count : 0
  }) + "\n" };
}
EOF
}

container_state() {
  for c in $(cd "$REPO_DIR" && docker compose ps -aq); do
    name=$(docker inspect -f '{{.Name}}' "$c" | sed 's|^/||')
    docker inspect -f "${name}: status={{.State.Status}} oom={{.State.OOMKilled}} restarts={{.RestartCount}}" "$c"
  done
}

echo ""
echo "===================================================================="
echo "BASELINE SWEEP: image=$(docker compose -f $REPO_DIR/docker-compose.yml config | grep -m1 image: | awk '{print $2}')"
echo "===================================================================="
printf "%5s | %7s | %7s | %7s | %5s | %5s | state\n" "rate" "p50" "p95" "p99" "ok" "err"
echo "----------------------------------------------------------------------"

for RATE in "${RATES[@]}"; do
  # ensure stack is fully up (in case previous step crashed something)
  cd "$REPO_DIR"
  docker compose up -d >/dev/null 2>&1
  for i in $(seq 1 30); do
    curl -s -m 2 -o /dev/null -w "%{http_code}" http://localhost:9999/ready 2>/dev/null | grep -q 200 && break
    sleep 1
  done

  K6_SCRIPT=$(mktemp /tmp/k6_$RATE.XXXXXX.js)
  gen_k6 "$RATE" "$DUR" "$K6_SCRIPT"
  RESULT_FILE="$OUT_DIR/rate-$RATE.json"
  K6_NO_USAGE_REPORT=true k6 run --quiet "$K6_SCRIPT" > "$RESULT_FILE" 2>&1 || true

  # parse the JSON line from stdout
  STATS=$(grep -oE '\{"rate":[^}]*\}' "$RESULT_FILE" | tail -1)
  if [ -z "$STATS" ]; then
    printf "%5d | %s\n" "$RATE" "k6 failed; see $RESULT_FILE"
    continue
  fi
  P50=$(echo "$STATS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["p50"])')
  P95=$(echo "$STATS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["p95"])')
  P99=$(echo "$STATS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["p99"])')
  OK=$(echo "$STATS"  | python3 -c 'import json,sys;print(json.load(sys.stdin)["ok"])')
  ERR=$(echo "$STATS" | python3 -c 'import json,sys;print(json.load(sys.stdin)["err"])')

  # Check container state right after each rate
  STATE_LINE=$(container_state | grep -E 'api-[12]' | head -1 | awk -F: '{print $2}')
  printf "%5d | %7s | %7s | %7s | %5s | %5s |%s\n" "$RATE" "$P50" "$P95" "$P99" "$OK" "$ERR" "$STATE_LINE"

  rm -f "$K6_SCRIPT"
done

echo "----------------------------------------------------------------------"
echo ""
echo "FULL CONTAINER STATE NOW:"
container_state
echo ""
echo "results saved to $OUT_DIR/"
