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
DIR="metadata"

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

if [ ! -d "$DIR/version/$version" ]; then
  echo "::error::$DIR/version/$version not found. The editable version changed; re-scaffold with"
  echo "::error::  asc metadata pull --app $APP --version $version --platform IOS --dir $DIR"
  exit 1
fi

# Declarative push of the version localizations. --confirm for non-interactive CI; NO --allow-deletes
# (absent field = no-op). Scoped to localizations so app-info/app-level fields are out of scope.
"$ASC" metadata push \
  --app "$APP" \
  --version "$version" \
  --platform IOS \
  --dir "$DIR" \
  --include localizations \
  --confirm
