## Context

Six defects, one cause. `2026-07-04-add-url-session-upload` added a second upload tier and made two mistakes
at once — and every symptom below descends from them.

**Stale premise.** The change was written against an *event-scoped* ledger and byte store. That model had
already been replaced by the **device-global** layout on `2026-06-30-dedup-files-device-manifests` — four days
earlier. Bytes go to `/files/devices/<deviceId>/<filename>`; the `eventId` never appears in the byte URL, and
the ledger keys off the bare `<assetId>-<role>.<ext>`. Two spec bullets encode the dead model:

| Bullet (`ios-url-session-upload`) | Why it is incoherent |
| --- | --- |
| re-provision: *"cancel in-flight tasks **for the old event**"* | No task belongs to an event. The destination URL is identical before and after a switch, so cancelling throws away bytes we immediately re-upload to the same place. |
| leave: *"clear the ledger and discovery cursor"* | Directly contradicts `event-rejoin-reconciliation`, which requires leave to **keep** them — *"the ledger is device-global and valid across events"*. That justification is a fact about storage, not a tier preference, so it binds both tiers. |

**Missed generalization.** `event-rejoin-reconciliation` is written *extension-only* — *"**The extension**
SHALL run a join reconciliation on its own cycle…"*. It was never widened when the second tier landed. So the
app-driven tier reconciles nothing, and its `design.md` premise — *"reusing the engine, ledger, discovery,
status, **reconcile**, and edge contract unchanged"* — was false. Reconcile is the one shared concern wired in
each **composition root** rather than in the shared `UploadCycle`, so it did not come along for free.

**Why nothing caught it.** The lifecycle lives in `SnapSyncRoot.kt` — the module CLAUDE.md declares
*"wiring-only and untested"*. Grep finds zero tests referencing `SnapSyncRoot` or `UrlSessionUploadController`.
The original change's own on-device lifecycle check (task 7.3 — *"re-provision against a fresh event (reconcile
seeds stored as COMPLETED, uploads only the gap) and leave"*) was **archived unchecked**. Its sibling task 5.2
was ticked while its parenthetical — *"(re-provision reuses the same config-refresh-per-cycle path)"* — was
never implemented.

