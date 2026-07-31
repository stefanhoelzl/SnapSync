## Why

Release 0.2 could not be submitted. `asc review submit` refused it — `submission-blocking
localization fields are missing: en-US: whatsNew` (run 30632785849) — because Apple requires
"What's New in This Version" on every **update**, and nothing in the repo or the pipeline produces
it. Every field of the listing is already repo-owned and applied by CI *except* the one field whose
content is different for every release, so the release path has a hand-written step that nobody
performs and no gate names. The information needed to write it already exists and is already
curated: `/ship` labels every PR `enhancement` / `bug` / `internal` (capability `ship-command`), so
the set of user-facing changes since the last release is a query, not a writing task.

## What Changes

- The release notes for a promoted version are **derived from the labeled pull requests merged since
  the previous release** — the range from the nearest ancestor `vX.Y` tag of the build's origin
  commit to that commit — and written to the version's `en-US` localization as `whatsNew`. `internal`
  PRs are excluded; `enhancement` and `bug` PRs become the two groups a user reads.
- A committed `.github/release.yml` becomes the **single declarative source** of that grouping and
  exclusion (label → heading, `internal` excluded), consumed by GitHub's release-notes generator.
- The promote workflow renders the generated notes as plain text (headings, one `- ` bullet per PR,
  the conventional-commit `type(scope):` prefix stripped), **before any App Store Connect
  mutation**, and fails fast if the result exceeds Apple's 4000-character limit. A release whose PRs
  were all `internal` gets a committed fallback sentence rather than an empty field.
- The notes are applied on **every** promote, not only when submitting — like the App Review details
  — so a promote-only run leaves the version submit-ready.
- A new `check-label` workflow fails a pull request that carries none of the three labels, and
  becomes a **required status check**. Without it an unlabeled PR is silently absent from the
  changelog: GitHub's generator drops what it cannot categorize, so the omission has no other
  signal.
- The promote still creates only the `vX.Y` tag — **no GitHub Release is published**. The generated
  notes have exactly one consumer, the App Store listing.

## Capabilities

### New Capabilities
- `changelog-labels`: the changelog contract — the three-label vocabulary, the committed
  `.github/release.yml` that maps labels to headings and excludes `internal`, the PR gate that
  refuses an unlabeled PR, and the derivation of a plain-text changelog for a commit range from the
  pull requests it contains.

### Modified Capabilities
- `ios-appstore-release`: gains a requirement that the promoted version's release notes are the
  derived changelog for the range since the previous release, applied on every run, generated before
  any App Store Connect mutation.
- `ios-appstore-metadata`: the committed per-locale listing deliberately carries **no** `whatsNew`,
  and the declarative main-only push must never delete the CI-written one — stated so a later change
  neither commits a static value nor adds `--allow-deletes`.
- `branch-protection`: the committed ruleset requires the new `check-label` context.

## Impact

- `.github/workflows/ios-appstore-promote.yml` — two new steps (generate; apply), no new credential,
  still one `ubuntu` job.
- `.github/scripts/` — a notes generator and an `asc localizations update --whats-new` applier,
  siblings of the existing `asc_*.sh` release scripts.
- `.github/release.yml` (new), `.github/workflows/check-label.yaml` (new),
  `.github/rulesets/main.json` (one context added).
- No app, backend, or site code. No Gradle module is touched, so `./gradlew build` is unaffected.
