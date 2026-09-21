## MODIFIED Requirements

### Requirement: Leave use-case resets local event state

The capability SHALL provide a `LeaveEvent` use-case that tears down the configured event's **local**
state and notifies the backend, best-effort, in this order: (1) **disable** the background-upload
producer, then (2) **clear the upload ledger** (`LedgerStore.clear()`), then (3) **clear the persisted
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

The producer is disabled **first** so no mechanism starts new work against rows about to vanish. The ledger
is cleared **before** the config so a device that has left never shows a stale share set; that order is not
needed for correctness, because the next join clears the ledger anyway before loading it (capability
`join-event`).

A completion that arrives **after** the clear — a background transfer or an OS upload job already in
flight — finds no row: it SHALL be acknowledged and discarded, writing no row. Its bytes are on the backend
with no row, and the next join's load from the device's stored-file listing marks them `COMPLETED` (or, if
that load fails, they re-upload idempotently to the same destination). Nothing is lost and nothing loops.
Rows an upload cycle already running at the leave may still write after the clear are cleared by the next
join, and no deciding reader acts on them in between.

The platform side-effects — disabling the producer and the backend notify — SHALL be injected as suspend
lambdas (the notify as `suspend (eventId: String) -> Unit`), so the use-case is pure `commonMain` logic and
the app shell stays wiring-only. The use-case SHALL reach the ledger only through the `LedgerStore` port and
SHALL construct **no** ledger type.

#### Scenario: Leaving disables the producer, clears config, and notifies the backend

- **WHEN** `LeaveEvent` runs with an event configured
- **THEN** the producer is disabled first, then the upload ledger is cleared, then the config is cleared, then the backend leave is notified with the snapshotted `eventId`, and no download-store or `EventStatus` operation is performed

#### Scenario: Leaving keeps the download store

- **WHEN** `LeaveEvent` runs on a device that has imported foreign photos
- **THEN** every download-store row, and each imported row's recorded local asset identifier, is left
  intact, so the upload path still suppresses those imports

#### Scenario: A completion after the clear is discarded and healed by the next join

- **WHEN** an upload that was already in flight completes after the leave cleared the ledger
- **THEN** the completion is acknowledged and discarded with no row written, and a later join's load from
  the device's stored-file listing records that resource as `COMPLETED`

#### Scenario: After leaving, the gate yields the setup screen

- **WHEN** a leave completes its local teardown (disable + ledger clear + config clear)
- **THEN** `ConfigSource.config` is `null` and the presentation reduces to the setup gate (storage not connected), regardless of whether the backend notify has completed

### Requirement: Leave is best-effort with no rollback

A failing step SHALL be logged and SHALL NOT roll back earlier steps; there is no transaction across the
producer registration, the upload ledger, the config store (the App-Group config file — capability
`event-link`), and the backend notify. The step order — disable producer, clear the upload ledger, clear
config, then notify backend — SHALL be chosen so the worst partial outcome self-heals: a failed backend
notify SHALL NOT abort or reverse the local teardown (the device still leaves locally; the un-removed
backend membership is the accepted abandon-leak), and if the config clear fails, the event remains
configured (the user is simply still joined, with the producer disabled until the next enable) rather
than leaving a half-torn-down state — the store's own clear ordering (Keychain copy first, file second)
guarantees a partial clear leaves the file, and therefore the membership, intact rather than a state the
migration fallback would resurrect. The backend notify SHALL be dispatched **unconditionally** after the
clear step — a failed `clear()` SHALL NOT suppress it — preserving the independence of each best-effort
step; the resulting transient state (backend told the device left while it is still joined locally)
self-heals when the producer re-enables and re-writes the device manifest. A failed **ledger clear** SHALL
NOT suppress the config clear or the notify: the device still leaves, and the rows it failed to clear are
cleared by the next join before that join loads the ledger (capability `join-event`), so a leftover
`COMPLETED` row can never suppress a needed upload. A config clear that fails **after** a successful ledger
clear leaves the user joined with an empty ledger: the next cycle re-derives the rows from the library, and
at worst re-uploads resources the backend already holds, idempotently to the same destinations.

#### Scenario: A failed backend notify still completes local teardown

- **WHEN** the `HttpLeaveNotifier` call fails (offline, timeout, or error)
- **THEN** the failure is logged, the config has already been cleared, and the device leaves locally; the backend membership is simply not removed

#### Scenario: A failed config clear leaves the user joined, not corrupted

- **WHEN** the producer has been disabled but `ConfigStore.clear()` fails
- **THEN** the event is still configured and consistent; re-running leave retries the clear, and the only
  cost of the already-emptied ledger is an idempotent re-upload of what the backend already holds

#### Scenario: A failed ledger clear still leaves

- **WHEN** the producer has been disabled but clearing the upload ledger fails
- **THEN** the failure is logged, the config is still cleared and the backend still notified, and the
  leftover rows are cleared by the next join before it loads the ledger

### Requirement: Leave action is presented only in the joined layer

The presentation layer SHALL expose an `onLeaveEvent()` intent that invokes the `LeaveEvent`
use-case. The leave affordance SHALL be offered to the user **only** while the screen is in the
joined layer — defined as **config present** (the `UiState.Joined` state, any health including
`NeedsAccess`) — and SHALL NOT be offered in the loading or create-layer states. Restricting the
affordance to the joined layer guarantees no join is in flight when a leave runs, so the leave needs
no cancellation of, and no coordination with, a concurrent join. (Leave is available even when
permission is not granted — a user may leave regardless of access.)

