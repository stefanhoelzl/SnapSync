// The backend's RELATIONAL STORE (capability `database`): the schema, the one narrow port every caller
// speaks, and the statements that express this backend's invariants.
//
// WHY A DATABASE AT ALL. Until this change every relational fact was encoded in the shape of an S3 key
// namespace: an event existed iff `events/<id>/metadata.json` was present; a device was an active member
// iff its `<id>.json` was newer than its `<id>.left.json`; the union was assembled by listing one
// directory per member and cross-checking a second listing. Storage has no referential integrity, no
// atomic multi-key write and no compare-and-set, so each invariant was re-implemented by every consumer
// that needed it — and two whole classes of sweep logic existed only to repair states a foreign key
// forbids. See `openspec/changes/record-uploads-in-database/design.md`.
//
// WHY `resources` IS NOT UNDER THE EVENT CASCADE. This is FORCED, not chosen. The byte upload route
// addresses a resource row from the URL path alone — `/api/v1/files/devices/<deviceId>/<filename>`,
// which carries NO event (capability `api-endpoints`). A resource row bearing `event_id` could not be
// written by the one route that knows a byte landed. The upload URL is compile-time on the client
// (PhotoKit forces it), so this outlives any schema revision: a proposal to move `resources` under the
// event chain must first explain how the byte route learns the event. The same separation is what lets
// one uploaded byte serve two events during an event switch without being stored twice.
//
// WHY THE UNION CAN JOIN RESOURCES BY (device, asset) rather than through a per-event resource list: an
// asset's ORIGINAL resource set is a property of the ASSET, not of the membership (capability
// `device-manifest` — one `primary`, at most one `live`, no edit artifacts). Two events' manifests for
// the same asset therefore name the same resources, so a device-global resource table reproduces each
// event's projection exactly, with no sixth join table.
//
// EVERY TEXT PRIMARY KEY IS EXPLICITLY `NOT NULL`. Only `INTEGER PRIMARY KEY` implies it in SQLite —
// measured: an explicit `INSERT … VALUES (NULL)` into a bare `TEXT PRIMARY KEY` SUCCEEDED, while the same
// column declared `PRIMARY KEY NOT NULL` rejected it (`PROBE-FINDINGS.md` §4.5). Without it a stray
// `undefined` inserts a NULL-keyed row instead of failing.
//
// THE TABLES ARE `STRICT`, and that is a MEASUREMENT rather than an assumption. A platform-capability
// claim is settled by a run, not by a version number — so it was withheld until a probe against the
// DEPLOYED store reported not merely that the keyword parses but that it actually REJECTS a wrong-typed
// value (`PROBE-FINDINGS.md` §5). A syntax an engine accepts and ignores is worse than not using it,
// because it reads as a guarantee. Both engines this schema meets — bunny Database and the `node:sqlite`
// the tests and the local rig run — enforce it.
//
// What it buys: SQLite otherwise COERCES silently, so a handler bug that put a number where a key belongs
// would store `42` as text and be unfindable by the code that wrote `"42"`. Under STRICT it raises.

/** One row of a result set. Values are whatever the driver yields for SQLite's storage classes. */
export type Row = Record<string, unknown>;

/** One statement plus its positional arguments. Positional throughout: no named-parameter dialect gap. */
export type Statement = { sql: string; args?: unknown[] };

/** What a write reports back. `rowsAffected` is load-bearing for the capacity gate — see `ENROLL`. */
export type WriteResult = { rowsAffected: number };

/**
 * The store, as narrow as the callers need.
 *
 * `batch` is ATOMIC and is the manifest publish's vehicle: it is ONE HTTP request against the deployed
 * store, and it rolls back on both a duplicate primary key and a foreign-key violation
 * (`PROBE-FINDINGS.md` §4.1). `transaction` is the interactive form — a round-trip per statement, and
 * therefore reserved for the ONE caller that needs its other property: it runs against the PRIMARY, which
 * is what keeps the nightly sweep from deleting a live event on a stale replica read (design.md D9).
 */
