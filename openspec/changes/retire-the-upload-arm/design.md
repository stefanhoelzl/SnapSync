## Context

Phase 4 of the seven-phase upload-path rework (phases 0–3 and 6 shipped; phase 3 is `join-loads-leave-clears`,
`ad779308`). Current tree:

- `UploadArm` holds one `UploadMechanismRuntime` (`current`, starting at `IdleUploadMechanism`) and fires
  `start()`/`stop()` on `onProvision` / `onPermissionChanged` / `onLeave`. `uploadMechanismTable` maps a
  resolved kind to an instance, wrapping each in `RelinquishThenRun` so starting one first stops the other.
  Triggers are delivered to `current`.
- The app-driven mechanism (`UrlSessionUploadController`): `start()` = `cycle.signalRestart()` +
  `pump.onStart()` (drain + arm the first `BGProcessingTask`); `stop()` = `platform.cancelAll()` +
  `scheduler.cancel()`. The OS-driven mechanism (`OsDrivenUploadMechanism`): `start()` = disable →
  `demoteRequested` → enable; `stop()` = disable; every trigger is declined.
- Eleven production touch points: `SnapSyncApp` construction; `LeaveEvent.stopUploads`; `Provision`'s
  `uploadArm` constructor parameter (`onProvision()`); **`ReconfigureEvent.armUpload`** (`SnapSyncApp.kt:561`);
  `MembershipEntry`'s switch-leave; the foreground flow's `pumpUploads`; the silent-push fan-out; the
  permission collector; the selection-change collector; `SnapSyncRoot.runUploadHeartbeat`; and the rig pin.

Facts found while verifying the handoff that shape this design:

1. **The permission collector's replay is a per-launch transition.** `installPermissionSubscriptions` runs at
   host assembly; the `StateFlow`'s first emission calls `onPermissionChanged()` → `start()` on every UI launch.
   So today every UI launch runs the OS-driven ritual (stale-record repair) or `signalRestart` +
   `pump.onStart`.
2. **Cold background wakes reach `IdleUploadMechanism`.** Nothing but host assembly moves `current` off idle,
   and a `BGProcessingTask` or silent-push launch does not assemble the host. The heartbeat's re-submission
   happens inside the pump, which idle never reaches — so the chain ends at the first cold wake (by code
   reading; not measured on device).
3. **Settling is not inert.** `recreateRetrySpent` re-creates spent retries (`platform.createJob`) and runs
   `reconcileStranded`, which demotes `REQUESTED` rows the transport holds no live transfer for. On the app
   transport, rows the extension requested have no URLSession task — so an app cycle that settles while the
   extension is live demotes the extension's in-flight rows: a second ledger writer.
4. **The structural exclusion was already porous.** `handleBackgroundUrlSession` routes the upload session's
   relaunch straight to `UrlSessionUploadController.onBackgroundSessionEvents`, bypassing the arm; its drain
   pumps a cycle whatever the resolved mechanism is — constructing the app's `LedgerWriter` and running (3).
5. **The extension cannot see the rig override.** `UploadMechanismPin` lives in app-process memory.
6. **The OS's registration read is grant-dependent.** `UploadExtensionRegistry.isEnabled()` answered `false`
   under `NOT_DETERMINED` for a live record (SE2 / 26.6); `setEnabled` is refused both ways under `LIMITED`
   (3311).
7. **The `NOT_DETERMINED` prompt.** `uploadCore`'s membership policy supplier calls the unguarded album reader
   (`UploadCore.kt:211`), and `UploadCycle.settle()` builds the policy before the direction gate — a
   `PHAssetCollection` fetch under `NOT_DETERMINED` presents the permission dialog (simulator, iOS 26.4).
8. **Revocation drift.** `ios-url-session-upload` says revoking access cancels transfers and the heartbeat;
   `onPermissionChanged` returns on `IDLE` without stopping anything.

## Goals / Non-Goals

