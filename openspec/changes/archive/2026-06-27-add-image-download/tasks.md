## 1. Config — PUBLIC_BASE_URL (backend-config)

- [x] 1.1 Add `baseUrl` to the `Config` type and `ENV_PUBLIC_BASE_URL = "PUBLIC_BASE_URL"` in `backend/src/config.ts`.
- [x] 1.2 Read, trim, and strip any trailing slash from `PUBLIC_BASE_URL` in `readConfig`; include it in the missing-vars check so a missing/blank value throws (fail-closed at boot).
- [x] 1.3 Add `config.test.ts` cases: `PUBLIC_BASE_URL` required (missing/blank throws, naming the var) and trailing-slash normalized.

## 2. Shared download-URL builder

- [x] 2.1 Add a `downloadUrl(config, eventId, filename)` helper in `backend/src/app.ts` composing `${config.baseUrl}/event/${eventId}/file/${encodeURIComponent(filename)}` (per-segment encoding; eventId identity).
- [x] 2.2 Unit-test the builder: a filename needing percent-encoding (space, non-ASCII) yields a single-segment encoded path and no double slash after the origin.

## 3. List endpoint — add `url`

- [x] 3.1 Extend the `FileEntry` type and the list mapping in `backend/src/app.ts` to add `url` via the helper, keeping the entry exactly `{ filename, size, lastModified, url }`.
- [x] 3.2 Update list tests in `app.test.ts` to assert each entry carries the correct absolute `url` (and no extra fields).

## 4. Download endpoint — GET /event/:eventId/file/:filename

- [x] 4.1 Add a `get("/")` handler on the existing file child Hono (alongside `put`/`options`); validate UUID + filename, returning `400` on failure with no upstream request.
- [x] 4.2 Issue a single ungated bunny object `GET` of `${eventId}/${encodeURIComponent(filename)}` with the `AccessKey` header; do NOT read the marker.
- [x] 4.3 Map outcomes: bunny `200` → stream body with `200`; bunny `404` → `404`; any other status / connect error / pre-body timeout → `5xx`.
- [x] 4.4 Stream the upstream body through without buffering (pass-through, mirroring the upload write path).
- [x] 4.5 Relay response headers: `Content-Type` (fallback `application/octet-stream`), `Content-Length`, and `ETag`/`Last-Modified`/`Cache-Control` when present; set no `Content-Disposition`; do not honor `Range`.

## 5. Download tests

- [x] 5.1 `200` path: valid request streams the bunny body through and relays `Content-Type`, `Content-Length`, and present cache validators.
- [x] 5.2 `400` path: non-UUID event id and unsafe filename each rejected with no upstream request.
- [x] 5.3 `404` path: bunny object `404` (missing object / unknown event — same outcome) yields `404`; assert no marker `GET` is performed.
- [x] 5.4 `5xx` path: non-`404` upstream / connect error / timeout yields `5xx` and no body.
- [x] 5.5 Round-trip: a `url` produced by the list builder, fetched against the download route, resolves to the same stored object (encode → decode → re-encode round-trips with no double-encoding).

## 6. Spec relocation & wiring

- [x] 6.1 Confirm the upload handler still reads config from the shared `Config` (no behavior change) now that the config requirement lives in `backend-config`.
- [x] 6.2 Update `backend/README.md` env-var inventory to list `PUBLIC_BASE_URL` as a required Edge Script runtime variable.

## 7. Verify

- [x] 7.1 Run `deno fmt --check`, `deno lint`, `deno check src/*.ts`, and `deno task test` in `backend/`; all green.
- [x] 7.2 Run `npx openspec validate add-image-download` and confirm the change is valid before shipping.