export interface Db {
  execute(sql: string, args?: unknown[]): Promise<{ rows: Row[]; rowsAffected: number }>;
  batch(statements: Statement[]): Promise<void>;
  transaction<T>(fn: (tx: Db) => Promise<T>): Promise<T>;
  /** `PRAGMA foreign_keys` as the store reports it. The boot probe asserts this rather than trusting it. */
  foreignKeysEnabled(): Promise<boolean>;
}

/**
 * The schema. Idempotent and re-runnable — every statement is `IF NOT EXISTS`, so running it against an
 * already-migrated store is a no-op rather than an error.
 */
export const SCHEMA: readonly string[] = [
  `CREATE TABLE IF NOT EXISTS events (
     id               TEXT PRIMARY KEY NOT NULL,
     name             TEXT NOT NULL,
     created_at       TEXT NOT NULL,
     starts_at        TEXT NOT NULL,
     ends_at          TEXT NOT NULL,
     capacity         INTEGER NOT NULL,
     lifetime_seconds INTEGER NOT NULL
   ) STRICT`,
  `CREATE TABLE IF NOT EXISTS memberships (
     event_id  TEXT NOT NULL REFERENCES events(id) ON DELETE CASCADE,
     device_id TEXT NOT NULL,
     state     TEXT NOT NULL,
     joined_at TEXT NOT NULL,
     PRIMARY KEY (event_id, device_id)
   ) STRICT`,
  `CREATE TABLE IF NOT EXISTS event_assets (
     event_id      TEXT NOT NULL,
     device_id     TEXT NOT NULL,
     asset_id      TEXT NOT NULL,
     creation_date TEXT NOT NULL,
     PRIMARY KEY (event_id, device_id, asset_id),
     FOREIGN KEY (event_id, device_id)
       REFERENCES memberships(event_id, device_id) ON DELETE CASCADE
   ) STRICT`,
  `CREATE TABLE IF NOT EXISTS resources (
     device_id    TEXT NOT NULL,
     -- The BARE stored object name under the device's byte partition (<assetId>-<role>.<ext>), exactly
     -- as the device manifest names it and as the byte upload route's final path segment carries it —
     -- NOT a full files/devices/<id>/… path. The two are one fact reached from two directions, and the
     -- byte route's record must land on the same row the manifest publish upserts.
     key          TEXT NOT NULL,
     asset_id     TEXT NOT NULL,
     role         TEXT NOT NULL,
     content_type TEXT NOT NULL,
     filename     TEXT NOT NULL,
     uploaded     INTEGER NOT NULL DEFAULT 0,
     PRIMARY KEY (device_id, key)
   ) STRICT`,
  `CREATE INDEX IF NOT EXISTS resources_by_asset ON resources (device_id, asset_id)`,
  `CREATE TABLE IF NOT EXISTS device_records (
     device_id  TEXT PRIMARY KEY NOT NULL,
     push_token TEXT,
     updated_at TEXT NOT NULL
   ) STRICT`,
];

/** Apply the schema. Safe to run against an already-migrated store. */
export async function migrate(db: Db): Promise<void> {
  for (const sql of SCHEMA) await db.execute(sql);
}

// ── Events ────────────────────────────────────────────────────────────────────────────────────────

/** An event row, in the shape the wire and the lifecycle rules already speak. */
export type EventRow = {
  eventId: string;
  name: string;
  createdAt: string;
  startsAt: string;
  endsAt: string;
  capacity: number;
  lifetimeSeconds: number;
};

function toEventRow(r: Row): EventRow {
  return {
    eventId: String(r.id),
    name: String(r.name),
    createdAt: String(r.created_at),
    startsAt: String(r.starts_at),
    endsAt: String(r.ends_at),
    capacity: Number(r.capacity),
    lifetimeSeconds: Number(r.lifetime_seconds),
  };
}

