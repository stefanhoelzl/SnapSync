## Context

The backend keeps every relational fact in the shape of an S3 key namespace. `proposal.md` gives the
three costs; this document records how the move is made and why each choice beat its alternative.

All quantitative claims below were measured against a throwaway Bunny Database using the schema in D1 at
realistic scale (1 event, 10 devices, 2 000 assets each → 20 000 `event_assets`, 30 000 `resources`).
The measurements, their provenance, and their expiry triggers are in **`PROBE-FINDINGS.md`** beside this
file; each decision cites the section that settles it. Nothing here is justified by the current code.

Two constraints shape everything:

- **The byte storage key is event-independent** — `files/devices/<deviceId>/<filename>`, fixed by
  `bunny-upload-endpoint`'s *Object key from the URL path*. A byte `PUT` therefore cannot know which event
  it serves.
- **This change must not require a client change.** The programme's later steps do; this one must be
  revertible against a shipped app.

## Goals / Non-Goals

**Goals:**

- The backend can answer *"has this device uploaded resource K?"* from its own state, authoritatively.
- The event union is one query, not a fan-out over directory listings.
- Invariants that are currently re-implemented per consumer (membership state, referential integrity,
  capacity) are expressed once, where they cannot be forgotten.
- The route surface — paths, bodies, status codes — is **unchanged** but for one unread field, so the
  deployed app keeps working and the change can be rolled back.

**Non-Goals:**

- Retiring the on-device upload ledger. That is change #2, and this change deliberately leaves
  `device-manifest`'s projection requirements untouched so they are rewritten once, not twice.
- Deleting the S3 objects this change stops reading. They are left in place as the rollback path
  (D13); reclaiming them is a later change.
- Pending-visibility in the manifest (an admitted-but-not-yet-uploaded set). It requires a client change
  and would make `uploaded` non-constant; both are out of scope here.
- Moving the attestation record `devices/<deviceId>.attest.json` into the database. It stays an object,
  owned by `device-attestation`; only the *config* object becomes a row.
- Any `min-app-version` gate.

## Decisions

### D1 — Five tables, with `resources` deliberately **not** under the event FK

```sql
CREATE TABLE events (
  id               TEXT PRIMARY KEY NOT NULL,
  name             TEXT NOT NULL,
  created_at       TEXT NOT NULL,   -- ISO-8601, milliseconds (new Date().toISOString())
  starts_at        TEXT NOT NULL,   -- canonical cutoff form yyyy-MM-dd'T'HH:mm:ss'Z'
  ends_at          TEXT NOT NULL,   -- canonical cutoff form
  capacity         INTEGER NOT NULL,
  lifetime_seconds INTEGER NOT NULL
);

CREATE TABLE memberships (
  event_id  TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  state     TEXT NOT NULL,          -- 'active' | 'departed'
  joined_at TEXT NOT NULL,
  PRIMARY KEY (event_id, device_id)
);

CREATE TABLE event_assets (
  event_id      TEXT NOT NULL,
  device_id     TEXT NOT NULL,
  asset_id      TEXT NOT NULL,
  creation_date TEXT NOT NULL,
  PRIMARY KEY (event_id, device_id, asset_id),
  FOREIGN KEY (event_id, device_id)
    REFERENCES memberships(event_id, device_id) ON DELETE CASCADE
);

CREATE TABLE resources (
  device_id    TEXT NOT NULL,
  key          TEXT NOT NULL,       -- files/devices/<deviceId>/<filename>
  asset_id     TEXT NOT NULL,
  role         TEXT NOT NULL,       -- 'primary' | 'live'
  content_type TEXT NOT NULL,
  filename     TEXT NOT NULL,
  uploaded     INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (device_id, key)
);
CREATE INDEX resources_by_asset ON resources(device_id, asset_id);

CREATE TABLE device_records (
  device_id  TEXT PRIMARY KEY NOT NULL,
  push_token TEXT,
  updated_at TEXT NOT NULL
);
```

