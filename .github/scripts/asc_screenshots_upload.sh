#!/usr/bin/env bash
# Upload the composited listing images to App Store Connect — main-only (capability
# `ios-appstore-metadata`). The committed raws + headline file are the source of truth; `--replace` makes
# the live set exactly what they compose to, so a screenshot added by hand in the console is removed.
#
# `upload`, NOT `apply`: `asc screenshots apply` is [experimental] and is the tail of a human review flow
# (`review-generate` -> `review-open`, which opens a browser -> `review-approve`), which is useless
# headless. `upload` is the stable App Store command.
#
# `--replace` and `--skip-existing` are MUTUALLY EXCLUSIVE by construction: replace empties the set, so
# nothing survives for a checksum to dedupe against. Declarative wins; the caller's path gate (only run
# when the inputs changed) is what keeps this from re-uploading on every merge.
#
# The state gate is OURS, not the tool's — exactly as `asc_metadata_apply.sh` argues: `asc` takes an
# explicit --version and its behaviour on an in-review version is undefined (upstream epic #587). That
# matters MORE here than for text: replacing a screenshot set is destructive.
#
# Env: ASC (path to the asc binary), ASC_APP_ID, plus asc's auth vars (ASC_KEY_ID / ASC_ISSUER_ID /
# ASC_PRIVATE_KEY, ASC_BYPASS_KEYCHAIN=1). LOCALE defaults to en-US.
set -euo pipefail

ASC="${ASC:-${RUNNER_TEMP:-/tmp}/asc}"
APP="${ASC_APP_ID:?ASC_APP_ID is required}"
LOCALE="${LOCALE:-en-US}"
DEVICE_TYPE="APP_IPHONE_69"   # 1320x2868; App Store Connect scales this class down to smaller iPhones

# State gate: only PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED are editable. Resolve the version string
# shape-agnostically (every version returned is already editable, so any is safe).
versions_json="$("$ASC" versions list --app "$APP" --platform IOS \
  --state PREPARE_FOR_SUBMISSION,DEVELOPER_REJECTED --output json)"
version="$(printf '%s' "$versions_json" | jq -r '[.. | .versionString? // empty] | .[0] // empty')"

if [ -z "$version" ]; then
  echo "No editable App Store version (nothing in PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED)."
  echo "Nothing to upload — concluding green."
  exit 0
fi
echo "Editable App Store version: $version"

# Compose from the committed raws. Fails loudly on a missing raw or a wrong output size.
OUT_DIR="${RUNNER_TEMP:-/tmp}/shots-out"
RAW_DIR=screenshots OUT_DIR="$OUT_DIR" bash .github/scripts/compose_screenshots.sh "$LOCALE"

# Show the plan before mutating a public listing. --dry-run reports what would be uploaded/deleted.
echo "=== dry run ==="
"$ASC" screenshots upload --app "$APP" --version "$version" --platform IOS \
  --path "$OUT_DIR" --device-type "$DEVICE_TYPE" --replace --dry-run

echo "=== apply ==="
"$ASC" screenshots upload --app "$APP" --version "$version" --platform IOS \
  --path "$OUT_DIR" --device-type "$DEVICE_TYPE" --replace