export async function insertEvent(db: Db, e: EventRow): Promise<void> {
  await db.execute(
    `INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds)
     VALUES (?, ?, ?, ?, ?, ?, ?)`,
    [e.eventId, e.name, e.createdAt, e.startsAt, e.endsAt, e.capacity, e.lifetimeSeconds],
  );
}

/**
 * Read one event, or `null` when it does not exist. An event EXISTS exactly when this row does — the
 * whole existence gate, and the reason a `404` is a SEALED absence that `leave-event`'s two-witness
 * teardown can act on. A transport failure THROWS, so a transient fault is never mistaken for absence.
 */
export async function readEvent(db: Db, eventId: string): Promise<EventRow | null> {
  const { rows } = await db.execute(`SELECT * FROM events WHERE id = ?`, [eventId]);
  return rows.length === 0 ? null : toEventRow(rows[0]);
}

/**
 * Rename. The ONLY write to an existing event, and it writes `name` ALONE — every other column is
 * write-once (capability `event-limits`). Under an object store violating that meant rewriting a whole
 * document; here it is one careless `SET` away, which is why the statement is spelled out in one place
 * rather than composed.
 */
export async function renameEvent(db: Db, eventId: string, name: string): Promise<WriteResult> {
  const { rowsAffected } = await db.execute(`UPDATE events SET name = ? WHERE id = ?`, [
    name,
    eventId,
  ]);
  return { rowsAffected };
}

// ── Memberships and the capacity gate ─────────────────────────────────────────────────────────────

export type MembershipState = "active" | "departed";

/**
 * The capacity gate, as ONE conditional statement (capability `event-limits`, design.md D5).
 *
 * It admits a device when the event exists AND (the device already holds a membership — a rejoin reuses
 * its own slot — OR the event has fewer than `capacity` memberships of ANY state, because leaving frees
 * no slot). The count and the insert are evaluated together, so concurrent first enrollments cannot
 * overshoot: measured, ten devices racing for three slots enrolled TEN under read-then-write and exactly
 * THREE here, in 158 ms (`PROBE-FINDINGS.md` §4.4).
 *
 * ⚠️ `rowsAffected === 0` CONFLATES TWO ANSWERS — at capacity (`409`) and no such event (`404`) — because
 * the capacity subquery yields NULL for a missing event and the `WHERE` is then false. Callers MUST
 * disambiguate with `readEvent` rather than pick one; see `enroll`.
 */
const ENROLL = `
  INSERT INTO memberships (event_id, device_id, state, joined_at)
  SELECT ?, ?, 'active', ?
  WHERE EXISTS (SELECT 1 FROM events WHERE id = ?)
    AND (
      EXISTS (SELECT 1 FROM memberships WHERE event_id = ? AND device_id = ?)
      OR (SELECT COUNT(*) FROM memberships WHERE event_id = ?)
         < (SELECT capacity FROM events WHERE id = ?)
    )
  ON CONFLICT (event_id, device_id) DO UPDATE SET state = 'active'`;

/** What an enrollment attempt resolved to — the three answers a route must tell apart. */
export type EnrollOutcome = "enrolled" | "full" | "no-such-event";

/**
 * Enroll (or re-enroll) a device, exactly. Absence is never silent here: a zero-row outcome is resolved
 * to `full` or `no-such-event` by a follow-up existence read, never collapsed into one status.
 */
export async function enroll(
  db: Db,
  eventId: string,
  deviceId: string,
  joinedAt: string,
): Promise<EnrollOutcome> {
  const { rowsAffected } = await db.execute(ENROLL, [
    eventId,
    deviceId,
    joinedAt,
    eventId,
    eventId,
    deviceId,
    eventId,
    eventId,
  ]);
  if (rowsAffected > 0) return "enrolled";
  return (await readEvent(db, eventId)) === null ? "no-such-event" : "full";
}

