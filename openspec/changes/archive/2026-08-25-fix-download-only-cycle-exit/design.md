## Context

`UploadCycle.run()` is the choke point every trigger on every tier funnels through. Its opening
sequence is, today:

```
entry gate (Skip / NotJoined / Run)
  walkFloor ?: { log.e("no capture floor"); return SKIPPED }     ← taken by EVERY download-only cycle
  if (!enumerates) { log.i("contributes nothing"); return SKIPPED }  ← unreachable
  reconcile()                    🌐 network
  Phase 1  fetchRetryJobs()
  Phase 2  fetchAckJobs()        ← discharges the OS's acknowledgement obligation
  Phase 3  discoverResources()   ⚠ the walk the gate exists to prevent
           onDiscovery()         ⚠ device manifest
           onBatchUploaded()     ⚠ notify
```

Two independent defects live in those seven lines.

**The inversion.** `SelectionPolicy.None` carries no rules, so `walkFloor` is `null` for it. The guard
above the direction check therefore absorbs the download-only case, and the comment three lines up
("`None` never reaches here (it returned above)") describes the code as it was intended, not as it is.
The `enumerates` branch is dead. Both branches return `SKIPPED` having touched nothing, which is
precisely why `UploadCycleTest`'s two download-only tests pass while running through the wrong one.

**The obligation.** Phase 2's comment justifies sitting behind the direction gate:

> Skipping the acknowledgement pass is safe on both tiers: on iOS ≥26.1 the OS presents no jobs because
> `stop()` deregistered the extension.

That premise holds on the join and leave paths, where `UploadArm` runs `stopAll()`. It does **not** hold
on the reconfigure path: `ReconfigureEvent` deliberately declines to call `armUpload()` when the new
direction excludes upload, so no arm verb fires and the registration survives. The arm has exactly three
entry points — `Provision`, `stopUploads`→`onLeave`, and the permission collector (which no-ops for a
download-only membership) — and a reconfigure-off reaches none of them.

Measured on device (SE2, iOS 26.6, `GRANTED`, PhotoKit tier), varying only whether `stop()` runs:
deregistration → clean; no deregistration → `com.apple.photos.error Code=50008`, the outstanding jobs
discarded, and iOS recording a failed attempt against the upload-job configuration with a ~300 s
backoff that escalates. The full evidence is in the proposal.

Constraints that shape every decision below:

- `upload-lifecycle` requires the direction gate at the choke point: **no upload job created and no
  device manifest written** for a non-contributing membership, at any trigger, on any tier.
- The walk is the expensive thing — one synchronous PhotoKit round-trip per asset (~110 ms on an SE2).
  `the_gate_precedes_the_reconcile_and_the_walk` exists to keep a non-contributor out of it.
- `crash-reporting` maps `Error`/`Assert` to events. Severity is therefore a product decision, not a
  logging preference.
- `:app:*` Kotlin is wiring-only and untested, so every decision here must land in `:domain` or an
  adapter.

## Goals / Non-Goals

**Goals:**

- Make "an admitting policy with no capture floor" impossible to construct, rather than a state each
  consumer guards against — and delete the accessor that made the two cases confusable.
- Report a download-only skip as the routine event it is, so it never becomes a crash report.
- Discharge the OS's acknowledgement obligation on every cycle that the OS invoked, regardless of the
  membership's direction, without weakening the choke-point guarantee.
- Make one cause arrive as one Bugsink issue.
- Leave `reconfigure-membership`'s drain requirement stating something the code actually does.

**Non-Goals:**

- Changing *what* a download-only membership contributes. It contributed nothing before and contributes
  nothing after; no job, no manifest, no notify, no walk.
- Deregistering the extension on a reconfigure-off. That would cancel in-flight work the drain
  requirement deliberately preserves; this change makes the *cycle* discharge its obligation instead.
- Rewriting producer resolution. `collapse-upload-tier-seam` owns that, and this change sequences
  behind it.
- Building rate-limiting or dedupe into the reporting seam. Measured: 62 events in a month,
  `stored == digested` on every issue, and eight of nine `Error` call sites already separate routine
  from exceptional correctly. The mechanism is unbounded but has never materialised; the one violation
  is fixed here directly.

## Decisions

### D1 — `Admitting` carries the cutoff; the rule is derived

```kotlin
data class Admitting(
    val cutoff: CaptureCutoff,
    val rest: List<SelectionRule>,
) : SelectionPolicy {
    val rules: List<SelectionRule> = listOf(SelectionRule.CaptureAfter(cutoff)) + rest
}
```

