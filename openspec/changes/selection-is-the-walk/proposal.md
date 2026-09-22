## Why

Under a partial (`LIMITED`) grant the selection is the member's own-photo scope. But the ledger, and so the
device manifest, still treats it as "not the library": a photo the member de-selects stays listed in the
event for good. The phone showed a second gap too (SE2, iOS 26.6, 2026-09-22). Four uploads were queued
under a full grant, access was then narrowed to `LIMITED`, and the network came back: all four objects
reached the backend within ~30 s. The extension, though, was presented none of them (`drainTerminals = 0`).
Their rows stayed `REQUESTED`, and the status read `Syncing` until full access returned. The member was
told the device had work outstanding when the backend already held every byte.

Checking the handoff against the tree found a third gap, already live today. Before the first selection
read, the unread selection becomes `Scoped(emptyList())`. The enqueue then resolves every admitted
`DISCOVERED` row against that empty snapshot and deletes each one as "its asset is gone"
(`UploadCycle.createChunk`).

## What Changes

- **De-selecting is deleting.** Under `LIMITED`, a selection snapshot that has been **read** is an
  authoritative walk (`fullEnumeration = true`), exactly like a full-library walk under `GRANTED`. The other
  policies still decide what may be shared. A photo absent from the snapshot is removed from the ledger, and
  so from the device manifest the same cycle publishes. Re-selecting it re-uploads the same object
  (idempotent `PUT`). **BREAKING** (behavior): `limited-photo-access`'s "deselection is not withdrawal" and
  `device-manifest`'s "a partial grant retracts nothing" are reversed.
- **An unread selection is not an empty one.** A new `SelectionScope.Unread` state is added. While photo
  access is `LIMITED` and the selection has not been read yet, the app's cycle is **Withheld**: it settles
  narrowly, reads nothing, creates nothing, deletes nothing. This fixes the live deletion above. The false
  KDoc on `PermissionAwareCandidateSource` is corrected.
- **Removal reaches in-flight rows.** An authoritative walk also deletes `REQUESTED` rows of assets it no
  longer returns, whether a library deletion or a de-selection. The in-flight transfer still finishes. Its
  guarded terminal write finds no row and does nothing. The bytes stay in storage, listed in no manifest.
- **A job whose row is gone is answered and forgotten.** Every settle and retry path must handle a key
  with no ledger row: `acknowledgePresented`, `recreateRetrySpent`, `adjudicateFailure`, and the
  `fetchRetryJobs` loop. Each one acknowledges such a job to the OS and writes nothing. It never retries
  the job and never recreates the row. Today a late failure re-records the deleted row as a bare
  `DISCOVERED` row. That row is never admitted and never deleted, and it counts as pending forever. The
  PhotoKit adapter reports a destination whose row was pruned at `Info`, not as an `Error`-level
  "unrecoverable" job.
- **Foreground asks the backend.** On foreground, in the app process only, the app lists
  `GET /files/devices/<deviceId>`. It marks every `REQUESTED` row whose file the backend stores as
  `COMPLETED`, through the existing guarded `markTerminal(key, COMPLETED)`. It never touches another state
  and never marks a key that has no stored bytes. The upload cycle still never fetches the listing, and no
  pull-to-refresh gesture is added.
- **Rider, batch size 1.** `RESOLVE_CHUNK` and the chunking in `UploadCycle.enqueue` are deleted. For each
  admitted row the cycle resolves it, creates its job, and stops at the first `LIMIT_EXCEEDED`. Measured
  `resourcesFor` cost: ~4.5 ms per request + ~3.45 ms per photo. That is 0.80–0.94 s per 100 photos at
  batch 1, against 0.46 s at batch 4. It applies only under `GRANTED`, because under `LIMITED` keys resolve
  from the snapshot in memory. The spec's "11 ms per key" figure is corrected.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `limited-photo-access`: a read snapshot is authoritative and de-selection removes the photo from the
  event; an unread snapshot withholds the app's cycle; the downgrade no longer keeps de-selected photos
  listed.
- `sync-ledger`: the selection is presence, not policy; deletion drops the `REQUESTED` exemption; a read
  selection snapshot counts as authoritative; the work source resolves one row at a time; the guarded
  terminal write gains the foreground settle as a caller.
- `device-manifest`: under a partial grant, de-selection retracts the photo from the manifest.
- `upload-lifecycle`: the app's admission withholds while the selection is unread. A new requirement
  covers how a presented job whose row is gone is answered.
- `upload-state-reconciliation`: a new foreground settle of `REQUESTED` rows against the per-device
  listing. The "no later foreground fetches the listing" wording of the failed-load rule is narrowed to
  "nothing re-seeds".
- `ios-url-session-upload`: the producer resolves and creates per row, with no chunk; the cost figure is
  corrected.
- `ios-photokit-upload`: a job whose row was pruned is acknowledged at `Info` and never reaches the
  cycle; the extension's deletion reaches `REQUESTED` rows.

## Impact

- `:domain` `model/`: `SelectionScope` (+`Unread`) and the `selectionScope` derivation.
- `:domain` `feature/upload`: `UploadCycle` (departedKeys, enqueue, and the no-row skip on the failure
  paths), `SelectionScopedDiscovery` (authoritative when read; refuses to answer when unread),
  `appAdmission`, and a new foreground settle use-case.
- `:domain` `compose/`: `SnapSyncApp` (admission input, and wiring the settle as a `Foreground` effect),
  plus the KDoc on `PermissionAwareCandidateSource`.
- `:domain` `flow/Foreground`: one more launch.
- `:adapter:ios:ext-safe` `IosPhotoKitUploadPlatform`: classifies a pruned row.
- Tests: `UploadCycleTest`, `SelectionScopedDiscoveryTest`, `CycleGateTest`, the new settle test, and
  `:test:world` / `:test:integration` scenarios.
- No schema change, no backend change, no new port operation. The per-device listing endpoint already
  exists.
- Sequencing: phase 8 ("manifest versions") also touches `device-manifest`. The two are independent;
  whichever merges second rebases its delta.
