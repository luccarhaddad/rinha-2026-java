#!/usr/bin/env bash
# Builds the FAISS C API .so files once and extracts them to vendor/linux-amd64/.
# Re-run this only when bumping the pinned FAISS_TAG in Dockerfile.faiss-builder.
set -euo pipefail

cd "$(dirname "$0")/.."

IMAGE=faiss-builder:v1.14.2
VENDOR=vendor/linux-amd64

echo "==> Building $IMAGE (linux/amd64) — this is slow on ARM hosts under QEMU"
docker build --platform linux/amd64 -f docker/Dockerfile.faiss-builder -t "$IMAGE" .

echo "==> Extracting .so files into $VENDOR/"
mkdir -p "$VENDOR"
TMP=$(docker create --platform linux/amd64 "$IMAGE")
trap 'docker rm -f "$TMP" >/dev/null 2>&1 || true' EXIT

docker cp "$TMP:/build/faiss/build/faiss/libfaiss.so"        "$VENDOR/libfaiss.so"
docker cp "$TMP:/build/faiss/build/faiss/libfaiss_avx2.so"   "$VENDOR/libfaiss_avx2.so"
docker cp "$TMP:/build/faiss/build/c_api/libfaiss_c.so"      "$VENDOR/libfaiss_c.so"
docker cp "$TMP:/build/faiss/LICENSE"                        "$VENDOR/FAISS-LICENSE"

echo "==> Verifying"
ls -lh "$VENDOR/"
echo "--- symbols (sanity: expect faiss_Index_search, faiss_read_index_fname, etc.) ---"
docker run --rm --platform linux/amd64 -v "$PWD/$VENDOR:/v:ro" "$IMAGE" \
    sh -c "nm -D --defined-only /v/libfaiss_c.so | grep -E ' T (faiss_Index_search|faiss_read_index|faiss_index_factory|faiss_IndexIVF_set_nprobe|faiss_Index_free)' | head"
echo "--- runtime deps (apt: libgomp1 libopenblas0 needed in prod image) ---"
docker run --rm --platform linux/amd64 -v "$PWD/$VENDOR:/v:ro" "$IMAGE" \
    sh -c "ldd /v/libfaiss_c.so"

echo "==> Done. .so files vendored at $VENDOR/"
