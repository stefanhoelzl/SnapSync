## Context

`ios.yml` has three merge gates — `ios-build`, `ios-test`, `ios-contracts` — run as parallel jobs with no `needs:`
between them (capability `ios-ci`, "The merge gates are exactly the three parallel jobs"). Delivery is a fourth job,
`ios-deliver`, that runs on a delivering run (a push to `main`, or a `workflow_dispatch`) and declares
`needs: [ios-build, ios-test]`.

That list predates the third gate. `changes/archive/2026-07-14-gate-testflight-on-tests` split delivery out of
`ios-build` precisely so that a red gate stops the upload (run #297 shipped a build whose `ios-test` was red), and it
listed the two gates that existed. `changes/archive/2026-09-23-photokit-contracts` (D8) then added `ios-contracts` as a
third merge gate "with no `needs:` edges, for the reason `ios-ci` gives for the other two" — a statement about edges
*between gates* — and did not touch delivery. Nothing connects the two lists, so the hole #297 exposed reopened for
everything `ios-contracts` checks: PhotoKit under a real full grant, both URLSession transports, the other in-app
contracts, and the all-real journeys against a local `api/`.

The same two-gate wording also sits in the `ios-appstore-release` provenance guarantee ("every promotable build passed
both merge gates"), which is what lets the App Store promote skip re-checking a build's commit.

## Goals / Non-Goals

**Goals:**
- A red `ios-contracts` on a delivering run stops the TestFlight upload, exactly as a red `ios-test` does.
- State the rule as "delivery depends on every merge gate", so the next gate that joins cannot silently miss it.
- Keep the merge gates parallel and independent; `ios-deliver` stays non-required.

**Non-Goals:**
- Merging or sharing work between `ios-test` and `ios-contracts` (discussed separately; the gates stay three jobs).
- Speeding up or de-flaking `ios-contracts`. Its latency and flake rate become delivery's; see Risks.
- A mechanical guard (e.g. a test asserting `needs:` equals the required-check set). See D2.
- Correcting the pre-existing `ios-appstore-release` wording that `ios-deliver` "uploads a build only on
  `refs/heads/main`" — untrue since the branch dispatch — beyond the one clause this change rewrites. Noted in Open Questions.

## Decisions

### D1 — `needs: [ios-build, ios-test, ios-contracts]`, structural, as in 2026-07-14's D1

The dependency stays a property of the job graph, checkable by inspection, rather than a step that queries check runs.
`needs:` from the delivery job **to** a gate cannot skip another gate's required check, so the rule forbidding edges
between gates is untouched.

*Alternatives:* a status-API check inside `ios-deliver` that waits for `ios-contracts` — rejected, it rebuilds with
polling what `needs:` already expresses; making `ios-contracts` non-blocking for delivery and alerting instead —
rejected for the reason 2026-07-14 gave: an alert still lets the bad build reach testers.

### D2 — The spec states the rule over the set, not the pair; no build-time guard

`ios-testflight-delivery` says `ios-deliver` needs **every** merge gate `ios-ci` names, and that a job joining the
gates joins delivery's `needs:` in the same change. That puts the coupling where the next author reading either spec
meets it.

*Alternative:* a `:test:architecture` test parsing `ios.yml` and asserting `ios-deliver.needs` equals the set of jobs
with no `needs:` that run on every push. Rejected for now: the set changes rarely (twice in the project's life), and
the required-check list it would compare against is live-only on the ruleset, not in the tree. Revisit if a fourth
gate appears.

### D3 — Fix the adjacent two-gate wording in the requirements already being restated

Each MODIFIED requirement is restated whole, so it carries the corrections it touches: the ruleset list in "Delivery
never blocks merges" (it omitted `ios-contracts`), and `ios-ci`'s "runs only on `refs/heads/main`" (it also runs on a
dispatch). The three Purposes and the workflow comments say "both gates" too; they are not requirements, so the tasks
edit them directly.

## Risks / Trade-offs

- [Delivery waits for the slowest gate] → `ios-contracts` took 13–47 min across the last five successful runs, against
  ~13 min for a Release `ios-build`, so a `main` upload lands later. Accepted: internal TestFlight is not on a clock, and
  shipping a build whose journeys are red is the worse outcome.
- [A flaky `ios-contracts` skips that commit's upload] → re-run the failed job; `ios-deliver` then runs. The next merge
  delivers anyway. Retry-on-flake inside the gate stays rejected (2026-07-14 non-goal): flakes are fixed.
- [Cancel-superseded interaction] → a longer path to `ios-deliver` widens the window in which a newer push to `main`
  cancels an in-flight run before it uploads (capability `ios-ci`, "Cancel superseded builds"). The newer commit then
  delivers, which is the existing accepted gap, now somewhat wider.

## Open Questions

- `ios-appstore-release`'s provenance paragraph (Purpose and requirement) says builds are uploaded only on `main`, so
  "every build in the pool is from a merged commit". A dispatched branch build is also uploaded and therefore
  promotable. Out of scope here; worth its own change.
