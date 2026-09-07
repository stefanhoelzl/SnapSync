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
}

// ── Where the schema lives ────────────────────────────────────────────────────────────────────────
//
// NOT HERE ANY MORE. The statements in this file are written against **`api/schema.sql`**, which is
// GENERATED (`deno task schema`) by replaying **`api/migrations/*.sql`** — the ordered files that are the
// only thing able to change a store that already holds rows.
//
// This file used to carry a `SCHEMA` constant stating the same shape a second time, by hand, kept honest
// by a test asserting the two agreed. Deriving one from the other removes the possibility of disagreement
// rather than policing it, and the prose that annotated those tables moved with them into
// `migrations/0001_baseline.sql`, where SQLite preserves it verbatim into the generated snapshot.
//
// To read the current shape: open `api/schema.sql`. To change it: add a migration.

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
 * ALWAYS: the membership's asset set is REPLACED with exactly what the body lists (a full-state replace,
 * so an omitted asset is removed — a short manifest is a RETRACTION, not an oversight), each row carrying
 * the roles that asset declares. That declaration is what lets the union tell "this asset is complete"
 * from "one of its two resources has not arrived": `resources` holds only what arrived, and one row
 * proves nothing about whether a second is still owed.
 *
 * ADDITIONALLY, under `legacy` (the v1 route only): the membership becomes `active`, and each listed
 * resource is upserted. Both are behaviours v2 does not have and v1 keeps unchanged — v1 is spoken by
 * builds that cannot be updated, so its behaviour is frozen rather than corrected (capability
 * `database`, "Each table has exactly one writer, on the current API version").
 *
 * The legacy resource upsert is what REPAIRS a byte route whose best-effort record was lost: v1's device
 * lists only COMPLETED resources, so an entry that does not say otherwise means the bytes are stored, and
 * the row is created when missing. It stays MONOTONE — an entry that explicitly says NOT uploaded emits
 * no statement at all, so it can never remove a row an earlier publish recorded. Under the old schema
 * that was `MAX(uploaded, …)`; under row-existence semantics it is simply "never delete", which is the
 * same guarantee spelled without a column.
 */
