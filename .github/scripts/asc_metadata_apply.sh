#!/usr/bin/env bash
# Apply the committed App Store listing text to App Store Connect — main-only (capability
# ios-appstore-metadata). Declarative: fields present in the files are enforced, omitted fields are a
# no-op (never deleted — no --allow-deletes). Text only; screenshots are never touched.
#
# The safety gate is OURS, not the tool's: `asc` takes an explicit --version and its behaviour on an
# in-review version is undefined (upstream epic #587 is open). So we resolve the EDITABLE version
# ourselves and refuse to touch anything else. A run with no editable version is a green no-op.
#
# Env: ASC (path to the asc binary), ASC_APP_ID, plus asc's auth vars (ASC_KEY_ID / ASC_ISSUER_ID /
# ASC_PRIVATE_KEY, ASC_BYPASS_KEYCHAIN=1).
set -euo pipefail

ASC="${ASC:-${RUNNER_TEMP:-/tmp}/asc}"
APP="${ASC_APP_ID:?ASC_APP_ID is required}"
# The RENDERED listing, not the committed one. The committed files are hand-written copy carrying a
# `{{domain}}` placeholder for the three URL fields derived from the device-facing domain; the resolver
# substitutes it (capability `deployment-configuration`) so the store listing cannot advertise a host the
# rest of the system has moved off. Everything else in those files is copy and is never templated, so
# editing App Store text still needs no generator. Renderings are generated, never committed.
DIR="${METADATA_DIR:-build/metadata}"
if [ ! -d "$DIR" ]; then
  echo "error: $DIR does not exist — run scripts/resolve-deployment.py <deployment> first" >&2
  exit 1
fi

# State gate: only PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED are editable. Resolve the version
# string shape-agnostically (the first versionString anywhere in the filtered response — every version
# returned is already editable, so any is safe).
versions_json="$("$ASC" versions list --app "$APP" --platform IOS \
  --state PREPARE_FOR_SUBMISSION,DEVELOPER_REJECTED --output json)"
version="$(printf '%s' "$versions_json" | jq -r '[.. | .versionString? // empty] | .[0] // empty')"

if [ -z "$version" ]; then
  echo "No editable App Store version (nothing in PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED)."
  echo "Nothing to apply — concluding green."
  exit 0
fi
echo "Editable App Store version: $version"

# The committed listing is VERSION-INDEPENDENT (version/current) — store versions auto-advance
# with every release (capability ios-appstore-release derives them from builds), so a version-named
# directory goes stale the moment the editable version changes; that failure shipped (run
# 29789667939, 1.0 vs 0.1). The tool still wants the version-named layout, so materialize it in a
# scratch root holding ONLY app-info + the resolved version — nothing else for the push to read.
# (`version/current` stays under version/ so the offline validate gate scans it; verified.)
if [ ! -d "$DIR/version/current" ]; then
  echo "::error::$DIR/version/current not found — the committed listing is gone."
  exit 1
fi
PUSH_DIR="$(mktemp -d)"
mkdir -p "$PUSH_DIR/version"
cp -R "$DIR/app-info" "$PUSH_DIR/app-info"
cp -R "$DIR/version/current" "$PUSH_DIR/version/$version"

# Declarative push of the localizations. --confirm for non-interactive CI; NO --allow-deletes
# (absent field = no-op).
#
# `--include localizations` covers BOTH version/<v>/<locale>.json and app-info/<locale>.json: the tool
# self-resolves the appInfoId from the app id and plans `"scope": "app-info"` writes without an
# `--app-info` flag (which is documented as an override for apps with multiple app-infos, not a
# requirement). Verified by --dry-run of this exact command; see
# `changes/archive/2026-07-16-close-appstore-submission-gaps`. What is out of scope is app-level
# ATTRIBUTES (e.g. a version's copyright) — a closed schema the tool rejects — not app-info text.
"$ASC" metadata push \
  --app "$APP" \
  --version "$version" \
  --platform IOS \
  --dir "$PUSH_DIR" \
  --include localizations \
  --confirm
