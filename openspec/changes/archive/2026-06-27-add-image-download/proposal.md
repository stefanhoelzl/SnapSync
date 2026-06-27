## Why

The backend can store and list an event's photos, but there is no way to read the bytes back —
the list endpoint returns metadata only, with no address a client can fetch. We want to download
images by name, and we want each list entry to carry the link that fetches it, so a consumer can
list-then-download without reconstructing URLs.

## What Changes

- Add `GET /event/<eventId>/file/<filename>` — streams the stored object back from bunny. It is a
  third verb on the existing `/event/:eventId/file/:filename` child app (today `PUT` + `OPTIONS`).
  Ungated (no marker read): a single bunny object `GET`, body streamed through. `200` only when
  bunny began a `200`; bunny `404` → `404` (a missing object and an unknown event are
  indistinguishably `404` **by design**); any other status / connect error / pre-body timeout →
  `5xx`. Relays `Content-Type`, `Content-Length`, and `ETag`/`Last-Modified`/`Cache-Control` when
  present. No `Content-Disposition`, no `Range`.
- Each list entry gains a `url` field — the absolute download URL for that object — so the entry
  shape becomes exactly `{ filename, size, lastModified, url }`. The list spec **references**
  `bunny-download-endpoint` for the URL format rather than restating it.
- New required, fail-closed env var `PUBLIC_BASE_URL` (the backend's public origin) used by the
  list endpoint to build each `url`. Absent/blank → the app does not boot (same posture as the
  bunny credentials).
- **BREAKING (contract):** the list response entry shape changes from three fields to four. The
  current Kotlin consumer decodes with `ignoreUnknownKeys`, so it is unaffected, but the
  `bunny-list-endpoint` contract's "exactly three fields" requirement changes.
- Relocate the backend's runtime configuration contract out of `bunny-upload-endpoint` into a new
  shared `backend-config` capability, since config is now read by multiple endpoints.

## Capabilities

### New Capabilities
- `bunny-download-endpoint`: per-event object download (`GET /event/<id>/file/<name>`) — streaming
  pass-through from bunny, the status/header contract, the ungated existence semantics, the
  read-faithfulness contract (status committed before the body; `Content-Length`-detected
  short-read as the integrity signal), and the authority on the public download-URL format.
- `backend-config`: the env-only, fail-closed runtime configuration inventory for the backend
  (`zone`, `host`, `accessKey`, `PUBLIC_BASE_URL`), referenced by every endpoint.

### Modified Capabilities
- `bunny-upload-endpoint`: its `Environment-only configuration, fail-closed` requirement is removed
  here and relocated to `backend-config` (behavior preserved; ownership moves).
- `bunny-list-endpoint`: the normalized entry shape gains `url` (now exactly four fields), defined
  by reference to `bunny-download-endpoint`; adds a round-trip note that a listed `url` fetches the
  object it describes.

## Impact

- **Code:** `backend/src/app.ts` (new `get` on the file child app; `url` added to each list entry;
  a shared download-URL builder), `backend/src/config.ts` (add `PUBLIC_BASE_URL`, fail-closed).
- **Tests:** `backend/test/app.test.ts` (download `200`-stream + relayed headers, `400`, `404`,
  `5xx`; list asserts `url`), `backend/test/config.test.ts` (`PUBLIC_BASE_URL` required/fail-closed).
- **Deployment:** `PUBLIC_BASE_URL` must be set as an Edge Script environment variable or the
  backend will not boot; it is a runtime env var (same category as `AccessKey`), not a CI secret.
- **Consumers:** the Kotlin `HttpEventFilesSource` is unaffected (`ignoreUnknownKeys`); no client
  change required to ship the contract.