The deepest fact: **no capability owns the upload lifecycle.** It is smeared across `ios-photokit-upload`
(whose "Re-provision resets sync state" says *"the **host app** SHALL re-provision… the extension SHALL be
re-registered"*, tier-scoped only by its Purpose — and the code applies it unconditionally),
`ios-url-session-upload`, and an untested app shell. A third tier (iOS 27's async protocol) is already
anticipated in `app/ios/CLAUDE.md`.

Current chain on iOS 18–26.0, permission GRANTED, direction includes upload:

```
provisionEvent(cfg)                        [fresh join or switch; a same-event rescan
  └─ enableBackgroundUpload()               short-circuits AlreadyJoined in JoinEvent]
      ├─ disableExtension()
      │   └─ useAppDrivenUpload → urlSessionUpload.leave()      ◄── a LEAVE, not a toggle-off
      │        ├─ platform.cancelAll()          transfers + staged temps destroyed
      │        ├─ scheduler.cancel()            heartbeat destroyed
      │        └─ scope.launch {                un-awaited, fire-and-forget on Main
      │             ledgerBackend.clear()       ◄── EVERY row, incl. all COMPLETED
      │             discoveryStore.clearToken() ◄── cursor gone → next walk is FULL
      │           }
      └─ setUploadExtensionEnabled(true)  → no-op below 26.1
                                              ◄── nothing started. no start(). no cycle.
```

`platform.cancelAll()` does **not** invalidate the `NSURLSession` (unlike the download side's
`IosPhotoDownloadJobs`, whose SIGABRT is the `crash` workspace's problem), and `SnapSyncRoot.onForeground()`
unconditionally pumps a cycle on this tier — so the *pump* self-heals on the next foreground entry. The
**destroyed ledger and cursor do not**. That is the lasting damage, and with no reconciler there is no repair
path: the next cycle walks the full post-cutoff library against an empty ledger and re-uploads all of it.
Bounded by `minPhotoDate`, so it is a whole-*membership* re-upload, not a whole-library one — the cutoff
invariant holds and there is no privacy breach. PUTs are idempotent on deterministic keys, so no corruption
either. It is a bandwidth, battery, and trust bug.

## Goals / Non-Goals

**Goals:**
- Make the destructive-provision edge **unrepresentable**, not merely fixed.
- Make reconciliation **impossible to forget** when a tier is added — compiler-enforced, not documented.
- Give the upload lifecycle a **named capability, a seam, and tests** that run on JVM + `iosSimulatorArm64`.
- Correct the two spec bullets that encode the dead event-scoped model.
- Make the app-driven tier **verifiable on the one agent-driveable device** (SE2), and actually verify it.

**Non-Goals:**
- The download `SIGABRT` (`crash` workspace) and the event-switch download teardown (`download-switch`
  workspace, sequenced behind it). We touch neither `IosPhotoDownloadJobs` nor `DownloadController`.
- Rewriting the archived `2026-07-04-add-url-session-upload` record. Archives are immutable; this document is
  where the correction lives.
- Changing upload *execution* — `UploadCycle`, the engine, the edge contract, and both `UploadJobPlatform`
  adapters keep their behavior. Only lifecycle and reconcile wiring move.
- Any UI change. Status regression to 0-of-N disappears as a consequence of not wiping, not by touching status.

## Decisions

### D1 — A two-verb producer seam, with no destructive verb

```kotlin
interface UploadProducer {          // :capability:upload
    suspend fun start()             // begin/resume uploading for the current config
    suspend fun stop()              // stop. NO durable-state destruction. ever.
}
```

The tier-neutral **decision** (which verb, when) moves to a tested orchestrator in `:capability:upload`; the
tier-specific **mechanism** stays behind the seam in `:app:ios`.

|  | `start()` | `stop()` |
| --- | --- | --- |
| **PhotoKit (≥26.1)** | the 3202 disable→enable toggle | `enable(false)` + `clearRequested` + clear cursor *(the OS disable wipes in-flight jobs; this is the repair)* |
| **URLSession (18–26.0)** | `sweepStaging()` + pump a cycle + arm the first `BGProcessingTask` | `cancelAll()` + `scheduler.cancel()` *(no repair needed — `fetchAckJobs` already reconciles stranded rows precisely via `getAllTasks`, per the tier's D5)* |

The point is not testability. It is that **`leave()` ceases to exist**, so there is no edge from *provision* to
*destruction* to get wrong. `UrlSessionUploadController.leave()` is deleted; `disable()` is renamed `stop()`;
the composition root and all four pump triggers survive untouched.

*Alternatives rejected.* **Three verbs (`start`/`stop`/`leave`)** — keeps a destructive verb in the seam,
which is exactly the shape that produced the bug; and D2 makes it unnecessary. **A tier enum + `when` blocks in
`SnapSyncRoot`** — cheapest diff, but leaves behavior in the untested shell, which is the root cause. **Fixing
`disableExtension()` in place** (one line: `.leave()` → `.disable()`) — makes the bug go away without making the
bug *class* go away, and a third tier is already on the roadmap.

### D2 — Leave keeps the ledger; `clear()` loses its only production caller

The ledger is **device-global dedup state**, not event state. Leaving an event does not delete your bytes from
`/files/devices/<deviceId>/`, so the `COMPLETED` rows describing them remain **true**. Wiping them destroys
dedup that is still valid and guarantees a re-upload on the next join.

This is not a new position — `event-rejoin-reconciliation` already requires it (*"clears the `joinedEventId`
marker **only** … while **keeping** the ledger, cursor, and accumulator intact"*), and the PhotoKit tier already
honors it. Only the app-driven tier wipes, on the authority of a bullet written under the dead event-scoped
model. We delete the wipe rather than the requirement.

Consequence: `LedgerBackend.clear()` has exactly **one** production call site in the repo —
`UrlSessionUploadController.kt:222`, the bug — and after this change it has none. We keep `clear()` on the
interface (the harness and `resetTo`'s contract lean on it) but the `sync-ledger` spec must stop implying it is
the re-provision mechanism.

Leave therefore reduces to `stop()` + clear the config. The reconciler's marker handles the rest on the next
join, exactly as specified.

### D3 — `reconcile` becomes a required `UploadCycle` parameter

`UploadCycle` already uses *required constructor parameter* as its guardrail against a safety-critical
omission: `photoCutoff` sits with **no default** in the middle of a run of defaulted hooks, because a forgotten
cutoff means uploading a guest's camera roll. A forgotten reconcile means re-uploading their whole membership.
Same class of mistake, same enforcement:

```kotlin
private val reconcile: suspend (eventId: String) -> Boolean,   // marker-gated; false ⇒ defer this cycle
```

Reconcile moves **out of the composition roots and into the shared cycle**. `UrlSessionUploadController` stops
compiling until it supplies one. The false premise — *"reuse … reconcile … unchanged"* — becomes true **by
construction** instead of true by hope.

It belongs in the *cycle*, not the *lifecycle*, because the cycle is the only thing that runs on **all** routes
to an empty ledger: reinstall (no provision happens — a cold relaunch never calls `provisionEvent`), leave →
rejoin, event switch. A fix in the provision path structurally cannot heal a reinstall, which is the exact
scenario `event-rejoin-reconciliation`'s Purpose was written for.

*Alternative rejected:* a defaulted `reconcile = { true }` hook — a tier that forgets it still compiles, and
silence is precisely the failure we are removing.

### D4 — Re-provision cancels nothing

The byte URL is `/files/devices/<deviceId>/<filename>`. An in-flight transfer at switch time is uploading to a
URL that is **equally valid after the switch**; cancelling it re-uploads identical bytes to an identical place.
Transfers are at-least-once idempotent, so finishing one always costs less than restarting it.

The steelman for cancelling — *"don't spend a guest's cellular data finishing an upload for an event they
left"* — keys on the **cutoff**, not the event, and today's code cancels *everything*, including the transfers
the new event still wants. Bytes outside the new cutoff land in the device partition but are absent from the
new event's `device.json` (projected with `startDate = cutoff`), so they never enter its union: no leak, just a
short tail. Not worth a per-task cutoff check.

Re-provision therefore reduces to: **persist the config, then `start()`**. The cycle re-reads config each run,
and its marker-gated reconcile does the seeding and the cursor clear. This is precisely what the original
change's task 5.2 already claimed — *"(re-provision reuses the same config-refresh-per-cycle path)"*.

### D5 — Tier selection collapses to one `if`; the force flag stops meaning two things

Under D1 the tier is chosen **once**, at composition:

```kotlin
private val producer: UploadProducer by lazy {
    if (useAppDrivenUpload) urlSessionUpload else photoKitProducer
}
```

`setUploadExtensionEnabled` lives *inside* `PhotoKitUploadProducer`, which is never constructed on the
app-driven tier. The dual-tier bug — where `SNAPSYNC_FORCE_URLSESSION_UPLOAD` on a ≥26.1 device skips
`enable(false)` but still fires `enable(true)`, yielding **two `LedgerWriter`s** over one App-Group ledger and a
bare `enable(true)` that re-exposes `PHPhotosError 3202` — cannot be expressed: only one producer object exists.
The existing requirement (*"The two tiers SHALL be mutually exclusive within one running process"*) becomes
structural rather than aspirational.

Separately, the flag today *also* sets `useBackgroundSession = !forceUrlSessionUpload`, silently downgrading the
transport to a **foreground** session — contradicting this tier's own spec (*"Because a background `URLSession`
**runs in the iOS simulator**, the transport MAY be exercised end-to-end in the simulator; `BGProcessingTask`
**timing** remains device-only"*). Being on a simulator is a **fact**, not a flag someone must remember to pass;
derive it (`SIMULATOR_DEVICE_NAME`) or drop the downgrade entirely, and always use a background session on
device.

This is **not cleanup — it is a prerequisite for verification.** The iPhone XR (18.7.9) is a tester's phone, not
USB-attached; the SE2 (26.5) is the only agent-driveable device and runs the *wrong* tier. Today, SE2 + force
flag is an unfaithful proxy in three ways that all bias the same direction — it enables both tiers, uses the
wrong transport, and lets the extension's reconciler silently re-seed `COMPLETED`, **masking the very storm we
need to observe**. Fixing the flag is what makes task 7.3 runnable at all.

### D6 — `upload-lifecycle` is a new capability

The lifecycle had no owner, which is why it had no test and no contract. Naming it is the spec-level counterpart
of D1: it owns the producer seam, the verb-per-transition table, the one-producer-per-process rule, and the
invariant that **no lifecycle transition destroys durable dedup state**. The two tier specs then describe only
their producer's *mechanism* and defer the *decision* to it.

## Risks / Trade-offs

- **[The shared `UploadCycle` edit and the new PhotoKit producer touch the working ≥26.1 tier]** → This is the
  main risk: we are refactoring the one tier that currently works, and `:app:ios` is untested by rule, so the
  producers themselves get no unit coverage. Mitigated by the two-device verification (SE2 = PhotoKit
  no-regression; XR = app-driven), which is the whole reason D5 is in scope. Task order puts the shared,
  compiler-enforced change (D3) first and the PhotoKit-touching refactor (D1) behind it.
- **[Leave no longer wipes → a stale `COMPLETED` row could suppress a needed upload]** → Only if the ledger and
  storage diverge, which is exactly what reconciliation repairs — and D3 makes it run on both tiers. A confirmed
  listing is authoritative (including an empty one), so a storage reset re-baselines and re-uploads. Strictly
  better than today, where the app-driven tier has *no* repair path at all.
- **[`UploadProducer` sits in `:capability:upload`, which the PhotoKit extension does not compose]** → The
  extension process has no lifecycle (the OS drives it); only the **app** selects and drives a producer. The
  PhotoKit producer therefore lives in `:app:ios` beside the URLSession one, and the extension root is untouched
  except for reconcile moving into the cycle.
- **[Merge surface with the `crash` workspace]** → One shared line, `SnapSyncRoot.kt:340` (the `leave` lambda).
  Both branches sit on `ffc0594`. Trivially mergeable; we touch neither `IosPhotoDownloadJobs` nor
  `DownloadController`.
- **[`BGProcessingTask` timing is OS-owned]** → The heartbeat arm can be *observed* in the log but not *forced*.
  Verification asserts the submit, not the fire.