**Goals:**
- Delete the held instance, the table, the relinquish wrapper, the idle stand-in and the producer seam.
- Keep one tested, platform-free place that decides what each membership transition does.
- Keep exactly one ledger writer, now enforced at each engine's entry gate, and say so as a weakening.
- Put photo permission into the entry gate in both processes, without either severity-1 trap.
- Fix cold background wakes, announced as customer-visible.

**Non-Goals:**
- Moving the stranded restart rule to "once per process start" (phase 5).
- The `FileLogWriter` silent give-up, the post-switch app-driven hang phase 1 recorded, a walk-less top-up.
- Changing `resolveUploadMechanism`, the ritual, or `UploadPushReceiver`'s `GRANTED`-exactly guard.
- The pre-existing `GRANTED` → `LIMITED` hazard where the app's restart rule may demote rows of extension
  jobs still running under a surviving record: unchanged by this phase (the record is inert per
  `ios-photokit-upload`, and the extension now also declines there).

## Decisions

### D1 — One stateless transitions object in `feature/upload`

`UploadTransitions` (name provisional) replaces `UploadArm`. It holds **no** mutable state. Inputs: `resolve()`
(the existing composition of `resolveUploadMechanism` over the OS fact, `photoAccess.permission.value` and the
override), `membershipIncludesUpload(): Boolean?` (unchanged three-valued posture), `permission()`, an optional
`ExtensionRegistration` (present only where the OS carries the selector), and the `AppUploadEngine`.

From `(kind, posture)` it derives the desired state:

| | registration wanted | app engine armed |
|---|---|---|
| posture `true`, kind `PHOTOKIT` | yes | no |
| posture `true`, kind `URL_SESSION` | no | yes |
| posture `true`, kind `IDLE` | unchanged | no |
| posture `false` or `null` | no | no |

Verbs, and what each forces:

| verb | registration | app engine |
|---|---|---|
| `onJoin()` (Provision, after the share-set load) | **forced**: wanted → ritual; not wanted → disable | armed if wanted, else disarmed |
| `onReconfigure()` | compared | armed if wanted, else disarmed |
| `onPermissionChanged()` (real changes only) | compared | armed if wanted, else disarmed |
| `onLaunch()` (host assembly) | compared | armed if wanted, else disarmed |
| `onLeave()` (leave and switch) | forced disable | disarmed |

*Compared* means: read `isEnabled()` **only under `GRANTED`** (fact 6); under `GRANTED`, wanted-and-absent →
ritual, unwanted-and-present → disable; under any other grant, change nothing (every write is refused under
`LIMITED`, and the read cannot be trusted under `NOT_DETERMINED`). Every *enable*, forced or compared, goes
through the ritual — never a bare enable. Posture `true` with kind `IDLE` leaves the registration as it is:
the extension withholds on permission at its own gate (D4), and no write is permitted anyway.

Disarming whenever the app engine is not wanted — launch included — replaces what `RelinquishThenRun` did for
work a previous process left behind: in-flight background transfers and a submitted `BGProcessingTask` survive
process death, and their delegate's guarded terminal writes run outside the cycle gate. Disarming with nothing
in flight is a no-op.

`onReconfigure()` keeps `ReconfigureEvent`'s asymmetry: it is called only when the new direction includes
upload, so a disabling reconfigure still drains rather than stops (`reconfigure-membership`).

*Alternatives:* keep a kind→instance table with a stateless arm (keeps two of the five concepts for no fact);
fold the decisions into the composition root (untested by rule — the defect `upload-lifecycle` exists to
prevent).

### D2 — The app engine keeps two verbs, renamed for what they do

`AppUploadEngine` (the reshaped `UrlSessionUploadController` surface) exposes `arm()` = `signalRestart()` +
`pump.onStart()` and `disarm()` = `cancelAll()` + `scheduler.cancel()`, plus the four triggers. They are
exactly today's `start()`/`stop()`: the restart rule (phase 5's) and the first-`BGProcessingTask` duty keep
firing wherever the app engine is armed — join, reconfigure, permission change, **and launch** — so neither
is traded away. The OS-driven mechanism becomes `ExtensionRegistration` (`register()` = the ritual unchanged,
`deregister()`, `isRegistered()`); the ritual's demote stays between its disable and enable.

