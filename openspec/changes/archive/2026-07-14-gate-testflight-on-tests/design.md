## Context

On 12 Jul 2026, `ios.yml` run **#297** on `main` (commit `40a6ee2`) concluded with:

| job | conclusion | consequence |
|---|---|---|
| `ios-build` | **success** | exported the IPA and **uploaded build 297 to TestFlight** |
| `ios-test` | **failure** | `create_event_lifts_the_setup_gate[iosSimulatorArm64]`, `19 tests completed, 1 failed` |

Testers received a build from a commit whose test suite was failing. No step misbehaved — the jobs are
independent by design, and the delivery steps live inside `ios-build`, which never looks at `ios-test`.

**Why the merge gate did not catch it.** It was never asked to. `.github/rulesets/main.json` *does* require
`ios-test`, and it did its job: `ios-test` was **green** on the PR head (`eca0cd3`) when the PR merged. The
tree that then failed on `main` is **byte-identical** to it — same tree hash `9679731…`, nine minutes apart.
The failing test was **flaky**, not regressed. (Root cause: the test awaited a predicate — "left the create
layer" — strictly weaker than the value it asserted, so it could sample the intended transient
`Joined(SyncHealth.Loading)` frame that exists while the sync snapshot's asynchronous first read lands. It
is fixed in this PR's first commit, reproduced under CPU starvation on a `macos-26` simulator: 3 failures in
20 loaded runs vs. 0 in 30 unloaded, then 30/30 green with the fix.)

So there are two separable facts, and conflating them would produce the wrong fix:

1. **This particular red was a flake.** Fixing the test removes *this* red.
2. **Delivery does not consult the test suite at all.** Fixing the test does not touch this. A red `main`
   from *any* cause — a semantic conflict between two individually-green PRs, a genuinely broken merge, a
   different flake tomorrow — still ships. The gate hole is independent of what reddened the tests.

This change addresses (2). Only (2).

## Goals / Non-Goals

**Goals**

- A red `ios-test` on `main` must prevent the TestFlight upload for that commit.
- Keep the two merge gates parallel: neither may wait on the other.
- Keep the device app compiled **exactly once** per push (an existing `ios-ci` requirement).
- Keep "delivery flakiness never blocks merges" true — and stop delivery failures from being silent.

**Non-Goals**

- *Retry / flake tolerance.* Explicitly rejected. A retry would have hidden precisely this bug: a test
  asserting a settled value against an unsettled frame. Flakes are fixed, not re-rolled.
- *Alert-instead-of-block.* Considered and rejected: a notification still lets the bad build reach testers'
  devices. The point is to not ship it.
- *Changing what the tests cover, or the app itself.* No product code changes here.
- *Making `main` runs uncancellable.* Noted below as a known, accepted gap.

## Decisions

### D1 — Delivery becomes its own job, `needs: [ios-build, ios-test]`

`ios-deliver` runs on `main` only and depends on **both** gates. If either is red it does not run, and
nothing is uploaded. Gating is therefore **structural** — a property of the job graph, checkable by
inspection — rather than a conditional inside a job that would have to query another job's result.

**Rejected: `needs: ios-test` on `ios-build`.** The one-line alternative, and it is a trap on two counts:

1. It **serializes the two gates on every push**, roughly doubling gate wall-clock, for a dependency that
   only matters on `main`.
2. Worse, a red `ios-test` would cause `ios-build` to be **skipped** — and a skipped job posts **no status
   check**. `ios-build` is a *required* check, so it would never be reported and **merges would freeze**
   whenever a test failed. It also destroys information: when the tests are red you can no longer see
   whether the device app even compiles.

**Rejected: gate the delivery *steps* inside `ios-build`** by querying `ios-test`'s conclusion via the API.
Polling another job from inside a job is a race and a maintenance liability; the job graph already
expresses exactly this dependency.

### D2 — Hand the **archive** across, not the IPA; the gate job stops talking to Apple

`ios-deliver` needs something to deliver, and re-archiving is forbidden (the compile-once requirement, and
it would double the expensive part of the run). Two shapes were possible:

- **(A) `ios-build` exports the IPA**, publishes that small artifact; `ios-deliver` only uploads.
- **(B) `ios-build` publishes the `.xcarchive`**; `ios-deliver` imports certs, exports, and uploads. ✅

**(B) chosen.** Under (A) the export stays welded to the gate job, which means the gate job still talks to
Apple and an export failure still has to be suppressed with `continue-on-error` to avoid reddening the
gate — preserving the exact silent-failure defect we are trying to remove. Under (B) `ios-build` is a
**pure gate**: archive, publish, done. Everything that can fail for Apple-side reasons lives in a job that
is allowed to fail. The cost is a larger artifact and a duplicated cert import (seconds); the benefit is
that the decoupling stops being a convention and becomes the shape of the pipeline.

The compile-once requirement holds: `ios-deliver` re-signs and packages an already-compiled archive. It
needs no Java, Gradle, or Konan setup at all.

### D3 — `tar` the archive across the artifact boundary

`actions/upload-artifact` zips its payload and does **not** preserve executable bits or symlinks. A signed
`.app` restored through that round-trip is corrupt: the main binary loses `+x`, and the code signature no
longer validates, so `xcodebuild -exportArchive` fails (or, worse, produces an IPA that dies at launch).
Packing with `tar -czf` before upload and unpacking after download preserves the modes, so the archive
crosses the job boundary intact. This is the one genuinely new mechanism in the change and the only part
not provable by inspection alone.

### D4 — Delete `continue-on-error`; let delivery fail red

The old `continue-on-error: true` on export/upload existed to protect the **merge gate** — the steps were
inside the gate job, so a delivery flake would otherwise have reddened a required check and blocked merges.

Once delivery lives in its own `main`-only job that posts **no required status check**, it **cannot block a
merge no matter how it concludes** — the commit is already merged, and no ruleset waits on it. The
protection is now structural, so the suppression is not merely unnecessary, it is harmful: it was making a
failed TestFlight upload conclude the run **green**. Removing it means a broken delivery is **red and
obvious**. This strictly improves on the old behaviour, whose comment claimed the failure was "still
visible in the run" — true only if you open the run and read the individual steps.

### D5 — `ios-deliver` must never become a required check

Recorded because it is a live foot-gun and the failure mode is total. `ios-deliver` is guarded by
`if: github.ref == 'refs/heads/main'`, so on a pull-request branch it **never runs** and therefore **never
posts a status check**. GitHub does not treat a never-posted required check as passing — it waits for it
forever. Adding `ios-deliver` to `.github/rulesets/main.json` would freeze **every** merge in the
repository, permanently. The required contexts stay `build`, `ios-build`, `ios-test`. This is now written
into `ios-ci` as a requirement so the rule survives the next person who reads the ruleset and notices the
"missing" job.

### D6 — Known gap: `cancel-in-progress` still applies to `main`

`concurrency.cancel-in-progress` is `true` for all refs, so a rapid second merge to `main` cancels the
first commit's in-flight run — and with delivery now waiting on **both** gates, the window in which a
`main` run can be cancelled before it delivers is **wider** than before. A merged commit can therefore
never reach TestFlight.

Accepted, deliberately, rather than fixed here: merges to `main` are serialized by the PR queue, so the
window is narrow; build numbers are monotonic; and the next merge ships a superseding build that contains
the skipped commit anyway. The one-line fix — `cancel-in-progress: ${{ github.ref != 'refs/heads/main' }}`
— is available if a commit is ever observed to be silently skipped. Noted so that observation is
recognised rather than re-investigated from scratch.