/**
 * Leave: mark the membership `departed`. Idempotent — a repeated leave, or one naming a membership that
 * never existed, changes nothing and is not an error. The membership's assets are RETAINED, so the union
 * keeps serving what the device shared before it left.
 */
export async function departMembership(db: Db, eventId: string, deviceId: string): Promise<void> {
  await db.execute(
    `UPDATE memberships SET state = 'departed' WHERE event_id = ? AND device_id = ?`,
    [eventId, deviceId],
  );
}

/** The event's members in the requested states. One column read — no timestamps, no tie-break. */
export async function membersOf(
  db: Db,
  eventId: string,
  states: readonly MembershipState[],
): Promise<string[]> {
  const placeholders = states.map(() => "?").join(", ");
  const { rows } = await db.execute(
    `SELECT device_id FROM memberships WHERE event_id = ? AND state IN (${placeholders})
     ORDER BY device_id`,
    [eventId, ...states],
  );
  return rows.map((r) => String(r.device_id));
}

// ── The manifest publish ──────────────────────────────────────────────────────────────────────────

/**
 * One resource of one asset, as the device manifest names it (wire format: `device-manifest`).
 *
 * `key` is the BARE object name the bytes are stored under; `filename` is the human capture name. They
 * are different facts and routinely differ — the union projects both, and the download URL is built from
 * `key`.
 */
export type ManifestResourceEntry = {
  role: string;
  contentType: string;
  key: string;
  filename: string;
  /** Whether the bytes are known uploaded. ABSENT means `true` — see `publishStatements`. */
  uploaded?: boolean;
};

export type ManifestAssetEntry = {
  assetId: string;
  creationDate: string;
  resources: ManifestResourceEntry[];
};

/**
 * The statements a manifest publish applies, in order — to be run as ONE atomic unit.
 *
 * Three effects: the membership becomes `active`; the membership's asset set is REPLACED with exactly
 * what the body lists (a full-state replace — an omitted asset is removed); and each listed resource is
 * upserted.
 *
 * `uploaded` defaults to TRUE when the entry does not say otherwise, and this is what REPAIRS a byte
 * route whose best-effort record was lost: the device lists only `COMPLETED` resources, so `true` is
 * right by construction (capability `api-endpoints`). `uploaded` is MONOTONE — the upsert raises `0 → 1`
 * via `MAX` and never lowers it, so an out-of-order publish cannot un-say an upload.
 */
export function publishStatements(
  eventId: string,
  deviceId: string,
  assets: ManifestAssetEntry[],
): Statement[] {
  const out: Statement[] = [
    {
      sql: `UPDATE memberships SET state = 'active' WHERE event_id = ? AND device_id = ?`,
      args: [eventId, deviceId],
    },
    {
      sql: `DELETE FROM event_assets WHERE event_id = ? AND device_id = ?`,
      args: [eventId, deviceId],
    },
  ];
  for (const a of assets) {
    out.push({
      sql: `INSERT INTO event_assets (event_id, device_id, asset_id, creation_date)
            VALUES (?, ?, ?, ?)`,
      args: [eventId, deviceId, a.assetId, a.creationDate],
    });
    for (const r of a.resources) {
      out.push({
        sql:
          `INSERT INTO resources (device_id, key, asset_id, role, content_type, filename, uploaded)
              VALUES (?, ?, ?, ?, ?, ?, ?)
              ON CONFLICT (device_id, key) DO UPDATE SET
                asset_id     = excluded.asset_id,
                role         = excluded.role,
                content_type = excluded.content_type,
                filename     = excluded.filename,
                uploaded     = MAX(resources.uploaded, excluded.uploaded)`,
        args: [
          deviceId,
          r.key,
          a.assetId,
          r.role,
          r.contentType,
          r.filename,
          (r.uploaded ?? true) ? 1 : 0,
        ],
      });
    }
  }
  return out;
}

