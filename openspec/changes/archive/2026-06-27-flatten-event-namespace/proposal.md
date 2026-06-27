## Why

Objects are keyed `<eventId>/<deviceId>/<filename>`. The documented driver (design.md §3.1) is
**collision avoidance** — `PHAsset.localIdentifier` is only guaranteed unique *within one library*,
so a per-device directory keeps two devices from writing the same key. In practice that hedge is
over-engineered: `localIdentifier` is UUID-based, so a flat `<eventId>/<filename>` namespace only
collides when two devices produce the *same* `localId` — either the same physical asset (identical
bytes → harmless idempotent overwrite) or a genuine UUID collision (~0). The other thing the device
level buys — anonymous per-contributor grouping for an external/future gallery — is **not a product
goal** (v1 is a one-way personal backup; there is no in-scope consumer of `deviceId`). The one
in-scope consumer, re-join reconciliation, already matches on the reinstall-stable **`filename`** and
*ignores* `deviceId` (`HttpEventFilesSource` does not even parse it).

So the `<deviceId>` level is paid-for complexity — a lazily-minted App-Group UUID, a path segment, and
a two-level directory walk in the list endpoint — with no consumer. This change removes it.

## What Changes

- **BREAKING** The storage key drops the device level: `<eventId>/<deviceId>/<filename>` →
  `<eventId>/<filename>`.
- **BREAKING** The upload route drops the device label: `PUT /event/<eventId>/device/<deviceId>/file/<filename>`
  → `PUT /event/<eventId>/file/<filename>`. The backend validates only `eventId` as a UUID.
- **BREAKING** The list endpoint stops aggregating across device sub-directories: it now does a
  **single** non-recursive LIST of `<eventId>/` (files are direct children). The two-level fan-out
  and its per-device partial-failure handling are deleted.
- **BREAKING** The list entry shape drops `deviceId`: `{ filename, deviceId, size, lastModified }` →
  `{ filename, size, lastModified }`. (The re-join consumer already ignores it — no consumer impact.)
- The on-device `EdgeUploadRequestProvider` drops its `deviceId` constructor input and the
  `/device/<deviceId>/` URL segment; its idempotency tuple becomes `(host, eventId, filename)`.
- The extension's **device-id store is removed**: the lazily-minted App-Group `deviceId`
  (`DeviceIdStore`/`DeviceIdProvider`) is deleted, along with the "device id unavailable → no-op"
  branch in config assembly. Provider config now comes from **two** sources (host + eventId).
- `:capability:rejoin` (`HttpEventFilesSource`) is **unchanged** in behavior (already filename-only);
  only a stale `deviceId` mention in its doc comment is dropped.
- **Last-write-wins widens in scope** (accepted): with device-scoped keys, the existing
  `bunny-upload-endpoint` last-write-wins rule was effectively *intra-device*; under a flat namespace
  the same key is reachable by two devices, so an overwrite can now be cross-device. For a shared
  `localId` this is the same physical asset (identical bytes); a distinct-photo overwrite needs a
  UUID collision. Recorded as an explicit, accepted trade-off.
- **No data migration.** No production/TestFlight data depends on the nested layout; the bunny zone's
  disposable test objects are cleared. A flat LIST of `<eventId>/` would not see objects stored under
  the old nested layout, so this is a clean break, not a back-compat shim.

### Foreclosed by this change (accepted)

Flattening permanently gives up **anonymous per-contributor grouping** in any future/external gallery
and **clean per-device deletion** (a future "leave event / remove my photos" feature would need a
different identity mechanism, e.g. a contributor id folded into the filename). Both were judged out of
scope for v1's one-way personal backup; this note keeps the decision legible.

## Capabilities

### Modified Capabilities
- `bunny-list-endpoint`: single-directory listing replaces cross-device aggregation; entry shape
  drops `deviceId`; faithful-outcome and completeness reworded for a single LIST.
- `bunny-upload-endpoint`: route and storage key drop the device level; only `eventId` is validated
  as a UUID.
- `edge-upload-provider`: URL, configuration contract, and idempotency tuple drop `deviceId`.
- `ios-background-upload`: the App-Group device-id store requirement is removed; config assembly
  drops the `deviceId` source and its no-op branch.
- `deeplink-config`: drops a `deviceId` mention in the destination-composition note.

### Unaffected (verified)
- `event-rejoin-reconciliation`: its `EventFilesSource` seam already requires only
  `filename`/`lastModified`; no requirement changes.
