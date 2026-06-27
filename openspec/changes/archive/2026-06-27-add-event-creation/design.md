## Context

The backend (Deno + Hono on bunny Edge Scripting) is today a **stateless** streaming proxy:
`PUT /event/<id>/file/<name>` writes to the flat key `<id>/<name>`, `GET /event/<id>/files` LISTs
`<id>/`, and possession of the event id is the only capability — there is **no event registry**, so
the list endpoint cannot distinguish an empty event from a never-created one (`bunny-list-endpoint`:
"empty or unknown … never 404"). Events are born only by client-held UUIDs handed over via
QR/deeplink; there is no create path.

This change introduces the minimal server state needed to *create* an event and to *know whether one
exists*. It is backend-only; the on-device create UI and auto-join are a follow-up.

Authoritative existing contracts: `bunny-upload-endpoint`, `bunny-list-endpoint`,
`backend/src/app.ts`, `backend/README.md`. Keys are flat (`<id>/<name>`) since the archived
`flatten-event-namespace` change removed the device level.

## Goals / Non-Goals

**Goals:**
- A `POST /event` that mints a UUID, names the event, and persists it; `201 { eventId, name, createdAt }`.
- A `GET /event/<id>` that returns the event's metadata or `404` — the canonical existence check.
- A storage-backed event registry with no new infrastructure.
- List and upload that agree on existence: both `404` an event that was never created.
- Faithful outcomes everywhere (no false success), consistent with the existing endpoints.

**Non-Goals:**
- Any KMP/iOS/desktop code: the create screen, `EventConfigPayload.name`, the HTTP create client, and
  auto-join are a separate change.
- Authentication / authorization beyond the existing possession-is-capability model (create is open).
- Deleting or mutating events (no delete endpoint, no rename); name uniqueness; multi-tenancy.
- Changing the engine's retry-forever policy (see Risks: the 404-on-upload gap is deferred).

## Decisions

### D1 — Registry as a marker object in the storage zone (`events/<eventId>.json`)
An event exists iff the object `events/<eventId>.json` (contents `{ eventId, name, createdAt }`,
`Content-Type: application/json`) is present. Chosen over a bunny KV / external DB: no new dependency,
reuses the one storage seam every route already speaks. The `events/` prefix is **disjoint** from
photo dirs (`<eventId>/`) because `eventId` is UUID-validated and can never be the literal string
`events`; so a LIST of `<eventId>/` never sees the marker and a photo can never collide with it.
_Alternative considered_: marker **inside** the event dir (`<id>/event.json`) — folds existence into
the list's single LIST, but risks colliding with a photo literally named `event.json` and needs
response filtering. Rejected for the cleaner disjoint namespace.

### D2 — Server mints the UUID via `crypto.randomUUID()`
Create is the source of truth for existence, so the server allocates the id (canonical
`8-4-4-4-12`, already accepted by `validateUUID`). Returned in the `201` body. _Alternative_:
client-supplied id recorded by the server — rejected; weaker source-of-truth and invites collisions.

### D3 — Existence check is a `GET` of the marker (no HEAD)
Bunny's Edge Storage API documents only GET/PUT/DELETE/LIST — **no HEAD**, no describe/metadata op.
So existence is "GET `events/<id>.json` → 404?". For the tiny marker this is cheap, and the same read
serves the `GET /event/<id>` metadata response. One mechanism, three call sites (metadata, list gate,
upload gate).

### D4 — Gate **both** list and upload on the marker
- List: `GET events/<id>.json` → absent ⇒ `404`; present ⇒ LIST `<id>/` ⇒ `200 [...]`/`200 []`.
- Upload: `GET events/<id>.json` → absent ⇒ `404` (no body streamed); present ⇒ stream the PUT.

Gating uploads is a deliberate correctness choice: storage stays self-consistent against any client
(list and upload never disagree on existence), upholding the fail-closed ethos. Its cost is one small
marker GET before each photo PUT — modest against a multi-MB streamed body, but it does amend the
upload endpoint's "exactly one upstream subrequest / no pre-check / last-write-wins" invariant.
_Alternative_: gate the list only (keep the upload path pristine). Rejected: in the real flow the gate
is redundant with the join-time existence check, but gating both is the only way the invariant holds
against a misbehaving client, and the cost is small. (See Risks for why the redundancy is acceptable
and the 404 path is unreachable in v1.)

### D5 — Faithful create (`201` only after the marker store is confirmed; else `502`)
Mirrors `Faithful outcome propagation` on upload. A `POST /event` returns `201` only once bunny
acknowledges the marker write; any upstream error/abort ⇒ `502` and no event is reported created.
Because a bunny object PUT is atomic per object, there is no partial-marker state to read back.

### D6 — Server-side name validation (trim, non-empty, ≤100 chars; else `400`)
Validated server-side regardless of client: parse JSON body, `name` trimmed, non-empty, length ≤100.
Non-JSON / missing / empty / over-long ⇒ `400`, no upstream write. The stored name is the trimmed
value. No uniqueness (names are labels, not identifiers).

## Risks / Trade-offs

- **[Create→join read-after-write race]** Auto-join (follow-up) reconciles via `GET /files`
  immediately after create; that read must see the just-written marker. → **Mitigation**: all
  storage ops use the single configured `BUNNY_STORAGE_HOST`, the zone's **main** region where writes
  land; bunny is read-after-write consistent within the main region (replicas lag asynchronously).
  Recorded as a **deployment invariant**: `BUNNY_STORAGE_HOST` MUST be the main region host, never a
  replica endpoint. No retry/poll on the join path.

- **[404-on-upload → infinite retry]** The engine's `sync-engine` "retry forever, every error
  variant, no budget" invariant means a `404` upload response would retry indefinitely. →
  **Mitigation / why deferred**: the path is **unreachable in v1** — uploads only start after join,
  and join's first act (`GET /files`) now `404`s a non-existent event, bouncing the user to
  `JoinFailed` before any upload. The only actor who could hit upload-404 is a client uploading
  without joining (already misbehaving), against whom the gate still does its real job: **no orphan
  bytes land**. **Known gap** for later: if upload-404 ever becomes *reachable* (e.g. a future
  delete-event endpoint or server cleanup), the client needs a **terminal / event-level** disposition
  for `404` (treat as "event gone → re-setup", not a per-resource retry). No engine change here.

- **[Per-upload marker GET cost]** Adds a small read before every photo PUT inside the extension's
  30 s CPU budget. → **Mitigation**: the marker is a few-hundred-byte JSON; the read is I/O-bound and
  small relative to the streamed body. Accepted as the price of cross-client storage consistency.

- **[Two upstream calls on list/upload]** Existence pre-check + LIST/PUT. → Accepted; the
  `single LIST` requirement is scoped to the *file listing* (still one LIST), the marker read is a
  separate small GET.

- **[Backward incompatibility]** Existing client-generated event ids (never "created") will now
  `404` on list and upload. → For a personal v1 with few/no in-the-wild events this is acceptable; the
  rejoin path already depended on `GET /files`, which now `404`s such ids at join time anyway.
