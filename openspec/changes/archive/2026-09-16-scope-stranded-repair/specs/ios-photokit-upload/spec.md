## RENAMED Requirements

- FROM: `### Requirement: Disabling the extension clears orphaned REQUESTED rows`
- TO: `### Requirement: Re-registering the extension demotes orphaned REQUESTED rows`

## MODIFIED Requirements

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1**, on a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; "enable" starts the app-driven pump and "disable" cancels it (see `ios-url-session-upload`).

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
- **THEN** the rows are demoted to `FAILED` **before** the enable is attempted, and the discovery cursor is
  left untouched, so the repair cannot demote rows belonging to the registration it is about to re-create

#### Scenario: Stopping is the disable alone
- **WHEN** the OS-driven mechanism's `stop()` runs — on a leave, or to relinquish it to the app-driven one
- **THEN** the registration is removed (or, under a partial grant, the attempt is refused) and neither the
  ledger rows nor the discovery cursor is touched; the next mechanism start repairs any row left `REQUESTED`

### Requirement: Re-provision resets sync state

On a **valid event-link (re)scan**, the host app SHALL re-provision the (possibly new) event
by persisting the config and driving the upload arm through the tier-neutral lifecycle
(`upload-lifecycle`). The mechanism below is **this tier's** (iOS ≥26.1) and SHALL NOT be applied on
the app-driven tier, which has no OS registration record to re-create (see `ios-url-session-upload`,
"App-driven lifecycle").

On this tier the re-provision's `start()` SHALL re-register the extension (the disable→enable toggle).
On its next cycle the extension reconciles against the per-device file listing (capability
`api-endpoints`, see `upload-state-reconciliation`): it **`resetTo`s** (atomic clear-and-seed)
the ledger to one already-uploaded row per stored file and **clears the discovery cursor** (forcing a
full re-enumeration). The device-global listing re-seeds the same files as already-uploaded, so
**nothing already stored re-uploads**, while the clear drops stale/phantom rows and the cursor clear
re-enumerates to find genuinely-unstored work. The re-baselined ledger is then **re-projected** to the
**new** event's `device.json` path, and the joined-event marker is set. Rows seeded from the listing are
**bare** (a filename carries no capture date) and are therefore not listed until the forced full
re-enumeration backfills their manifest detail. The app decodes the event link only to gate this on a
valid payload; the authoritative decode/validate/persist still happens in the shared container intent.

The re-provision itself SHALL NOT clear the **ledger** (`upload-lifecycle`): only the reconciliation's
`resetTo` re-baselines it, from the authoritative per-device listing. The **discovery cursor** is cleared
on this path by the reconciliation itself — not by the provisioning logic, and not by the re-register, whose
repair demotes rows instead (see "Re-registering the extension demotes orphaned REQUESTED rows"). The clear costs only a re-enumeration — the ledger it leaves intact
is what knows the work is already done.

#### Scenario: Valid re-scan reconciles and re-projects to the new event
- **WHEN** a valid `https://<link domain>/join#…` event link is opened for a different event on iOS ≥26.1
- **THEN** the extension is re-registered (disable→enable), and the next cycle `resetTo`s the ledger
  from the per-device file listing, clears the discovery cursor, and re-projects `device.json` from
  that ledger to the new event path with the joined-event marker set

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `api-endpoints`)
- **THEN** the clear-and-seed reconcile re-seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid event link does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger, cursor, and joined-event marker are untouched)

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the app-driven producer's `start()` runs instead

### Requirement: Re-registering the extension demotes orphaned REQUESTED rows

The app SHALL recover the in-flight jobs a disable wipes. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**, and no API surfaces a vanished job. Without a recovery the
rows stay `REQUESTED` forever: the engine treats `REQUESTED` as in-flight and never re-issues it, and a
same-event cycle never reconciles — so the photos that were mid-upload are permanently abandoned.

The recovery SHALL run in this mechanism's **`start()`** — the disable→enable re-register — **between** the
disable and the enable, and SHALL be the ledger's reset-family `demoteRequested()` (`sync-ledger`): every
`REQUESTED` row becomes `FAILED`. Every one of them is unsettleable at that moment: the disable has just wiped
this tier's jobs, and wherever the app-driven mechanism also exists, starting this mechanism is preceded by the
app-driven mechanism's `stop()` (`upload-lifecycle`, "The upload mechanism is resolved, never selected"), so
no app-driven transfer is carrying a row either.

The recovery SHALL NOT reset the discovery cursor. A `FAILED` row needs a job, so the ledger's work read
returns it on the next cycle with no re-enumeration; the reset existed only because the former recovery
*deleted* the rows, which a settled cursor would never re-surface.

This mechanism's **`stop()`** SHALL be the disable alone and SHALL repair nothing — on a leave and on a
relinquish to the app-driven mechanism alike. On a leave nothing uploads until a mechanism starts again, and
that start repairs; on a relinquish, the app-driven mechanism's own start repairs (`ios-url-session-upload`,
"Stranded reconciliation: scoped each cycle, complete at a start"). There SHALL therefore be no narrower
teardown verb for a hand-off.

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

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered, the discovery cursor settled)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs, `demoteRequested()` marks the rows `FAILED`, and the discovery
  cursor is untouched — so the next cycle's work read re-creates the not-yet-stored jobs (bytes resume
  landing), with no permanently-stuck `REQUESTED` and no re-enumeration

#### Scenario: The re-enable does not race the repair

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `demoteRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is demoted by the repair

#### Scenario: The repair runs off the main thread

- **WHEN** a re-register triggers `demoteRequested()`
- **THEN** the SQLite write executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: A stop repairs nothing

- **WHEN** the extension is disabled by this mechanism's `stop()` — a leave, or a relinquish to the app-driven
  mechanism — while `REQUESTED` rows exist
- **THEN** no ledger row changes and the cursor is untouched, and the rows are demoted by the next mechanism
  start

#### Scenario: Completed rows survive the repair

- **WHEN** a re-register triggers `demoteRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are unchanged, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes
