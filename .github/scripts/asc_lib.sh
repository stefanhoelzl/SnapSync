# shellcheck shell=bash
# The App Store version lookups the asc_*.sh scripts share. Sourced, never run:
#
#   source "$(dirname "${BASH_SOURCE[0]}")/asc_lib.sh"
#
# Each reads the caller's ASC (the asc binary) and APP (the app id), and sets a global the caller reads next.

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

# Make the version hold a localization for every committed store locale (`metadata/listing/<locale>.json`, one per
# language the app speaks — capability `localization`), creating each it lacks, and set `localizations` to every
# locale it then holds, one per line. App Store Connect seeds a new version's localizations from the previous
# version, so this creates nothing in practice — except in a locale's first release, where the screenshot upload
# refuses a locale the version lacks ("no matching App Store version localizations found", measured on the first
# de-DE dry run) and the release notes would fail on it. A created localization gets its text from the same promote's
# listing-text apply (asc_metadata_apply.sh), which runs after the screenshot upload that creates it.
#
# Usage: asc_ensure_localizations <version id> <version string>
asc_ensure_localizations() {
  local id="$1" label="$2" listing locale
  localizations="$("$ASC" localizations list --version "$id" --output json \
    | jq -r '[.. | objects | .attributes?.locale? // empty] | unique | .[]')"
  for listing in metadata/listing/*.json; do
    locale="$(basename "$listing" .json)"
    if ! grep -qxF "$locale" <<<"$localizations"; then
      echo "version '$label' has no $locale localization — creating"
      "$ASC" localizations create --version "$id" --locale "$locale"
      localizations="$(printf '%s\n%s' "$localizations" "$locale" | sed '/^$/d')"
    fi
  done
}
