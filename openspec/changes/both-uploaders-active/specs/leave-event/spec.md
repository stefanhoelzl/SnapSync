## MODIFIED Requirements

### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state and notifies the backend, best-effort, in this order: (1) **stop the uploads** — the upload arm's
leave transition (capability `upload-lifecycle`): **deregister** the upload extension where it is
registered, **cancel the app's in-flight transfers** (deleting their staged files), and stop the app's
heartbeat — then (2) **clear the upload ledger** (`LedgerStore.clear()`), then (3) **clear the persisted
config** (`ConfigStore.clear()`), then (4) **notify the backend** that this device is leaving via
`HttpLeaveNotifier` (`DELETE /events/<eventId>/devices/<deviceId>`).
The `eventId` and `deviceId` SHALL be read **before** the config is cleared; the `eventId` is
snapshotted synchronously (from `ConfigSource.config`) into the use-case's own frame before the clear
and passed into the notify, so the notify targets the correct event with no race against the cleared
config. The backend notify SHALL be dispatched **fire-and-forget** on an injected app-lifetime
`CoroutineScope` so it does not block the local teardown (see "Local teardown does not block on the
backend notify").

The upload ledger is the **current membership's share set** (capability `sync-ledger`), so a leave empties
it. This **reverses** this requirement's earlier rule that a leave never touches the ledger and that the
extension reset its ledger and a `joinedEventId` marker on its next join: there is no marker and no
extension-side reset any more, and every change of membership is an explicit app action. Only the
**upload** ledger is cleared. The **download store** SHALL NOT be touched by the leave use-case: its
handle-carrying rows are permanent (capability `download-store`), because they are what stops the device
uploading its own imports back into an event. The use-case SHALL touch no `EventStatus`.

The uploads are stopped **first** so neither uploader starts new work against rows about to vanish. The
cancel SHALL be explicit in the leave: disarming the app's uploader only stops its heartbeat and cancels
nothing, because a revoke or a reconfigure lets in-flight transfers finish — the leave (a switch's leave
included) is the **only** transition that cancels transfers and the only one that deregisters the
extension. Decision record: `changes/both-uploaders-active`. The ledger
is cleared **before** the config so a device that has left never shows a stale share set; that order is not
needed for correctness, because the next join clears the ledger anyway before loading it (capability
`join-event`).

A completion that arrives **after** the clear — a background transfer or an OS upload job already in
flight, including a cancelled transfer's `-999` — finds no row: it is still recorded through the
transport's **guarded** terminal write, which applies only to a `REQUESTED` row and so matches none; it
SHALL therefore be acknowledged and discarded, writing no row. Its bytes are on the backend
with no row, and the next join's load from the device's stored-file listing marks them `COMPLETED` (or, if
that load fails, they re-upload idempotently to the same destination). Nothing is lost and nothing loops.
Rows an upload cycle already running at the leave may still write after the clear are cleared by the next
join, and no deciding reader acts on them in between.

The platform side-effects — stopping the uploads and the backend notify — SHALL be injected as suspend
lambdas (the notify as `suspend (eventId: String) -> Unit`), so the use-case is pure `commonMain` logic and
the app shell stays wiring-only. The use-case SHALL reach the ledger only through the `LedgerStore` port and
SHALL construct **no** ledger type.

#### Scenario: Leaving disables the producer, clears config, and notifies the backend

- **WHEN** `LeaveEvent` runs with an event configured
- **THEN** the uploads are stopped first — the extension deregistered, the app's in-flight transfers cancelled, its heartbeat stopped — then the upload ledger is cleared, then the config is cleared, then the backend leave is notified with the snapshotted `eventId`, and no download-store or `EventStatus` operation is performed

#### Scenario: Leaving keeps the download store

- **WHEN** `LeaveEvent` runs on a device that has imported foreign photos
- **THEN** every download-store row, and each imported row's recorded local asset identifier, is left
  intact, so the upload path still suppresses those imports

#### Scenario: A completion after the clear is discarded and healed by the next join

- **WHEN** an upload that was already in flight completes after the leave cleared the ledger
- **THEN** its guarded terminal write matches no row, the completion is acknowledged and discarded with no
  row written, and a later join's load from the device's stored-file listing records that resource as
  `COMPLETED`

#### Scenario: Leaving cancels the app's transfers even though disarm does not
- **WHEN** `LeaveEvent` runs while the app's uploader has transfers in flight
- **THEN** those transfers are cancelled and their staged files deleted by the leave itself, and a
  late `-999` for any of them writes no row

#### Scenario: After leaving, the gate yields the setup screen

- **WHEN** a leave completes its local teardown (disable + ledger clear + config clear)
- **THEN** `ConfigSource.config` is `null` and the presentation reduces to the setup gate (storage not connected), regardless of whether the backend notify has completed
