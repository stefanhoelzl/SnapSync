## Why

The device manifest is a projection of what has **finished uploading**. The backend's v2 schema was
built for a manifest that declares what a member **will provide** — `event_assets.roles` records the
roles an asset declares, and the union serves an asset only when every declared role has arrived — so
the backend can already tell *"this photo is coming"* from *"this photo does not exist"*. Nothing
supplies it that distinction, because the device only ever lists resources whose bytes already landed.

`api-endpoints` already states the target as fact — *"This check is now the primary completeness
mechanism… It became so when the manifest started declaring what a member contributes rather than only
what it had already uploaded"* — while `device-manifest` still requires *"exactly the ledger's
COMPLETED rows"*. Two capabilities in the contract of record disagree; this change makes one true.

It also closes a live defect. A recipient plans downloads per **asset**, not per resource
(`DownloadController`: `if (store.isSettled(ref)) continue`, keyed on `deviceId`+`assetId`). When a
Live Photo's `primary` and `live` complete in different cycles, the union serves a one-resource asset
in between, and a recipient reconciling in that window imports it as a plain **still**, marks it
settled, and never picks up the video. Declaring both roles up front keeps the photo hidden until it
is whole.

## What Changes

- The manifest projection becomes **state-blind**: every non-absent ledger row carrying manifest
  detail is listed, whatever its upload state. `FAILED` included — retry is forever, so a failed
  resource is still intended, and the declared role set never oscillates.
- `LedgerStore.completedManifestRows()` becomes `manifestRows()`. The name carries no state adjective,
  because a stale adjective is what produced the contradiction above.
- The projection's `creationDate != ''` SQL predicate is **removed**. It duplicates an admission rule
  the policy already owns (`CaptureAfter` — *"the one place a missing fact excludes rather than
  admits"*), and a rule stated twice is the defect class `photo-selection-policy` exists to prevent.
  The store filters row facts (`absent`); the policy decides admission.
- **The fan-out gains its correct trigger.** Today the backend wakes members as an effect of the
  manifest publish, and that is the only trigger. Under intent a `DISCOVERED` row and its later
  `COMPLETED` row project byte-identical fields, so the publish no longer coincides with the union
  growing: members would be woken when a photo is *declared* (still hidden) and never when it becomes
  fetchable. The byte route SHALL notify when its resource was the last declared role missing, and the
  manifest route's notify becomes conditional on the publish making an asset newly fetchable.
- Silent pushes carry `apns-collapse-id: <eventId>`. A wake's payload is `{content-available, eventId}`
  and the receiver's response is "reconcile the union", so any two wakes for one event are
  interchangeable and collapsing loses nothing — while APNs delivers no more than two or three
  background notifications per hour.
- A per-event lookup index for the byte route, which names no event in its path.
- The wire format does **not** change. Intent is expressed by listing a resource; every field of an
  entry is knowable before its bytes move.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `device-manifest`: the projection is what the device **intends to provide**, not its COMPLETED rows.
- `sync-ledger`: `DISCOVERED` and `UPLOADED` no longer state that they are excluded from the manifest
  projection; the manifest read is renamed and loses its state scope.
- `upload-completion-notify`: the fan-out gains a second trigger (the byte route) and the manifest
  trigger becomes conditional. The "a retraction wakes members, and that is accepted" scenario is
  removed — it stops happening.
- `api-endpoints`: the v2 byte-upload route notifies on asset completion; the manifest route's notify
  is conditional.
- `database`: the byte route's per-event lookup index, and its migration.
- `apns-push-sender`: the header set gains `apns-collapse-id`.
- `photo-selection-policy`: one clause described the manifest as a projection of the ledger's `COMPLETED`
  rows; the rule it belongs to (origin exclusions applied before the ledger) is unchanged.

## Impact

**Device** — `domain/compose/UploadCore.kt` (one call site), `domain/ports/LedgerStore.kt`,
`domain/feature/upload/LedgerWriter.kt`, both `LedgerStore` implementations, `Ledger.sq`,
`domain/model/DeviceManifest.kt` and `LedgerState` (documentation), `DeviceManifestProducer`.

**Backend** — `api/src/app.ts` (both v2 write routes), `api/src/db.ts` (a completeness read),
`api/src/apns.ts` (one header), `api/migrations/0002_*.sql` (the index) and the regenerated
`api/schema.sql`.

**Behaviour** — the event union's output is unchanged except that a partially-uploaded asset can no
longer be served shrunk to its landed resources. Push volume falls: declaration-time and
retraction-time wakes stop, and wakes for one event coalesce.

**Not in scope** — moving the union's completeness filter from JS into SQL (an unmeasured
optimisation; `D8` of `add-v2-device-api` decided the *semantics*, which the current code already
implements). Retiring the upload ledger, and making the cycle's skip decision a difference against the
backend, are separate changes.
