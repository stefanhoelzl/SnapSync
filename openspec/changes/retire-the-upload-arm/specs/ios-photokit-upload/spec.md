## ADDED Requirements

### Requirement: The extension withholds its cycle without a full grant

The extension SHALL read the photo grant **in its own process** and pass it to its cycle's entry gate as the
admission answer (`upload-lifecycle`, "The upload cycle owns its entry decision"): it SHALL admit exactly
under `GRANTED` and **withhold** under `LIMITED`, `DENIED` and `NOT_DETERMINED`.

A withheld cycle SHALL acknowledge the terminal jobs the OS presented and record their outcomes — the
acknowledgement obligation whose omission makes the system report `50008`, discard the outstanding jobs and
defer the extension — but SHALL re-create no retry, create no job, run no stranded pass, walk nothing, and
publish **no** device manifest. It SHALL report `SKIPPED` at routine severity.

It SHALL NOT reuse the direction gate's decline, which publishes an **empty** manifest: that is honest for a
direction that permanently excludes upload, and a grant is temporary. Publishing empty on a revoked or
undetermined grant would remove this device's photos from every member's view the moment the grant flipped.

The decision SHALL be made before the membership's selection policy is built, so a `NOT_DETERMINED` grant never
reaches the denylisted-album read that would present the permission dialog.

The grant read SHALL be the same `PHAuthorizationStatus` → `PermissionStatus` mapping the app process uses, held
once in `:adapter:ios:ext-safe` (whose `Photos` import the extension-safety gate allows) and delegated to by the
app-only permission adapter — one mapping, not a copy.

#### Scenario: A downgraded grant withholds the extension's cycle
- **WHEN** the OS invokes the extension while photo access is `LIMITED`
- **THEN** it acknowledges the presented jobs, creates no job, walks nothing, writes no manifest, and reports
  `SKIPPED`

#### Scenario: A withheld cycle does not blank the event union
- **WHEN** the extension withholds on a device whose manifest lists uploaded photos
- **THEN** that manifest is left as it was, so the photos stay visible to every member

#### Scenario: An undetermined grant raises no dialog in the extension
- **WHEN** the OS invokes the extension while photo access is `NOT_DETERMINED`
- **THEN** the cycle withholds before any album structure is read, and no permission prompt is issued

