## Context

`port-contracts` gives every clause one of five outcomes. Two paths judge them on CI:

| path | hosts | fails on today |
|---|---|---|
| `verify` (`Runner.kt`), one `@Test` per (contract, binding) | `JVM`, `IOS_SIM_KEXE` — every `Fake`, the JVM `Live` bindings, every `Replay` | `Failed`, `Diverged` |
| `scripts/sim-contracts`, the `ios-contracts` job over the rig | `IOS_SIM_APP` | `Failed`, `NotWithin`, refusal, empty registry |

The spec states the first list in "A port contract is a hand-written list of clause values, and the code is its
specification" and a different one — `Failed`, refusal, empty registry — in "In-app hosts CI can reach are run live
over the rig". "Outcomes are explicit and none is silent", which defines the vocabulary, says nothing about which
outcomes fail.

The `verify` rule dates from `changes/archive/2026-09-22-establish-port-contracts` D5, written when `NotWithin`
was "expressible; unused in this change". The contracts that now produce it:

- `DiagnosticsReporterContract` — wire clauses wait up to 45 s on the loopback ingest (`IOS_SIM_KEXE`, `Live`);
  the in-memory binding (`JVM`, `Fake`) throws `WaitExpired(0)` when the fake has not delivered.
- `BackgroundTransferContract`, `DownloadTransportContract` — `awaitWithin`, 20 s, against the loopback fixture
  (`IOS_SIM_APP`, `Live`) and the world's transfer doubles (`JVM`, `Fake`).
- `LinkOpenerContract`, `SharePresenterContract` — `withinRealTime`, 10 s, on UIKit's main-queue completion
  (`IOS_SIM_APP`, `Live`; `LinkOpener` also recorded on `IOS_DEVICE_APP` and replayed on `IOS_SIM_KEXE`, and
  bound to its inert fake on `JVM`).

## Goals / Non-Goals

**Goals:**
- One rule for which outcomes fail a run, stated once in the spec and obeyed by both CI paths.
- A clause whose wait expires turns the build red, whatever the binding.

**Non-Goals:**
- Changing any bound. Nothing measured ends `NotWithin` today (see Risks).
- Reclassifying a replay's or a fake's expired wait into another outcome.
- Judging the live outcomes a device recording carries in its header. A recording is taken by an operator and
  committed deliberately; what gates it is the replay on every build, which this change already makes strict.
- Any other `port-contracts` mechanism change, and the Sentry `413` queue question (F2).

## Decisions

### D1. `NotWithin` fails a run on every binding kind

A wait that expired established nothing about the clause: the implementation neither held nor broke it within
the bound. Across the three binding kinds that reads differently, and fails in every case:

- **`Live`** — on a host CI runs, the clause ran nothing; counting it as a pass is the hole this change closes.
  The simulator-app job already applies this reading.
- **`Replay`** — replay answers from a recording, with no real clock of consequence behind it, so an expired
  wait means the replay itself is broken (a recorded answer that is never delivered). That is a fault.
- **`Fake`** — a fake answers synchronously or through the world's operator actions; an expired wait means the
  fake did not deliver what the clause required — which the in-memory `DiagnosticsReporter` binding reports as
  `WaitExpired(0)`. Passing it would let a fake quietly stop satisfying its contract, the exact failure
  `port-contracts` exists to stop.

*Alternatives:*
- **Fail only on `Live` bindings on hosts CI runs.** Leaves the fake hole above open, and needs `verify` to
  branch on binding kind for a rule with no case where passing is right.
- **Rename a replay's `NotWithin` to `Diverged`.** Both fail now, and `NotWithin(T)` says where it broke —
  a delivered-answer gap, not an unrecorded call — which `Diverged`'s remedy (re-record) would misdirect.
- **Keep the outcome non-failing and rely on bounds.** A bound can only turn a slow platform into a pass; it
  cannot turn a platform that never answers into a failure.

### D2. The rule is stated once, in "Outcomes are explicit and none is silent"

That requirement owns the vocabulary, so it owns what each outcome does to a run. The two requirements that
carried their own lists now refer to the rule instead of restating it: a restated list is how the three current
lists came to disagree.

*Alternative:* add `NotWithin` to each existing list. Fixes today's drift and keeps three copies to drift again.

### D3. Land before phase 6b

6b adds stage-chained waits across the extension's `process()` calls. Landing this first means its waits are
bounded knowing an expiry is red, instead of discovering the rule when a later merge tightens it under them.
The change is one line of runner code and a spec delta, so it does not hold 6b up.

## Risks / Trade-offs

- **[A real-clock wait flakes on a loaded shared CI runner and turns the build red]** → Measured 2026-09-23:
  zero `NotWithin` in every JVM `verify` binding (run with this rule applied) and in the last two
  `ios-contracts-evidence` artifacts, where the rule already held. The `IOS_SIM_KEXE` bindings are unmeasurable
  on Linux and are measured by this change's own CI run. A clause that flakes has its bound raised, per clause,
  with the measurement in the PR — never the rule relaxed.
- **[The Sentry wire clauses' 45 s bound is the tightest unmeasured one]** → It sits inside `runTest`'s one-minute
  timeout by design; if this change's CI run shows it expiring, that is either the regression the hole hid or a
  bound to raise, and the PR says which.

## Migration Plan

None: no persisted format or production code changes. Rollback is reverting the runner line and the delta.