export function publishStatements(
  eventId: string,
  deviceId: string,
  assets: ManifestAssetEntry[],
  opts: { legacy: boolean },
): Statement[] {
  const out: Statement[] = [];
  if (opts.legacy) {
    out.push({
      sql: `UPDATE memberships SET state = 'active' WHERE event_id = ? AND device_id = ?`,
      args: [eventId, deviceId],
    });
  }
  out.push({
    sql: `DELETE FROM event_assets WHERE event_id = ? AND device_id = ?`,
    args: [eventId, deviceId],
  });
  for (const a of assets) {
    out.push({
      sql: `INSERT INTO event_assets (event_id, device_id, asset_id, creation_date, roles)
            VALUES (?, ?, ?, ?, ?)`,
      args: [
        eventId,
        deviceId,
        a.assetId,
        a.creationDate,
        JSON.stringify(a.resources.map((r) => r.role)),
      ],
    });
    if (!opts.legacy) continue;
    for (const r of a.resources) {
      // Monotone by omission: an entry that says the bytes are NOT stored contributes no statement, so
      // it cannot un-say an upload an earlier publish recorded.
      if (r.uploaded === false) continue;
      out.push({
        sql: `INSERT INTO resources (device_id, asset_id, role, key, content_type, filename)
              VALUES (?, ?, ?, ?, ?, ?)
              ON CONFLICT (device_id, asset_id, role) DO UPDATE SET
                key          = excluded.key,
                content_type = excluded.content_type,
                filename     = excluded.filename`,
        args: [deviceId, a.assetId, r.role, r.key, r.contentType, r.filename],
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
 * Record that a resource's bytes landed. The row's EXISTENCE is the record — there is no upload flag,
 * because the only writer of this table is the route that watched the bytes arrive (capability
 * `database`).
 *
 * There are no placeholder rows any more. The old shape inserted `asset_id = ''` because v1's URL
 * carries only the object name, and the manifest filled the identity in later; under a key of
 * `(device_id, asset_id, role)` a placeholder has no identity to be stored under, and every device's
 * placeholders would collide on one empty pair. Both routes therefore supply real identity: v2 reads it
 * from its path, and v1 parses it out of the object name (the parse lives in the v1 adapter, and is
 * deleted with v1).
 *
 * Whether a failure here fails the REQUEST differs by version and is the caller's business: v1 swallows
 * it, because its manifest publish repairs a missing row; v2 does not, because nothing repairs it there.
 */
export async function recordResource(db: Db, r: {
  deviceId: string;
  assetId: string;
  role: string;
  key: string;
  contentType: string;
  filename: string;
}): Promise<void> {
  await db.execute(
    `INSERT INTO resources (device_id, asset_id, role, key, content_type, filename)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT (device_id, asset_id, role) DO UPDATE SET
       key          = excluded.key,
       content_type = excluded.content_type,
       filename     = excluded.filename`,
    [r.deviceId, r.assetId, r.role, r.key, r.contentType, r.filename],
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
  /** Whether this declared role's bytes have arrived. False rows are what make an asset incomplete. */
  present: boolean;
};

/**
 * One row per DECLARED role of every asset the event's memberships name — `active` AND `departed`, so a
 * member who has left keeps contributing what it already shared.
 *
 * The row set is driven by `event_assets.roles` (what the manifest says the asset is made of) and the
 * resource is LEFT-joined onto it, so a declared role whose bytes never arrived still produces a row with
 * `present = false`. That is what makes completeness a **set** question the caller can answer by
 * grouping: an asset is complete iff every one of its rows is present.
 *
 * Driving from the declaration rather than from `resources` is load-bearing in two directions. An inner
 * join would make a missing resource *vanish* rather than read as incomplete — the asset would silently
 * shrink to its uploaded resources and be served as whole. And counting rows instead of comparing sets
 * would misjudge the reverse case: `resources` is device-scoped while `roles` is event-scoped, so a
 * device may hold a role this event does not declare, and a count would call that asset incomplete and
 * drop it from an event it belongs in.
 */
export async function unionRows(db: Db, eventId: string): Promise<UnionResourceRow[]> {
  const { rows } = await db.execute(
    `SELECT ea.device_id, ea.asset_id, ea.creation_date,
            j.value AS role,
            r.content_type, r.key, r.filename,
            (r.device_id IS NOT NULL) AS present
     FROM event_assets ea
     JOIN json_each(ea.roles) j
     LEFT JOIN resources r
       ON r.device_id = ea.device_id AND r.asset_id = ea.asset_id AND r.role = j.value
     WHERE ea.event_id = ?
     -- Deterministic, and PRIMARY first within an asset: no consumer depends on the order, but an
     -- unordered join makes a response diff noise rather than signal. Plain ORDER BY role would put
     -- 'live' ahead of 'primary', which reads as a bug to anyone eyeballing a union.
     ORDER BY ea.device_id, ea.asset_id, (j.value <> 'primary'), j.value`,
    [eventId],
  );
  return rows.map((r) => ({
    deviceId: String(r.device_id),
    assetId: String(r.asset_id),
    creationDate: String(r.creation_date),
    role: String(r.role),
    contentType: String(r.content_type ?? ""),
    key: String(r.key ?? ""),
    filename: String(r.filename ?? ""),
    present: Number(r.present) === 1,
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
    `SELECT key FROM resources WHERE device_id = ? ORDER BY key`,
    [deviceId],
  );
  return rows.map((r) => ({ key: String(r.key) }));
}

// ── Devices: the attestation group ────────────────────────────────────────────────────────────────
//
// The ONE writer of `attest_*` and `created_at`, and the only route that may create a row at all.

/** A device's attestation, as `/attest/token` records it and `/attest/renew` reads it. */
export type DeviceAttestation = {
  /** The attested public key, base64 — a raw uncompressed EC point. */
  publicKey: string;
  environment: string;
};

/**
 * Record an attestation. Creates the row, or replaces the attestation on a device that already has one
 * (a reinstall mints a fresh Secure-Enclave key against the same `deviceId`).
 *
 * Names ONLY the attestation columns in the conflict clause, so a re-attestation cannot disturb a push
 * registration — and leaves `created_at` alone, so it keeps meaning *first* attested rather than *most
 * recently* attested.
 */
export async function putAttestation(
  db: Db,
  deviceId: string,
  attestation: DeviceAttestation,
  at: string,
  tokenExpiresAt: string,
): Promise<void> {
  await db.execute(
    `INSERT INTO devices (device_id, created_at, attest_key, attest_env, attested_at,
                          attest_token_expires_at)
     VALUES (?, ?, ?, ?, ?, ?)
     ON CONFLICT (device_id) DO UPDATE SET
       attest_key              = excluded.attest_key,
       attest_env              = excluded.attest_env,
       attested_at             = excluded.attested_at,
       attest_token_expires_at = excluded.attest_token_expires_at`,
    [deviceId, at, attestation.publicKey, attestation.environment, at, tokenExpiresAt],
  );
}

/**
 * The device's attested public key, or `null` when the backend holds no attestation for it.
 *
 * Absence: `null` means **no row** and nothing else. A transport failure THROWS, so "we have never seen
 * this device" is never confused with "we could not ask" — the first is a `401` telling the device to
 * attest afresh, the second a `502` telling it to retry. Collapsing them would send a device down a full
 * Apple attestation because the database blinked.
 */
export async function readAttestation(
  db: Db,
  deviceId: string,
): Promise<DeviceAttestation | null> {
  const { rows } = await db.execute(
    `SELECT attest_key, attest_env FROM devices WHERE device_id = ?`,
    [deviceId],
  );
  if (rows.length === 0) return null;
  // NO NULL CHECK ON THE COLUMNS, and that is the schema's guarantee rather than an omission: the
  // attestation group is `NOT NULL`, so a row exists if and only if the device has attested. Absence is
  // therefore expressed by the ABSENT ROW above and by nothing else.
  //
  // It was not always so. During the relational cutover a row could exist with the attestation columns
  // still NULL — migration v2 carried every legacy row across before their values, which lived in the
  // storage zone, could be backfilled — and this function guarded against handing renewal `String(null)`,
  // the literal "null", which fails to verify and reads as a REFUSED ASSERTION blaming the device's
  // Secure Enclave for something the backend never had. v3 tightened the columns and the cutover is long
  // finished; the guard outlived the state it was written for.
  const { attest_key: key, attest_env: env } = rows[0];
  return { publicKey: String(key), environment: String(env) };
}

/**
 * Advance the recorded expiry of the device's most recently minted token. Called by renewal BEFORE it
 * mints, so the store never understates how long a device is protected from collection.
 *
 * `rowsAffected` is returned rather than swallowed: zero means the row vanished between the read and this
 * write (the sweep, a restore), and the caller must not mint against a record that is gone.
 */
export async function touchTokenExpiry(
  db: Db,
  deviceId: string,
  expiresAt: string,
): Promise<WriteResult> {
  const { rowsAffected } = await db.execute(
    `UPDATE devices SET attest_token_expires_at = ? WHERE device_id = ?`,
    [expiresAt, deviceId],
  );
  return { rowsAffected };
}

// ── Devices: the push-registration group ──────────────────────────────────────────────────────────

/** A device's registered push token, as the notify fan-out needs it (capability `apns-push-sender`). */
export type DevicePushToken = { kind: string; token: string; env: string };

/**
 * Last-write-wins over the push columns alone. Passing `null` clears the registration, which is a real
 * state and distinct from "this device has no record at all".
 *
 * An UPDATE, never an upsert. A row exists only where a device has attested, and this route cannot attest
 * on the device's behalf — so `rowsAffected === 0` means the backend holds no attestation for this device,
 * which the caller answers with `401` (capability `api-endpoints`). Inserting a row here would fabricate
 * an enrolment, and the `NOT NULL` attestation columns make it impossible anyway.
 */
export async function putDeviceRecord(
  db: Db,
  deviceId: string,
  push: DevicePushToken | null,
  at: string,
): Promise<WriteResult> {
  const { rowsAffected } = await db.execute(
    // `AND attest_key IS NOT NULL` keeps the question "has this device ATTESTED", which is what the 401
    // means — rather than letting it degrade into "does a row exist" during the cutover window, when v2
    // has carried legacy rows across but their attestation columns are not yet filled. Such a device
    // keeps the push token it already had (the row survived) and is refused the next write until it
    // attests, which fills the columns and lets the write through.
    `UPDATE devices SET push_kind       = ?,
                        push_token      = ?,
                        push_env        = ?,
                        push_updated_at = ?
      WHERE device_id = ? AND attest_key IS NOT NULL`,
    [push?.kind ?? null, push?.token ?? null, push?.env ?? null, at, deviceId],
  );
  return { rowsAffected };
}

/**
 * The device's registered token, or `null` when it has no record or no registration. The two collapse
 * deliberately: the only caller is the best-effort notify fan-out, which skips the device either way,
 * and no consequence distinguishes them.
 */
export async function readDeviceRecord(
  db: Db,
  deviceId: string,
): Promise<DevicePushToken | null> {
  const { rows } = await db.execute(
    `SELECT push_kind, push_token, push_env FROM devices WHERE device_id = ?`,
    [deviceId],
  );
  if (rows.length === 0) return null;
  const { push_kind: kind, push_token: token, push_env: env } = rows[0];
  if (typeof kind !== "string" || typeof token !== "string" || typeof env !== "string") return null;
  return { kind, token, env };
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

/**
 * The devices whose row may be collected: those holding **no membership of any state** in a surviving
 * event, whose recorded token expiry has **passed**.
 *
 * ⚠️ THE EXPIRY CLAUSE IS FORCING, NOT TIDINESS. A device token is verified from its own signature, so it
 * keeps working for its full lifetime whether or not this row still exists. Collect earlier and the next
 * device-scoped write from that device is refused, and it recovers by minting a fresh Secure-Enclave key
 * and completing a full Apple attestation — the throttled path. Because this sweep runs nightly and the
 * device is still orphaned the following night, that repeats once per launch-day for as long as it stays
 * orphaned, silently, until Apple throttles it. Every event has a stamped lifetime of at most thirty days,
 * so EVERY user is orphaned between events: this is the common path, not an edge case. Past the expiry it
 * costs nothing — the device cannot make a gated call anyway.
 *
 * `excludeEvents` mirrors every other sweep query: in a dry run nothing was deleted, so the stale ids must
 * be excluded explicitly for both modes to evaluate the identical surviving world.
 */
export async function collectableDevices(
  db: Db,
  nowIso: string,
  excludeEvents: ReadonlySet<string> = new Set(),
): Promise<string[]> {
  const { rows } = await db.execute(
    `SELECT d.device_id,
            (SELECT GROUP_CONCAT(m.event_id) FROM memberships m WHERE m.device_id = d.device_id)
              AS event_ids
       FROM devices d
      WHERE d.attest_token_expires_at < ?
      ORDER BY d.device_id`,
    [nowIso],
  );
  const out: string[] = [];
  for (const r of rows) {
    const raw = r.event_ids;
    // No membership row at all, or every one of them belongs to an event this run is deleting.
    const eventIds = raw === null || raw === undefined ? [] : String(raw).split(",");
    if (eventIds.every((id) => excludeEvents.has(id))) out.push(String(r.device_id));
  }
  return out;
}

/**
 * Drop a collected byte's resource row. Called BEFORE the byte object is deleted, and the order is
 * load-bearing (design.md D8): row-then-byte leaves, on a crash, an orphan byte that is still
 * unreferenced and still below the floor, so the next run collects it — self-healing. Byte-then-row
 * would leave a row still asserting, by its existence, that bytes are stored which are gone — inert only
 * until something reads it for dedup and then silently suppresses a needed re-upload.
 *
 * Addressed by object NAME rather than by identity, because that is what the sweep holds: it walks the
 * byte partition and knows only what it found there. The `UNIQUE (device_id, key)` index is what makes
 * that lookup exact.
 */
export async function deleteResource(db: Db, deviceId: string, key: string): Promise<void> {
  await db.execute(`DELETE FROM resources WHERE device_id = ? AND key = ?`, [deviceId, key]);
}

/** How many device rows the store holds — the summary's devices tier reports kept as total minus deleted. */
export async function countDevices(db: Db): Promise<number> {
  const { rows } = await db.execute(`SELECT COUNT(*) AS n FROM devices`);
  return Number(rows[0].n);
}

/**
 * Collect a fully-orphaned device's row — its whole global record, attestation included. There is no
 * second object beside it any more; the row IS the record.
 */
export async function deleteDevice(db: Db, deviceId: string): Promise<void> {
  await db.execute(`DELETE FROM devices WHERE device_id = ?`, [deviceId]);
}

/** One resource the backend holds for a device, in the terms v2 addresses resources by. */
export type DeviceResourceRow = { assetId: string; role: string; filename: string };

/**
 * Everything this device has had recorded as arrived (capability `api-endpoints`, the v2 listing).
 *
 * Answers in IDENTITY terms rather than by object name, because that is the question v2 asks: "what do
 * you hold for me?" A consumer comparing this against what it intends to contribute gets its pending set
 * as a plain difference — which is why per-resource upload state needs no column, no flag and no second
 * endpoint. No `url` is minted: this route's consumer fetches no bytes.
 */
export async function deviceResources(db: Db, deviceId: string): Promise<DeviceResourceRow[]> {
  const { rows } = await db.execute(
    `SELECT asset_id, role, filename FROM resources WHERE device_id = ? ORDER BY asset_id, role`,
    [deviceId],
  );
  return rows.map((r) => ({
    assetId: String(r.asset_id),
    role: String(r.role),
    filename: String(r.filename),
  }));
}

/** Whether this device holds a membership in this event, in either state. */
export async function isMember(db: Db, eventId: string, deviceId: string): Promise<boolean> {
  const { rows } = await db.execute(
    `SELECT 1 FROM memberships WHERE event_id = ? AND device_id = ? LIMIT 1`,
    [eventId, deviceId],
  );
  return rows.length > 0;
}

/**
 * The push tokens of an event's ACTIVE members, optionally excluding one device — the recipient set of a
 * silent-push fan-out, resolved in ONE query.
 *
 * The shape it replaces — enumerate the members, then read each member's record — is a fossil of the
 * object store, where one document per device was the only way to ask. Under a relational store it is a
 * join, and the per-member form cost a round-trip per member on a path that now runs INSIDE a request the
 * caller times out. A member with no registered token is excluded by the query rather than by a later
 * filter, so "has no token" and "is not a member" stop being two passes over the same rows.
 */
export async function pushTokensForEvent(
  db: Db,
  eventId: string,
  excludeDeviceId?: string,
): Promise<DevicePushToken[]> {
  const { rows } = await db.execute(
    `SELECT dr.push_kind, dr.push_token, dr.push_env
     FROM memberships m
     JOIN devices dr ON dr.device_id = m.device_id
     WHERE m.event_id = ? AND m.state = 'active'
       AND dr.push_token IS NOT NULL AND dr.push_kind IS NOT NULL AND dr.push_env IS NOT NULL
       AND m.device_id IS NOT ?
     ORDER BY m.device_id`,
    [eventId, excludeDeviceId ?? null],
  );
  return rows.map((r) => ({
    kind: String(r.push_kind),
    token: String(r.push_token),
    env: String(r.push_env),
  }));
}
