> **Landed after the spec restructure.** Before this merged, `c19b6c4b` ("specs define user-observable
> outcomes only") deleted `ios-ci`, `ios-testflight-delivery` and `ios-appstore-release` and moved their content
> to `docs/deployment.md`. The deltas under `specs/` record the change as proposed; what merged is the `needs:`
> line in `ios.yml` and the rule in `docs/deployment.md` §4–§5.

## Why

`ios-contracts` became the third iOS merge gate (`changes/archive/2026-09-23-photokit-contracts`), but `ios-deliver`
still declares `needs: [ios-build, ios-test]`. A commit whose in-app port contracts or all-real journeys are red on
`main` — or on a dispatched branch — is therefore still uploaded to TestFlight. That is exactly the hole
`changes/archive/2026-07-14-gate-testflight-on-tests` closed for `ios-test`: delivery that does not consult a gate
ships whatever that gate would have stopped. It reopened unnoticed when the third gate joined, because nothing ties
"is a merge gate" to "is a delivery dependency".

## What Changes

- `ios-deliver` in `.github/workflows/ios.yml` declares `needs: [ios-build, ios-test, ios-contracts]`: a red
  `ios-contracts` on a delivering run (a push to `main`, or a `workflow_dispatch`) stops the TestFlight upload.
- The rule is stated as **delivery depends on every merge gate**, not on a named pair, so the specs say why the
  list is what it is.
- The upload-time provenance guarantee the App Store release relies on (`ios-appstore-release`) widens accordingly:
  a promotable build has passed all three gates, the in-app contracts and journeys included.
- The workflow's header and job comments, and the three capabilities' Purposes, stop saying "both gates".
- No change to the merge gates themselves: they stay three parallel jobs with no `needs:` between them.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `ios-testflight-delivery`: "Delivery gates on the test suite" — `ios-deliver` needs all three merge gates;
  a red `ios-contracts` stops the upload. "Signed device build delivered…" — a dispatch depends on every merge gate.
- `ios-ci`: "The merge gates are exactly the three parallel jobs" — the delivery job depends on all three gates;
  "Build iOS on every push" — the dispatch's safety rests on `ios-deliver`'s dependency on every merge gate.
- `ios-appstore-release`: "A release promotes an already-gated build" — provenance is a pass of all three gates.

## Impact

- `.github/workflows/ios.yml`: one `needs:` line plus the comments that restate it; `.github/workflows/ios-appstore-promote.yml`'s
  provenance comment.
- Delivery latency: `ios-deliver` now starts when the slowest of the three gates finishes. `ios-contracts` is the
  slowest (13–47 min over the last five successful runs, against ~13 min for a Release `ios-build`), so a TestFlight
  upload lands correspondingly later, and a flaky `ios-contracts` failure skips that commit's upload until it is re-run.
- No product code, no branch-ruleset change (`ios-deliver` stays a non-required check).