**The restart rule is cleanly phase 5's.** Moving it to "process start" would newly fire it on cold background
launches, where it has never run; this phase keeps it at `arm()`.

**Foreground already re-arms the heartbeat.** `BackgroundUploadPump.onForeground` schedules unconditionally
unless the cycle returns `SKIPPED`. Today that trigger only reaches the pump while the arm holds the app
engine; after this change it reaches it always, so the launch-time heartbeat no longer depends on `onLaunch`'s
`arm()` — which is kept anyway, because it also carries the restart signal.

### D3 — Launch is explicit; the collector reacts to real changes only

Host assembly calls `transitions.onLaunch()` directly. The permission collector skips the `StateFlow`'s replay
(`drop(1)`) and calls `onPermissionChanged()` on real changes. Launch therefore compares instead of forcing:
a stale record that still reads enabled is repaired only at the next join (user decision), and extension jobs
survive app launches. A cold background launch still installs nothing and runs no transition (`ios-app-shell`).

### D4 — Admission is a gate input, decided before the policy is built

`cycleGate` gains a required, port-pure input, `admission: UploadAdmission`, computed by each root:

- **app**: `resolve() == URL_SESSION` → `Admit`, else `NotResolved`;
- **extension**: `permissionFromPhotos() == GRANTED` → `Admit`, else `Withheld`.

Order: unreadable → `Skip`; absent → `NotJoined`; then `NotResolved` → `CycleGate.NotResolved`, `Withheld` →
`CycleGate.Withheld(config)`; else `Run`. Both short-circuit **before** `membership.policy()` (fact 7), so
neither outcome can reach the album reader.

The app gates on **resolution**, the extension on **permission**, never on `selectionScope` (the extension's
default `Unrestricted` is a lie under a partial grant). Under `LIMITED` the app admits and scopes discovery to
the selection snapshot; the extension withholds. The extension ignores the override (fact 5); under a pin,
exclusivity rests on the registration reconcile the pin command triggers (D7).

`UploadPorts.admission` is required with no default ("Every selection and side-effect port is answered at the
call site"). `UploadPorts` goes 18 → 19 fields, under `compose.yml`'s ceiling.

### D5 — Two cycle outcomes, two settle shapes, one publication answer

- `CycleOutcome.NotResolved` — no settle, no stranded pass, no retry, no ledger write. Needed because of fact 3.
- `CycleOutcome.Withheld` — **acknowledge-only settle**: drain the platform's terminal jobs and adjudicate
  their failures (recording them; a failed row returns to `DISCOVERED`) — which discharges the 50008
  obligation — but call neither `createJob` nor `retryJob`, and skip `reconcileStranded`.
- Both publish **nothing**. `Declined`'s empty manifest is honest for a direction that permanently excludes
  upload; a revoked or undetermined grant is temporary, so publishing empty would delete this device's photos
  from every member's view on a grant flip.
- Both return `SKIPPED`. The pump already schedules nothing on `SKIPPED`, which is right: the transition that
  makes the engine eligible again re-arms it.
- Both log at routine severity; neither is a fault.

The acknowledge-only pass is a new private stage in `UploadCycle`, not a flag on `recreateRetrySpent`, so the
exhaustive `publish()` and the settle requirement read directly.

### D6 — Triggers go to the app engine unconditionally

`app.uploadArm.triggers.X` becomes `appUploads.X` at all four sites. The OS-driven mechanism's declines are
deleted with it. `UploadPushReceiver`'s `GRANTED`-exactly check stays (`limited-photo-access` forbids widening
a trigger as a side effect of relocating a gate). `OsReceipt` is unaffected: handlers are held by the entry
point, never by a mechanism. Cold background wakes now run cycles (the announced fix); on the OS-driven tier
they decline as `NotResolved` and schedule nothing.