One source of truth: the `CaptureAfter` rule is derived from `cutoff`, so the two cannot drift. Data
class equality keys on the two constructor parameters, which is correct. `from()` stores the parameter
it already receives verbatim; `excluding()` becomes `Admitting(cutoff, rest + extras)`.

*Alternative — store the rule (`floor: SelectionRule.CaptureAfter`).* Same guarantee, and it survives
`CaptureAfter` gaining a field without touching the derivation. Rejected because every reader wants the
domain value: `InMemoryCandidateSource` reads `.at.iso`, `OwnDeviceGalleryStatusSource` passes the
cutoff into `albumExcludedAssetIds` and logs it, `UploadCycle` hands it to discovery. Storing the rule
adds a `.cutoff` hop at every read to save one edit at a single derivation site.

*Alternative — keep `Admitting(rules)` and `init { require(rules.any { it is CaptureAfter }) }`.*
Smallest diff, but it is a runtime check: the state stays representable and fails at construction rather
than at compile time, which is not what "unrepresentable" means.

### D2 — `walkFloor` is deleted, and both consumers become an exhaustive `when`

`walkFloor: CaptureCutoff?` is the defect's shape, not an incidental accessor: it answers "what is the
floor" with a `null` that means *either* "this membership contributes nothing" *or* "this policy is
malformed", and a caller can branch on it before checking direction. That is exactly what happened.
Keeping it — even with `Admitting.cutoff` non-null — leaves the conflation one hop from the site being
fixed.

Both consumers instead exhaust the sealed type:

```kotlin
val cutoff = when (configPolicy) {
    SelectionPolicy.None -> { log.i { "…contributes nothing…" }; return CycleResult.SKIPPED }
    is SelectionPolicy.Admitting -> configPolicy.cutoff
}
```

`OwnDeviceGalleryStatusSource` is converted too. Its `!enumerates || cutoff == null` is *correct* today
(it checks both together), but the second disjunct becomes provably dead, and a dead null-check sitting
beside the bug we just fixed reads as though the null were still reachable.

`enumerates` survives — `EventPhotoSet` and `ShareableCount` still use it, and neither can reach the
inverted-order mistake because neither has a floor to read.

### D3 — The terminal-job pass moves ahead of the direction gate; nothing else does

```
entry gate
Phase 2′  fetchAckJobs() + acknowledge + ledger settle   ← no walk, no manifest, no network
DIRECTION GATE ─────────── None → SKIPPED
reconcile / Phase 1 retry / Phase 3 walk / manifest / notify
```

Acknowledging a job the OS **already presented** creates nothing, writes no manifest, enumerates no
library and issues no network call. Every `⚠` the choke-point requirement names stays behind the gate,
so the guarantee is unchanged in substance; what changes is that the cycle stops owing the OS a debt it
refuses to pay.

*Alternative — move the whole gate below Phase 2 (after the reconcile and retry pass).* Rejected: it
would make a download-only membership perform a network round-trip on every foreground forever, and
`the_gate_precedes_the_reconcile_and_the_walk` forbids exactly that, for a stated reason.

*Alternative — leave it and deregister on reconfigure-off instead.* Rejected: `stop()` on the
app-driven tier cancels in-flight transfers, and on the PhotoKit tier `enable(false)` wipes every
in-flight job. Both destroy the work `reconfigure-membership` requires be drained.

*Alternative — do nothing; the OS backoff is self-limiting.* Rejected on the measurement: the penalty
attaches to the upload-job configuration and escalates with an attempt count, so it outlives the
condition, and the outstanding jobs are dropped rather than retried.

The invariant is now stated more precisely than "nothing below this line runs": a non-contributor
starts no new work, and settles what it already started. `upload-lifecycle` must say so explicitly,
otherwise the requirement and the code disagree in a way a reader cannot resolve.

### D4 — The ambient log context leaves the event message and becomes a tag

In `SentryLogWriter`, capture the bare redacted message and set the entry point as a **scope-local**
tag, on both the message and the exception path:

```kotlin
val withCtx: (Scope) -> Unit = { s -> if (ctx != null) s.setTag("entry_point", ctx) }
if (throwable != null) Sentry.captureException(throwable, withCtx)
else Sentry.captureMessage(redactUuids(message), withCtx)
```

The `[ctx]`-prefixed text still goes out as the preceding error breadcrumb, so the event's own trail is
unchanged. Both overloads exist in sentry-kmp 0.27.0 (`captureMessage(String, ScopeCallback)`,
`captureException(Throwable, ScopeCallback)`, `Scope.setTag`) — read from the klib, and settled by the
compile, per the project's own rule about symbol tables.

Cost, stated plainly: the issue title loses the entry point. It moves to a filterable tag, and it stays
in the breadcrumb.

