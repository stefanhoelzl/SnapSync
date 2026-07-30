## Why

The committed raw captures can reach the App Store listing **only** through
`appstore-screenshots.yml`, which fires on a push to `main` touching `screenshots/**` or
`metadata/**`. Its upload script resolves the target itself and accepts only a version in
`PREPARE_FOR_SUBMISSION` / `DEVELOPER_REJECTED`; with no such version it concludes **green**
having uploaded nothing. That gate is correct — but it leaves a hole at exactly the moment a
release happens.

Today the app has one version record, `0.1`, in `READY_FOR_SALE`. So merging a capture refresh
is a green no-op. When `ios-appstore-promote.yml` later find-or-creates the *next* version
record, **nothing re-runs the upload** — promote does not touch screenshots, and
`appstore-screenshots.yml` has no `workflow_dispatch` and no push to react to. The refreshed
raws are then simply absent from the version that ships, and every run involved is green. The
failure is invisible in CI and only observable on the storefront after the fact.

The workflow that *creates* the editable version is the one place that can close this without
guessing: at that moment the target exists, by construction.

## What Changes

- `ios-appstore-promote.yml` composites the committed raws and uploads them to the version
  record it just attached the build to — after the attach, **before** the submit gate, so a
  version that gets submitted carries the current screenshots.
- It reuses `.github/scripts/asc_screenshots_upload.sh` **unchanged**. The script resolves the
  editable version itself, which by that point in the job exists; the same script therefore has
  two callers and one behaviour.
- The promote job installs `imagemagick` + `fonts-liberation`, matching what
  `appstore-screenshots.yml` installs for the same composite step.
- **BREAKING (operator-facing):** `appstore-screenshots.yml` is **deleted**. Its remaining value
  was correcting an *already-prepared* version — which the release cannot do, because a promote is
  single-shot per version (the `vX.Y` tag is pushed on success and the tag-absent guard refuses a
  re-run). Those corrections are done **manually in the ASC console** by decision. A merge to
  `main` therefore uploads nothing to the store; the next release is what carries the raws.
- Promote keeps `cancel-in-progress: true`. The upload's `--replace` is destructive, but its
  target is a `PREPARE_FOR_SUBMISSION` version, which is never on the storefront: a cancelled
  run can leave that unpublished version's set partial, and the next run restores it. No public
  set is ever partial. (Operator decision; recorded in design.md with the alternative.)

Rejected, and why (detail in design.md):

- **Merging `appstore-screenshots.yml` into `appstore.yml`** — blocked twice. That file hosts
  `appstore-metadata-validate`, a required status check that must keep its unfiltered trigger or
  merges freeze; and its run-scoped `cancel-in-progress: true` cannot be opted out of per-job,
  so it would cancel a destructive `--replace` mid-flight.
- **Auto-creating the next version record from a screenshots push** — contradicts
  `ios-appstore-metadata`'s explicit "SHALL NOT create a version", and relocates the silent
  failure rather than removing it: the version would be a guess, orphaned the moment the
  `Config.xcconfig` floor is bumped for a major.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-appstore-release`: the promote workflow gains a screenshot upload — a new obligation
  between attaching the build and the submit gate, and a second (non-Apple-toolchain) tool
  dependency in that job.
- `ios-appstore-metadata`: the screenshot set's writer **moves** from a push-triggered job to the
  release. "The repo is the source of truth for the screenshots" is now enforced at release time
  instead of on input change, and the requirement that the upload runs only when its inputs change
  is **removed** with the job it described.

## Impact

- `.github/workflows/ios-appstore-promote.yml` — two new steps (tool install, upload).
- `.github/scripts/asc_screenshots_upload.sh` — no change; its caller moves.
- `.github/scripts/compose_screenshots.sh` — no change; still reached via the upload script.
- `.github/workflows/appstore-screenshots.yml` — **deleted**.
- `CLAUDE.md` — the screenshot runbook says a merge ships the listing; it no longer does.
- `site-deploy.yml` is **unaffected**: it triggers on `screenshots/**` itself, so the landing page
  still rebuilds on merge. Only the store consumer moves to release time.
- No new secret, no new credential: promote already runs `ubuntu` with the Admin ASC key and the
  pinned `asc`. Still no Apple toolchain in the job.
- Runtime: one `apt-get install` plus a composite and upload added to a dispatched release run.