`resources` is **device-scoped and event-independent**, joined to `event_assets` by
`(device_id, asset_id)` rather than owned by it. This is not a modelling preference — it is forced. The
byte `PUT` addresses a row from the URL path alone, and that path carries no event. Put `event_id` on the
resource row and the byte `PUT` could no longer write it.

The obvious refactor ("just add `event_id`") is therefore permanently unavailable, and the reason should
outlive everyone here: it is a URL grammar, not a schema choice. Changing it would change the upload URL,
which is compile-time on the client because PhotoKit forces it.

*Alternative considered:* one denormalized `uploads` table keyed by `(event_id, device_id, key)`. Rejected
for the same reason, and because the same byte is legitimately referenced by two events during a switch.

**Every text primary key carries an explicit `NOT NULL`.** Only `INTEGER PRIMARY KEY` implies it in
SQLite; measured, an explicit `INSERT … VALUES (NULL)` into a bare `TEXT PRIMARY KEY` **succeeded**
(`PROBE-FINDINGS.md` §4.5). Without it a stray `undefined` inserts a NULL-keyed row instead of failing.

### D2 — The manifest `PUT` is the authority; the byte `PUT` is best-effort

| write | carries | on DB failure |
|---|---|---|
| byte `PUT /api/v1/files/devices/<d>/<f>` | `resources.uploaded = 1` | **still `201`** |
| manifest `PUT /api/v1/events/<e>/devices/<d>` | membership row + full-state asset replace + resource upserts, **one `batch()`** | `502`, nothing written |

The byte route stays a streaming proxy whose success is *the bytes landed*. Failing it because a
bookkeeping row did not land would turn a successful upload into a retried one.

That is a deliberate absence-collapse, so it must name what makes it safe for **every** cause it absorbs:
the manifest `PUT` is **full-state** and upserts each resource with `uploaded ?? true` — the device lists
only `COMPLETED` resources, so `true` is right by construction — and it fires in the **same cycle**,
because a newly-uploaded resource changes the projection.

The double-failure case is covered by one word already in `device-manifest`: the extension may skip an
unchanged manifest `PUT` only when the last write was **successful**. A failed manifest `PUT` leaves that
record untouched, so the next cycle re-`PUT`s rather than skipping. **`api-endpoints`' best-effort clause is
only safe because of that word**, so both specs state the dependency; neither may be edited alone.

`uploaded` is monotone `0 → 1`. Nothing lowers it.

### D3 — `size` is removed from both read routes

It has no reader: the iOS `UnionResource` omits it, `HttpDeviceFilesSource` documents it as an ignored
unknown key, and the web zip page reads `role`/`url`/`filename`/`key`. The sweep's byte accounting reads
storage `Length` directly.

This is not tidying. `size` is the only union field sourced from storage rather than the manifest, so
carrying it would force `size_bytes` into the schema, writable **only** by the byte `PUT` — whose write is
best-effort by D2. One lost write would then leave a NULL, the closed resource shape could not be emitted,
and the asset would silently vanish from the union: the invisible, unfixable loss the selection policy
exists to prevent. **Removing `size` is the enabling condition for D2.**

### D4 — One atomic `batch()` per manifest `PUT`, not an interactive transaction

Measured: `batch()` is one HTTP request and rolls back on both duplicate-PK and FK violation; an
interactive `transaction()` also works but costs a round-trip per statement (§4.1). A full-state replace of
2 000 assets is 460 ms as a batch (§4.3). Chosen on latency, with atomicity equal.

Watch the **32 766 bound-parameter limit** (§4.3): a full-state replace must chunk. Chunking inside one
`batch()` keeps atomicity; chunking across batches would not, and would leave a half-replaced event set
visible to the union.

### D5 — Capacity is exact, as a single conditional `INSERT … SELECT`

