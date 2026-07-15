## Context

SnapSync ships to a public *alpha* TestFlight channel: every `main` merge builds, uploads and promotes a `MARKETING_VERSION = 0.1.0` build (capability `ios-testflight-delivery`). It has never shipped to the App Store. The App Store version record (`app.snapsync`, ASC app id `6781692480`) is `PREPARE_FOR_SUBMISSION` at version **"1.0"** with no build attached, and no build is attachable: a "1.0" record accepts only a build whose `CFBundleShortVersionString` is `1.0`, and every uploaded build is `0.1.0`.

Two facts about the existing pipeline shape the design:
- `CURRENT_PROJECT_VERSION` is already injected at build time on the `xcodebuild` line (`CURRENT_PROJECT_VERSION=${{ github.run_number }}`), not read from committed source. `MARKETING_VERSION` can be injected identically.
- Bumping `MARKETING_VERSION` on `main` triggers a genuine first-of-version Beta App Review that silently stalls the alpha channel (the "MARKETING_VERSION trap", documented in `ios-testflight-delivery` and CLAUDE.md).

The `.github/rulesets/main.json` required checks are exactly `build`, `ios-build`, `ios-test` (all GitHub Actions, `strict_required_status_checks_policy: true`). `Config.xcconfig` is the **project-level** base configuration; the app (build configs `0013`/`0014`) and extension (`005F`/`0060`) targets have no target-level xcconfig and already inherit `$(BUNDLE_ID)` etc. from it, but each pins `MARKETING_VERSION = 0.1.0` at target level (4 entries), which outranks the project xcconfig.

## Goals / Non-Goals

**Goals:**
- A repeatable, tag-driven App Store release: `git push vX.Y` → an `X.Y` build attached to its App Store version record, ready for a human to Submit.
- Never touch the MARKETING_VERSION trap: `main`/alpha stays permanently pinned; real versions reach the store via tags, not committed bumps.
- Share the security-sensitive archive/sign scaffolding with the existing alpha path without renaming the required `ios-build` status check.
- Fix the latent production-APNs defect for all TestFlight/App Store distribution builds while I'm in the archive path.

**Non-Goals:**
- Submitting for App Store Review (needs listing/screenshots/privacy, owned by other workspaces).
- Pushing the first `v1.0` tag as part of this change (the operator does that later).
- Reworking the alpha channel's promotion/notify behavior.
- A three-part (`X.Y.Z`) version scheme.

## Decisions

**Inject `MARKETING_VERSION` from the tag; never commit a bump.** The release workflow passes `MARKETING_VERSION=<tag minus v>` on the `xcodebuild` line, overriding the committed fallback for that build only. *Alternatives rejected:* (a) editing the App Store record down to `0.1.0` — doesn't scale past one release and makes the public version read pre-release forever; (b) committing a bump to `1.0` on `main` — recurs the alpha-channel stall on every release.

**Two-part `vX.Y` tags.** Store version = tag minus `v`; `v1.0` → "1.0", reusing the existing record. Trade-off: a hotfix is a minor bump, not `X.Y.Z`. A strict `^v\d+\.\d+$` guard rejects malformed tags.

