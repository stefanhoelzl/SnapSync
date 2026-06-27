## Why

Today an event exists only as a client-held UUID: the backend keeps zero state, possession of the
id is the only capability, and `GET /event/<id>/files` cannot tell an empty event from one that was
never used. Users can only *join* an event someone handed them via QR/deeplink — there is no way to
*create* one. To let a user start a brand-new backup, the backend must mint an event, name it, and
become able to say whether a given event id actually exists.

This change is **backend-only**. It establishes the server-side event registry the create flow
needs; the on-device UI to enter a name and auto-join is a deliberate follow-up that builds on these
endpoints.

## What Changes

- **New `POST /event`** — unauthenticated; body `{ name }`; mints a UUID server-side
  (`crypto.randomUUID()`), writes a marker object `events/<eventId>.json` (`{ eventId, name,
  createdAt }`, ISO-8601) into the storage zone, and responds `201 { eventId, name, createdAt }`.
  Name is validated (trimmed, non-empty, ≤100 chars) → `400` otherwise. Faithful outcome: `201`
  only after bunny confirms the marker store, else `502`.
- **New `GET /event/<id>`** — returns the marker (`200 { eventId, name, createdAt }`), `404` when the
  marker is absent, `400` when the id is not a UUID. Doubles as the existence check.
- **An event registry** — event existence is defined by the presence of `events/<eventId>.json`. The
  `events/` prefix is disjoint from photo dirs (`<eventId>/`) since `eventId` is UUID-validated and
  can never be the literal `events`. The existence check is a `GET` of the marker (bunny's Edge
  Storage API has no HEAD); for the tiny marker this is cheap and reuses the same read as the
  metadata endpoint.
- **MODIFIED list** — `GET /event/<id>/files` now `404`s when the event does not exist (marker
  absent), while still returning `200 []` for a created-but-empty event. **BREAKING** relative to the
  current "empty == unknown, never 404" contract.
- **MODIFIED upload** — `PUT /event/<id>/file/<filename>` is now gated: it first reads the marker and
  `404`s when the event does not exist, otherwise streams as before. **BREAKING** relative to the
  current "exactly one upstream subrequest / no pre-check / last-write-wins" invariant (a marker read
  precedes the streamed PUT).

## Capabilities

### New Capabilities
- `event-creation`: the server-side event registry — the `POST /event` create endpoint, the
  `GET /event/<id>` metadata/existence endpoint, the `events/<eventId>.json` marker scheme, name
  validation, and the faithful-create outcome.

### Modified Capabilities
- `bunny-list-endpoint`: now distinguishes unknown (marker absent → `404`) from empty (created, no
  objects → `200 []`); replaces the "empty or unknown event yields an empty array / never 404"
  requirement and adds a marker existence pre-check to its single LIST.
- `bunny-upload-endpoint`: now gated on event existence (marker read → `404` when absent), amending
  the "exactly one upstream subrequest / no pre-check" requirement to permit the existence pre-check.

## Impact

- **Code**: `backend/src/app.ts` (new create + metadata routes; existence gate on list and upload),
  `backend/src/validators.ts` (name validation), `backend/test/app.test.ts` (new cases). Possibly a
  small marker-key/registry helper. No KMP/iOS/desktop code in this change.
- **Deployment invariant**: `BUNNY_STORAGE_HOST` must be the storage zone's **main** region host
  (where writes land), never a replica endpoint — guarantees read-after-write so a just-created
  marker is visible to the immediately-following join/list/upload. (Bunny replicates to other regions
  asynchronously; same-main-region reads are read-after-write consistent.)
- **Docs**: `backend/README.md` contract section updated for the new endpoints and gating.
- **Downstream / deferred (NOT in this change)**: the iOS/UI create flow (`EventConfigPayload` gains
  a name, a create screen, a network create client, auto-join via `ConfigStore.save`); and an
  iOS retry-policy gap — the engine's "retry forever, every error variant" invariant means a `404`
  on upload would retry indefinitely *if reached*. It is **unreachable in v1** (the join-time `GET
  /files` existence check 404s first, so uploads never start against a non-existent event), so no
  engine change is made here; this is recorded as a known gap for when `404`-on-upload could become
  reachable (e.g. a future delete-event endpoint).