*Alternative — set `event.fingerprint` in `beforeSend`.* Keeps the title intact and pins grouping
explicitly rather than inheriting it from the message. Rejected because `fingerprint` lives on
`SentryEvent`, reachable only from `beforeSend`, which sees the final string and would have to re-parse
the `[…] ` prefix back out — putting the fix in a different file from the code that added the prefix.

Applying the tag on the exception path too costs nothing (exceptions group by stacktrace) and means one
rule rather than two.

### D5 — Severity discipline is a review question, not a mechanism

The correct pattern already exists three lines below the bug, at `UploadCycle:205`: the **expected**
condition (the rejoin listing failed or timed out) returns `false` and logs at `Info`; only an
**unexpected throw** logs at `Error`. `UploadCycle:187` is the single site in the tree that violates it.

So no dedupe window, sampling, or suppression is introduced. A suppressed error is the silence
`crash-reporting` exists to break, and the realized volume does not justify the machinery. What is
adopted instead is a question to ask at review — *does this `.e` fire on a machine-repeated path, and
can its cause be persistent?* — and an expiry: revisit if a single issue's event count reaches the
hundreds, or if Bugsink ever reports `stored < digested`.

## Risks / Trade-offs

- **Moving the acknowledgement pass ahead of the gate weakens a one-line invariant.** "A
  non-contributor does nothing" becomes "a non-contributor starts nothing and settles what it already
  started." → Mitigation: state it in `upload-lifecycle` explicitly, with the reason, rather than
  leaving the code and the spec to disagree. A test pins that a download-only cycle acknowledges
  presented jobs and still creates none, writes no manifest, and never enumerates.

- **A download-only cycle now touches the platform where it previously returned immediately.** →
  Mitigation: `fetchAckJobs` on the app-driven tier drains an in-memory list; on the PhotoKit tier it
  reads jobs the OS is already holding open for this invocation. Neither enumerates the library. The
  cursor still never advances for a non-contributor.

- **The reconfigure trigger itself is unmeasured.** The device evidence reached the same cycle state by
  voiding the membership, because `:test:rig` excludes `onReconfigure`. → Mitigation: the link is an
  exhaustive read of the arm's three entry points, and the OS's observable is identical either way
  (`process()` returned without acknowledging). Wiring `onReconfigure` into the rig is the honest
  follow-up; the exclusion's stated reason — "no scenario needs it driven" — no longer holds.

- **Evidence is n=1**: one device, iOS 26.6, one job batch per arm. → Mitigation: the failing arm
  reproduced twice, on two builds, with 20 and 3 outstanding jobs respectively, each producing exactly
  one 50008. Re-measure at the next iOS major.

- **Dropping the entry point from the issue title changes triage ergonomics.** Four issues collapsing
  into one is the point, but the prefix did make "four different triggers, one gate" visible at a
  glance. → Mitigation: the tag is filterable and the breadcrumb still carries the prefixed line.

- **Spec-delta collision with `collapse-upload-tier-seam`**, which carries its own `upload-lifecycle`
  delta and is close to shipping. → Mitigation: sequence this change after it merges, and rebuild the
  delta block from the *current* spec text rather than from today's.

## Migration Plan

No data migration, no persisted format change, no backend change. `SelectionPolicy` is constructed per
cycle from the stored config; nothing serialises it.

Rollout is an ordinary branch → PR → `/ship` with a single `internal` label — no customer-visible
behaviour changes. Rollback is a revert: the type change, both call sites and the writer change are
independent of any stored state, so a reverted build resumes the old (wrong) behaviour without
corrupting anything.

The one ordering constraint is external: land after `collapse-upload-tier-seam`.

## Open Questions

- Should `onReconfigure` be wired into `:test:rig` as part of this change, or as its own? Wiring it
  would let the true reconfigure path be driven rather than approximated — but the rig's exclusion
  argues an unexercised destructive command is worse than an absent one, and that argument is about
  *drive-by* use rather than a scenario that needs it. Leaning: separate change, so this one stays
  provable without a Mac build.
- Does the OS's attempt count against the upload-job configuration ever reset on its own, or only on a
  successful acknowledgement? The measurement saw `attempt count (1)` and a ~300 s delay; escalation
  and decay were not characterised. It does not gate any decision here — the fix removes the failure
  entirely — but it bounds how bad the current behaviour is for a member already in the penalty box.
- `reconfigure-membership`'s drain requirement is being strengthened to settle what it drained. Is
  settling sufficient, or should a direction-off also be prevented from stranding a *partially*
  uploaded resource set? Out of scope as written; worth naming.
