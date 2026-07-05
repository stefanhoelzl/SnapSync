## Why

The backend's URL and storage vocabulary drifted into two small inconsistencies. The byte store,
the device config, and the per-event manifest are addressed under three different top-level shapes
(`/devices/<id>/files/…`, `/devices/<id>/config`, `/event/<id>/device/<id>`), and the noun swings
between singular and plural across the surface — the storage prefix is `events/…` (plural) while
every URL is `/event/…` (singular), and the per-event manifest sub-prefix is `device/` (singular)
while its siblings are plural. This change makes the surface consistent: byte objects live under a
single `files/` prefix, everything pluralizes, and the config document becomes a flat sibling of the
byte namespace. It is a mechanical relocation/rename with **no behavior change** beyond the moved
paths — same gating, same faithful-outcome rules, same presigned-download model.

## What Changes

- **Byte objects move under a `files/` prefix. BREAKING (device↔backend HTTP contract).** The byte
  key changes from `devices/<deviceId>/files/<filename>` to **`files/devices/<deviceId>/<filename>`**,
  and the listed/aggregated partition from `devices/<deviceId>/files/` to
  **`files/devices/<deviceId>/`**. Upload route `PUT /devices/<deviceId>/files/<filename>` →
  **`PUT /files/devices/<deviceId>/<filename>`** (`OPTIONS` follows); per-device list route
  `GET /devices/<deviceId>/files` → **`GET /files/devices/<deviceId>`** (still a listing — no
  filename, no byte-proxy). The filename stays a single flat segment (validation unchanged).
- **The device config becomes a flat sibling. BREAKING.** The config key changes from
  `devices/<deviceId>/config.json` to **`devices/<deviceId>.json`**, and the route from
  `PUT /devices/<deviceId>/config` → **`PUT /devices/<deviceId>`**. The top-level `devices/` prefix
  now holds only flat `<deviceId>.json` config documents; all bytes live under `files/`.
- **Every event URL pluralizes. BREAKING.** `POST /event` → **`POST /events`**,
  `GET /event/<id>` → **`GET /events/<id>`**, `GET /event/<id>/files` → **`GET /events/<id>/files`**,
  `POST /event/<id>/notify` → **`POST /events/<id>/notify`**.
- **The per-event device manifest pluralizes, key and route. BREAKING.** Storage key
  `events/<eventId>/device/<deviceId>.json` → **`events/<eventId>/devices/<deviceId>.json`**; write
  route `PUT /event/<eventId>/device/<deviceId>` → **`PUT /events/<eventId>/devices/<deviceId>`**;
  the union's and notify's per-event manifest LIST directory moves from `events/<eventId>/device/`
  to **`events/<eventId>/devices/`**.
- **Presigned download URLs follow the byte key automatically.** Each listed object's `url` becomes
  a path-style `https://<s3-host>/<zone>/files/devices/<deviceId>/<filename>?X-Amz-…`; format,
  signing (zone as Access Key ID), and 7-day expiry are unchanged.
- **Unchanged:** the event marker key `events/<eventId>/metadata.json`; the deviceId-as-capability
  trust model (byte/list/config ungated, manifest/union/notify gated on marker existence);
  completeness semantics (complete-asset-only union); faithful-outcome (`2xx` only on confirmed
  store, `502` never a partial read); last-write-wins; the presigned-URL crypto/expiry; the silent
  no-byte-proxy download model.
- **Clean cut, no migration.** The app is pre-release (TestFlight, throwaway data); old objects
  under the prior keys are abandoned and wiped via `scripts/reset-storage.ts`. No dual-read, no
  back-compat, no migration script.

## Capabilities

### New Capabilities

<!-- none — this is a rename/relocation of existing capabilities' paths and keys -->

### Modified Capabilities

- `event-creation`: `POST /event` → `POST /events` and `GET /event/<id>` → `GET /events/<id>`; the
  marker key is unchanged, but the illustrative per-event manifest key and byte-store prefix update
  to `events/<id>/devices/<id>.json` and `files/devices/<id>/…`.
