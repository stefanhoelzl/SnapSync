## Context

`changes/archive/2026-07-07-add-join-direction-mode` added a join-time participation direction. Its framing:
the direction is *"just a masking layer over three existing gates (producer-enable, reconcile, arrow render),
not a new subsystem."* Two of those three are sound. The first is not, and the reason is dated.

**D3** gated the upload arm at the **invoker**:

> "Under `DownloadOnly` the producer is never enabled, so the OS never invokes the upload extension and the
> in-extension reconciliation never runs — correct, nothing to reconcile for a non-contributor. No extension
> code changes."

That reasoning admits exactly one invoker: the OS. But `changes/archive/2026-07-04-add-url-session-upload`
had merged **three days earlier**, and on that tier there is no extension and the OS invokes nothing — the
**app** drives its own cycle. `SnapSyncRoot.onForeground()` calls the pump gated on `useAppDrivenUpload`, the
iOS version and nothing else. `UploadArm` knows the direction, responds with `producer.stop()`, and `stop()`
cancels transfers and the heartbeat while recording nothing. `UploadCycle` never reads direction;
`ExtensionReconciler` returns `false` only for *no event configured*, and a download-only membership has one.

So on iOS 18–26.0 a member who chose **"Only receive the event's photos — you won't share yours"** uploads
their post-cutoff camera roll on the next foreground.

**D4**, three paragraphs later in the same document, gated the **download** arm at its choke point and
reasoned about precisely this hazard — *"All download triggers (foreground, provision, push, harness) funnel
through `DownloadController.reconcile`… 2 of the 3 iOS call sites are in the untested `SnapSyncRoot`"* — and
rejected per-call-site gating because it *"risks the three sites drifting."* D3 never asked the same question,
because it believed there was one invoker and it belonged to the OS.

**D5** masked the status arrows per direction. Its comment concedes what it is covering: a download-only
device reads "In sync" *"regardless of its un-uploaded gallery."* The upload total `N` is non-zero on such a
device, and the mask hides the arrow over the top.

Two things kept this invisible. `:app:ios` is wiring-only and untested by project rule, so nothing covers
`SnapSyncRoot.kt:518`. And D5's mask silenced the one surface that would have shown the member an upload they
never asked for.

Constraints that shape the fix:

- **Both tiers share exactly one choke point**: `UploadCycle.run()`. `createJob` — the verb that creates an
  upload — is called from two places, both inside `UploadCycle.kt`.
- **`UploadCycle` is constructed per run**, inside `runCycle`, after config is re-read. All eleven
  `photoCutoff` call sites pass `{ constant }`; the `suspend () -> String` lambda wraps a construction-time
  value everywhere.
- **The walk costs ~110 ms of PhotoKit XPC per asset.** Any gate that concludes "contributes nothing" must do
  so before the walk, not within it.
- **iOS ≥26.1 is already correct.** `PhotoKitUploadProducer.stop()` calls
  `setUploadJobExtensionEnabled(false)`, which deregisters the extension and wipes in-flight jobs; the OS then
  invokes nothing. That tier's invoker-gate works because deregistering *is* removing the invoker.
- **`main` is the public alpha channel** — every merge reaches public TestFlight testers silently.

## Goals / Non-Goals

**Goals:**

- A membership whose direction excludes upload creates no upload job and writes no manifest, at every
  trigger, on every tier.
- The gate lives where a new trigger cannot bypass it, and where a tier-neutral test can reach it.
- `N` counts what the cycle admits — the identity `photo-selection-policy` already declares "a requirement,
  not an implementation coincidence."
- A non-contributing device schedules no background work.
- Silent push drives an upload scan, since `BGProcessingTask` is deferred at OS discretion and push is not.
- The record supersedes D3 by name, so the next tier author reads why the invoker-gate failed.

**Non-Goals:**

- Changing iOS ≥26.1. It is correct; `setUploadJobExtensionEnabled(false)` is a real invoker removal.
- Making direction a **per-resource** rule inside the selection filter. It is per-membership and must
  short-circuit before the walk (see D2).
- Backend awareness of direction. Uploads stay ungated server-side; the union stays identity-blind.
- Changing how direction is chosen at join (`join-event` is untouched).
- A Konsist guard for the choke point (see Open Questions).

## Decisions

### D1: Gate at the choke point, not the invoker — superseding D3

`UploadCycle.run()` reads the membership's contribution and declines before any walk. This is D4's shape
applied to the upload arm, and the requirement is now pinned in `upload-lifecycle` so a third tier inherits it.

The choke point is real: `createJob` is called only from inside `UploadCycle.kt`, and `run()` is the class's
only public method. It is *conventional* rather than compiler-enforced — nothing stops a future tier calling
`UploadJobPlatform.createJob` directly — but the two failure modes are not comparable. Breaking an
invoker-gate costs **one line in the untested shell** that looks obviously correct; that is what happened.
Breaking a choke-point gate means building a parallel upload path, which nobody does by accident.

