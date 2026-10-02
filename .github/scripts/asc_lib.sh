# shellcheck shell=bash
# The App Store version lookups the asc_*.sh scripts share. Sourced, never run:
#
#   source "$(dirname "${BASH_SOURCE[0]}")/asc_lib.sh"
#
# Both read the caller's ASC (the asc binary) and APP (the app id), and set a global the caller reads next.

# The state gate: resolve the EDITABLE version into `version`, or conclude the calling script green when there is
# none. Only PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED are editable. `asc` takes an explicit --version and its
# behaviour on an in-review version is undefined (upstream epic #587), so the gate is ours, and nothing else is
# touched. The version string is resolved shape-agnostically (the first versionString anywhere in the filtered
# response — every version returned is already editable, so any is safe).
#
# Usage: asc_require_editable_version <verb>   (what the caller would have done: "apply", "upload")
asc_require_editable_version() {
  local verb="$1" versions_json
  versions_json="$("$ASC" versions list --app "$APP" --platform IOS \
    --state PREPARE_FOR_SUBMISSION,DEVELOPER_REJECTED --output json)"
  version="$(printf '%s' "$versions_json" | jq -r '[.. | .versionString? // empty] | .[0] // empty')"

  if [ -z "$version" ]; then
    echo "No editable App Store version (nothing in PREPARE_FOR_SUBMISSION / DEVELOPER_REJECTED)."
    echo "Nothing to $verb — concluding green."
    exit 0
  fi
  echo "Editable App Store version: $version"
}

# Resolve the id of the version whose versionString is <version> into `version_id`, or fail the calling script.
# Pinned to ONE object, so the id and the versionString provably came from the same version.
#
# Usage: asc_require_version_id <version> <what>   (what the caller applies: "review details", "release notes")
asc_require_version_id() {
  local wanted="$1" what="$2" versions_json
  versions_json="$("$ASC" versions list --app "$APP" --platform IOS --output json)"
  version_id="$(printf '%s' "$versions_json" \
    | jq -r --arg v "$wanted" '[.. | objects | select(.id? and (.attributes?.versionString? == $v))] | .[0].id // empty')"

  if [ -z "$version_id" ]; then
    echo "::error::no App Store version record with versionString '$wanted' — cannot apply $what"
    exit 1
  fi
  echo "App Store version '$wanted' = $version_id"
}