### D7 — The rig pin triggers the reconcile

`POST /device/upload-mechanism` calls the compared reconcile (`onPermissionChanged()` path) after setting the
pin, so a pin to `url_session` or `idle` on ≥26.1 under `GRANTED` deregisters the extension immediately. Rig
builds only.

### D8 — The guard follows the risk

`ProducerExclusivityTest` drives, over every OS fact × permission × override × posture: the resolver cells
(unchanged assertion); both roots' admission functions; and sequences of the five verbs over a fake
registration (with 3311 refusal under `LIMITED` and the grant-dependent read) and a fake app engine. After every
step it asserts that there is no state where the app admits while the extension is registered and admits
(`GRANTED`, no pin); that no enable bypasses the ritual; that no registration write is attempted under a
non-`GRANTED` grant except the forced join/leave writes, whose refusal is tolerated; and that the app engine is
armed only when resolution yields `URL_SESSION`.

### D9 — Composition budgets

`AppPorts`: `osDrivenUpload` + `relinquishOsRegistration` → `extensionRegistration: () -> ExtensionRegistration?`;
`appDrivenUpload` is retyped to `() -> AppUploadEngine`; `osSupportsOsDrivenUpload` stays (the resolver input).
45 → 44. `CompositionSeamTest`'s pins change in the same commit. `Provision`'s `uploadArm: UploadArm` becomes
`reconcileUploads: suspend () -> Unit`: 9 parameters, no new branch. `architectureDiagrams` is regenerated.

### D10 — The extension reads permission from ext-safe

The `PHAuthorizationStatus` → `PermissionStatus` mapping (`PhotoLibraryPermission.kt:128`) moves to
`:adapter:ios:ext-safe` as one function over `PHPhotoLibrary.authorizationStatusForAccessLevel` (`Photos` is
on the extension-safety allowlist). `PhotoLibraryPermission` delegates to it; app-only already depends on
ext-safe. One mapping, not a copy.

## Risks / Trade-offs

- **Exclusivity is gated, not structural** → the guard (D8) drives every reachable combination. The weakening is
  small in practice: the structural form was already bypassed by the session relaunch (fact 4), which this
  gate closes.
- **Cold background wakes now run the app cycle** → on the OS-driven tier they decline before touching anything
  (`NotResolved`); on the app-driven tier they are the intended fix. Decline placement (D4, D5) is what makes
  this safe, and unit tests cover each outcome's side effects.
- **A stale "enabled" record is repaired only at join** → accepted by the user; `RegistrationOutcome` still
  reports a failing enable at `Error`.
- **Dropping the replay loses a launch where permission changed while the app was dead** → `onLaunch()`
  reconciles from current state, so the first launch after a Settings change still converges.
- **Revocation now cancels in-flight app transfers** → matches the existing `ios-url-session-upload` contract;
  cancelled rows are demoted by the next `arm()`'s restart rule.
- **Constructing the app engine under the OS-driven tier** → its session identifier is stable and the session is
  never invalidated (`ios-url-session-upload`); constructing it already happens on a session relaunch.

## Migration Plan

No schema change and no data migration. Rollback is a revert: the only durable residue is the OS's registration
record, which a rolled-back build re-registers through its per-launch replay. Device verification before ship:
cold heartbeat wake on iOS < 26.1 re-submits the next task; ≥26.1 `GRANTED` foreground and cold wakes log
`NotResolved`, write nothing, and the extension keeps uploading; `NOT_DETERMINED` cold wake raises no dialog;
`LIMITED` uploads through the app engine; download-only join then reconfigure to upload on ≥26.1 registers the
extension.

## Open Questions

- Should `onReconfigure()` be forced rather than compared, for symmetry with join? Compared suffices for the
  download-only → upload case (the record is absent after a download-only join). Recommendation: compared.
