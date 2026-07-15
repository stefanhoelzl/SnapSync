## Context

The App Store listing text for app `6781692480` (`app.snapsync`, "SnapSync Photos") was populated once by hand through the App Store Connect (ASC) API — description, keywords, promotional text, support URL, marketing URL — and exists nowhere but Apple's console. There is no committed source, no diff, no gate. `main` already auto-delivers the binary to the public alpha channel (`ios-testflight-delivery`); this change gives the *listing* the same "repo is the source of truth, CI applies it" shape.

Constraints inherited from the repo:
- **No fastlane, no Ruby, no `match`** — stated repeatedly across `ios-testflight-delivery` and CLAUDE.md; the pipeline uses the codemagic CLI + REST and the Admin ASC API key.
- Required status checks must **always post** on every ref, or merges freeze (the hard-won lesson in `branch-protection` / `ios-testflight-delivery`: a `main`-only job can never be a required check).
- Operator/CI tooling is fetched as a pinned binary into scratchpad when needed (e.g. `cloudflared` in `ssh-mac.yml`) rather than globally installed.

This design is backed by a live spike (2026-07-15): the `asc` CLI (`rudrankriyam/App-Store-Connect-CLI` `2.8.2`, linux_amd64, SHA-256 `b6be35bf7d8694d312b933aa4873723d5ea15c97733309542b7a1c531431808b`) was downloaded, checksum-verified, and run `metadata pull` against the live app using the existing Admin key. It round-tripped every field written by hand, byte-for-byte.

## Goals / Non-Goals

**Goals:**
- Make committed per-locale JSON the source of truth for the App Store **text** listing.
- Apply it automatically on `main` to the **currently editable** App Store version's localizations, declaratively (file wins; console drift is overwritten).
- Catch listing errors (over-length fields, malformed URLs, unknown keys) **before merge**, with no credentials.
- Never edit a version that is in review; never auto-create a version.
- Stay in the no-fastlane / no-Ruby lane, reusing the existing Admin key and introducing no new secret.

**Non-Goals:**
- **Screenshots / app previews** — binaries, no CLI reserve/commit path, explicitly out (stay manual; a separate handoff already exists).
- **App-level metadata** beyond listing text — categories, age rating, review information (`asc` Phase-1 excludes them too).
- **Public App Store release / submission automation** — this change syncs listing *text* onto the editable version; it does not submit the app for review or manage phased release.
- **Multi-locale beyond en-US today** — the layout is per-locale and ready for more, but creating a *new* locale localization (POST vs PATCH) is deferred.
- **`whatsNew` / release notes** — version-specific, tangled with the `MARKETING_VERSION` story; deferred.

## Decisions

### D1: Adopt the `asc` CLI for format + validate + apply; do not build custom glue or use fastlane

`asc metadata` provides exactly the declarative layer needed: canonical per-locale JSON, an **offline** `validate`, a `plan → approve → apply --confirm` review flow, and `pull` for scaffolding — as a single Go binary, no Ruby, authenticating with the existing ASC key.

Alternatives considered:
- **`fastlane deliver`** — mature, file-based, metadata-only capable (`skip_binary_upload`/`skip_screenshots`). Rejected: it *is* fastlane + Ruby, which the repo forbids on principle.
- **Custom glue over `codemagic-cli-tools`** (already in the repo) — only exposes per-field `app-store-version-localizations modify`; no declarative directory sync, no schema/keyword validation, no diff. We would hand-build version resolution, per-field PATCH, and length checks. Rejected: reinvents what `asc` ships, more code to own.
- **EAS Metadata (Expo)** — declarative `store.config.json`, but Expo-ecosystem; wrong fit for a KMP/Xcode app. Rejected.
- **Raw ASC REST** — zero deps, maximum control, maximum code. Rejected as the default; retained as the fallback if `asc` is ever untrustworthy.

### D2: Pin `asc` by tag + SHA-256; treat it as an untrusted third-party dependency

The tool is a young (2026) community project, and there are **three near-identical repos** (`rorkai`, `rudrankriyam`, `atwo-dev`). Canonical source is `rudrankriyam/App-Store-Connect-CLI` (where the official `setup-asc` Action pulls from). CI SHALL pin a specific release tag and verify the published `asc_<ver>_checksums.txt` SHA-256 before executing — the same discipline as the `cloudflared` fetch. Either `rudrankriyam/setup-asc@<sha>` (which does the checksum) or a hand-rolled `curl` + `sha256sum` is acceptable.

### D3: Declarative, absent = no-op (not delete)