_Alternative considered:_ an `armed` flag on the pump, set by `stop()`. Rejected — the flag dies with the
process, and this tier is relaunched **cold** (a `BGProcessingTask` handler, `handleEventsForBackgroundURLSession`)
with no `UploadArm` transition to re-arm it. The OS's registration record is durable because it is system-side;
an in-memory flag is not.

_Alternative considered:_ gate at the `SnapSyncRoot` call sites. Rejected for D4's stated reason — it parks
behavior in a module the hard rule declares untested, which is how this shipped.

### D2: `Contribution` — direction is a selection input, carried with the cutoff

```kotlin
sealed interface Contribution {
    data object None : Contribution
    data class Since(val cutoff: String) : Contribution
}
```

Lives in `:domain:gallery` — the only module both `:capability:upload` and `:domain:status` can see, which is
the reason the selection policy lives there at all. Required on both consumers, no default.

This is `CLAUDE.md`'s own sentence made into a type: *what a member contributes* is decided by one policy at
one place. The cutoff bounds **when**, the origin exclusions bound **what it is**, and direction bounds
**whether at all**. Direction was the one input routed around the policy — and each consumer improvised
around its absence: the cycle did nothing (the leak), the status total did nothing (and D5 masked the result).

**Why sealed over `(cutoff, uploadsEnabled)`:** the pair can express "contributes nothing, and here is the
cutoff it is not using." The sealed form deletes that state rather than tolerating it — `None` carries no
cutoff because a non-contributor has none.

**Why no default, in either polarity.** A permissive default (`Since("")`) uploads the whole library from the
beginning of time — `""` compares `>=` true against every `creationDate`, as `photoCutoff`'s own KDoc already
warns. A fail-closed default (`None`) is *also* wrong, and for this codebase's central reason: a contributing
member would silently share nothing, `N` would read `0`, and the screen would read "In sync." That is the
*invisible* failure — *"an event photo that silently fails to upload is invisible and unfixable"* — which is
worse than the visible one. So: required, like `photoCutoff` and `reconcile` already are.

**Why not a per-resource rule.** Folding direction into the selection filter means a download-only device with
4000 photos spends ~440 s of XPC enumerating a library to conclude it uploads nothing. A per-resource policy
structurally cannot express "don't start."

`photoCutoff` becomes a plain `Contribution` value rather than a `suspend () -> …`. All eleven call sites
already pass `{ constant }`, and the cycle is per-run, so the lambda buys no freshness. `DownloadController`
keeps its `() -> Boolean` predicate — it is a **long-lived field** and genuinely must re-read.

### D3: `CycleResult.SKIPPED` carries the decline to the pump

The gate alone does not stop background wakes: `onBackgroundTask` re-arms **unconditionally**, so a gated
cycle returning `COMPLETED` would schedule a heartbeat forever on a device that will never upload. The pump
cannot distinguish a gated `COMPLETED` from a real one.

A new `CycleResult` variant carries it, and the pump extends the re-arm switch it already has for
`PROCESSING`. One posture read, in one place; "no bgtasks on a non-contributor" falls out of the *result*
rather than a second read of the same policy.

The variant is also the enforcement: adding it makes every non-exhaustive `when` over `CycleResult` a
**compile error**, so the pump's re-arm policy has to state its answer for `SKIPPED` explicitly. That is the
compiler making the next person decide, not a convention.

_Alternative considered:_ read posture in the pump too. Rejected — two reads of one policy, which is the
smell this change exists to remove.

### D4: `N` moves downstream of the gate; the arrow masks go

The two arms are structurally different today, and that asymmetry is what D5 was compensating for:

```
DOWNLOAD ARM                          UPLOAD ARM

trigger → reconcile() ← GATE          trigger → UploadCycle ← GATE
              ↓                                     ↓
          store.plan()                          createJob()
              ↓                                     ↓
          assetCount() → total                  ledger → completed

total flows THROUGH the gate.         OwnDeviceGalleryStatusSource → N
Gate it once and total is 0 free.     walks the gallery ITSELF.
The download mask never had a job.    No gate feeds it. The mask is the
                                      only thing between N and an arrow
                                      that never settles.
```

Passing `Contribution` into `OwnDeviceGalleryStatusSource` puts `N` where the download arm's total already
is. Then both masks are dead, and both arrows hide themselves.

**Both masks are removed, and not merely as dead code.** A force-hidden arrow can only ever conceal a mismatch
between the direction contract and reality — and concealing that mismatch is exactly how this bug survived a
release cycle. With the mask, a download-only member sees "In sync" while their camera roll uploads; without
it, they see an upload arrow pulsing and file a bug on day one. If the counts are right the arrow is already
correct; if they are wrong, the arrow is the only smoke detector anyone gets. This is the only place in the
repo that trades visibility for tidiness, and it cuts against the bias `CLAUDE.md` opens with.