/**
 * The largest number of bound parameters one statement may carry on the deployed store — measured:
 * 32 766 accepted, 40 000 refused with "too many SQL variables" (`PROBE-FINDINGS.md` §4.3).
 *
 * `publishStatements` emits one statement per asset and per resource rather than one multi-row insert,
 * so no single statement approaches this. It is stated here because the obvious optimization — batching
 * the inserts into one multi-row statement — walks straight into it, and the resulting failure would
 * appear only for a device with a large library.
 */
export const MAX_BOUND_PARAMETERS = 32766;

// ── The byte route's record ───────────────────────────────────────────────────────────────────────

/**
 * Record that a resource's bytes landed. BEST-EFFORT at the call site: the byte route's response is the
 * storage outcome, and a failure here never changes it (capability `api-endpoints`).
 *
 * The row may not exist yet — a device can upload bytes before the manifest naming them is published —
 * so this inserts a placeholder carrying what the URL knows (device, key) and nothing it does not. The
 * placeholder's `asset_id` is empty, which is why the union joins through `event_assets` and cannot
 * surface one: a placeholder belongs to no asset until a manifest says which.
 */
export async function markUploaded(db: Db, deviceId: string, key: string): Promise<void> {
  // `key` is the URL's final path segment — the same bare object name the manifest publish upserts on.
  await db.execute(
    `INSERT INTO resources (device_id, key, asset_id, role, content_type, filename, uploaded)
     VALUES (?, ?, '', '', '', ?, 1)
     ON CONFLICT (device_id, key) DO UPDATE SET uploaded = 1`,
    [deviceId, key, key],
  );
}

// ── Reads ─────────────────────────────────────────────────────────────────────────────────────────

/** One resource of one union asset, before its presigned `url` is minted. */
export type UnionResourceRow = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  role: string;
  contentType: string;
  key: string;
  filename: string;
  uploaded: boolean;
};

/**
 * Every resource of every asset the event's memberships name — `active` AND `departed`, so a member who
 * has left keeps contributing what it already shared. `uploaded` rides along so the caller can drop an
 * asset that names an unrecorded resource (defense-in-depth, capability `api-endpoints`).
 */
export async function unionRows(db: Db, eventId: string): Promise<UnionResourceRow[]> {
  const { rows } = await db.execute(
    `SELECT ea.device_id, ea.asset_id, ea.creation_date,
            r.role, r.content_type, r.key, r.filename, r.uploaded
     FROM event_assets ea
     JOIN resources r ON r.device_id = ea.device_id AND r.asset_id = ea.asset_id
     WHERE ea.event_id = ?
     -- Deterministic, and PRIMARY first within an asset: no consumer depends on the order, but an
     -- unordered join makes a response diff noise rather than signal. Plain ORDER BY role would put
     -- 'live' ahead of 'primary', which reads as a bug to anyone eyeballing a union.
     ORDER BY ea.device_id, ea.asset_id, (r.role <> 'primary'), r.role`,
    [eventId],
  );
  return rows.map((r) => ({
    deviceId: String(r.device_id),
    assetId: String(r.asset_id),
    creationDate: String(r.creation_date),
    role: String(r.role),
    contentType: String(r.content_type),
    key: String(r.key),
    filename: String(r.filename),
    uploaded: Number(r.uploaded) === 1,
  }));
}

/**
 * The device's uploaded resources, for the per-device listing and the rejoin reconcile's seed.
 *
 * It yields the stored `key`, NOT the human `filename`: the listing's `filename` field is the object name
 * the reconciler matches its ledger against (capability `event-rejoin-reconciliation` — "the bare
 * `<assetId>-<role>.<ext>`"), and handing it a capture name instead would seed nothing and look exactly
 * like "this device has uploaded nothing".
 */
