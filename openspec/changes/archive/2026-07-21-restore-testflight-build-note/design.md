## Context

The TestFlight "What to Test" note died as collateral of removing the alpha promotion: the note was set by the deleted `ios-promote` job (`app-store-connect builds add-beta-test-info … --whats-new "$(git log -1 --format='%s') (${GITHUB_SHA:0:7})"`, resolved via the retry loop in the deleted `testflight_promote.py`). `ios-deliver` today only uploads (`apple-actions/upload-testflight-build@v5`) and sets no build metadata, so every build since 2026-07-19 shows an empty note in the internal `development` group.

Constraints that shape the fix:

- The repo is **rebase-merge only** (`squashMergeAllowed: false`, `mergeCommitAllowed: false`), so a PR can land as several commits and the head-commit subject may be a trailing docs/test commit rather than the substance of the landing.
- A freshly uploaded build is **not immediately discoverable** in App Store Connect — any post-upload metadata call needs a bounded wait.
- `ios-deliver` is deliberately **non-gating but never silently failing** (spec requirement "Delivery never blocks merges, and never fails silently").
- `ios.yml` grants `permissions: contents: read`; the job already holds the Admin ASC key.

## Goals / Non-Goals

**Goals:**

- Every delivered build's TestFlight note identifies its source change: PR title, PR number, short SHA.
- No new credentials, no new required checks, no change to `ios-build`/`ios-test` or the signing path.
- No bespoke retry machinery in the repo — the find-build wait is owned by the tool.

**Non-Goals:**

- Suppressing the per-build internal-tester notification (the `silence` half of the deleted script). The spec documents that acceptance; folding it in here is scope creep with no ask behind it.
- Any change to external distribution — the App Store promote channel (`ios-appstore-release`) is untouched.
- Backfilling notes on already-delivered builds.

## Decisions

**D1 — Replace the upload action with codemagic `publish` rather than bolting a resolve step after it.**
`app-store-connect publish --path … --whats-new …` uploads, waits for the build to become discoverable (`--max-find-build-wait`), and attaches the beta test info in one invocation. The alternative — keep `apple-actions/upload-testflight-build@v5` and resurrect the `resolve`-with-retry half of `testflight_promote.py` plus a separate `add-beta-test-info` call — is additive and lower-risk to the upload path, but permanently owns retry code the tool already ships, and splits one logical operation ("deliver an identified build") across two tools. codemagic-cli-tools is already the repo's blessed ASC client (CLAUDE.md portal-chores section; the old note step used it too). **No `--testflight` or submit flag is passed**: plain publish uploads and sets the note — no beta-review submission, no group assignment.

**D2 — Note content is `<PR title> (#<num>, <short sha>)`, falling back to the head-commit subject.**
The PR is resolved with `GET repos/{repo}/commits/{GITHUB_SHA}/pulls`, which associates rebase-merged commits with their PR; the run's default `GITHUB_TOKEN` covers this read on a public repo (no permissions-block change). Branch protection forbids direct pushes, so the fallback (`git log -1 --format='%s'`) should never fire — it exists so a missing association degrades the note, never the delivery. The old subject-only format was rejected because under rebase-merge it names the last commit, not the landing.

**D3 — The title crosses into the shell only via an env var.**
PR titles are arbitrary text (quotes, backticks, `$`). The lookup step writes the composed note to `$GITHUB_ENV`; the publish step passes `"$WHATS_NEW"`. The title is never interpolated into a command line by the workflow templating engine (`${{ }}` inside `run:` is script injection by construction).

**D4 — Failure stays red.**
No `continue-on-error` anywhere in the new steps, consistent with the existing requirement: a discovery timeout or note failure is a visibly red, non-gating run. The alternative (best-effort note) reintroduces exactly the silent-failure pattern the job's shape was built to kill.

## Risks / Trade-offs

- [`publish` behaves differently from the Apple action on `macos-26` (upload tooling availability/behavior)] → Consciously accepted first-run risk instead of a pre-spike: `ios-deliver` is non-gating, so the worst case is one visibly red delivery and a same-day revert to the action. The current action drives the same underlying upload machinery on the same runner image, so the runner demonstrably supports it.
- [The find-build wait extends `ios-deliver` by up to `--max-find-build-wait` (default 10 min) on a slow ASC day] → Public repo ⇒ runner minutes are free; the job posts no required check, so latency blocks nothing.
- [PR-title lookup returns nothing (e.g. API flake) and the fallback subject is a trailing docs commit] → Degraded note, correct delivery; acceptable because the SHA is always present and authoritative.
- [`pip install codemagic-cli-tools` adds a network dependency to the job] → Same dependency the deleted `ios-promote` carried for months; a failed install is a red run, not a silent skip.