The file is desired-state; each `main` apply overwrites ASC to match. `asc` already defaults this way: *"Omitted fields are treated as no-op; they do not imply deletion"*, and deletion is gated behind an explicit `--allow-deletes`/`--confirm`. The apply job SHALL NOT pass those delete flags. This mirrors the `ios-testflight-delivery` philosophy ("every main build promoted, unfiltered" — the declarative option wins; console hand-edits lose).

### D4: Split validate (every ref, no secrets, required) from apply (main only, secret, non-required)

`asc metadata validate --dir` runs **offline** — a credential-free gate that posts on every ref, so it is safe to make a **required** check (`branch-protection`). The apply job is `main`-only, holds the key, and — like `ios-deliver`/`ios-promote` — posts no required check and is free to fail **red without blocking a merge**. This is the same gate/deliver split the pipeline already uses.

### D5: The version-state gate is ours, not the tool's

`asc` takes an explicit `--version` and its behavior on an in-review version is **undefined** — upstream epic #587 lists "editable vs in-review versions and version creation logic" as an *open* decision. So the apply job SHALL resolve the editable version itself: `asc versions list --app <id> --state PREPARE_FOR_SUBMISSION` → if empty, **skip green** (nothing editable); if present, apply to that version string. It SHALL NOT pass a version that is `WAITING_FOR_REVIEW`/`IN_REVIEW`, and SHALL NOT create a version.

### D6: Resolve the version string at run time; never store a localization id or version path

The spike surfaced two traps: the localization UUID (`f2495af4…`) is **version-specific** (a new version mints new ids), and the ASC **version string is `1.0`**, which is *not* the build's `MARKETING_VERSION` (`0.1.0`). So neither the id nor the `metadata/version/<ver>/` path may be hardcoded — both derive from the editable version resolved in D5. The committed files live under the current editable version string; when a new version is created, the directory is re-scaffolded via `asc metadata pull`.

### D7: File layout (empirically confirmed)

```
metadata/
  app-info/<locale>.json          # {"name": "...", "subtitle": ..., "privacyPolicyUrl": ...}  (v1: name only)
  version/<ver>/<locale>.json      # description, keywords, promotionalText, supportUrl, marketingUrl [, whatsNew]
```
Singular `version/`, keyed by the ASC version string. Compact JSON, git-safe, one file per locale.

## Risks / Trade-offs

- **`asc` is young + forked three ways** → Mitigation: pin tag + SHA-256 (D2); the entire surface we use is text-metadata sync, and raw ASC REST remains a documented fallback (D1).
- **In-review edits can void a submission** → Mitigation: the D5 state gate refuses any non-editable version and skips green.
- **Declarative overwrite silently clobbers console hand-edits** → Accepted, by design (D3); the repo becomes the only place to change the listing, which is the point. Documented so no one is surprised.
- **Marketing URL / support URL may not be publicly reachable** (the marketing URL currently 401s) → CI can `curl` and *warn*, but cannot hard-gate an auth-walled page; final reachability stays a human check before submission.
- **Feeding an Admin key to third-party code** → Mitigation: pinned+checksummed binary; longer term, mint a scoped App-Manager/read-narrow key for the metadata job rather than the full Admin key.
- **A new version resets the `version/<ver>/` path** → Mitigation: re-scaffold with `asc metadata pull` when a version is created; D6 forbids hardcoded paths.

## Migration Plan

1. Land the `asc`-pinned CI jobs and the `metadata/**` files (seeded from `asc metadata pull` of the current `1.0` listing — already spiked, matches what was hand-applied, so the first apply is a no-op).
2. Add the validator to `.github/rulesets/main.json` as a required check.
3. Thereafter, all listing edits go through the file + PR; the console becomes read-only by convention.
- **Rollback**: remove the jobs and the required check; the listing in ASC is unaffected (last-applied state persists). No data migration.

## Open Questions

- Which workflow hosts the jobs — extend `ios.yml`, or a new `appstore.yml`? (Leaning: a small dedicated `appstore.yml`, since it shares nothing with the Xcode build.)
- Use `rudrankriyam/setup-asc@<sha>` or a hand-rolled pinned `curl`+`sha256sum`? (Leaning: hand-rolled, to match the `cloudflared` pattern and avoid a second third-party trust root.)
- Should the metadata job use a **scoped** ASC key instead of the Admin key? (Recommended, but depends on minting one.)
- When should `whatsNew` and additional locales be brought in — this change, or a follow-up? (Leaning: follow-up.)
