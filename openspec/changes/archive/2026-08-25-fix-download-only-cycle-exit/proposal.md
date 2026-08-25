## Why

`UploadCycle.run()` returns early at the wrong point **twice**, and a download-only membership pays for
both.

**First**, the walk-floor guard sits ahead of the direction check. `SelectionPolicy.None.walkFloor` is
`null` by construction, so *every* download-only cycle takes a branch that logs at `Error` — *"admitting
policy carries no capture floor — refusing an unbounded walk"* — and the benign line one branch below
(*"this membership contributes nothing"*) has never executed in production. Because `crash-reporting`
turns every `Error` line into an event, and the ambient `[entryPoint]` prefix is part of the message,
one cause reached the operator's Bugsink as **four separate issues** (`SNAPSYNC-27/28/29/30`), one per
trigger, unbounded going forward: one event per foreground and one per completed upload task.

**Second**, the direction gate itself sits ahead of the terminal-job acknowledgement pass. On iOS ≥26.1
that is only safe when the extension has been deregistered — and on the reconfigure path it has not
been. Turning sharing off fires **no** arm verb at all (`ReconfigureEvent` guards `armUpload()` behind
`direction.includesUpload`, and the arm's only other entry points are leave and a permission
transition), so the OS keeps presenting jobs to a cycle that returns before acknowledging them.
Measured on device (SE2, iOS 26.6, `GRANTED`, PhotoKit tier), three arms differing in one variable:

| arm | `stop()` runs | extension after | outcome |
|---|---|---|---|
| join download-only | yes | deregistered | clean |
| leave with jobs outstanding | yes | `true` → **`false`** | clean |
| membership voided, `stop()` skipped | **no** | stays **`true`** | **error 50008**, jobs discarded |

The OS's own log names the consequence, and it is worse than an error line:

```
UploadExtensionRunnerWorker: Extension failed completion validation:
    Registered jobs: YES   Acknowledged jobs: YES
UploadExtensionRunnerWorker: Updating attempt count (1) after completion for configuration
<ERROR> com.apple.photos.error Code=50008 "appex failed to acknowledge jobs for processing state"
UploadExtensionRunnerWorker: Delaying due to failed attempts for configuration
    — skipping until 299.893686 seconds have…
```

iOS records a failed attempt **against the upload-job configuration** and defers the extension by ~5
minutes, escalating with the attempt count. The penalty outlives the condition that caused it, and the
outstanding jobs are dropped rather than re-presented (`fetchAckJobs = 0` on the next joined cycle). So
a member who turns sharing off and later back on finds their uploads in a penalty box with no visible
cause — while `reconfigure-membership` promises the opposite, justifying its drain with *"cancelling it
would only re-upload identical bytes"*. The drain does complete the transfer, but its completion is
never harvested into the ledger, so draining causes the very re-upload the clause claims to avoid.

## What Changes

- **The missing capture floor becomes unrepresentable.** `SelectionPolicy.Admitting` carries the
  `CaptureCutoff` as a non-null field and derives its `CaptureAfter` rule from it. `walkFloor` — a
  nullable accessor that collapsed "contributes nothing" and "has no floor" into one `null` a caller
  could branch on — is **deleted**. Both consumers (`UploadCycle`, `OwnDeviceGalleryStatusSource`)
  become an exhaustive `when` over the sealed policy, leaving no branch to get wrong. This makes the
  code conform to a requirement `photo-selection-policy` already states: a membership without a lower
  bound "is not a representable state", and "every consumer SHALL receive a non-null value".
- **A download-only skip is reported as routine.** The cycle logs the direction skip at `Info`, not
  `Error`, so a normal non-contributing membership never produces a crash report.
- **The terminal-job acknowledgement pass moves ahead of the direction gate.** Acknowledging a job the
  OS already presented creates nothing, writes no manifest, walks no library and issues no network
  call — so it does not weaken the choke-point guarantee, and it is the only way the acknowledgement
  obligation can hold on a tier whose extension is still registered. The reconcile, the retry pass, the
  discovery walk, the manifest and the notify all stay behind the gate.
- **The reporting seam stops splitting one cause across issues.** The captured event carries the bare
  redacted message; the ambient log context rides as an `entry_point` **tag**, on both the message and
  the exception path. The `[ctx]` prefix stays on the error breadcrumb, so the trail is unchanged.
- **The drain requirement is made to cash its claim** — an in-flight upload left to drain has its
  completion recorded, so re-enabling the direction does not re-upload what already landed.

No **BREAKING** changes: no user-visible behaviour changes for a contributing membership, and a
download-only membership still creates no upload job and writes no device manifest at any trigger.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-selection-policy`: the non-contributing case is already unrepresentable-by-variant; an
  **admitting policy with no capture-date lower bound** becomes unrepresentable too, rather than a
  state the type permits and a consumer must guard.
- `upload-lifecycle`: states what may run **ahead** of the direction gate — the terminal-job
  acknowledgement pass, and nothing else — and that a download-only skip is reported at routine
  severity rather than as a fault.
- `reconfigure-membership`: the drain requirement gains the obligation its rationale already assumes —
  a drained upload's completion is settled, not discarded.
- `crash-reporting`: an automatically captured event's message excludes the ambient log-context prefix,
  which travels as a tag instead, so one cause is one issue rather than one issue per entry point.
- `gallery-status`: supporting text describing "an `Admitting` policy carrying no capture-date lower
  bound" as a reachable hazard is corrected — that state no longer exists.

## Impact

**Code.** `:domain` `model/SelectionPolicy.kt` (the variant's shape, `from`, `excluding`, `walkFloor`
deleted); `feature/upload/UploadCycle.kt` (both exit points); `feature/status/OwnDeviceGalleryStatusSource.kt`;
`:adapter:generic:fake`'s `InMemoryCandidateSource`; `:adapter:ios:ext-safe`'s `SentryLogWriter`.
`PhotoKitCandidateSource.predicateFor` iterates rules order-independently, so re-seating the floor rule
needs no change there.

**Tests.** `UploadCycleTest`'s two download-only tests currently pass *through the wrong branch* — both
branches returned `SKIPPED` and touched nothing, so the suite was blind to the inversion. The exhaustive
`when` removes the second branch structurally; a new `commonTest` pins that a download-only cycle emits
no `Error`-severity line, and `SentryLogWriterTest` (iOS-only, proved by the required `ios-test` job)
pins the message/tag split.

**Sequencing.** `collapse-upload-tier-seam` is in flight and carries its own `upload-lifecycle` delta.
This change SHOULD land after it merges so the two deltas never coexist. The two are adjacent but
distinct: that change fixes a mechanism with *no route* to its teardown (producer resolution); this one
fixes a cycle that returns before discharging an obligation it still owes.

**Not covered.** The reconfigure trigger itself was not driven on device — `:test:rig` excludes
`onReconfigure` on the grounds that "no scenario needs it driven", which this investigation expires.
The measurement reached the same cycle state by voiding the membership; the link from the reconfigure
path to that state is an exhaustive read of the arm's three entry points, not a measurement. Evidence is
one device, one OS version.