_Alternative considered:_ keep the masks as defense in depth. Rejected on the above — they are not insurance,
they are a failure concealer. The `sync-status-screen` delta adds a scenario asserting the arrow **shows** if
a non-contributing membership ever reports upload work.

### D5: Silent push drives the upload arm, via a receiver mirroring the download arm's

`BGProcessingTask` with `requiresExternalPower = false` is still scheduled at OS discretion and routinely
deferred far beyond its 60 s `earliestBeginDate` — least reliable exactly when an event is live. A silent push
is the reliable wake, and it *clusters* on live events: it is emitted when another member's device drains a
cycle that completed an upload (`upload-completion-notify`).

`UploadPushReceiver` mirrors `DownloadPushReceiver` — active-event guard, then `pump.onSilentPush()` — and the
root composes both behind a fan-out. The active-event decision stays in a tested capability, per D4 of the
superseded document. The two guards stay orthogonal exactly as `photo-download` already pins them: active-event
asks "is this push for my event", direction asks "should I ever upload here."

The handler releases the OS completion promptly and does not hold it for the cycle. iOS grants a silent push a
short budget; a library walk can exceed it, and blowing the budget risks throttled push delivery — which would
damage the **download** path, the push's actual job. The scan is best-effort: a cycle cut short advances no
discovery cursor, so the next wake redoes it.

`onForeground` also re-arms. A force-quit cancels pending `BGTaskScheduler` requests and the OS does not
relaunch until the user opens the app — so reopening, the exact moment the device is available, was the one
event that did not re-arm.

### D6: Posture-explicit bindings on both arms

`downloadEnabled = { …?.includesDownload ?: true }` resolves *no membership* to *download enabled*. It is
currently unreachable (reconcile is only called with a config-derived event id), but it is the same collapse
`UploadArm`'s KDoc blames for starting a producer for a nonexistent event via `?: true`. Both arms' reads
become three-valued: no membership and an excluding membership are distinct answers, and neither enables the
arm. `DownloadController`'s `{ true }` default goes with it — a permissive default on a safety gate is how a
tier ships without one.

## Risks / Trade-offs

- **Removing the upload mask depends on `N` being correct** → If it is wrong, a download-only member watches an
  arrow that never settles, shipped silently to public testers. Exercise a download-only preset in the
  full-stack harness (`:app:desktop:run`, whose counts emerge from the real status source, never forged)
  before the mask comes out. Note the failure is *visible* — which is the point of removing the mask.
- **`CycleResult.SKIPPED` is a breaking change** → Every `when` over it stops compiling. Deliberate; the
  compile errors are the review.
- **The choke point is conventional, not enforced** → A future tier could call `UploadJobPlatform.createJob`
  directly. Only the expensive mistake breaks it, and the required `Contribution` means a tier that *uses*
  `UploadCycle` cannot forget the posture. See Open Questions.
- **This change is large** — a privacy fix, a refactor, a UI change, and a feature. The leak stays live while
  it lands. Accepted deliberately in favor of touching `UploadCycle` once: sequencing the fix first means
  designing its parameter twice, since the refactor immediately subsumes it.
- **`onSilentPush` adds a wake path that could blow the push budget** → Fire-and-forget after `completion()`;
  never hold the handler. A truncated cycle is safe (the cursor only advances on a drained one).
- **The bug is argued, not yet demonstrated** → The first task is a **failing** test. If it passes, the
  analysis is wrong and the change stops there.

## Migration Plan

No data migration. The discovery cursor, ledger, and download store are untouched — `Contribution` changes
what is *admitted*, never what is *stored*. A download-only device that already uploaded under the bug keeps
its `COMPLETED` rows (they are true — the bytes are in its storage partition), and stops uploading more.
Removing already-leaked bytes from an event is out of scope and would need a separate deletion path.

Rollback is a revert: no persisted format changes shape.

## Open Questions

- **Should `:test:architecture` guard the choke point?** "Every `.createJob(` call is inside `UploadCycle.kt`"
  is Konsist-shaped and would make the containment structural rather than conventional — the same two-part
  proof `KeychainContainmentTest` describes (containment there, behavior in the capability's own tests,
  *"which neither half establishes alone"*). Deferred: the repo's stated criterion is *prefer the dependency
  graph, reach for Konsist only when there is no lever to withhold* — and there is none here, since
  `UploadJobPlatform` and `UploadCycle` share a module and the implementations live in others. So it likely
  qualifies; it is deferred on scope, not on merit.
- **Does `syncHealth` still need `direction` at all?** After D4 it should not. If a consumer still passes it,
  that is a signal the counts are not carrying the contract and should be investigated rather than papered
  over.
