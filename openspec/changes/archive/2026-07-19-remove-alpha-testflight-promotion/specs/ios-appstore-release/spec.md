## MODIFIED Requirements

### Requirement: A release only builds a merged, fully-green commit

Before building or uploading, the workflow SHALL verify that the released commit is an **ancestor of
`origin/main`** and that **every REQUIRED check-run on the released commit's SHA concluded `success`** — the
required set derived at run time from the default branch's protection rules, never a hand-kept list —
excluding check-runs produced by the release workflow itself; if either check fails, the workflow SHALL
fail before building or uploading. If the required set cannot be resolved, the guard SHALL degrade in
the strict direction and treat every check-run as required. Because a release may be dispatched from any ref, the ancestor check is what confines a
release to merged code, and it SHALL NOT be skipped.

The self-exclusion SHALL identify the workflow's own check-runs by the **check-suites its runs produced for
that commit**, and SHALL therefore exclude **every** run of the release workflow on that commit, whatever its
state — not merely those still in progress. A release that failed leaves a completed, non-success check-run on
the commit; were that treated as foreign, it would refuse every subsequent attempt and render the commit
permanently unreleasable.

Non-required checks SHALL NOT block a release (changed 2026-07-17, with the introduction of the
red-by-design migration beacon `verify`): `ios-deliver` is not a required check, so a red TestFlight
upload no longer refuses a release dispatch. Releasing a commit whose internal-TestFlight upload failed is
therefore possible; when the internal-TestFlight build of the released commit matters, the operator SHALL
check `ios-deliver` (capability `ios-testflight-delivery`) rather than rely on this guard.

#### Scenario: A release off main is rejected
- **WHEN** a release is dispatched from a ref whose commit is not an ancestor of `origin/main`
- **THEN** the workflow fails before building or uploading

#### Scenario: A release on a commit with a red required check is rejected
- **WHEN** a release is dispatched for a commit on `main` with any non-`success` REQUIRED check-run (e.g. a failed `ios-test`)
- **THEN** the workflow fails before building or uploading

#### Scenario: A red non-required check does not block a release
- **WHEN** a release is dispatched for a commit whose required check-runs are all green while a non-required check-run (the red-by-design `verify` beacon, or a red `ios-deliver`) is not
- **THEN** the workflow logs the ignored check-runs and proceeds

#### Scenario: A release on a fully-green main commit proceeds
- **WHEN** a release is dispatched for a commit that is an ancestor of `origin/main` and whose every required check-run (excluding the release workflow's own) concluded `success`
- **THEN** the workflow proceeds to build, upload and attach

#### Scenario: A previously failed release does not block its own retry
- **WHEN** a release for a commit failed and left a completed, non-success check-run of the release workflow on that commit, and a release is dispatched for that commit again
- **THEN** the green check ignores that check-run and the release proceeds
