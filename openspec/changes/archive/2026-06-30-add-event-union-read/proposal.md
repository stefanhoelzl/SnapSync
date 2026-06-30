## Why

The app holds **no storage credential** — it reads only through the edge. Today there is **no route**
to discover an event's contributors or read their manifests: `GET /files/device/<id>` lists one
device's raw bytes (and you must already know the id), and the per-event device manifests under
`/events/<eventId>/device/` are unreachable from the device. The `dedup-files-device-manifests`
change deliberately deferred the event-wide union ("external/admin-direct", design.md §3.5). That
deferral is the missing prerequisite for the planned on-device download/restore feature, whose first
need is: for one event, enumerate every **other** device's **COMPLETE** assets and the URLs to fetch
their resources.

This change adds that read to the edge and aligns the manifest's field vocabulary with it.

## What Changes

- **NEW edge read — `GET /event/<eventId>/files`.** A single, **server-computed union** (one client
  round-trip). The backend gates on the event marker, enumerates the event's contributing devices
  (one LIST of `events/<eventId>/device/`), then per device reads its `device.json` and LISTs its
  `/files/<deviceId>/` partition, and emits **only complete** assets — an asset is complete iff every
  resource its manifest names is present in that device's byte store. The response is a flat array of
  assets, each tagged with its owning `deviceId`. Mirrors the existing list/marker faithfulness:
  any non-404 failure anywhere in the fan-out → `502`, never a partial union.
- **Field rename in `device.json` (and the union).** Per resource, `filename` → `key` (the storage
  object name `<assetId>-<role>.<ext>`) and `originalFilename` → `filename` (the human capture name).
  This clarifies the vocabulary — `key` is the fetch handle, `filename` is the human name — and makes
  the union a straight projection of the manifest. All other fields are unchanged. **Clean cutover,
  no backfill** (matching the dedup change's ethos): the union reader expects the new names only; the
  producer re-projects `device.json` with the new names on the new build (the field-name change makes
  the projected snapshot differ, so skip-if-unchanged naturally rewrites it). Old-format stored
  manifests are stale/malformed until rewritten — acceptable for a personal TestFlight app.

The **own-vs-foreign** skip stays a **client** concern: the endpoint returns every contributing
device's assets tagged with `deviceId` and never knows "you" (no exclude param). The event id is the
capability (the marker gate is existence, not authorization), consistent with the other event-scoped
routes; every upstream read carries the configured `AccessKey`, never the account API key.

## Capabilities

### Modified Capabilities

- `bunny-list-endpoint`: adds the event-wide union route `GET /event/<eventId>/files` alongside the
  existing per-device raw listing `GET /files/device/<deviceId>`. The union is event-gated and
  fans out (marker → device-manifest LIST → per-device manifest read + file LIST → completeness),
  where the per-device list is event-blind and single-LIST; both live in the same capability and the
  same Hono app, and both build each download `url` through the one `bunny-download-endpoint` builder.
- `device-manifest`: per-resource field rename `filename` → `key`, `originalFilename` → `filename`;
  the document, asset, and other resource fields are unchanged. The manifest stays write-only in v1
  and gains the union as its (now on-edge) consumer of record.
- `ios-background-upload`: the producer (the upload extension, sole writer of `device.json`) writes
  the renamed resource fields (`key`, `filename`); the in-cycle synchronous PUT and accumulator model
  are otherwise unchanged. The skip-if-unchanged compares the projected snapshot **content**, so the
  rename forces a one-time rewrite to the new field names on the new build.

## Impact

- **Code:** `backend/src/app.ts` — the new `GET /event/:eventId/files` handler + a small union
  assembler (reusing `readMarker`, `listDir`, `downloadUrl`); `backend/test/app.test.ts` — union
  tests (canned bunny fan-out via the injected `fetch`). `iosApp/` upload extension — the manifest
  projection writes `key`/`filename` (field names only).
- **Untouched:** `:domain:engine`/ledger (event-blind), `:domain:presentation`/`:domain:ui` (status
  reads no manifest), event-creation/upload/download routes, `backend-config`, `device-identity`.
  The single `createApp` app deploys to both targets via the existing `backend-deploy.yml` — no
  CI/deploy/config change.
- **Docs:** `docs/design.md` §3.5 — record that the event-wide union is now an **edge** read
  (`GET /event/<id>/files`, complete-only, foreign-inclusive), reversing the "external/admin-direct"
  deferral; note the `device.json` `key`/`filename` field names.
- **Accepted, eyes open:** the union fans out `1 + 2N` upstream reads (N = contributing devices) per
  request, all behind strict faithfulness (a partial fan-out is a `502`, never a half-union). For a
  personal event this N is tiny. The restore/download client that consumes this union is still
  **deferred** — this change only builds the read it needs.
