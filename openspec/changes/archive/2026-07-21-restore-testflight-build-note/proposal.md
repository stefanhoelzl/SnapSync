## Why

Since the public alpha promotion was removed (`changes/archive/2026-07-19-remove-alpha-testflight-promotion`), TestFlight builds carry no "What to Test" note: the note-setting step lived in the deleted `ios-promote` job (`--whats-new "$(git log -1 --format='%s') (${GITHUB_SHA:0:7})"`), so both the commit message and the short SHA vanished with it. A build in the internal `development` group is now identifiable only by its build number, which makes "which change is this build?" a manual archaeology step.

## What Changes

- `ios-deliver` sets a TestFlight "What to Test" note on every delivered build: the **PR title, PR number, and short commit SHA** (e.g. `Sentry test-link opts for ext-safe (#42, 882eb5b)`). Under rebase-merge the head-commit subject can be a trailing docs/test commit, so the PR title — resolved via `GET repos/{repo}/commits/{sha}/pulls` with the run's own token — is the honest summary; the head-commit subject is the fallback when no PR resolves.
- The upload mechanism changes: `apple-actions/upload-testflight-build@v5` is replaced by codemagic-cli-tools' `app-store-connect publish --whats-new …`, which uploads, waits for the build to become discoverable in App Store Connect, and attaches the note in one invocation — no resurrected resolve/retry script. No `--testflight`/submit flag is passed: upload + note only, no beta-review submission, no group changes.
- Failure posture is unchanged: no `continue-on-error` — a failed upload, discovery timeout, or note failure is a visibly red (but non-gating) `ios-deliver` run.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `ios-testflight-delivery`: the "Cloud-managed code signing" requirement pins the upload tool (`Apple-Actions/upload-testflight-build` + its "official Apple action" scenario) — rewritten to the codemagic `publish` invocation. A new requirement is added: every delivered build carries a "What to Test" note identifying its source change (PR title, PR number, short SHA; head-commit-subject fallback).

## Impact

- `.github/workflows/ios.yml` — `ios-deliver` job only: drop the upload action, add a PR-title lookup step and the `pip install codemagic-cli-tools` + `publish` step. No change to `ios-build`/`ios-test`, signing, permissions (`contents: read` suffices for the public-repo PR lookup), or the required-checks surface.
- `CLAUDE.md` — the "`main` uploads to internal TestFlight" section gains a line describing the note.
- No app code, no Gradle modules, no new secrets or credentials (the publish step uses the same ASC Admin key already in the job).
