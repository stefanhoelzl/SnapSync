## Why

The App Store listing text (description, keywords, promotional text, support/marketing URLs) was set once, by hand, straight into App Store Connect via the ASC API — it lives **only** in Apple's web console, with no version-controlled source, no review, and no reproducibility. The next edit is another untracked hand-mutation, and a wrong one (an over-length keyword string, a broken support URL) is invisible until a reviewer or a user hits it. `main` already *is* the public alpha channel for the binary (`ios-testflight-delivery`); the listing that ships alongside it has no equivalent source-of-truth or gate.

## What Changes

- Introduce a **repo-versioned App Store listing**: canonical per-locale JSON files (`metadata/version/<ver>/<locale>.json` + `metadata/app-info/<locale>.json`) become the source of truth for the App Store **text** metadata of app `6781692480`.
- Add a **`main`-only CI job** that applies those files to the **currently editable** App Store version's localizations, declaratively — the file wins; drift typed into the ASC web console is overwritten. Text only: **screenshots and app previews stay out of scope** (they are binaries with no CLI path).
- Add an **offline validation gate** (no credentials) that runs on every ref and fails the merge on any schema / character-limit / URL-format violation *before* it can reach Apple.
- The apply job **owns the safety gate the tooling does not**: it edits only a version in an editable state and never one in review — resolved at run time, never from a stored id.
- Adopt the pinned, checksum-verified **`asc` CLI** (`rudrankriyam/App-Store-Connect-CLI`) for the file format, offline `validate`, and `plan`/`apply` — reusing the **existing Admin ASC key**; **no fastlane, no Ruby** (consistent with the rest of the pipeline).
- Make the validator a **required status check** so an invalid listing file cannot merge.

## Capabilities

### New Capabilities
- `ios-appstore-metadata`: the repo → App Store Connect listing-text sync — the canonical metadata files, the offline validation gate, and the `main`-only apply job that writes only an editable version's localizations (text only, declaratively, red-but-blocks-nothing).

### Modified Capabilities
- `branch-protection`: add the offline metadata-validation job to the set of **required** status checks on `main`. It is safe to require because it runs on **every** ref (offline, no secrets), so it always posts — it can never freeze merges the way a `main`-only job would.

## Impact

- **New CI jobs** (in `.github/workflows/`): a metadata-validate job (every ref, `ubuntu`, no secrets) and a metadata-apply job (`main` only, `ubuntu`, existing ASC key, no Xcode/keychain/cert) — shaped like `ios-promote`.
- **New committed content**: `metadata/**` per-locale listing JSON (public marketing copy, no secrets).
- **New tool dependency**: the `asc` binary, pinned to a release tag + SHA-256 (fetched like `cloudflared`, or via `setup-asc`).
- **Reuses** the existing Admin App Store Connect key secrets (`ASC_KEY_ID` / `ASC_ISSUER_ID` / `ASC_API_PRIVATE_KEY`); introduces no new secret.
- **`.github/rulesets/main.json`**: adds the validator as a required check.
- **Out of scope**: screenshots/app previews, app-level fields beyond the listing text (categories, age rating, review info), and public-App-Store *release/submission* automation (this change only syncs listing text onto the editable version).
