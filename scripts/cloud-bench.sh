#!/usr/bin/env bash
# Bootstrap + benchmark on a cloud VM (Ubuntu 24.04 amd64).
#
# Usage:
#   curl -sL https://raw.githubusercontent.com/luccarhaddad/rinha-2026-java/main/scripts/cloud-bench.sh | bash
# OR after git clone:
#   bash scripts/cloud-bench.sh [image-tag]
#
# Default image tag: faiss-v3. Override: bash scripts/cloud-bench.sh faiss-v4
set -euo pipefail

IMAGE_TAG="${1:-faiss-v3}"
WORK=${WORK:-$HOME/rinha-bench}
REPO_URL=${REPO_URL:-https://github.com/luccarhaddad/rinha-2026-java.git}
RINHA_REPO=${RINHA_REPO:-https://github.com/zanfranceschi/rinha-de-backend-2026.git}

echo "=== [1/6] system deps ==="
sudo apt-get update -qq
sudo apt-get install -y -qq docker.io docker-compose-v2 git python3-venv curl gnupg ca-certificates
if ! groups "$USER" | grep -q docker; then
  sudo usermod -aG docker "$USER"
  echo "NOTE: added $USER to docker group. Re-ssh or run 'newgrp docker' before continuing."
  exit 1
fi

echo "=== [2/6] k6 ==="
if ! command -v k6 >/dev/null; then
  sudo mkdir -p /etc/apt/keyrings
  curl -fsSL https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /etc/apt/keyrings/k6-archive-keyring.gpg
  echo "deb [signed-by=/etc/apt/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
    | sudo tee /etc/apt/sources.list.d/k6.list >/dev/null
  sudo apt-get update -qq
  sudo apt-get install -y -qq k6
fi
k6 version

echo "=== [3/6] clone our repo + rinha official repo ==="
mkdir -p "$WORK" && cd "$WORK"
[[ -d rinha-2026-java ]] || git clone --depth=1 "$REPO_URL" rinha-2026-java
[[ -d rinha-de-backend-2026 ]] || git clone --depth=1 "$RINHA_REPO"

cd "$WORK/rinha-2026-java"
sed -i "s|luccarhaddad/rinha2026-java:.*|luccarhaddad/rinha2026-java:$IMAGE_TAG|" docker-compose.yml

echo "=== [4/6] generate data.faiss + labels.bin (one-time, ~30s) ==="
if [[ ! -f api/src/main/resources/data.faiss ]]; then
  python3 -m venv /tmp/faiss-venv
  /tmp/faiss-venv/bin/pip install -q --upgrade pip
  /tmp/faiss-venv/bin/pip install -q faiss-cpu numpy
  /tmp/faiss-venv/bin/python scripts/preprocess_faiss.py \
    "$WORK/rinha-de-backend-2026/resources/references.json.gz" \
    api/src/main/resources
fi
ls -lh api/src/main/resources/

echo "=== [5/6] pull image + start stack ==="
docker compose pull
docker compose up -d
echo "waiting for ready..."
for i in $(seq 1 60); do
  if curl -s -m 2 -o /dev/null -w "%{http_code}" http://localhost:9999/ready 2>/dev/null | grep -q 200; then
    echo "ready after ${i}s"
    break
  fi
  sleep 1
done
docker compose ps
echo "--- logs (head) ---"
docker compose logs --tail=8 api-1

echo "=== [6/6] run official k6 test ==="
# IMPORTANT: official test.js writes results to "test/results.json" relative to
# k6's cwd — must run from repo root, NOT from inside test/.
RINHA_DIR="$WORK/rinha-de-backend-2026"
cd "$RINHA_DIR"
echo "starting k6 (this takes ~2min: ramping arrival rate up to 900 rps over 120s)..."
K6_NO_USAGE_REPORT=true k6 run test/test.js > "$WORK/k6.stdout.log" 2>&1 || true
echo
RESULTS="$RINHA_DIR/test/results.json"
echo "=== RESULT ($RESULTS) ==="
if [[ -f "$RESULTS" ]]; then
  python3 -m json.tool < "$RESULTS"
else
  echo "results.json not found at expected path; searching..."
  find "$WORK" -name results.json 2>/dev/null
  echo "--- k6 stdout tail ---"
  tail -40 "$WORK/k6.stdout.log"
fi
echo
echo "=== runtime memory (final snapshot) ==="
docker stats --no-stream --format '{{.Name}} {{.MemUsage}} cpu={{.CPUPerc}}'

cd "$WORK/rinha-2026-java"
echo
echo "==================================================================="
echo "DONE. Image tested: luccarhaddad/rinha2026-java:$IMAGE_TAG"
echo "k6 stdout: $WORK/k6.stdout.log"
echo "scoring:   $TEST_DIR/results.json"
echo "tear down: cd $WORK/rinha-2026-java && docker compose down"
echo "==================================================================="