## MODIFIED Requirements

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1** the app SHALL register the background-upload extension with a **disable→enable toggle**
whenever it registers it — forced at a **join**, and at a reconfigure, a permission change, a launch or an
override change only when the compared reconcile finds it wanted and absent (`upload-lifecycle`, "Membership
transitions reconcile the upload mechanisms in one tested place") — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; the transitions arm and disarm the app-driven engine instead (see `ios-url-session-upload`).

A stale record that still **reads** enabled is repaired only at the next join: the launch reconcile compares
rather than forcing, so the extension's in-flight jobs survive app launches (`upload-lifecycle`, "Launch
reconciles by comparison; only a join forces the repair").

The registration change SHALL be made through a **port** in `:domain` `ports/`, named for the need, whose
iOS adapter — the only implementation that calls `PHPhotoLibrary.setUploadJobExtensionEnabled` or
`isUploadJobExtensionEnabled` — lives in `:adapter:ios:app-only`, because only the app process ever
registers. The mechanism that performs the ritual SHALL hold no platform call of its own — its repair reaches
the ledger through the ledger's own port (see "Re-registering the extension demotes orphaned REQUESTED rows") — and SHALL therefore live in `:domain` `feature/upload` beside the
app-driven tier's mechanism, named for the need rather than for the platform. This is the ports law applied where it was not: the call sat in
`:app:ios`, which is wiring-only and gated at `CyclomaticComplexMethod` threshold 2, so it could report the
platform's raw facts but could hold no decision about them. Behind a port, the ritual, its repair,
and every arm of the outcome classification become executable on any host that can implement the port,
including JVM.

The registration record is OS state that this repo does not own, exactly as the upload-job queue is. Where
a target's host cannot hold such a record, the port's binding for that target answers in its place; see
"The upload-job subsystem binding is fixed by the compilation target".

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension

#### Scenario: The mechanism holds no platform call
- **WHEN** the mechanism that performs the disable→enable ritual is compiled
- **THEN** it names no platform API at all — the registration change and its read-back are reached through
  the registration port, and the repair through the ledger port — so it compiles for every
  target the platform-free core does

#### Scenario: The ritual is executable off a device
- **WHEN** the ritual runs against a port implementation that reports a pre-existing configuration record
- **THEN** the leading disable reports that a record existed and was removed, the enable reports success,
  and the sequence is asserted without a physical device

#### Scenario: The repair completes before the re-enable
- **WHEN** the ritual runs while the ledger holds orphaned `REQUESTED` rows
- **THEN** the rows are demoted to `DISCOVERED` **before** the enable is attempted, so the repair cannot demote
  rows belonging to the registration it is about to re-create

#### Scenario: Deregistering is the disable alone
- **WHEN** the extension is deregistered — on a leave, a download-only join, or a pin away from this mechanism
- **THEN** the registration is removed (or, under a partial grant, the attempt is refused) and no ledger
  row is touched; the next bring-up repairs any row left `REQUESTED`

### Requirement: Re-provision resets sync state

On a **valid event-link (re)scan**, the host app SHALL re-provision the (possibly new) event
by persisting the config and driving the upload arm through the tier-neutral lifecycle
(`upload-lifecycle`). The mechanism below is **this tier's** (iOS ≥26.1) and SHALL NOT be applied on
the app-driven tier, which has no OS registration record to re-create (see `ios-url-session-upload`,
"App-driven lifecycle").

A **switch** (a re-provision into a different event) is a leave followed by a join (capabilities
`upload-lifecycle`, `join-event`). On this tier it SHALL run in the app, in this order: the leave transition
**deregisters** the extension (the disable alone — see "Re-registering the extension demotes orphaned REQUESTED rows"),
the provision's **join-time load** re-baselines the ledger — it fetches the
per-device file listing (capability `api-endpoints`) and **`resetTo`s** (atomic clear-and-seed) the ledger
to one already-uploaded row per stored file, or `clear()`s it when the fetch fails — through the app's
`LedgerStore`, with no `LedgerWriter` (see `ios-app-shell`, "The app resets the upload ledger at membership
transitions on every tier"), the new config is then saved, and only then does the join transition
re-register the extension (the
disable→enable toggle, forced). A first join takes the same load before the first registration. The extension
therefore never re-baselines the ledger itself: it constructs no join marker and runs no in-cycle
reconciliation, and its first cycle after the re-register already reads the loaded ledger.

The device-global listing seeds the stored files as already-uploaded, so **nothing already stored
re-uploads**, while the clear drops every row from before the provision and the cycle's walk, a full
enumeration like every walk, finds genuinely-unstored work. The extension's next cycle **re-projects**
the re-baselined ledger to the **new** event's `device.json` path. Rows seeded from the listing are
**bare** (a filename carries no capture date) and are therefore not listed until the walk backfills their
manifest detail; a bare row is always re-read by the walk (capability `sync-ledger`, "A walk re-reads only
the assets the ledger does not fully know"). The app decodes the event link only to gate this on a
valid payload; the authoritative decode/validate/persist still happens in the shared container intent.

A re-provision of the **already-joined** event SHALL NOT reset the ledger: only a provision that changes
the membership loads it. The re-register's repair demotes rows instead (see "Re-registering the extension
demotes orphaned REQUESTED rows").

#### Scenario: Valid re-scan re-baselines and re-projects to the new event
- **WHEN** a valid `https://<link domain>/join#…` event link is opened for a different event on iOS ≥26.1
- **THEN** the app disables the extension, `resetTo`s the ledger from the per-device file listing, saves
  the new config, and re-registers the extension (disable→enable), and the extension's next
  cycle re-projects `device.json` from that ledger to the new event path

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `api-endpoints`)
- **THEN** the join-time load's clear-and-seed seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid event link does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger and the config are untouched)

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the join transition arms the app-driven engine instead

### Requirement: Re-registering the extension demotes orphaned REQUESTED rows

The app SHALL recover the in-flight jobs a disable wipes. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**, and no API surfaces a vanished job. Without a recovery the
rows stay `REQUESTED` forever: the engine treats `REQUESTED` as in-flight and never re-issues it, and no
cycle re-baselines the ledger — so the photos that were mid-upload are permanently abandoned.

The recovery SHALL run in the **registration** — the disable→enable re-register — **between** the
disable and the enable, and SHALL be the ledger's reset-family `demoteRequested()` (`sync-ledger`): every
`REQUESTED` row becomes `DISCOVERED`. Every one of them is unsettleable at that moment: the disable has just wiped
this tier's jobs, and wherever the app-driven engine also exists, the transition that registers disarms it
first (`upload-lifecycle`, "Membership transitions reconcile the upload mechanisms in one tested place"), so
no app-driven transfer is carrying a row either.

A `DISCOVERED` row needs a job, so the ledger's work read returns it on the next cycle without any walk
re-deriving it. The former recovery *deleted* the rows, which only a walk that re-read the asset's
resources could re-surface; a demoted row needs no such walk.

**Deregistration** SHALL be the disable alone and SHALL repair nothing — on a leave, a download-only join, and
a pin away from this mechanism alike. On a leave nothing uploads until a mechanism is brought up again, and
the leave's own ledger clear — `LeaveEvent`'s, after the deregistration, never its (capability
`leave-event`) — leaves no row to repair; where the app-driven engine is armed instead, its restart rule
repairs (`ios-url-session-upload`, "Stranded reconciliation: scoped each cycle, complete at a start"). There
SHALL therefore be no narrower teardown verb for a hand-off.

The demote SHALL be **awaited off the main thread and completed before the enable**. The write SHALL run on
`Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`), never on the `Dispatchers.Main` scope — it is a
synchronous SQLite write that on the main thread is a hang risk under cross-process WAL contention — and SHALL
use a small bounded retry around the write. `setUploadJobExtensionEnabled(true)` SHALL NOT be called until the
demote has completed, so a `REQUESTED` row the re-enabled extension records can never be demoted by a
still-running repair. The demote SHALL NOT be fire-and-forget. The bounded-retry, off-main helper is pure logic
and SHALL live in a tested `:domain` helper (`feature/upload`), not in the untested app shell.

The app SHALL use the `LedgerStore` directly (constructing no `LedgerWriter`): on this tier the extension is
the one recording process, and `demoteRequested` is a reset-family operation that a non-writer may perform.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs and `demoteRequested()` marks the rows `DISCOVERED`, so the next
  cycle's work read re-creates the not-yet-stored jobs (bytes resume landing), with no permanently-stuck
  `REQUESTED` and no re-read of the assets' resources

#### Scenario: The re-enable does not race the repair

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `demoteRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is demoted by the repair

#### Scenario: The repair runs off the main thread

- **WHEN** a re-register triggers `demoteRequested()`
- **THEN** the SQLite write executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: A deregistration repairs nothing

- **WHEN** the extension is deregistered — a leave, or a download-only join — while `REQUESTED` rows exist
- **THEN** the deregistration itself changes no ledger row, and the rows are demoted by the next bring-up
  (or removed by a leave's subsequent ledger clear)

#### Scenario: Completed rows survive the repair

- **WHEN** a re-register triggers `demoteRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are unchanged, so a subsequent discovery does not re-upload
  already-stored bytes

### Requirement: The registration cannot be changed under a partial grant

The OS-driven tier SHALL be treated as **unavailable** while the containing app holds a partial
(`.limited`) photo grant, because a partially-granted process **cannot change its upload-job registration
in either direction**.

Forcing proof: `setUploadJobExtensionEnabled` is refused with `PHPhotosErrorAccessUserDenied` (3311) for
both `false` and `true` — measured on device (SE2 / iOS 26.6, 2026-08-24 and 2026-08-25; decision record
`changes/archive/2026-08-25-collapse-upload-tier-seam`, D11 and D11b). The **enable** was reached only by
pinning the OS-driven mechanism under a partial grant through a development mechanism override, which no
shipped build can supply; in production an enable is never attempted there, because resolution never
yields this mechanism under a partial grant.

An earlier probe (SE2 / iOS 26.5, 2026-07-20;
`changes/archive/2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md`) measured that with real
pending work and the extension re-registered twice under `.limited`, the OS issued **zero** `process()`
invocations over 22 minutes, then invoked the extension **within seconds** of the grant returning to full.
That observation stands. The mechanism it was read as — *"registration succeeds and lies, with no error
and no callback"* — is **contradicted by measurement**: the call site discarded its `Boolean` and
`NSError` at the time, so "succeeds" described a return value nobody read and "no error" meant none was
looked for. A registration that could not be created explains those 22 minutes at least as economically.
Because that probe is not re-runnable, this SHALL be stated as the asserted mechanism being contradicted,
never as a claim about what that probe observed.

Evidence limits, stated so a reader can tell what would falsify this: one device, one OS point release,
and an enable reached through a development pin rather than a path a user can take. Expiry trigger:
re-evaluate at the iOS 27 GM re-assessment (~Sept 2026, the existing
`PHBackgroundResourceUploadJobExtension` trigger) — the constraint MUST be re-measured against the async
protocol before assuming it persists.

Consequently, under `LIMITED` resolution SHALL NOT yield this tier — the app-driven engine runs instead —
and no registration write SHALL be attempted there by a compared reconcile (capability `upload-lifecycle`);
only the forced writes of a join or a leave reach the platform, and their refusal is reported, not fatal. A `LIMITED` membership relying on this tier
would be a silent no-op: the screen would sit at "Synchronization pending…" indefinitely, which is exactly
the failure mode this requirement exists to prevent.

A registration that **survives** a downgrade to a partial grant SHALL be made **inert by the extension's own
gate**, not only by the OS's observed behaviour: the extension reads the grant in its own process and
withholds under anything but `GRANTED` ("The extension withholds its cycle without a full grant"). A return to
a full grant is a permission change whose compared reconcile re-registers through the disable→enable ritual
if the record is gone. There is therefore no state in which a surviving registration and a running
app-driven mechanism produce two ledger writers. Deregistration
remains both possible and required under a **full** grant, which is where a development mechanism override
places the app-driven mechanism (`upload-lifecycle`).

#### Scenario: A limited grant never waits on the extension
- **WHEN** photo access is `LIMITED` and an upload-inclusive membership has pending work
- **THEN** no upload waits on a `process()` invocation — the work runs on the app-driven mechanism

#### Scenario: A downgrade to a partial grant cannot deregister
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` while the extension is registered
- **THEN** no deregistration is attempted (it would be refused with `PHPhotosErrorAccessUserDenied`), the
  configuration record survives, and the app-driven engine is armed regardless

#### Scenario: The surviving registration causes no second writer
- **WHEN** a registration survives a downgrade to a partial grant and the app-driven mechanism is running
- **THEN** an extension invocation withholds at its gate, so exactly one process writes ledger records

#### Scenario: An enable under a partial grant is refused too
- **WHEN** the OS-driven mechanism is pinned by a development override under a `LIMITED` grant and its
  registration ritual calls `setUploadJobExtensionEnabled(true)`
- **THEN** the call is refused with `PHPhotosErrorAccessUserDenied` and no configuration record is created