`event-limits` currently accepts an overshoot: *"the count is read-then-write without coordination (bunny
has no compare-and-set): concurrent first enrollments may transiently overshoot, accepted."* That premise
does not survive the move and the caveat is **deleted rather than carried forward**.

Measured with 10 devices racing for 3 slots (§4.4): naive read-then-write enrolled **10**; interactive
transactions enrolled 3 in 918 ms; a single conditional statement enrolled **exactly 3 in 158 ms**. The
full production rule holds as one statement — a known device always passes, a new device is refused at
capacity, leaving frees no slot, a departed device reuses its slot, and an active re-enrol is idempotent.

### D6 — `rowsAffected = 0` must not collapse two answers

The conditional insert of D5 returns `0` both when the event is **at capacity** (`409`) and when it **does
not exist** (`404`), because the capacity subquery yields NULL for a missing event and the `WHERE` is then
false (§4.5). Distinguishing them requires a deliberate follow-up existence read on the zero path.

This is the "absence is never silent" law reintroduced by a SQL idiom rather than by a swallowed `catch`,
which is exactly why it is written down: nothing in the type system or the test suite would notice.

### D7 — Foreign keys are relied upon, and asserted at boot

Measured (§4.1): `PRAGMA foreign_keys` defaults to **`1`** — unlike stock SQLite — violations are rejected
on a bare `execute()` and inside `batch()`, the value persists across requests, and two-level
`ON DELETE CASCADE` removes events → memberships → event_assets in 299 ms.

Two staleness classes in `scheduled-cleanup` become **unstateable** as a result: *incomplete* (a marker
missing while manifests remain) and *tombstone reclamation* (an empty directory). Both exist only because
S3 has no referential integrity.

**Expiry trigger:** a provisioning change that turned `foreign_keys` off would disable every constraint
**silently**, with no error anywhere. The boot probe (`deployment-configuration`) therefore asserts the
pragma's value rather than trusting this measurement.

### D8 — The sweep marks from the database and deletes from storage, row **before** byte

Phase ordering survives unchanged and for its original reason: the asset phase computes its root set and
per-device floors over events that survived the event phase. What changes is the source — the root set is a
query (227 ms over 30 000 keys, §4.3) instead of a manifest read per member.

A collected byte's `resources` row is deleted **before** the byte:

- row → byte: a crash leaves an orphan byte, still unreferenced and still below the device's floor, so the
  next run collects it. **Self-healing.**
- byte → row: a crash leaves a row claiming `uploaded = 1` for bytes that are gone.

The second residue is inert today and lethal in change #2, when the rejoin reconcile stops reading the
storage listing and reads the database instead: a stale row would suppress re-upload of a photo whose bytes
no longer exist. The ordering decision belongs to **this** change because this change writes the sweep.

### D9 — The sweep's deletion decision runs inside an interactive transaction

Read-your-writes held 10/10, and a separate read-only token saw a fresh write immediately (§4.2) — but
that was a laptop against a test database, **not an Edge Script against the production one**, and replica
routing is exactly what differs. `api/src/config.ts` already records the matching hazard for storage:
*"a stale replica read is the one failure mode that would delete live data."*

The exposure is narrow and real: emptiness deletes an event with memberships but none `active`. A stale
replica that missed a **rejoin** would see a fully-departed event and delete a live one. The deadline rule
is stale-safe by contrast — it reads immutable stamped columns.

So the sweep makes its decision inside an interactive `transaction()`, which runs against the primary
(measured working, 108 ms, alive after 7 s idle, §4.1). Nightly, once, on a path where 918 ms is free.

**Expiry trigger:** the docs gate baton sessions behind "contact us" although they worked; that permission
could be withdrawn. And §4.2 must be **re-confirmed from the edge** before any future change lets the
sweep delete on an ordinary read's word.

### D10 — Membership state is a column; the departed sibling is deleted

`<deviceId>.left.json` and the last-write-wins tie-break over directory timestamps are replaced by
`memberships.state`. Three consumers — the union, the notify fan-out, and the leave reap — each currently
reimplement that rule; all three become `WHERE state = 'active'`.