- `bunny-upload-endpoint`: the byte route and its derived key move to
  `PUT /files/devices/<deviceId>/<filename>` → key `files/devices/<deviceId>/<filename>`; the
  device-manifest route and key move to `PUT /events/<eventId>/devices/<deviceId>` → key
  `events/<eventId>/devices/<deviceId>.json`.
- `bunny-list-endpoint`: the per-device list route (`GET /files/devices/<deviceId>`), its listed
  partition and presigned keys (`files/devices/<deviceId>/…`), the event-union route
  (`GET /events/<eventId>/files`), and its per-event manifest LIST directory
  (`events/<eventId>/devices/`) all update.
- `device-config-endpoint`: the config route moves to `PUT /devices/<deviceId>` and its key to
  `devices/<deviceId>.json` (a flat sibling, outside the `files/devices/<deviceId>/` byte partition).
- `device-manifest`: the manifest object key moves to `events/<eventId>/devices/<deviceId>.json` and
  the write route to `PUT /events/<eventId>/devices/<deviceId>`.
- `event-notify-endpoint`: the route moves to `POST /events/<eventId>/notify`; the member LIST
  directory to `events/<eventId>/devices/` and the per-member config read to `devices/<deviceId>.json`.
- `edge-upload-provider`: the composed byte URL moves to
  `<host>/files/devices/<deviceId>/<encoded-filename>`.
- `event-creation-ui`: the create client posts to `<host>/events` and the metadata client gets
  `<host>/events/<id>`.
- `photo-download`: the consumed event-wide union read moves to `GET /events/<eventId>/files`.
- `push-registration`: the config write moves to `PUT <host>/devices/<deviceId>`.
- `deeplink-config`: the create/name-refresh flow posts `POST /events` and gets `GET /events/<id>`.
- `ios-photokit-upload`: the in-cycle device-manifest PUT moves to
  `<host>/events/<eventId>/devices/<deviceId>`.
- `harness-world-model`: the mini-edge faithfully answers the renamed routes
  (`GET /files/devices/<id>`, `GET /events/<id>/files`, `POST /events`,
  `PUT /events/<id>/devices/<id>`) over the new byte-partition keys.

## Impact

- **Backend (`backend/`, Deno/TS):** `backend/src/app.ts` — the five key helpers (`byteKey`,
  `deviceDir`, `deviceConfigKey`, `deviceManifestKey`, `deviceManifestDir`), the eight route/mount
  templates, and the header doc block; the presigned-URL builder follows `byteKey` unchanged.
  `backend/README.md` (storage layout + contract). `backend/test/**/*.test.ts` (route paths +
  expected upstream keys).
- **Device (Kotlin):** `:capability:upload-url` (`EdgeUploadRequestProvider` + its URL-composition
  test), `:capability:rejoin` (`DeviceFilesSource`), `:capability:download` (`EventUnionSource`),
  `:capability:push` (`PushRegistration`), `:capability:event-creation-ui` (`EventCreationClient`,
  `EventMetadataSource`), `:app:ios:photokit-extension` (`IosDeviceManifestUploader`).
- **Test harness:** `:test:world` — `MiniEdge` segment matcher, `HttpDeviceManifestUploader`, and
  `BackendStore`/comment references to the old paths/keys.
- **APIs (BREAKING):** every device↔backend URL changes; the app and backend must ship together (no
  external consumers — the device is the only client, and it's pre-release).
- **Docs/specs:** `docs/design.md` §3–4; the 13 modified capability specs above. Incidental
  non-normative path references in `device-identity` (Purpose) and `event-rejoin-reconciliation`
  (historical mention) are refreshed for accuracy.
- **Not touched:** event-marker existence semantics, the gating/trust model, download/import crypto,
  completeness/faithful-outcome rules, and the iOS app shell wiring.
