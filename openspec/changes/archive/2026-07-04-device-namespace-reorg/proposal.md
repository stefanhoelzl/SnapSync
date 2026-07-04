## Why

The backend storage layout puts a device's raw photo objects at the top-level key prefix
`files/<deviceId>/…`, addressed over the URL labels `/files/device/<deviceId>/…`. An upcoming
change needs a *second* per-device object — a device config document holding the push-notification
token — and there is no clean home for it: dropping it under `files/<deviceId>/` would make it show
up in `GET /files/device/<deviceId>` as a bogus "photo". This change reorganizes the byte store into
a single device-scoped namespace (`devices/<deviceId>/…`) so device-owned objects (files today,
config next) share one partition without colliding, keeping the per-device listing clean. It is a
mechanical relocation with **no behavior change** beyond the moved paths.

## What Changes

- **Byte objects move under a device namespace.** The storage key for an uploaded resource changes
  from `files/<deviceId>/<filename>` to **`devices/<deviceId>/files/<filename>`**, and the byte
  partition a device lists/aggregates over changes from `files/<deviceId>/` to
  **`devices/<deviceId>/files/`**. This reserves `devices/<deviceId>/` as the device's own namespace
  (a `config.json` sibling lands there in the follow-up push change — out of scope here).
- **Upload/list URL routes change. BREAKING (device↔backend HTTP contract).**
  - Byte upload: `PUT /files/device/<deviceId>/<filename>` → **`PUT /devices/<deviceId>/files/<filename>`**.
  - Per-device list: `GET /files/device/<deviceId>` → **`GET /devices/<deviceId>/files`**.
- **The event-wide union reads the new prefix internally.** `GET /event/<eventId>/files` keeps its
  path, but its per-device fan-out lists `devices/<deviceId>/files/` and its presigned-URL keys
  become `devices/<deviceId>/files/<filename>`.
- **The device-side URL builder is repointed.** `EdgeUploadRequestProvider` composes
  `<host>/devices/<deviceId>/files/<encoded-filename>` (was `<host>/files/device/<deviceId>/…`).
- **Unchanged:** the `events/<eventId>/…` namespace (marker `metadata.json` and the per-event device
  manifest `events/<eventId>/device/<deviceId>.json`), the device-manifest write route
  `PUT /event/<eventId>/device/<deviceId>`, the deviceId-as-capability trust model, the presigned-URL
  format/expiry, completeness semantics, faithful-outcome (`2xx`/`5xx`) rules, and last-write-wins.
- **Clean cut, no migration.** The app is pre-release (TestFlight, throwaway data); old objects under
  `files/<deviceId>/` are abandoned and may be wiped manually. No dual-read, no back-compat, no
  migration script.

## Capabilities

### New Capabilities
<!-- none — this is a relocation of existing capabilities' paths -->

### Modified Capabilities

- `bunny-upload-endpoint`: the byte route path and its derived storage key move to the device
  namespace (`PUT /devices/<deviceId>/files/<filename>` → key `devices/<deviceId>/files/<filename>`);
  the device-manifest route is unchanged.
- `bunny-list-endpoint`: the per-device list route (`GET /devices/<deviceId>/files`) and its listed
  partition/presigned keys move to `devices/<deviceId>/files/`; the event-union route path is
  unchanged but its per-device fan-out, completeness check, and resource `key`s read the new prefix.
- `edge-upload-provider`: the composed byte URL moves to `<host>/devices/<deviceId>/files/<encoded-filename>`.

## Impact

- **Backend (`backend/`, Deno/TS):** `backend/src/app.ts` route templates + storage-key helpers for
  the byte upload, per-device list, and event-union fan-out; `backend/README.md`. Tests in
  `backend/**/*.test.ts` (route paths + expected upstream keys).
- **Device (Kotlin):** `:capability:upload-url` (`EdgeUploadRequestProvider` composed path) and its
  `commonMain` URL-composition tests.
- **APIs (BREAKING):** the byte upload and per-device list URL paths change; the app and backend must
  ship together (no external consumers — the device is the only client, and it's pre-release).
- **Test harnesses:** any `:test:world` / harness fake that hardcodes the old `files/device/…` paths
  or `files/<deviceId>/` keys.
- **Not touched:** event lifecycle, manifest write path, download/import flow, presigned-URL crypto,
  and the iOS app shell.