It also deletes an edge case the spec itself calls unreachable (*"An exact-tie of last-modified times (not
producible in practice)"*) and a "SHALL NOT be counted twice when both siblings are present" rule that a
primary key makes unstateable.

Semantics are preserved: a departed membership keeps its `event_assets` rows, so a leaver's photos stay in
the union, and the device stops publishing, so the set freezes exactly as the snapshot did.

### D11 — Spec layout: `api-endpoints` is the surface, other capabilities are the decisions

```
  DECISION spec          │  api-endpoints
  ───────────────────────┼────────────────────────────────
  what the bound IS      │  route · method · body fields
  why it exists          │  which status a violation gets
  what may vary          │  what makes no upstream write
```

`api-endpoints` **cites, never restates**. It says a body violating an `event-limits` window rule yields
`400` with no upstream write; it never repeats "30 days", so it cannot come to disagree about it.

This resolves duplication that predates the change. The `endsAt` validation rules — canonical shape, real
round-tripping instant, strictly after `startsAt`, within `windowMax`, and the absent-`endsAt` fallback —
are currently stated in full in **both** `event-limits` and `event-creation`. Nothing contradicts today;
`openspec validate --strict` would never notice if it did, because it does not compare specs to each other.
The duplicate copy is deleted, not relocated.

Applied to `event-creation`, which turns out to be three specs in one: the route mechanics, the minted id,
the name validation and the faithful outcome go to `api-endpoints`; the marker registry becomes the
`events` table in `database`; the date validations are deleted as duplicates; and the **write-once-except-
`name`** rule moves to `event-limits`, which is what it defends. That rule survives verbatim and matters
*more* under SQL, not less: a table with an `UPDATE` is a far easier place to add a careless `SET` than a
write-once JSON blob, and SQLite offers no column-level immutability without a trigger. It stays a route
discipline, stated where someone adding an endpoint will read it.

Name validation (trimmed, non-empty, ≤ 100 characters) is the one validation that really is surface: no
bound in `event-limits`' sense, no decision behind `100`, and no consumer but a label.

Five per-endpoint *"requires a device token"* requirements are deleted. `device-attestation` already owns
the rule **and** the closed list of ungated routes; the copies existed only because there were five endpoint
specs. Nothing is weakened — the same rule is stated once instead of seven times.

### D12 — Existence is a row, and `404` stays a sealed NotFound

An event exists exactly when a row in `events` exists. `leave-event`'s two-witness teardown rule depends on
a `404` being a **real** deletion rather than a transient miss, which holds for the same reason as before:
no route deletes on touch, so only the sweep removes the row.

### D13a — The cutover's programs are throwaway, and are not committed

The migrate/backfill/verify/probe programs run EXACTLY ONCE, against one store, on one day. Committing
them would leave four permanent modules whose only reader is a day in the past, and a reviewer a year from
now would have to work out whether they still mean anything. They live in a scratchpad, run through
`proton-env` (the credentials are stored there, and in CI and the Edge Script — never in the repo), and go
away with the cutover.

What SURVIVES them is what a later reader actually needs: this document's Migration Plan, the measurements
the probe took (`PROBE-FINDINGS.md`), and the run's own output. A tool is scaffolding; a measurement is
evidence, and only the second is worth keeping.

The nightly sweep is the deliberate contrast — it runs every night, so it stays in the repo and in CI, and
this change gives it the store credentials it now needs.

### D13 — The cutover writes the database and leaves the objects in place

Nothing in this change deletes an S3 marker, manifest, or config object. They stop being read; they stay
readable. That is the rollback path (see Migration Plan), and it is why the flip can be a single deploy
rather than a staged dual-write.

## Risks / Trade-offs

- **Bunny Database is in public preview** → the store holds only derived, rebuildable state: every row can
  be reconstructed from the storage zone plus one manifest republish per device. Nothing user-visible is
  destroyed by losing it; the union goes empty until devices republish.
