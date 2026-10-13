# Proposal

## Why

The privacy promise about how a phone is known to SnapSync's service says each **install** gets a **random**
identifier. That is not what the app does, and it never was on Android: there the identifier is derived from the
identifier Android gives SnapSync alone, so it stays the same when the app is reinstalled — deliberately, so a
reinstalled phone stays the same member of its events (decision record `changes/archive/2026-09-30-android-receiving`).
On iPhone the identifier is random but kept where it normally outlives deleting the app. The published Privacy Policy
repeats the wrong wording ("Each install generates a random identifier"), and the spec's own requirement says the
policy SHALL describe accurately what leaves the device. The contract and the policy are corrected to the truth; the
behaviour is kept.

## What Changes

- `privacy-security`: the three requirements that call the identifier a "random install identifier" say what it is —
  an identifier SnapSync keeps for this phone, linked to no person, no account and no other app, that can outlive a
  reinstall of the app; and the failure-report requirement stops calling the device, event and photo identifiers a
  report may carry "random" (the reporting's own per-install identifier is random, and stays so described).
- The Privacy Policy on the site describes the identifier the same way, for iPhone and Android.
- The store data-safety record (`metadata/play/declarations.md`) records that the identifier is declared as
  "Device or other IDs" (App functionality, not shared) — already declared in the Play Console by the operator —
  instead of treating a "random install ID" as part of the photos.
- No app behaviour changes.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `privacy-security`: "No account and no personal identity", "The service records who reads an event's photo list",
  "The Privacy Policy states what leaves the device" and "Automatic failure reports are minimal and anonymous" — the
  identifiers' description.

## Impact

- `openspec/specs/privacy-security/spec.md` (on archive).
- `site/src/pages/index.astro` — the Privacy Policy's identifier paragraph, its read-record paragraph and its
  crash-report paragraph; published on merge with the site.
- `metadata/play/declarations.md` — the data-safety table and its notes.
- No app code, no api code. Ships inside phase 4.1's PR ("trust").
