## Why

App `6781692480` ("SnapSync Photos") cannot be submitted: `asc validate --app 6781692480 --version 1.0`
reports gaps, of which two are cheap and unambiguous — an **empty subtitle** and a **missing privacy
policy URL** — while the privacy page they point at has existed since `marketing-site` shipped. Both are
warnings on a listing whose text is otherwise already repo-owned, so the listing is inconsistent with its
own contract for no reason other than two absent JSON keys.

A third gap, the version's **copyright**, was blocking and has been set by hand on version `1.0`. That
repair does not survive: `ios-release.yml` creates each `X.Y` App Store version record over the raw API
with `attributes: {platform, versionString}` and no copyright, so a future `v1.1` tag can mint a record
that re-opens the same blocker — silently, since nothing in the repo asserts the field.

## What Changes

- **Fill in the two app-info fields.** Add `subtitle` and `privacyPolicyUrl` to
  `metadata/app-info/en-US.json`. **No code changes** — `appstore-metadata-apply` already applies this
  file on every `main` push; it no-ops today only because the file holds a single key.
- **Set copyright where a version record is born.** `appstore_release.py`'s `_find_or_create_version`
  gains a `copyright` attribute in its create payload, so every API-created record carries it. An
  existing record is still reused untouched, so version `1.0`'s hand-set copyright is not disturbed.
- **Pin the app-info behavior with a scenario.** `ios-appstore-metadata` asserts that the repo owns "the
  app-info fields" but no scenario covers them reaching App Store Connect. That gap is not academic: it
  is why this work was briefed as "app-info is never applied" when it has always been applied.
- **Record the subtitle limit.** The validation gate enumerates its character limits; `subtitle` ≤ 30 is
  enforced by the tool but unstated in the spec.

Not breaking. Nothing is removed. `asc_metadata_apply.sh` and `appstore.yml` are **not touched** — no
per-merge copyright write, no trigger or `paths:` change, and no weakening of the text apply's
declarative overwrite.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-appstore-metadata`: the source-of-truth requirement gains a scenario pinning that app-info fields
  (`subtitle`, `privacyPolicyUrl`) reach the app-info localization; the validation-gate requirement gains
  the `subtitle` ≤ 30 limit it already enforces.
- `ios-appstore-release`: a new requirement that a **created** App Store version record carries the
  copyright, while an **existing** record is reused untouched.

## Impact

- `metadata/app-info/en-US.json` — two keys added (data only).
- `.github/scripts/appstore_release.py` — one attribute added to the version-create payload.
- App Store Connect — clears `metadata.required.subtitle` and `metadata.recommended.privacy_policy_url`
  on the editable version; future version records are born with a copyright.
- **Out of scope** (a follow-up owns these): the 3 remaining blocking items — `review_details.missing`,
  `build.required.missing`, `availability.missing` — plus category, pricing, the App Privacy
  questionnaire (console-only, not API-verifiable), and Submit itself.
- No new secret, no new dependency, no CI job added or retriggered.