- **10-second maximum data-loss window on primary failover** → an acknowledged `uploaded = 1` or asset row
  can vanish. Repaired by D2's next full-state manifest publish. A lost *event* row is the serious case: the
  event 404s and members tear down. Mitigated only by the backfill artifact being retained (Migration Plan)
  and by the window being small relative to the nightly sweep.
- **Stale replica reads are unmeasured from the edge** → D9 confines the exposure to the sweep and puts the
  decision on the primary. This is the single most load-bearing unverified assumption in the change.
- **1 GB per database ceiling** → 12.3 MB for 20 000 assets ⇒ roughly 1.6 M assets of headroom (§4.3).
  Raised on request. Not a near-term bound, and worth an alert rather than a design.
- **The union transfers rows, not just matches them** → 373–541 ms is dominated by row transfer; a
  count-only variant of the same join is 61 ms. It grows with event size, not library size, and events are
  bounded at 10 devices. Revisit if capacity becomes a paid lever.
- **`STRICT` was unverified on this platform** → now measured against the DEPLOYED store and **adopted**
  (`PROBE-FINDINGS.md` §5): it is accepted *and* it rejects a wrong-typed value, on both engines the schema
  meets. Without it SQLite coerces silently, so a handler bug storing `42` where a key belongs would be
  unfindable by the code that wrote `"42"`.
- **The sweep gains a dependency it did not have** → it now needs both the database and the storage zone.
  A database outage means no reclamation that night, which is the safe direction: it deletes nothing.

## Migration Plan

1. **Provision** the database; declare `BUNNY_DATABASE_URL` / `BUNNY_DATABASE_AUTH_TOKEN` as secret
   references in `deployments/prod.json`, resolved into the artifact like every other backend credential,
   and set them on the Edge Script and as repository secrets.
2. **Probe** the deployed store: foreign-key enforcement is what the schema depends on, and the cutover
   stops if it is off.
3. **Migrate** — create the five tables and the index. Idempotent, re-runnable; the backfill applies it.
4. **Deploy** the new bundle. Reads and writes flip together, and the post-publish boot probe refuses a
   deployment whose store is unreachable or whose foreign keys are off.
5. **Backfill** from the existing objects: every `events/<id>/metadata.json` → an `events` row; every
   `<deviceId>.json` / `<deviceId>.left.json` → a `memberships` row with the state the last-write-wins rule
   resolves, plus its `event_assets` and `resources` rows with `uploaded = 1`; every
   `devices/<deviceId>.json` → a `device_records` row. Retain the run's output as an artifact.
6. **Verify** — for each surviving event, the union served from the database matches the union the previous
   implementation would have served. Compare as sets of `(deviceId, assetId, key)`.
7. **Confirm before the nightly sweep window.** The sweep deletes from night one, on the database's word;
   an unverified backfill would let it collect live bytes. This ordering is the one step in the plan whose
   omission is destructive rather than merely wrong.

**Rollback:** revert the bundle. Every object the previous implementation reads is still in place and still
current, because D13 wrote nothing over them and deleted nothing. The database is left populated; a
re-deploy re-runs the backfill idempotently. The window in which a rollback loses data is the set of writes
that landed only in the database — repaired, again, by one manifest republish per device.

## Open Questions

- ~~`STRICT` tables~~ — **settled** (`PROBE-FINDINGS.md` §5). Adopted, on the terms this question set:
  accepted AND enforcing, measured on the deployed store rather than inferred from a version number.
- **Does `event-creation` survive as a thin decision spec?** The working answer is no — it dissolves, and
  write-once-except-`name` moves to `event-limits` (D11). Reversible while the specs are being written.
- **Attestation records stay objects** (Non-Goals). If a later change moves them, the fully-orphaned
  collection rule in `scheduled-cleanup` becomes wholly relational; today it stays mixed.
