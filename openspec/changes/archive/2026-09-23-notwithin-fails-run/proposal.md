## Why

A contract clause whose bounded wait expires reads `NotWithin(T)`, and on every CI path but one that outcome
passes the run: `verify` fails only on `Failed` or `Diverged`, so a regression that stops a platform callback
outright — the Sentry reporter no longer delivering, a transfer that never completes — leaves the clause timing
out and CI green (`changes/archive/2026-09-23-diagnostics-reporter-contracts`, Open Questions). The rule was
written when `NotWithin` had no user (`changes/archive/2026-09-22-establish-port-contracts`, D5: "expressible;
unused in this change") and was never revisited as three contracts started waiting.

Meanwhile the simulator app's CI job already fails on `NotWithin` (`scripts/sim-contracts`: "a bounded wait on
an OS callback that expired ran nothing: on a host CI runs live, that is not coverage"), while the requirement it
implements lists only `Failed`, refusals, and an empty registry. So the same outcome fails one path and passes
the others, and the spec agrees with neither exactly.

Now, because phase 6b (upload-job contracts) is adding stage-chained waits across the extension's `process()`
calls; landing first means those waits are born under the rule.

## What Changes

- A run fails on any `Failed`, `Diverged`, or `NotWithin` outcome, on **every** binding kind (`Live`, `Replay`,
  `Fake`) and on every runner — the CI `verify` entry point and the simulator app's CI job alike. `NotRunHere`
  alone still never fails a run; whether it is admissible remains the contract-coverage gate's question.
- The spec states that rule once, beside the outcome vocabulary, and the two requirements that each carry a
  list of failing outcomes are aligned to it.
- `verify` counts `NotWithin` among the outcomes that fail it; its KDoc states why.
- No bound changes. Measured on 2026-09-23: zero `NotWithin` across every JVM `verify` binding (run with the
  stricter rule applied) and across the two latest `ios-contracts-evidence` artifacts (runs 35875448036,
  35869298870). The `IOS_SIM_KEXE` bindings (the Sentry reporter's live wire clauses, the `LinkOpener` and
  `BackgroundScheduler` replays) cannot be measured on Linux and are measured by this change's own CI run.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `port-contracts`: "Outcomes are explicit and none is silent" gains the rule for which outcomes fail a run;
  "A port contract is a hand-written list of clause values, and the code is its specification" and "In-app hosts
  CI can reach are run live over the rig" stop carrying their own, divergent lists of failing outcomes.

## Impact

- `test/contracts/src/commonMain/kotlin/app/snapsync/contracts/Runner.kt` — `verify` and its KDoc.
- `scripts/sim-contracts` — no behaviour change (it already fails on `NotWithin`); its header comment cites the
  requirement it now matches.
- Every `verify` binding on JVM and `IOS_SIM_KEXE`: a clause that ends `NotWithin` now turns the build red.
- The `upload-job-contracts` workspace (phase 6b) is told the rule, so its new waits are bounded with it in
  mind.
