## 1. Registry + name validation helpers

- [x] 1.1 Add `validateEventName(raw)` to `backend/src/validators.ts`: parse-time trim, non-empty, length ≤100; return the trimmed name or a rejection (boolean/typed result consistent with the existing `validateUUID`/`validateFilename` style)
- [x] 1.2 Add a small marker-key helper (e.g. `markerKey(eventId) => events/<eventId>.json`) so the `events/` prefix is defined in one place; unit-test it stays disjoint from `<eventId>/`
- [x] 1.3 Add a marker existence/read helper in `backend/src/app.ts` (`readMarker(fetch, config, eventId)`): bunny `GET events/<id>.json` with `AccessKey`; returns parsed `{eventId,name,createdAt}` on `200`, `null` on `404`, and THROWS on any other status/timeout (so callers surface `5xx`) — mirrors the existing `listDir` shape

## 2. Create endpoint (POST /event)

- [x] 2.1 Add `app.post("/event")`: parse JSON body, validate `name` (2.1 helper) → `400` (non-JSON / missing / empty / >100), ignore any client-supplied id
- [x] 2.2 Mint `eventId` via `crypto.randomUUID()`, build `{ eventId, name, createdAt }` with `createdAt` = ISO-8601
- [x] 2.3 `PUT events/<id>.json` to bunny with `AccessKey` + `Content-Type: application/json`, body = the JSON; on confirmed store respond `201` with the body; on upstream error/abort respond `502` (faithful create); never expose `AccessKey`/account key

## 3. Metadata + existence endpoint (GET /event/:eventId)

- [x] 3.1 Add `app.get("/event/:eventId")`: `400` on non-UUID (no upstream call); else `readMarker` → `200 {eventId,name,createdAt}` on present, `404` on absent, `502` on non-404 read failure
- [x] 3.2 Ensure route ordering/precedence is correct alongside the existing `/event/:eventId/file/:filename` mount and `/event/:eventId/files` (no shadowing; wrong method/path → Hono `404`)

## 4. Gate the list endpoint

- [x] 4.1 In `app.get("/event/:eventId/files")`, call `readMarker` first: `null` → `404` (no LIST); throw → `502`; present → proceed to the existing single LIST of `<id>/`
- [x] 4.2 Confirm created-but-empty still returns `200 []` (marker present, LIST 404/empty) and that a marker read failure never collapses to `404`/`200`

## 5. Gate the upload endpoint

- [x] 5.1 In the `upload.put("/")` handler, after key validation, call `readMarker`: `null` → `404` (stream nothing, no upstream object PUT); throw → `502`; present → stream the body PUT exactly as today
- [x] 5.2 Verify last-write-wins is preserved: still no existence check on the **object key** (only the event-marker read precedes); still exactly one upstream object `PUT`

## 6. Tests (backend/test/app.test.ts)

- [x] 6.1 Create: valid `POST /event` → `201` with `{eventId(uuid),name,createdAt}` + one marker `PUT` to `events/<id>.json` carrying `AccessKey` and `application/json`; trimming applied
- [x] 6.2 Create validation: empty/whitespace name, >100 chars, non-JSON body → `400`, zero upstream calls; client-supplied id ignored
- [x] 6.3 Create faithful outcome: marker `PUT` 5xx/throw → `502`, no `201`
- [x] 6.4 Metadata: present → `200` body; absent (marker 404) → `404`; non-UUID → `400` (no upstream); non-404 read failure → `502`
- [x] 6.5 List gating: unknown event (marker 404) → `404` and **no** LIST; created-empty → `200 []`; created-with-files → `200 [...]`; marker read failure → `5xx`
- [x] 6.6 Upload gating: missing event (marker 404) → `404`, **no** object `PUT`; existing event → streams one object `PUT` (existing streaming/round-trip/last-write-wins tests still pass); marker read failure → `5xx`
- [x] 6.7 Run `deno task test`, `deno task lint`, `deno fmt --check` green

## 7. Docs

- [x] 7.1 Update `backend/README.md` contract section: add `POST /event` and `GET /event/<id>`; note list/upload now gate on `events/<id>.json` and `404` unknown events; record the **deployment invariant** that `BUNNY_STORAGE_HOST` MUST be the zone's main region host (read-after-write); note the deferred iOS `404`-retry known gap

## 8. Operator tooling

- [x] 8.1 Update `scripts/reset-storage.ts` to also delete the registry marker `events/<id>.json` per event (targeted reset otherwise leaves the event "existing"), and to enumerate full-reset events from the registry markers ∪ photo dirs (so created-but-empty events are caught and the `events/` dir is never treated as an event)
