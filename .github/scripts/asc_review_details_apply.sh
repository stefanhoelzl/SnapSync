#!/usr/bin/env bash
# Apply the committed App Review details to a version's appStoreReviewDetail (capability
# ios-appstore-release). Declarative: the committed notes win, overwriting a console hand-edit. Runs on
# EVERY release, not just when submitting, so a build-only run leaves the version submit-ready.
#
# The notes live in metadata/review/notes.md — OUTSIDE the metadata tool's canonical schema on purpose.
# `asc metadata validate` decodes only version/<v>/<locale>.json and app-info/<locale>.json, strictly; an
# unknown key there fails appstore-metadata-validate, a REQUIRED check, and freezes merges. It is a flat
# .md, not <locale>.json, because an appStoreReviewDetail is version-scoped and NOT localized — one per
# version, no locale — and because prose belongs in a file you can read in a diff.
#
# NO DEMO ACCOUNT: the app has no account and no sign-up, so there are no credentials to give. Reviewer
# guidance in the notes is the whole story.
#
# Env: ASC (path to the asc binary), ASC_APP_ID, STORE_VERSION, CONTACT_{FIRST_NAME,LAST_NAME,EMAIL,PHONE},
# plus asc's auth vars (ASC_KEY_ID / ASC_ISSUER_ID / ASC_PRIVATE_KEY, ASC_BYPASS_KEYCHAIN=1).
set -euo pipefail

ASC="${ASC:-${RUNNER_TEMP:-/tmp}/asc}"
APP="${ASC_APP_ID:?ASC_APP_ID is required}"
VERSION="${STORE_VERSION:?STORE_VERSION is required}"
NOTES_FILE="metadata/review/notes.md"

[ -f "$NOTES_FILE" ] || { echo "::error::$NOTES_FILE not found"; exit 1; }
notes="$(cat "$NOTES_FILE")"

# Resolve the version id by versionString. Shape-agnostic like asc_metadata_apply.sh, but pinned to ONE
# object so the id and the versionString provably came from the same version.
versions_json="$("$ASC" versions list --app "$APP" --platform IOS --output json)"
version_id="$(printf '%s' "$versions_json" \
  | jq -r --arg v "$VERSION" '[.. | objects | select(.id? and (.attributes?.versionString? == $v))] | .[0].id // empty')"

if [ -z "$version_id" ]; then
  echo "::error::no App Store version record with versionString '$VERSION' — cannot apply review details"
  exit 1
fi
echo "App Store version '$VERSION' = $version_id"

# find-or-create. details-for-version exits non-zero when none exists, which is not an error here.
detail_id="$("$ASC" review details-for-version --version-id "$version_id" --output json 2>/dev/null \
  | jq -r '[.. | objects | select(.id?) | .id] | .[0] // empty' || true)"

if [ -z "$detail_id" ]; then
  echo "no review detail on version '$VERSION' — creating"
  "$ASC" review details-create \
    --version-id "$version_id" \
    --contact-first-name "${CONTACT_FIRST_NAME:?CONTACT_FIRST_NAME is required}" \
    --contact-last-name "${CONTACT_LAST_NAME:?CONTACT_LAST_NAME is required}" \
    --contact-email "${CONTACT_EMAIL:?CONTACT_EMAIL is required}" \
    --contact-phone "${CONTACT_PHONE:?CONTACT_PHONE is required}" \
    --demo-account-required=false \
    --notes "$notes"
  echo "::notice::created App Review details for version $VERSION"
else
  echo "review detail $detail_id exists — updating in place"
  "$ASC" review details-update \
    --id "$detail_id" \
    --contact-first-name "${CONTACT_FIRST_NAME:?CONTACT_FIRST_NAME is required}" \
    --contact-last-name "${CONTACT_LAST_NAME:?CONTACT_LAST_NAME is required}" \
    --contact-email "${CONTACT_EMAIL:?CONTACT_EMAIL is required}" \
    --contact-phone "${CONTACT_PHONE:?CONTACT_PHONE is required}" \
    --demo-account-required=false \
    --notes "$notes"
  echo "::notice::updated App Review details for version $VERSION"
fi
