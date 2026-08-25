## Why

Every relational fact the backend holds is currently encoded in the **shape of an S3 key namespace**:
an event exists iff `events/<id>/metadata.json` is present; a device is an active member iff its
`<deviceId>.json` is newer than its `<deviceId>.left.json`; the event union is assembled by listing one
directory per member device and cross-checking a second listing for byte presence. Storage is doing the
job of a database, and it is doing it badly in three measurable ways:

- **Reads fan out.** The event union costs one marker read plus one directory listing per member plus one
  manifest GET per member. A relational union is a single query — measured **373–541 ms** against 20 000
  assets / 30 000 resources on a Bunny Database (`PROBE-FINDINGS.md`).
- **Invariants cannot be expressed, so they are re-implemented.** Three consumers each reimplement the
  active/departed last-write-wins rule from directory timestamps. Two of `scheduled-cleanup`'s staleness
  classes (*incomplete*, *tombstone*) exist **only** because S3 has no referential integrity.
- **Capacity is racy.** "10 devices ever enrolled" is enforced by read-then-write over a listing. Measured:
  10 concurrent joins against a capacity of 3 all succeed. A single conditional `INSERT … SELECT` admits
  **exactly 3**, in 158 ms.

This is change #1 of a programme that ends with the device-side upload ledger retired in favour of
backend-derived state. That end state requires the backend to be able to answer *"what has this device
uploaded?"* authoritatively, which no directory listing can do. This change builds that authority.

## What Changes

- **Add a Bunny Database as the backend's relational store**, with five tables — `events`, `memberships`,
  `event_assets`, `resources`, `device_records` — and the FK chain `events ← memberships ← event_assets`
  under two-level `ON DELETE CASCADE`.
- **Flip every relational write and read to it in one step.** The S3 marker, the device manifest object,
  the departed sibling, and the per-device config object stop being the source of truth. Bytes stay in
  storage; nothing else does.
- **The device manifest becomes a wire format, not an object.** `PUT /api/v1/events/<eventId>/devices/<deviceId>`
  keeps its route, its body shape, and its full-state semantics — the backend writes the membership row,
  replaces the event's asset set, and upserts resources in **one atomic `batch()`** instead of storing JSON.
  **No client change**, so this ships and rolls back without touching a shipped app.
- **The byte `PUT` records `uploaded = 1` best-effort** (still `201` if the DB write fails), repaired by the
  next manifest publish via `uploaded ?? true`.
- **BREAKING (wire, unobserved): `size` is removed** from both read routes' closed resource shapes. It has
  no reader — the iOS union model omits it, `HttpDeviceFilesSource` documents it as an ignored unknown key,
  and the web zip page reads only `role`/`url`/`filename`/`key`. Removing it is what makes the byte `PUT`'s
  DB write safe to lose.
- **Capacity becomes exact**, enforced by one conditional insert rather than read-then-write.
- **Membership state becomes a column.** The `.left.json` sibling and its last-write-wins tie-break are
  deleted; three consumers collapse to `WHERE state = 'active'`.
- **The sweep marks from the database and deletes from storage.** Two staleness classes become unstateable.
  A collected byte's `resources` row is deleted **before** the byte, so a crash leaves a self-healing orphan
  byte rather than a row claiming bytes that are gone.
- **Seven statements of one rule collapse into one.** Five per-endpoint "requires a device token"
  requirements are deleted; `device-attestation` already owns the rule and the closed list of exceptions.
- **Cutover**: migrate → deploy → backfill from the existing manifests → verify → confirm **before** the
  next nightly sweep window.

## Capabilities

### New Capabilities

- `api-endpoints`: the whole `/api/v1` surface in one place — every route's method, path, params, request
  body, response shape, status codes, and whether it is attestation-gated. It owns *what a request looks
  like and what it gets back*, and nothing else; every rule with a reason behind it lives in the capability
  that decides it and is cited, never restated.
- `database`: the relational store — the five tables and their columns, the key and FK-cascade structure,
  what atomicity the writes require, how existence and capacity are decided, and which platform facts the
  schema depends on.

### Modified Capabilities

- `device-manifest`: the storage key and the entire *Departed manifest and last-write-wins membership*
  requirement are removed; the document's field set moves to `api-endpoints` as a request body. The
  projection requirements (ledger, policy, deletion-awareness, publish-suppression) are **unchanged** —
  they belong to change #2.
- `scheduled-cleanup`: the *incomplete* staleness class and *Tombstone reclamation* are deleted as
  unstateable; the root set and device floors come from queries; the marker-last ordering rule is replaced
  by a row-before-byte one.
- `event-limits`: gains the write-once-except-`name` rule (relocated from `event-creation`, whose threat
  argument is load-bearing and gets easier to violate under SQL); capacity enforcement becomes exact.
- `device-attestation`: absorbs the five per-endpoint token requirements it already subsumes; its closed
  list of ungated routes is restated against the new route table.
- `event-rejoin-reconciliation`: the per-device listing it seeds from is served from the database and loses
  `size`; a resource the backend has not recorded as uploaded is not seeded.
- `harness-world-model`: `:test:world`'s backend model gains the relational state and loses the object
  layout it currently mirrors, including the active/departed sibling objects.
- `backend-deployment`: the database URL and token are deployment-declared secret references validated at
  startup, and the post-publish boot probe additionally asserts that foreign-key enforcement is on.

**Unchanged at requirement level, but carrying stale citations** to be swept: `event-rename`,
`web-event-download`, `push-registration`, `device-identity`, `leave-event`, `edge-upload-provider`. Each
names a capability this change removes, or a storage key it retires, in prose rather than in a SHALL. The
sweep is a task, not a delta — inventing requirement changes to carry a citation fix would misreport what
this change does.

**Removed** (absorbed whole into `api-endpoints`, with their decisions relocated as listed above):
`bunny-upload-endpoint`, `bunny-list-endpoint`, `event-creation`, `event-leave-endpoint`,
`event-notify-endpoint`, `device-config-endpoint`.

## Impact

- **`api/`** — the whole app: routing, every handler, the storage adapter's relational uses, the sweep
  script, and a new migration. `@libsql/client@^0.15/web` is added.
- **Deployment** — two new secrets (`BUNNY_DATABASE_URL`, `BUNNY_DATABASE_AUTH_TOKEN`), and the nightly
  sweep gains both (it marks from the database now, so without them it would fail at startup on its first
  scheduled run). The cutover's own programs are throwaway and are NOT committed: they run once, from a
  scratchpad, with credentials from `proton-env`.
- **No iOS change.** No Kotlin module is touched except `:test:world`, whose backend model must follow.
  The upload URL, the manifest route, and both read routes keep their shapes, minus one unread field.
- **Not in this change** (named so they are not assumed): retiring the device ledger, pending-visibility in
  the manifest, the per-asset notification rework, and any `min-app-version` gate.