**Move the `0.1.0` fallback to `Config.xcconfig`; delete the 4 target-level entries.** Confirmed via the pbxproj object graph: with the target-level `MARKETING_VERSION` removed, both targets resolve it from the project-level xcconfig. *Alternative rejected:* leaving it in pbxproj — the interview chose xcconfig for a single discoverable home, accepting the minor Xcode-General-tab write-back footgun (don't edit the Version field in Xcode, or it re-adds a pbxproj entry).

**Shared composite action, not a reusable workflow or duplicated steps.** `.github/actions/ios-archive/` holds the ASC-key prep, ephemeral keychain, both cert imports, extension-framework compile and archive, with `marketing_version`, `aps_environment`, `apns_env`, `build_config`, `upload_host` inputs. Both `ios-build` and the release job `uses:` it. *Alternatives rejected:* (a) a `workflow_call` reusable workflow — renames `ios-build`'s status-check context to `ios-build / <job>`, which would freeze merges; a composite action is step-level and leaves the job name (= context) untouched; (b) duplicating the ~80 steps into the release workflow — accepted by the repo elsewhere, but the interview preferred a single source; (c) reusing `ios.yml`'s archive **artifact** — it bakes `MARKETING_VERSION=0.1.0`, is `main`-only with 1-day retention, and lives in a different run; "reuse" would mean cross-run fetch + Info.plist patch + full inside-out re-sign, strictly more fragile than recompiling.

**Find-or-create the version record, then attach; stop before submit.** ~5 extra lines over create-only (a `GET …?filter[versionString]=…&filter[platform]=IOS` + a branch), and it removes the first-release bootstrap (reuses the existing "1.0" record) and gives re-run idempotency. *Alternative rejected:* create-only — needs a one-time manual delete of the "1.0" record and fails on the 409 when a partial run is retried.

**Guards: format + ancestor-of-`main` + every-check-run-green.** The green guard requires **every** check-run on the tag SHA to be `success`, excluding the release workflow's own in-progress runs. This deliberately includes the allowed-red plumbing (`ios-deliver`/`ios-promote`): the stance is "don't App-Store-release a commit whose full pipeline — including promotion to alpha — isn't green." *Alternative considered:* required-contexts-only (`build`, `ios-build`, `ios-test`) — dodges the cancelled-promote problem but was declined in favor of the stricter whole-pipeline signal. To make the strict form workable, **tag refs are excluded from the `push` triggers of both `build.yml` and `ios.yml`**, so a tag fires only `ios-release.yml` and the only in-progress runs to self-exclude are its own.

**Production APNs for Release/distribution archives.** The composite action injects `APS_ENVIRONMENT=production APNS_ENV=production` for Release builds; Debug/dev-IPA paths keep the xcconfig sandbox default. This fixes a latent defect: `ios.yml` never overrode these, so every alpha TestFlight build currently ships sandbox APNs despite the xcconfig comment claiming otherwise. The interview confirmed the intent — all TestFlight/App Store builds production, only dev-sideload sandbox.

**Version create/attach via raw REST.** `codemagic-cli-tools` has no version-attach subcommand, so `appstore_release.py` uses the ASC JWT + REST (`POST /v1/appStoreVersions`, `PATCH …/appStoreVersions/<id>/relationships/build`), mirroring `testflight_promote.py`'s idiom. Build number = the release run's `github.run_number`; safe because the store version is a fresh `MARKETING_VERSION` train disjoint from alpha's `0.1.0`.

## Risks / Trade-offs

- **A cancelled/red `ios-promote` on the target commit blocks the release** (the "every check-run green" cost). → Re-run the idempotent `ios-promote` job to green, then re-push the tag. Documented as an operational note in the spec/runbook.
- **ASC allows only one editable version at a time.** Pushing `vX.Y` while a prior version is still editable makes `POST /appStoreVersions` fail. → Acceptable: it surfaces the mistake (release the prior version first). Not enforced by the workflow.
- **Xcode General-tab write-back** after the xcconfig move re-adds a pbxproj `MARKETING_VERSION`. → Note in CLAUDE.md / the spec; don't edit the Version field in Xcode.
- **`appStoreVersions` REST create/attach is not codebase-verifiable.** → Apply-time verification against the real record; the localizations endpoints are already proven with the Admin key, and attach is a relationship PATCH.
- **Composite-action extraction touches the release-critical `ios.yml`.** → Verify after refactor that `ios-build`'s status-check context is byte-identical (job name unchanged) and the archive still bakes the fallback on `main`.

## Migration Plan

1. Land the pipeline via branch → PR → `/ship` (the required `ios-build`/`ios-test` gates still run on the PR; the composite-action refactor must keep those green with unchanged context names).
2. The change ships the mechanism only — **no `v1.0` tag is pushed here**. The "1.0" record stays empty until the operator pushes `v1.0`, which then uploads a production-APNs `1.0` build and attaches it (submission-ready; a human Submits once listing/screenshots/privacy land).
3. Rollback: the new workflow/action/script are additive; reverting the PR removes them. The xcconfig move + tag-trigger exclusions are the only edits to existing files — reverting restores the prior (0.1.0-in-pbxproj, tags-fire-everything) state.

## Open Questions

- Does `ios-release.yml` want its own `concurrency` group (e.g. `ios-release-${{ github.ref }}`, no cancel-in-progress across distinct tags)? Leaning yes, per-tag, so a re-pushed same tag cancels but distinct versions don't. Settle at apply.
- Exact self-exclusion mechanism for the green guard (filter check-runs by the release job's own name vs by check-suite id). Settle at apply against a real SHA.
