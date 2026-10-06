# Design

## Context

See proposal.md (Why). Current state, from the code:

- **Bytes**: `files/devices/<deviceId>/<assetId>-<role>.<ext>`. The backend composes the name
  (`legacyKeyFor`), and v1 and v2 compose it identically so a mixed-version event addresses one object.
- **`resources`** is keyed `(device_id, asset_id, role)`, sits outside the event cascade, and stores `key`
  (the bare object name) with `UNIQUE(device_id, key)` for the sweep's lookup by name. The union joins
  `event_assets` → `resources` on device and asset, so one byte serves every event that declares it.
- **Byte routes**: v2 `PUT /files/devices/<d>/<a>/<r>?filename=` and v1 `PUT /files/devices/<d>/<filename>`.
  Neither names an event or checks membership. v2's record is not best-effort; v1's is, and v1's manifest
  publish re-creates missing rows.
- **Listing**: v2 `GET /files/devices/<d>` (`{assetId, role, filename}`) feeds the join-time load
  (`ShareSetLoad`), which marks every listed resource `COMPLETED` in the new membership's ledger.
- **Presigns**: minted per request (the v2 redirect, v1's union and listing) by `presignDownloadUrl(deviceId,
  filename)`. They are valid 7 days, and an iOS background download resumes from the presign and never
  revisits the redirect.
- **Sweep**: EVENT phase (drop/complete; completion deletes memberships, so `event_assets` go, and keeps the
  event row), ASSET phase (per-byte collection of unreferenced legacy bytes below a floor, row before byte),
  DIRECTORY step (empty `files/devices/<d>/`).
- **Client**: `EdgeUploadRequestProvider` builds the byte URL. `UploadCycle` builds one per cycle from the
  gate's config, which already carries the event. The ledger records each request's destination path
  (`recordRequested`), and an OS job's completion is matched back by that path (`entryForDestination`).
- The publish rewrites `event_assets(e,d)` as DELETE + INSERT (`db.ts`, `publishStatements`).
- Single active membership is the current contract (`openspec/config.yaml`, named futures).

## Goals / Non-Goals

**Goals:**
- From the deploy on, every new byte is stored under its event, whichever build or API version sent it.
- Every read addresses bytes through a stored path, so no reader knows or composes a layout.
- No photo already shared stops arriving, no forced update, and no byte moves in this change.
- An event's bytes can be deleted, and later encrypted, as a unit.

**Non-Goals:**
- Per-event encryption.
- Copying old-layout bytes into event folders, and deleting `files/devices/` (the follow-ups below).
- Retiring v1 or the event-less v2 routes.
- Concurrent multi-event membership.

## Decisions

### D1. Path `files/<eventId>/<sha256 hex>`

The name is `SHA-256("<eventId>/<deviceId>/<assetId>/<role>")` in lowercase hex. The ids are validated
segments, so `/` cannot occur inside one and the input is unambiguous. It is deterministic, so a re-upload,
and the two uploaders' overlap, overwrite one object with no lookup on the streaming hot path, exactly as
today. Including the event means the same photo has unrelated names in two events, so nothing links them
once they are encrypted. There is no `events/` segment: an event id is a UUID and can never equal
`devices`. There is no extension: the content type is stored on PUT and in the row, and the event page
names zip entries from the union's `filename`/`key`.

Alternatives: a random id per upload (it orphans the old object on every re-upload and needs the row
lookup to find it); an HMAC with a server secret (a lost or rotated secret orphans every name, for no gain
over presigned access); a flat `files/<hash>` (deleting an event becomes per-object, and finding orphans
needs a full-zone listing).

### D2. `resources` reused, rebuilt event-scoped, `key` dropped

`resources(event_id, device_id, asset_id, role, path, content_type, filename)`, with PK
`(event_id, device_id, asset_id, role)` and FK `(event_id, device_id) → memberships ON DELETE CASCADE`.
`path` is the full storage path, and the union, redirect, listings, v1 presigns and sweep all follow it.
`key` and `UNIQUE(device_id, key)` go. The wire `key` (the union's, v1 listing's `filename`) is derived with
`legacyKeyFor(assetId, role, filename)`, the value v2 stored, so old clients see the same string.

The FK targets `memberships`, not `event_assets`, because the publish deletes and re-inserts
`event_assets(e,d)`, which would wipe every resource on every publish, and because a byte may land before
the manifest declaring it. A rejoin upserts the membership (`ON CONFLICT DO UPDATE`), so rows survive a
leave and rejoin, and a switch away and back, while the event lives. Completion deletes memberships, and
drop deletes the event; either way the cascade takes the rows.

Alternative: a separate `event_resources` beside the legacy table. Rejected once every route writes the new
layout (D4): it would keep an either-layout union, listing and redirect alive until the follow-up, for no
benefit.

### D3. Migration `0010` expands the device-wide rows in place

The table is rebuilt (SQLite cannot alter a PK), staged through an ordinary table as `0008` did. Each old
row `(d, a, r)` becomes one row per event in
`{events whose event_assets declare (d, a) with role r} ∪ {d's present membership}`, keeping
`path = files/devices/<d>/<old key>`. A row with a membership in neither set is dropped. Its byte is left
for the follow-up deletion. Declared events include gone members' events, whose memberships still exist,
so their photos stay in the union. The present membership covers a byte that landed before its
declaration. Bytes are not touched.

### D4. One write rule for every byte route

- **New**: `PUT /api/v2/events/<e>/files/devices/<d>/<a>/<r>?filename=`, the same path as the download
  redirect, but token-gated (the redirect's exemption is GET/HEAD only, and `isGatedRequest` /
  `GatedPaths.kt` must keep the PUT gated; both already do). It requires `d`'s membership in `e` to be
  `sharing` or `settled`, otherwise 403. A **closed** event still takes its members' bytes: settling never
  waits for uploads, so refusing them would lose declared photos. A completed event has no members left, so
  the same check refuses it. It writes `files/<e>/<hash>`, then the row, with the completion lookup,
  union-log gains, landing stamp and wake as today, scoped to `e`.
- **Event-less** (v2 `PUT /files/devices/<d>/<a>/<r>`, v1 `PUT /files/devices/<d>/<filename>`): `e` is `d`'s
  single present membership. With none, the answer is 409. Then the same write as above. v1's record stays
  best-effort, and v1's publish-time row re-creation computes the same deterministic path, so the repair
  still holds (an existing row keeps its path, which may name a pre-0010 byte). v1 now parses the object
  name BEFORE storing, since the path needs the identity, so a name that is not `<assetId>-<role>.<ext>` is
  `400` instead of stored-but-unrecorded; no shipped client produces one.
- The completion lookup (`eventsCompletedBy`) becomes per event, since a byte now completes at most one
  event.

Alternative: keep the event-less routes on the old layout. Rejected: legacy writes would never stop, and
two layouts would live for as long as old builds exist.

### D5. Listings answer for one event

- New `GET /api/v2/events/<e>/files/devices/<d>` returns `resources(e, d)`, same shape as today.
- The old `GET /files/devices/<d>` returns the rows of `d`'s present membership, or `[]` with none.

An old build therefore never marks a byte stored in another event as done, which would leave the asset
incomplete in the new event forever. The join-time load runs after the join, so the present membership is
the joined event.

### D6. Presigns from `path`

`presignDownloadUrl` takes the stored path. An already-issued presign keeps pointing at its object, and
nothing in this change deletes an old-layout byte, so every issued URL stays valid for its 7 days.

### D7. Deletion by event folder

Rows go by cascade. The sweep, after an event completes or is dropped, issues a recursive DELETE of
`files/<e>/`. Every run also lists the top level of `files/` and deletes any `<uuid>/` folder whose event
row is gone or completed. That covers a crash between the DB step and the folder delete, and a PUT that
passed the membership check just before completion and landed after the delete. Only folders named by an
event id are considered, so `devices` (and `site/`) are never touched. The listing is taken first and the
live set read after it, on the primary: a listed folder had its event row committed before its first byte,
and a stale or earlier read could delete a live event's photos. The legacy ASSET phase and DIRECTORY step
are **removed** rather than suspended: they addressed bytes by the dropped `key` column and per-device
references, which no longer describe the migrated rows, and the follow-up deletes `files/devices/`
outright anyway. Device-record collection stays as it was.

### D8. Client: the new route, nothing else

`EdgeUploadRequestProvider` takes the event id from the cycle's config and builds the event-scoped URL.
`HttpBackend`'s device listing takes the event. iOS and Android switch in the same build. A job created by
an old build keeps its old URL. It lands on the event-less route (D4), and its completion matches the
ledger row by the destination path recorded at request time, so nothing is cancelled or re-uploaded.
`BackendMock` mirrors D2, D4 and D5.

## Risks / Trade-offs

- [Overlapping events re-upload] → Accepted, and the spec is relaxed. Events rarely overlap, and per-event
  bytes are the precondition for per-event keys.
- [An event-less upload after a switch lands in the new event] → Undeclared there, so it is never served,
  and it is deleted with that event's folder.
- [An upload from a device no longer present is refused (403 / 409)] → Nothing would serve it: a gone
  member's late byte cannot complete a declared asset any more. A `left` member's in-flight upload after a
  manual leave is lost, which is the same as a leave that cancels its jobs. A refused upload is retried
  like any failed one until the leave clears the ledger.
- [v1's wire moves] → `v1.test.ts` is documented as passing unmodified across schema changes; it does not
  here. Its setup now joins before uploading, and it pins the event-scoped storage path, the 409 for a
  device in no event, and the 400 for an unparseable object name. These are D4's intended changes for v1,
  not internal ones.
- [The event-less routes deepen single-membership] → Named here. The new route carries the event
  explicitly, so new builds stay compatible with multi-event membership, and the event-less routes would
  have to be retired before it. `openspec/config.yaml`'s "bytes … already event-independent" line is
  corrected.
- [The migration drops a row the expansion misses] → The worst case is one re-upload: the device's next
  walk re-uploads a resource whose row is gone. Verified against a copy of production before deploy
  (`migration-rehearsal`).
- [The legacy sweep is suspended] → Legacy bytes accumulate only until the follow-up. No new legacy byte is
  written after the deploy.
- [A member of an event writes many bytes] → Bounded by membership, the same as today's attested-device
  bound, but narrower.

## Migration Plan

1. Deploy the backend (migration `0010` plus routes plus sweep) through the standard migration deploy (maintenance bundle, `docs/deployment.md` §2).
   Old builds keep working at once (D4, D5). Rollback is the previous bundle plus a reverse migration that
   collapses event rows back to `(device, asset, role)`, keeping legacy-path rows. Rows written to the new
   layout in between would then need re-uploading. That is acceptable for a rollback window of hours.
2. Ship the client build using the new route (iOS and Android together).
3. Follow-up, internal: a one-off copy script run locally (secrets via secrets-env). Every row whose
   `path` is under `files/devices/` is downloaded, PUT to `files/<e>/<hash>`, and gets its `path` rewritten.
   It is idempotent and re-runnable until it reports zero.
4. Follow-up, internal, at least 7 days after step 3 (the issued presigns' lifetime): delete everything
   under `files/devices/` and remove the legacy sweep phases.
