#!/usr/bin/env bash
# Write the derived release notes to EVERY localization of the version (`docs/deployment.md`). Runs on EVERY release, not just when submitting, so a promote-only run
# leaves the version submit-ready — Apple blocks a submission whose `whatsNew` is missing, which is
# exactly what refused version 0.2 (run 30632785849).
#
# The notes are DERIVED, never committed: they are the one part of the listing whose content differs
# per release. `.github/scripts/release_notes.py` renders them from the labelled PRs in the range
# (`docs/deployment.md`) BEFORE any App Store Connect mutation; this script only publishes
# the file it produced. The committed per-locale listing deliberately carries no `whatsNew` key, and
# the main-only metadata apply never deletes an absent field (`docs/deployment.md`), so
# a later merge cannot clear what this wrote.
#
# WHY `localizations update` AND NOT `metadata push`: `whatsNew` is inside the metadata tool's
# version-localization scope, so a scratch `version/<v>/en-US.json` tree pushed the way
# asc_metadata_apply.sh does would also work — but this is one call with no temporary tree, and
# nothing that could be mistaken for a change to the committed listing.
#
# THE SAME ENGLISH NOTES IN EVERY LOCALIZATION, by decision (capability `localization`: What's New stays English;
# `changes/archive/<date>-localize-store-listings`, D5). Apple refuses to submit an update while any localization
# lacks `whatsNew`, and the notes are derived from English PR titles, so English is the only text there is. That
# covers each localization the version holds (one a console edit added included) plus every committed store
# locale (`metadata/listing/<locale>.json`), created when the version lacks it.
#
# Env: ASC (path to the asc binary), ASC_APP_ID, STORE_VERSION, NOTES_FILE, plus asc's auth vars
# (ASC_KEY_ID / ASC_ISSUER_ID / ASC_PRIVATE_KEY, ASC_BYPASS_KEYCHAIN=1).
set -euo pipefail
# shellcheck source=.github/scripts/asc_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/asc_lib.sh"

ASC="${ASC:-${RUNNER_TEMP:-/tmp}/asc}"
APP="${ASC_APP_ID:?ASC_APP_ID is required}"
VERSION="${STORE_VERSION:?STORE_VERSION is required}"
NOTES_FILE="${NOTES_FILE:?NOTES_FILE is required}"

[ -s "$NOTES_FILE" ] || { echo "::error::$NOTES_FILE is missing or empty"; exit 1; }
notes="$(cat "$NOTES_FILE")"

# Resolve the version id by versionString (`asc_lib.sh`), pinned to ONE object.
asc_require_version_id "$VERSION" "release notes"

# find-or-create each committed locale. App Store Connect normally seeds a new version's localizations from the
# previous version, so this is a no-op in practice — except for a locale's first release, and a version born
# without one would otherwise fail the update with a message about the locale rather than about the notes.
held="$("$ASC" localizations list --version "$version_id" --output json \
  | jq -r '[.. | objects | .attributes?.locale? // empty] | unique | .[]')"
for listing in metadata/listing/*.json; do
  locale="$(basename "$listing" .json)"
  if ! grep -qxF "$locale" <<<"$held"; then
    echo "version '$VERSION' has no $locale localization — creating"
    "$ASC" localizations create --version "$version_id" --locale "$locale"
    held="$(printf '%s\n%s' "$held" "$locale")"
  fi
done

while IFS= read -r locale; do
  [ -n "$locale" ] || continue
  "$ASC" localizations update --version "$version_id" --locale "$locale" --whats-new "$notes"
  echo "::notice::wrote $locale release notes for version $VERSION ($(printf '%s' "$notes" | wc -c) chars)"
done <<<"$held"