export async function deviceFiles(db: Db, deviceId: string): Promise<{ key: string }[]> {
  const { rows } = await db.execute(
    `SELECT key FROM resources WHERE device_id = ? AND uploaded = 1 ORDER BY key`,
    [deviceId],
  );
  return rows.map((r) => ({ key: String(r.key) }));
}

// ── Device records ────────────────────────────────────────────────────────────────────────────────

/** Last-write-wins, by construction: the upsert replaces the stored document. */
export async function putDeviceRecord(
  db: Db,
  deviceId: string,
  document: string,
  at: string,
): Promise<void> {
  await db.execute(
    `INSERT INTO device_records (device_id, push_token, updated_at) VALUES (?, ?, ?)
     ON CONFLICT (device_id) DO UPDATE SET push_token = excluded.push_token,
                                          updated_at = excluded.updated_at`,
    [deviceId, document, at],
  );
}

export async function readDeviceRecord(db: Db, deviceId: string): Promise<string | null> {
  const { rows } = await db.execute(`SELECT push_token FROM device_records WHERE device_id = ?`, [
    deviceId,
  ]);
  if (rows.length === 0) return null;
  const v = rows[0].push_token;
  return v === null || v === undefined ? null : String(v);
}

// ── The nightly sweep's queries (capability `scheduled-cleanup`) ───────────────────────────────────
//
// The sweep runs its EVENT phase first and deletes stale rows; every query below then reads the store
// as it stands, so "the surviving events" needs no id list threaded through — it is simply what is left.
// That is the relational form of the phase ordering the sweep has always had, and the reason the order
// still matters.

/** Every event, with its membership counts — the whole input to the staleness decision, in one read. */
export async function eventsWithCounts(
  db: Db,
): Promise<{ event: EventRow; total: number; active: number }[]> {
  const { rows } = await db.execute(
    `SELECT e.*,
            (SELECT COUNT(*) FROM memberships m WHERE m.event_id = e.id) AS total,
            (SELECT COUNT(*) FROM memberships m WHERE m.event_id = e.id AND m.state = 'active')
              AS active
     FROM events e
     ORDER BY e.id`,
  );
  return rows.map((r) => ({
    event: toEventRow(r),
    total: Number(r.total),
    active: Number(r.active),
  }));
}

/**
 * Delete one event. The `ON DELETE CASCADE` takes its memberships and their assets with it, in ONE
 * statement — so there is no ordering to get right and no partially-deleted event to observe. The
 * marker-last ordering the object store needed, and the "incomplete" state it existed to survive, are
 * both retired by this.
 */
export async function deleteEvent(db: Db, eventId: string): Promise<void> {
  await db.execute(`DELETE FROM events WHERE id = ?`, [eventId]);
}

/**
 * Every byte key still referenced by a surviving event, as `${deviceId}/${key}` — the asset phase's root
 * set. Spans memberships in BOTH states: a departed member's photos stay in the union while its event
 * lives, so its bytes must stay too.
 */
export async function referencedKeys(
  db: Db,
  excludeEvents: ReadonlySet<string> = new Set(),
): Promise<Set<string>> {
  const { rows } = await db.execute(
    `SELECT DISTINCT ea.event_id, r.device_id, r.key
     FROM event_assets ea
     JOIN resources r ON r.device_id = ea.device_id AND r.asset_id = ea.asset_id`,
  );
  const out = new Set<string>();
  for (const r of rows) {
    if (excludeEvents.has(String(r.event_id))) continue;
    out.add(`${String(r.device_id)}/${String(r.key)}`);
  }
  return out;
}

/**
 * Each device's retention floor: the earliest `startsAt` over the surviving events it is an ACTIVE
 * member of. `startsAt` is in the canonical cutoff form — fixed width, UTC, second precision — so the
 * lexicographic minimum IS the earliest instant. (`createdAt` is NOT, which is why `deleteByMs` parses.) A device with no active surviving membership is absent here, which the caller reads as `+∞`
 * — nothing of its is above the floor, so nothing is protected by it.
 */