#### Scenario: The leave intent invokes the use-case
- **WHEN** `onLeaveEvent()` is invoked
- **THEN** the `LeaveEvent` use-case runs its disable → ledger clear → config clear sequence

#### Scenario: Leave is offered across all joined health states
- **WHEN** the screen is in `UiState.Joined` with health `NeedsAccess`, `Syncing`, or `InSync`
- **THEN** the leave affordance is presented

#### Scenario: No leave affordance outside the joined layer
- **WHEN** the screen is in the loading or create-layer state
- **THEN** no leave affordance is presented

### Requirement: Local teardown does not block on the backend notify

The `LeaveEvent` local teardown (disable producer + upload-ledger clear + `ConfigStore.clear()`) SHALL
complete without
awaiting the backend `DELETE`. The backend notify SHALL be dispatched fire-and-forget on an injected
app-lifetime `CoroutineScope` (owned by the composition root, outliving the screen transition), so
`ConfigSource.config` goes `null` — and the screen leaves the joined layer — with latency independent of
the DELETE's round-trip. This non-blocking behavior SHALL apply to **both** the explicit leave and the
switch path: on a switch, the departed event's DELETE SHALL NOT delay the enroll/provision of the new
event (the "Joining …" surface no longer waits on it).

#### Scenario: The screen flips before the DELETE completes

- **WHEN** the user confirms leaving and the backend `DELETE` is slow or never completes
- **THEN** the local teardown returns promptly, `ConfigSource.config` becomes `null`, and the presentation reduces to the setup gate without waiting for the DELETE

#### Scenario: A switch does not wait on the departed event's DELETE

- **WHEN** the user confirms switching to a different event while joined
- **THEN** the departed event's `DELETE` is dispatched fire-and-forget and the new event's enroll/provision proceeds without blocking on it

### Requirement: A confirmed-gone event tears the membership down without user action

The device SHALL return itself to the unjoined resting state when its configured event is confirmed gone,
running the **same** local teardown the user's explicit Leave performs (capability `leave-event`: stop the
producer, clear the upload ledger, clear the persisted config, then notify the backend best-effort — the notify is expected to
fail against a deleted event and its failure changes nothing).

The teardown SHALL fire only when **two independent witnesses agree**:

1. an event-details fetch resolves to a **definitive absence** — the sealed `NotFound` outcome of the one
   details client (capability `join-event`), never a transport failure, a timeout, a non-404 status, or an
   unparseable body, all of which resolve as inconclusive; **and**
2. the membership's **own persisted `deletesAt` has passed**, compared against the device's clock.

Neither witness alone SHALL be sufficient. A membership whose `deletesAt` is absent (persisted before the
field existed and not yet backfilled) SHALL never satisfy the second witness and SHALL therefore never
self-leave.

**Why two.** The persisted config is the only record of the join, and the invite QR is derived from the
`eventId` it holds — so a wrongful teardown is unrecoverable, and a systemic backend fault that answered
`404` for every event would otherwise destroy every membership in the install base at once. A
misconfiguration cannot move the device's own clock, so requiring an offline witness bounds that failure
to "every device declines to act". This is not a heuristic: emptiness-driven deletion (capability
`scheduled-cleanup`) requires every enrolled device to have departed, and departing clears the config
before the backend is notified — so a device that still holds a membership can only ever be observing a
**deadline** deletion, which is exactly what the second witness tests.

The teardown SHALL fire from the **foreground** trigger path only. The background trigger flows SHALL
keep their existing guarantee that no fetch outcome is destructive: a background wake may run against a
config that could not be read, and reading unreadable as absent there would destroy a healthy membership.
The foreground path re-reads the persisted membership from an unlocked device before any consumer runs,
which is the only context in which the destructive branch is safe.

Every failure mode of this rule SHALL resolve toward **keeping** the membership. A device that never
enrolled a manifest may outlive an emptiness sweep and hold a membership for an event that is already
gone; it is corrected at its deadline rather than earlier, and no photo or identifier is lost in the
interim.

#### Scenario: A deleted event past its deadline tears the membership down

- **WHEN** a foreground details fetch for the configured event resolves to a definitive `NotFound` and
  the membership's persisted `deletesAt` has passed
- **THEN** the device stops the producer, clears the upload ledger, clears the persisted config, and returns
  to the unjoined resting state — the same teardown an explicit Leave performs

#### Scenario: A 404 before the deadline is disbelieved

- **WHEN** a foreground details fetch resolves to a definitive `NotFound` while the membership's
  persisted `deletesAt` has **not** passed
- **THEN** the membership is left completely intact and syncing continues

#### Scenario: An inconclusive fetch after the deadline changes nothing

- **WHEN** a foreground details fetch fails on the network, times out, or returns a non-404 error, while
  the membership's persisted `deletesAt` has passed
- **THEN** the membership is left completely intact — absence was never confirmed

#### Scenario: A membership with no persisted deadline never self-leaves

- **WHEN** a membership persisted before `deletesAt` existed observes a definitive `NotFound` at any time
- **THEN** the membership is left intact, and it becomes eligible only once a reconcile has backfilled its
  deadline

#### Scenario: Background triggers never tear down

- **WHEN** a silent push or a background task wake runs while the configured event is in fact deleted
- **THEN** no teardown occurs from that trigger, and the membership is cleared only on a subsequent
  foreground entry that satisfies both witnesses

#### Scenario: The failed backend notify does not matter

- **WHEN** the self-leave's best-effort backend notify fails because the event no longer exists
- **THEN** the local teardown has already completed and the failure is logged and ignored

