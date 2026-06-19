#!/usr/bin/env bash
# Local S3 test rig for on-device upload verification (test equipment — see the real-s3-upload
# change, design.md D6). Brings up a MinIO server on the LAN, prints the host to bake into a dev IPA
# and the QR carrying bucket/region/creds, then streams every uploaded object live.
#
# One command:
#   scripts/local-s3.sh
#
# Then: dispatch the `ios` workflow with the printed `upload_host`, install the dev IPA, scan the QR,
# and watch photos land here. Ctrl-C tears everything down (the bucket data is ephemeral).
#
# Overridable via env: LOCAL_IP, S3_PORT (9000), CONSOLE_PORT (9001), BUCKET (snapsync),
# REGION (us-east-1), ACCESS_KEY (snapsync), SECRET_KEY (snapsync123).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

S3_PORT="${S3_PORT:-9000}"
CONSOLE_PORT="${CONSOLE_PORT:-9001}"
BUCKET="${BUCKET:-snapsync}"
REGION="${REGION:-us-east-1}"
ACCESS_KEY="${ACCESS_KEY:-snapsync}"
SECRET_KEY="${SECRET_KEY:-snapsync123}"
CONTAINER="snapsync-minio"
# A podman-managed named volume, not a host bind mount: rootless podman can't always grant the
# container's user write access to a bind-mounted dir (MinIO then dies with "file access denied"),
# whereas a named volume is owned correctly. Removed on exit, so data is still ephemeral.
DATA_VOLUME="${DATA_VOLUME:-snapsync-minio-data}"
# Fully-qualified so podman never falls into interactive short-name registry resolution.
MINIO_IMAGE="${MINIO_IMAGE:-docker.io/minio/minio}"
MC_IMAGE="${MC_IMAGE:-docker.io/minio/mc}"

# LAN IP the phone reaches over WiFi (first global IPv4), overridable.
LOCAL_IP="${LOCAL_IP:-$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1)}"
if [[ -z "${LOCAL_IP}" ]]; then
  echo "could not auto-detect a LAN IP; set LOCAL_IP=<your-ip> and retry" >&2
  exit 1
fi
UPLOAD_HOST="http://${LOCAL_IP}:${S3_PORT}"

# mc reads this alias from the env, so each one-shot mc container is self-configuring (no state).
MC_HOST_local="http://${ACCESS_KEY}:${SECRET_KEY}@127.0.0.1:${S3_PORT}"
export MC_HOST_local

cleanup() {
  echo
  echo "▶ tearing down…"
  podman rm -f "${CONTAINER}" >/dev/null 2>&1 || true
  podman volume rm -f "${DATA_VOLUME}" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "▶ starting MinIO (data is ephemeral: volume ${DATA_VOLUME}, removed on exit)"
podman rm -f "${CONTAINER}" >/dev/null 2>&1 || true
podman volume rm -f "${DATA_VOLUME}" >/dev/null 2>&1 || true   # fresh each run
podman run -d --rm --name "${CONTAINER}" --network host \
  -e "MINIO_ROOT_USER=${ACCESS_KEY}" \
  -e "MINIO_ROOT_PASSWORD=${SECRET_KEY}" \
  -v "${DATA_VOLUME}:/data" \
  "${MINIO_IMAGE}" server /data --console-address ":${CONSOLE_PORT}" >/dev/null

echo "▶ waiting for MinIO to be ready…"
for _ in $(seq 1 30); do
  if podman run --rm --network host -e "MC_HOST_local=${MC_HOST_local}" "${MC_IMAGE}" ready local >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

echo "▶ creating bucket '${BUCKET}'"
podman run --rm --network host -e "MC_HOST_local=${MC_HOST_local}" "${MC_IMAGE}" mb --ignore-existing "local/${BUCKET}" >/dev/null

echo
echo "════════════════════════════════════════════════════════════════════"
echo "  MinIO up:     ${UPLOAD_HOST}   (console http://${LOCAL_IP}:${CONSOLE_PORT})"
echo "  Bucket:       ${BUCKET}   region ${REGION}"
echo
echo "  1) Dispatch the iOS workflow to bake this host into a dev IPA:"
echo "       gh workflow run ios.yml --ref <branch> -f upload_host=${UPLOAD_HOST}"
echo "  2) Install the dev IPA, then scan the QR below (bucket/region/creds):"
echo "════════════════════════════════════════════════════════════════════"
echo

# The authoritative encoder (same codec the app decodes): terminal QR + PNG fallback.
SNAPSYNC_S3_BUCKET="${BUCKET}" \
SNAPSYNC_S3_REGION="${REGION}" \
SNAPSYNC_S3_ACCESS_KEY_ID="${ACCESS_KEY}" \
SNAPSYNC_S3_SECRET_ACCESS_KEY="${SECRET_KEY}" \
SNAPSYNC_QR_OUT="${REPO_ROOT}/build/snapsync-config-qr.png" \
  "${REPO_ROOT}/gradlew" -q --console=plain -p "${REPO_ROOT}" :capability:config:generateConfigQr

echo
echo "▶ watching '${BUCKET}' for uploads (Ctrl-C to stop)…"
# Stream each ObjectCreated event live; the key is the per-resource filename the engine minted.
podman run --rm --network host -e "MC_HOST_local=${MC_HOST_local}" "${MC_IMAGE}" watch --recursive "local/${BUCKET}"