export async function activeFloors(
  db: Db,
  excludeEvents: ReadonlySet<string> = new Set(),
): Promise<Map<string, string>> {
  const { rows } = await db.execute(
    `SELECT m.device_id, m.event_id, e.starts_at
     FROM memberships m
     JOIN events e ON e.id = m.event_id
     WHERE m.state = 'active'`,
  );
  const out = new Map<string, string>();
  for (const r of rows) {
    if (excludeEvents.has(String(r.event_id))) continue;
    const deviceId = String(r.device_id);
    const startsAt = String(r.starts_at);
    const prev = out.get(deviceId);
    if (prev === undefined || startsAt < prev) out.set(deviceId, startsAt);
  }
  return out;
}

/** Every device holding a membership in a surviving event, in either state. */
export async function devicesInEvents(
  db: Db,
  excludeEvents: ReadonlySet<string> = new Set(),
): Promise<Set<string>> {
  const { rows } = await db.execute(`SELECT DISTINCT device_id, event_id FROM memberships`);
  const out = new Set<string>();
  for (const r of rows) {
    if (excludeEvents.has(String(r.event_id))) continue;
    out.add(String(r.device_id));
  }
  return out;
}

/** Every device the store holds a record or a resource for — the asset phase's device roster. */
export async function knownDevices(db: Db): Promise<string[]> {
  const { rows } = await db.execute(
    `SELECT device_id FROM device_records
     UNION
     SELECT DISTINCT device_id FROM resources
     ORDER BY device_id`,
  );
  return rows.map((r) => String(r.device_id));
}

/**
 * Drop a collected byte's resource row. Called BEFORE the byte object is deleted, and the order is
 * load-bearing (design.md D8): row-then-byte leaves, on a crash, an orphan byte that is still
 * unreferenced and still below the floor, so the next run collects it — self-healing. Byte-then-row
 * would leave a row asserting `uploaded = 1` for bytes that no longer exist, which is inert only until
 * something reads the row for dedup and then silently suppresses a needed re-upload.
 */
export async function deleteResource(db: Db, deviceId: string, key: string): Promise<void> {
  await db.execute(`DELETE FROM resources WHERE device_id = ? AND key = ?`, [deviceId, key]);
}

/** Collect a fully-orphaned device's record. Its attestation object is collected from storage beside it. */
export async function deleteDeviceRecord(db: Db, deviceId: string): Promise<void> {
  await db.execute(`DELETE FROM device_records WHERE device_id = ?`, [deviceId]);
}

/**
 * Is the store COMPLETELY empty — no events, no memberships, no assets, no resources, no device records?
 *
 * This exists for exactly one caller and one question. The nightly sweep marks from this store, so an
 * empty one says "nothing is referenced" — and its asset phase then reads every byte in the zone as
 * unreferenced, with every device's retention floor `+∞` because no membership supplies one. That is a
 * correct reading of an empty store and a catastrophic reading of an un-backfilled one, and the two are
 * indistinguishable from inside the store. The caller pairs this with "storage nonetheless holds bytes",
 * which IS distinguishable, and refuses.
 *
 * A legitimately emptied world does not look like this: the sweep collects a fully-orphaned device's
 * bytes as it empties, so zero rows and a non-empty zone cannot both be true of a store that was ever
 * populated. They can only both be true before the first backfill.
 */
export async function storeIsEmpty(db: Db): Promise<boolean> {
  const { rows } = await db.execute(
    `SELECT (SELECT COUNT(*) FROM events)
          + (SELECT COUNT(*) FROM memberships)
          + (SELECT COUNT(*) FROM event_assets)
          + (SELECT COUNT(*) FROM resources)
          + (SELECT COUNT(*) FROM device_records) AS n`,
  );
  return Number(rows[0].n) === 0;
}
