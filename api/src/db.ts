// The backend's RELATIONAL STORE (`docs/architecture.md`): the schema, the one narrow port every caller
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
// WHY `resources` IS UNDER THE MEMBERSHIP CASCADE. A resource row bears its event (migration 0010,
// `changes/per-event-storage-layout`): the byte route `/api/v2/events/<eventId>/files/devices/<deviceId>/…`
// names it, and the event-less form a 0.4 build still uploads to resolves the device's present membership
// ([presentUploadMembership]). So a resource lives exactly as long as its membership, and the union joins
// resources within one event: two events holding the same asset each hold its bytes.
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

import { objectNameFor } from "./object-names.ts";

/** One row of a result set. Values are whatever the driver yields for SQLite's storage classes. */
export type Row = Record<string, unknown>;

/** One statement plus its positional arguments. Positional throughout: no named-parameter dialect gap. */
export type Statement = { sql: string; args?: unknown[] };

/** What a write reports back. `rowsAffected` is load-bearing for the enrollment — see `ENROLL`. */
export type WriteResult = { rowsAffected: number };

/**
 * The store, as narrow as the callers need.
 *
 * `batch` is ATOMIC and is the manifest publish's vehicle: it is ONE HTTP request against the deployed
 * store, and it rolls back on both a duplicate primary key and a foreign-key violation
 * (`PROBE-FINDINGS.md` §4.1). `transaction` is the interactive form — a round-trip per statement, and
 * therefore reserved for the ONE caller that needs its other property: it runs against the PRIMARY, which
 * is what keeps the nightly sweep from deleting a live event on a stale replica read (design.md D9).
 *
 * `batch` answers one `WriteResult` per statement, in order. The versioned manifest publish reads its
 * FIRST statement's count to learn whether it won the version comparison — the answer has to come from
 * inside the transaction, because a read afterwards could see a newer publish that landed in between.
 */
export interface Db {
  execute(sql: string, args?: unknown[]): Promise<{ rows: Row[]; rowsAffected: number }>;
  batch(statements: Statement[]): Promise<WriteResult[]>;
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
  /**
   * Until when the event stays open (migration 0014; decision record `changes/back-dated-events`, D1): the range
   * end for an ordinary event, the date its host chose for one created after its end. Every lifecycle rule counts
   * from it (`lifecycle.ts`). Write-once, like every column but `name`.
   */
  openUntil: string;
  capacity: number;
  lifetimeSeconds: number;
  /**
   * When the event CLOSED (capability `event-lifetime`): no join, no rename, no change to a member's
   * asset set from then on. Final — nothing clears it. Absent/null on a row that never closed, and on
   * every row a caller builds before inserting (the columns are written only by the transitions below).
   */
  closedAt?: string | null;
  /** When the sweep COMPLETED the event: memberships and assets gone, the row kept until the deadline. */
  completedAt?: string | null;
  /** The last time a byte landing completed an asset of this event — the clock's second anchor. */
  lastLandedAt?: string | null;
  /**
   * The host's IANA time zone (`Europe/Berlin`; migration 0009, capability `event-site`): the zone the event
   * page reads `startsAt`/`endsAt` in, so they show as the host chose them. Null for an event created by a
   * client that sent none, which the page renders in UTC. Write-once, like every column but `name`.
   */
  zone?: string | null;
  /**
   * The event key's id (migration 0011; the encrypted file format, `docs/architecture.md`): 16 lowercase hex
   * characters, set exactly when the event is ENCRYPTED. The key itself never reaches the backend. Write-once.
   */
  keyId?: string | null;
};

function toEventRow(r: Row): EventRow {
  return {
    eventId: String(r.id),
    name: String(r.name),
    createdAt: String(r.created_at),
    startsAt: String(r.starts_at),
    endsAt: String(r.ends_at),
    // Every row is backfilled (0014); the fallback keeps a row written by the previous bundle during a deploy
    // readable as what it was — open until its end.
    openUntil: String(r.open_until ?? r.ends_at),
    capacity: Number(r.capacity),
    lifetimeSeconds: Number(r.lifetime_seconds),
    closedAt: r.closed_at == null ? null : String(r.closed_at),
    completedAt: r.completed_at == null ? null : String(r.completed_at),
    lastLandedAt: r.last_landed_at == null ? null : String(r.last_landed_at),
    zone: r.zone == null ? null : String(r.zone),
    keyId: r.key_id == null ? null : String(r.key_id),
  };
}

export async function insertEvent(db: Db, e: EventRow): Promise<void> {
  await db.execute(
    `INSERT INTO events (id, name, created_at, starts_at, ends_at, open_until, capacity, lifetime_seconds,
                         zone, key_id)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      e.eventId,
      e.name,
      e.createdAt,
      e.startsAt,
      e.endsAt,
      e.openUntil,
      e.capacity,
      e.lifetimeSeconds,
      e.zone ?? null,
      e.keyId ?? null,
    ],
  );
}

/**
 * Read one event, or `null` when it does not exist. An event EXISTS exactly when this row does — the
 * whole existence gate, and the reason a `404` is a SEALED absence that `event-lifetime`'s two-witness
 * teardown can act on. A transport failure THROWS, so a transient fault is never mistaken for absence.
 */
export async function readEvent(db: Db, eventId: string): Promise<EventRow | null> {
  const { rows } = await db.execute(`SELECT * FROM events WHERE id = ?`, [eventId]);
  return rows.length === 0 ? null : toEventRow(rows[0]);
}

/**
 * Rename. The ONLY write to an existing event, and it writes `name` ALONE — every other column is
 * write-once (capability `event-lifetime`). Under an object store violating that meant rewriting a whole
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

// ── Memberships and the enrollment ────────────────────────────────────────────────────────────────

/**
 * Where a member stands (capabilities `event-lifetime`, `manage-membership`; migration 0008). `sharing` and
 * `settled` are PRESENT — still in the event; `done` and `left` are GONE, and are told apart for observability
 * only: everything the backend decides treats them alike.
 */
export type MembershipState = "sharing" | "settled" | "done" | "left";

/** The states of a member still in the event, as a SQL list — what every "who is still here" read selects. */
const PRESENT = `('sharing', 'settled')`;

/**
 * Whether the `resources` row under `alias` has LANDED (migration 0015) — what every "are these bytes there"
 * read selects. A row whose first upload is still under way is in no union, listing or completeness check: a
 * read that forgot this would announce a photo whose bytes do not exist yet.
 */
function landed(alias: string): string {
  return `${alias}.landed_at IS NOT NULL`;
}

/**
 * The enrollment, as ONE conditional statement: it admits any device while the event exists and carries no close
 * stamp. There is no device limit (decision record `changes/remove-event-device-cap`, D3); the `events.capacity`
 * column it once compared against is a placeholder nothing reads (D4).
 *
 * ⚠️ `rowsAffected === 0` CONFLATES TWO ANSWERS — closed (`410`) and no such event (`404`) — because the existence
 * test also requires the event to carry no close stamp. Callers MUST disambiguate with `readEvent` rather than pick
 * one; see `enroll`. Events no longer close (decision record `changes/back-dated-events`, D2): the stamp is carried
 * by a COMPLETED event, whose photos are gone, and by an event closed before that change, which keeps the old
 * rules until the sweep drops it (`PENDING_CLEANUPS.md`). Either admits nobody, a returning device included.
 *
 * A (re)join puts the membership back to `sharing` and CLEARS `manifest_version`. `sharing` because a rejoined
 * device has settled nothing yet for its new membership. `manifest_version` — the one column the join writes that the manifest publish owns, and
 * the named one-writer exception in `database`. A join starts a new sequence of manifests: the device's
 * version counter lives in its local ledger database while its identity (a Keychain item) outlives that
 * database, so a re-joined device whose counter restarted would otherwise have every publish refused as
 * older, forever. The write is a lifecycle reset, not a second source for the version — nothing merges it
 * with the publish's (decision record `changes/manifest-versions`, D7). A first join inserts NULL anyway.
 */
const ENROLL = `
  INSERT INTO memberships (event_id, device_id, state, joined_at)
  SELECT ?, ?, 'sharing', ?
  WHERE EXISTS (SELECT 1 FROM events WHERE id = ? AND closed_at IS NULL)
  ON CONFLICT (event_id, device_id) DO UPDATE SET state = 'sharing', manifest_version = NULL`;

/**
 * What an enrollment attempt resolved to — the three answers a route must tell apart. `closed` is a completed
 * event (its row carries `closed_at`), or one closed before events stopped closing (capability `event-lifetime`).
 */
export type EnrollOutcome = "enrolled" | "closed" | "no-such-event";

/**
 * Enroll (or re-enroll) a device, exactly. Absence is never silent here: a zero-row outcome is resolved
 * to `closed` or `no-such-event` by a follow-up existence read, never collapsed into one status.
 */
export async function enroll(
  db: Db,
  eventId: string,
  deviceId: string,
  joinedAt: string,
): Promise<EnrollOutcome> {
  const { rowsAffected } = await db.execute(ENROLL, [eventId, deviceId, joinedAt, eventId]);
  if (rowsAffected > 0) return "enrolled";
  return (await readEvent(db, eventId)) === null ? "no-such-event" : "closed";
}

/**
 * The statements a leave applies, in order — to be run as ONE atomic unit (capability `manage-membership`).
 *
 * The membership becomes `done` when it left having everything — it had `settled`, every role it declared has
 * landed, and the device says it [received] every photo of the others — and `left` otherwise. The backend judges
 * the first two itself; only receiving is the device's word, because only the device can see its library. A leave
 * is the same request however it was made (the member's own, or the app's once the event is finished for it).
 *
 * Only a PRESENT membership moves, so a repeated leave keeps the state its first one recorded, and a leave naming
 * a membership that never existed changes nothing; neither is an error. The membership's assets are RETAINED,
 * so the union keeps serving what the device shared before it left. A leave closes nothing: events no longer
 * close (decision record `changes/back-dated-events`, D2).
 */
export function leaveStatements(
  eventId: string,
  deviceId: string,
  received: boolean,
): Statement[] {
  return [{
    sql: `UPDATE memberships
             SET state = CASE
               WHEN ? AND ${sharedAll("memberships")} THEN 'done'
               ELSE 'left'
             END
           WHERE event_id = ? AND device_id = ? AND state IN ${PRESENT}`,
    args: [received ? 1 : 0, eventId, deviceId],
  }];
}

/** The membership's state, or `null` when this device holds none in this event. */
export async function membershipState(
  db: Db,
  eventId: string,
  deviceId: string,
): Promise<MembershipState | null> {
  const { rows } = await db.execute(
    `SELECT state FROM memberships WHERE event_id = ? AND device_id = ?`,
    [eventId, deviceId],
  );
  return rows.length === 0 ? null : String(rows[0].state) as MembershipState;
}

// ── The manifest publish ──────────────────────────────────────────────────────────────────────────

/**
 * One resource of one asset, as the device manifest names it (wire format: `photo-sharing`).
 *
 * `key` is the client's object name (`<assetId>-<role>.<ext>`); `filename` is the human capture name. Neither
 * is where the bytes are: that is the `resources` row's `path`, which the backend chooses.
 */
export type ManifestResourceEntry = {
  role: string;
  contentType: string;
  key: string;
  filename: string;
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
 * ORDERED (`docs/architecture.md`, "The v2 manifest publish is ordered by its version"). The
 * FIRST statement records the publish's manifest version, and when the publish carries one it matches only
 * if the stored version is absent or not newer — so its count is the verdict: `1` won, `0` refused. Every
 * later statement is then gated on the stored version now being exactly this one, which is true iff the
 * first statement matched: a refused publish applies none of itself, however many statements (chunks) it
 * spans, and all of it is one transaction (`docs/architecture.md`). Equal is admitted — two publishes with
 * one version carry one snapshot, so re-applying it is harmless. Every publish carries a version (the route
 * refuses one without); the stored one is absent only after a (re)join, so the next publish always wins.
 *
 * ONE STATEMENT PER ASSET AND PER RESOURCE, never one multi-row insert. The deployed store refuses a
 * statement past 32 766 bound parameters — measured: 32 766 accepted, 40 000 refused with "too many SQL
 * variables" (`PROBE-FINDINGS.md` §4.3). The obvious optimization, batching the inserts into one
 * multi-row statement, walks straight into it, and the failure would appear only for a device with a
 * large library.
 */
export function publishStatements(
  eventId: string,
  deviceId: string,
  assets: ManifestAssetEntry[],
  opts: {
    version: number;
    /** The device declares its asset set settled (capability `photo-sharing`) → `settled`, else `sharing`. */
    final?: boolean;
    /**
     * The union-log rows this publish causes ([publishUnionChanges]), written inside the batch under the
     * same gates as the asset set — so a refused (older) publish, or one racing a completion, logs nothing.
     */
    log?: { gained: readonly string[]; removed: readonly string[]; at: string };
  },
): Statement[] {
  const out: Statement[] = [];
  // The version gate appended to every statement after the first.
  out.push({
    sql: `UPDATE memberships SET manifest_version = ?
          WHERE event_id = ? AND device_id = ?
            AND (manifest_version IS NULL OR manifest_version <= ?)`,
    args: [opts.version, eventId, deviceId, opts.version],
  });
  let gate = ` AND EXISTS (SELECT 1 FROM memberships
                       WHERE event_id = ? AND device_id = ? AND manifest_version = ?)`;
  let gateArgs: unknown[] = [eventId, deviceId, opts.version];
  // An event carrying a close stamp — completed, or closed before events stopped closing — has fixed asset sets.
  // The route refuses a changed set before it gets here; this gate closes the race with a completion stamped
  // between that check and this batch, so no statement below can rewrite such an event's assets.
  gate += ` AND NOT EXISTS (SELECT 1 FROM events WHERE id = ? AND closed_at IS NOT NULL)`;
  gateArgs = [...gateArgs, eventId];
  out.push({
    sql: `DELETE FROM event_assets WHERE event_id = ? AND device_id = ?${gate}`,
    args: [eventId, deviceId, ...gateArgs],
  });
  for (const a of assets) {
    // `INSERT … SELECT … WHERE` rather than `VALUES`, so the insert can carry the gate. With no gate the
    // `WHERE 1` makes it the plain insert it always was.
    out.push({
      sql: `INSERT INTO event_assets (event_id, device_id, asset_id, creation_date, roles)
            SELECT ?, ?, ?, ?, ? WHERE 1${gate}`,
      args: [
        eventId,
        deviceId,
        a.assetId,
        a.creationDate,
        JSON.stringify(a.resources.map((r) => r.role)),
        ...gateArgs,
      ],
    });
  }
  if (opts.log) {
    const { at } = opts.log;
    for (
      const [kind, ids] of [["gained", opts.log.gained], ["removed", opts.log.removed]] as const
    ) {
      for (const assetId of ids) {
        out.push({
          sql: `INSERT INTO union_log (event_id, kind, device_id, asset_id, at)
                SELECT ?, ?, ?, ?, ? WHERE 1${gate}`,
          args: [eventId, kind, deviceId, assetId, at, ...gateArgs],
        });
      }
    }
  }
  // The device's own declaration, gated like the asset set so a refused (older) publish cannot flip it. It
  // follows each publish both ways, and moves only a PRESENT membership: a member that left stays gone.
  out.push({
    sql: `UPDATE memberships SET state = ?
          WHERE event_id = ? AND device_id = ? AND state IN ${PRESENT}${gate}`,
    args: [opts.final ? "settled" : "sharing", eventId, deviceId, ...gateArgs],
  });
  return out;
}

/**
 * The asset set a membership currently declares, as `assetId → sorted roles` — what a publish to an event
 * closed before events stopped closing is compared against: an identical set is a harmless no-op, a different
 * one is refused, as it was then (`PENDING_CLEANUPS.md`).
 */
export async function declaredAssets(
  db: Db,
  eventId: string,
  deviceId: string,
): Promise<Map<string, string>> {
  const { rows } = await db.execute(
    `SELECT asset_id, roles FROM event_assets WHERE event_id = ? AND device_id = ?`,
    [eventId, deviceId],
  );
  return new Map(
    rows.map((
      r,
    ) => [String(r.asset_id), (JSON.parse(String(r.roles)) as string[]).sort().join(",")]),
  );
}

/** Stamp the clock's landing anchor on every event a byte landing completed an asset of. */
export async function stampLanded(db: Db, eventIds: readonly string[], at: string): Promise<void> {
  if (eventIds.length === 0) return;
  await db.execute(
    `UPDATE events SET last_landed_at = ? WHERE id IN (${eventIds.map(() => "?").join(", ")})`,
    [at, ...eventIds],
  );
}

// ── Done and finished (capability `event-lifetime`; decision record `changes/back-dated-events`, D3) ────────
//
// COMPUTED ON EVERY READ, NEVER STAMPED. Each fact below is a SQL predicate over the membership alias `m` (or the
// event alias `e`), and the one place it is spelled: the sweep, the event read's counts and the compatibility
// `closedAt` all read these. Nothing has to be "un-set": a member's new photo is declared but not landed, a gain
// advances the union head past every other member's received position, a late join adds a `sharing` member — each
// makes the predicate false by itself, which is exactly the reversibility the specification asks for.

/** SQL: membership `m` has SHARED everything — it settled, and every role it declares has landed. */
function sharedAll(m: string): string {
  return `${m}.state = 'settled' AND NOT EXISTS (
            SELECT 1 FROM event_assets ea, json_each(ea.roles) r
             WHERE ea.event_id = ${m}.event_id AND ea.device_id = ${m}.device_id
               AND NOT EXISTS (SELECT 1 FROM resources rs
                                WHERE rs.event_id = ea.event_id AND rs.device_id = ea.device_id
                                  AND rs.asset_id = ea.asset_id AND rs.role = r.value
                                  AND ${landed("rs")}))`;
}

/**
 * SQL: membership `m` is DONE — it shared everything and holds every photo of the others up to the event's union
 * head. A device that never said what it received (NULL) is not done: only a build that reports can be.
 */
function doneMember(m: string): string {
  return `(${sharedAll(m)}) AND COALESCE(${m}.received_position, -1) >= (
            SELECT COALESCE(MAX(u.seq), 0) FROM union_log u
             WHERE u.event_id = ${m}.event_id AND u.kind = 'gained')`;
}

/**
 * SQL: event `e` is FINISHED at the canonical instant bound to the one `?` — its open-until date has passed, someone
 * has joined it, and every member still in it is done. Members who left are not asked: they hold up nothing.
 */
function finishedEvent(e: string): string {
  return `COALESCE(${e}.open_until, ${e}.ends_at) < ?
          AND EXISTS (SELECT 1 FROM memberships m0 WHERE m0.event_id = ${e}.id)
          AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.event_id = ${e}.id AND m.state IN ${PRESENT}
                            AND NOT (${doneMember("m")}))`;
}

/**
 * An event's present membership counts (capability `sync-status`): `active` — everyone still in it; `final` — of
 * those, the `settled` (the wire name predates the states, and builds that predate `waiting` read it); `waiting` —
 * of those, the ones still sharing: not settled, or with a declared photo that has not landed.
 */
export async function memberCounts(
  db: Db,
  eventId: string,
): Promise<{ active: number; final: number; waiting: number }> {
  const { rows } = await db.execute(
    `SELECT COUNT(*) AS active, COALESCE(SUM(m.state = 'settled'), 0) AS final,
            COALESCE(SUM(NOT (${sharedAll("m")})), 0) AS waiting
       FROM memberships m WHERE m.event_id = ? AND m.state IN ${PRESENT}`,
    [eventId],
  );
  return {
    active: Number(rows[0].active),
    final: Number(rows[0].final),
    waiting: Number(rows[0].waiting),
  };
}

/**
 * Whether everyone who still is in the event has SHARED everything — settled, every declared photo landed — and
 * someone has joined it at all. What the compatibility `closedAt` is derived from (decision record
 * `changes/back-dated-events`, D5): the moment a build that predates open-until dates may safely treat the event
 * as closed and leave once it holds everything. It deliberately does NOT ask what each member received: such a
 * build never reports it, so asking would keep it from ever seeing the close it waits for.
 */
export async function sharedByEveryone(db: Db, eventId: string): Promise<boolean> {
  const { rows } = await db.execute(
    `SELECT EXISTS (SELECT 1 FROM memberships m0 WHERE m0.event_id = ?)
            AND NOT EXISTS (SELECT 1 FROM memberships m WHERE m.event_id = ? AND m.state IN ${PRESENT}
                              AND NOT (${sharedAll("m")})) AS shared`,
    [eventId, eventId],
  );
  return Number(rows[0].shared) === 1;
}

/** Whether the event is FINISHED at [nowIso] (see [finishedEvent]); `false` for an event that does not exist. */
export async function eventFinished(db: Db, eventId: string, nowIso: string): Promise<boolean> {
  const { rows } = await db.execute(
    `SELECT 1 FROM events e WHERE e.id = ? AND ${finishedEvent("e")}`,
    [eventId, nowIso],
  );
  return rows.length > 0;
}

/**
 * Record that a present membership holds every photo of the others up to union position [position] (decision
 * record `changes/back-dated-events`, D4). MONOTONIC: a lower position than the one stored changes nothing, so a
 * late or repeated report cannot move a device backwards. Answers whether the device is a present member.
 */
export async function recordReceived(
  db: Db,
  eventId: string,
  deviceId: string,
  position: number,
): Promise<boolean> {
  const { rowsAffected } = await db.execute(
    `UPDATE memberships SET received_position = MAX(COALESCE(received_position, -1), ?)
      WHERE event_id = ? AND device_id = ? AND state IN ${PRESENT}`,
    [position, eventId, deviceId],
  );
  return rowsAffected > 0;
}

// ── The byte route's record ───────────────────────────────────────────────────────────────────────

/** What the byte route records for one landed resource. */
export type ResourceRecord = {
  eventId: string;
  deviceId: string;
  assetId: string;
  role: string;
  /** Where the bytes were written — the full storage path, unencoded ([eventBytePath]). */
  path: string;
  contentType: string;
  filename: string;
  /** When they landed. */
  landedAt: string;
};

/**
 * Record that a resource's bytes landed, as a statement. The row's EXISTENCE is the record — there is no
 * upload flag, because the only writer of this table is the route that watched the bytes arrive (capability
 * `database`), and it reads the resource's identity from its path.
 *
 * A statement rather than a call, so the byte route can commit the record and the union log's `gained` rows it
 * causes as ONE batch: a log row for bytes the backend never recorded would announce an asset no read can
 * serve, and a record without its log row would leave the asset out of every delta.
 */
export function recordResourceStatement(r: ResourceRecord): Statement {
  // `landed_at` on conflict too: a row whose first resumable upload is still under way lands with these
  // bytes, and that upload's own completion — conditional on its claim — then overwrites them with an equally
  // valid copy, which is the overlap's usual "uploaded twice, never lost".
  return {
    sql:
      `INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename, landed_at)
          VALUES (?, ?, ?, ?, ?, ?, ?, ?)
          ON CONFLICT (event_id, device_id, asset_id, role) DO UPDATE SET
            path         = excluded.path,
            content_type = excluded.content_type,
            filename     = excluded.filename,
            landed_at    = excluded.landed_at,
            sha256       = NULL`,
    args: [r.eventId, r.deviceId, r.assetId, r.role, r.path, r.contentType, r.filename, r.landedAt],
  };
}

// ── The resumable upload's state (migration 0015, `docs/architecture.md` "Resumable uploads") ─────────

/** Which resource a byte route writes: its row's key. */
export type ResourceKey = { eventId: string; deviceId: string; assetId: string; role: string };

const KEY_WHERE = `event_id = ? AND device_id = ? AND asset_id = ? AND role = ?`;
const keyArgs = (k: ResourceKey) => [k.eventId, k.deviceId, k.assetId, k.role];

/** What a resource's row says about its bytes and the upload under way, if any. */
export type UploadState = {
  landed: boolean;
  /** The SHA-256 the landed bytes were declared with; `null` when none was. */
  sha256: string | null;
  /** The S3 multipart upload holding the stored parts; `null` until the first part filled. */
  uploadId: string | null;
  /** The SHA-256 the upload under way is for; `null` when none is. */
  uploadSha256: string | null;
  uploadSize: number | null;
  partSize: number | null;
  /** The storage key of what a dropped request received past its last stored part, where it starts, its size. */
  tail: { key: string; start: number; bytes: number } | null;
  claim: string | null;
};

/** The resource's row as {@link UploadState}, or `null` when it has none. */
export async function readUploadState(db: Db, k: ResourceKey): Promise<UploadState | null> {
  const { rows } = await db.execute(
    `SELECT landed_at, sha256, upload_id, upload_sha256, upload_size, upload_part_size,
            tail_key, tail_start, tail_bytes, claim
       FROM resources WHERE ${KEY_WHERE}`,
    keyArgs(k),
  );
  if (rows.length === 0) return null;
  const r = rows[0];
  const text = (v: unknown) => v == null ? null : String(v);
  const num = (v: unknown) => v == null ? null : Number(v);
  return {
    landed: r.landed_at != null,
    sha256: text(r.sha256),
    uploadId: text(r.upload_id),
    uploadSha256: text(r.upload_sha256),
    uploadSize: num(r.upload_size),
    partSize: num(r.upload_part_size),
    tail: r.tail_key == null
      ? null
      : { key: String(r.tail_key), start: Number(r.tail_start), bytes: Number(r.tail_bytes) },
    claim: text(r.claim),
  };
}

/**
 * Start a NEW upload of `sha256` (`size` bytes, cut into `partSize` parts) on the resource's row, claimed by
 * `claim`: a resource with no row gets one that has not landed; an existing row keeps its landed bytes and
 * forgets any earlier upload — the caller has aborted it and deleted its tail.
 */
export async function startUpload(
  db: Db,
  k: ResourceKey,
  meta: { path: string; contentType: string; filename: string },
  upload: { sha256: string; size: number; partSize: number; claim: string },
): Promise<void> {
  await db.execute(
    `INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename,
                            upload_sha256, upload_size, upload_part_size, claim)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (event_id, device_id, asset_id, role) DO UPDATE SET
       upload_id = NULL, upload_sha256 = excluded.upload_sha256, upload_size = excluded.upload_size,
       upload_part_size = excluded.upload_part_size, tail_key = NULL, tail_start = NULL,
       tail_bytes = NULL, claim = excluded.claim`,
    [
      ...keyArgs(k),
      meta.path,
      meta.contentType,
      meta.filename,
      upload.sha256,
      upload.size,
      upload.partSize,
      upload.claim,
    ],
  );
}

/**
 * Take over the upload of `sha256` under way on the resource's row for `claim` — a newer request for the same
 * bytes wins, and the older one stops at its next conditional write. Answers whether such an upload exists.
 */
export async function claimUpload(
  db: Db,
  k: ResourceKey,
  sha256: string,
  claim: string,
): Promise<boolean> {
  const { rowsAffected } = await db.execute(
    `UPDATE resources SET claim = ? WHERE ${KEY_WHERE} AND upload_sha256 = ?`,
    [claim, ...keyArgs(k), sha256],
  );
  return rowsAffected > 0;
}

/** Record the S3 upload id of `claim`'s upload. Answers whether `claim` still holds the row. */
export async function recordUploadId(
  db: Db,
  k: ResourceKey,
  claim: string,
  uploadId: string,
): Promise<boolean> {
  const { rowsAffected } = await db.execute(
    `UPDATE resources SET upload_id = ? WHERE ${KEY_WHERE} AND claim = ?`,
    [uploadId, ...keyArgs(k), claim],
  );
  return rowsAffected > 0;
}

/** Record `claim`'s tail (or none). Answers whether `claim` still holds the row. */
export async function recordTail(
  db: Db,
  k: ResourceKey,
  claim: string,
  tail: { key: string; start: number; bytes: number } | null,
): Promise<boolean> {
  const { rowsAffected } = await db.execute(
    `UPDATE resources SET tail_key = ?, tail_start = ?, tail_bytes = ? WHERE ${KEY_WHERE} AND claim = ?`,
    [tail?.key ?? null, tail?.start ?? null, tail?.bytes ?? null, ...keyArgs(k), claim],
  );
  return rowsAffected > 0;
}

/** Whether `claim` still holds the row — asked before completing, so a superseded request leaves it alone. */
export async function holdsClaim(db: Db, k: ResourceKey, claim: string): Promise<boolean> {
  const { rows } = await db.execute(
    `SELECT 1 FROM resources WHERE ${KEY_WHERE} AND claim = ?`,
    [...keyArgs(k), claim],
  );
  return rows.length > 0;
}

/**
 * Land `claim`'s upload, as a statement: the bytes are the row's now, the upload's state goes, and the SHA-256
 * stays so a later status read for the same bytes answers "already landed". Conditional on the claim, so the
 * caller's batch puts its union-log rows behind {@link onlyIfLanded}.
 */
export function landUploadStatement(
  k: ResourceKey,
  claim: string,
  r: { path: string; contentType: string; filename: string; sha256: string; landedAt: string },
): Statement {
  return {
    sql: `UPDATE resources SET landed_at = ?, sha256 = ?, path = ?, content_type = ?, filename = ?,
            upload_id = NULL, upload_sha256 = NULL, upload_size = NULL, upload_part_size = NULL,
            tail_key = NULL, tail_start = NULL, tail_bytes = NULL, claim = NULL
          WHERE ${KEY_WHERE} AND claim = ?`,
    args: [r.landedAt, r.sha256, r.path, r.contentType, r.filename, ...keyArgs(k), claim],
  };
}

/**
 * `statement`, run only when the statement before it in the same batch changed a row — what puts a landing's
 * union-log rows behind {@link landUploadStatement}'s claim, so a superseded request logs no gain.
 */
export function onlyIfLanded(statement: Statement): Statement {
  return {
    sql: statement.sql.replace(/ VALUES \(([^)]*)\)$/, " SELECT $1 WHERE changes() > 0"),
    args: statement.args,
  };
}

/**
 * Whether the event's union would GAIN this asset if `role`'s bytes were recorded right now — the byte
 * route's wake list (capability `delivery`), as the event's id or nothing. A byte belongs to ONE
 * event (change `per-event-storage-layout`), so it can complete at most that one.
 *
 * ASK BEFORE WRITING, not after. "Is this asset complete?" answered after the insert cannot tell a
 * completion from a re-upload of a role that was already stored, and a re-upload makes the union gain
 * nothing. Asked before, the three conditions below say exactly "this write is the one that completes it":
 * the event declares this role, this role is not recorded yet, and every OTHER declared role already is.
 *
 * Completeness is a SET comparison, never a count — a device may hold a role this event does not
 * declare, and counting would misjudge it (`add-v2-device-api` D8). This is the per-asset lookup that idiom
 * was written for; the union's own completeness check is a different query with a different cost profile.
 */
export async function eventsCompletedBy(db: Db, r: {
  eventId: string;
  deviceId: string;
  assetId: string;
  role: string;
}): Promise<string[]> {
  const { rows } = await db.execute(
    `SELECT ea.event_id
       FROM event_assets ea
      WHERE ea.event_id = ? AND ea.device_id = ? AND ea.asset_id = ?
        AND EXISTS (SELECT 1 FROM json_each(ea.roles) j WHERE j.value = ?)
        AND NOT EXISTS (SELECT 1 FROM resources r
                         WHERE r.event_id = ea.event_id AND r.device_id = ea.device_id
                           AND r.asset_id = ea.asset_id AND r.role = ? AND ${landed("r")})
        AND NOT EXISTS (
              SELECT 1 FROM json_each(ea.roles) j
               WHERE j.value != ?
                 AND NOT EXISTS (SELECT 1 FROM resources r
                                  WHERE r.event_id = ea.event_id AND r.device_id = ea.device_id
                                    AND r.asset_id = ea.asset_id AND r.role = j.value
                                    AND ${landed("r")}))`,
    [r.eventId, r.deviceId, r.assetId, r.role, r.role, r.role],
  );
  return rows.map((row) => String(row.event_id));
}

/**
 * How this publish changes what the event's union SERVES (capability `receiving-photos`; decision record
 * `changes/incremental-union`, D4): the assets it makes servable (`gained` — the manifest route's wake list
 * and its union-log rows) and the servable assets it stops declaring or leaves incomplete (`removed` — log
 * rows only, never served).
 *
 * The manifest publish is no longer the moment the union grows: under a manifest that declares INTENT, its
 * content changes when discovery changes, and the assets it names are usually incomplete. The one case
 * where a publish alone makes something fetchable is a **widening** — a membership re-admits assets whose
 * bytes it uploaded under an earlier, broader range (capability `manage-membership`), so no byte
 * moves and only the declaration changed.
 *
 * Asked BEFORE the replace, for the same reason as [eventsCompletedBy]: afterwards, "complete" cannot be
 * told from "was already complete and still is".
 *
 * An asset that was already served and merely gained a resource is deliberately NOT a gain: recipients
 * plan per asset and would refetch nothing (capability `receiving-photos`), so the union gained no asset.
 *
 * COST: two reads proportional to the device's declared and stored sets. That is the same order as the
 * publish it precedes — `publishStatements` already emits one statement per declared asset — so it adds
 * no new scaling class to this route.
 */
export async function publishUnionChanges(
  db: Db,
  eventId: string,
  deviceId: string,
  incoming: readonly { assetId: string; roles: readonly string[] }[],
): Promise<{ gained: string[]; removed: string[] }> {
  const { rows } = await db.execute(
    `SELECT ea.asset_id,
            (NOT EXISTS (SELECT 1 FROM json_each(ea.roles) j
                          WHERE NOT EXISTS (SELECT 1 FROM resources r
                                             WHERE r.event_id = ea.event_id
                                               AND r.device_id = ea.device_id
                                               AND r.asset_id = ea.asset_id
                                               AND r.role = j.value
                                               AND ${landed("r")}))) AS complete
       FROM event_assets ea
      WHERE ea.event_id = ? AND ea.device_id = ?`,
    [eventId, deviceId],
  );
  const alreadyComplete = new Set(
    rows.filter((r) => Number(r.complete) === 1).map((r) => String(r.asset_id)),
  );

  // A stored resource row per (asset, role) this device holds in this event — the reality the incoming
  // declaration is compared against.
  const held = await db.execute(
    `SELECT asset_id, role FROM resources r WHERE event_id = ? AND device_id = ? AND ${
      landed("r")
    }`,
    [eventId, deviceId],
  );
  const present = new Set(held.rows.map((r) => `${String(r.asset_id)} ${String(r.role)}`));

  const completeAfter = new Set(
    incoming
      .filter((a) =>
        a.roles.length > 0 && a.roles.every((role) => present.has(`${a.assetId} ${role}`))
      )
      .map((a) => a.assetId),
  );
  return {
    // Newly fetchable: either this event did not declare the asset at all, or it declared it in a shape
    // that was not yet complete.
    gained: [...completeAfter].filter((id) => !alreadyComplete.has(id)),
    removed: [...alreadyComplete].filter((id) => !completeAfter.has(id)),
  };
}

/**
 * The union-log rows ONE gained asset causes, one per event (decision record `changes/incremental-union`,
 * D4) — the byte route's half; the publish writes its own inside its batch (`publishStatements`'s `log`).
 */
export function gainedStatements(
  eventIds: readonly string[],
  deviceId: string,
  assetId: string,
  at: string,
): Statement[] {
  return eventIds.map((eventId) => ({
    sql:
      `INSERT INTO union_log (event_id, kind, device_id, asset_id, at) VALUES (?, 'gained', ?, ?, ?)`,
    args: [eventId, deviceId, assetId, at],
  }));
}

// ── Reads ─────────────────────────────────────────────────────────────────────────────────────────

/** One resource of one union asset, before its presigned `url` is minted. */
export type UnionResourceRow = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  role: string;
  contentType: string;
  /**
   * The wire's object name, `<assetId>-<role>.<ext>` — DERIVED ([objectNameFor]), since migration 0010 keeps
   * no such column. Installed clients key their download records by it, so it never changes shape.
   */
  key: string;
  /** Where the bytes are — the full storage path, unencoded. Empty when not present. */
  path: string;
  filename: string;
  /** Whether this declared role's bytes have arrived. False rows are what make an asset incomplete. */
  present: boolean;
};

/**
 * One row per DECLARED role of every asset the event's memberships name — present AND gone, so a
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
 * would misjudge the reverse case: a device may hold a role this event does not declare, and a count
 * would call that asset incomplete and drop it from an event it belongs in.
 */
export async function unionRows(
  db: Db,
  eventId: string,
  /**
   * A DELTA (decision record `changes/incremental-union`, D4): only assets the union log says were gained
   * after this position. Still put through the same declaration and completeness filter, so an asset
   * removed since its gain is not served, and one gained twice is served once.
   */
  after?: number,
): Promise<UnionResourceRow[]> {
  const delta = after === undefined ? "" : `
       AND EXISTS (SELECT 1 FROM union_log l
                    WHERE l.event_id = ea.event_id AND l.kind = 'gained' AND l.seq > ?
                      AND l.device_id = ea.device_id AND l.asset_id = ea.asset_id)`;
  const { rows } = await db.execute(
    `SELECT ea.device_id, ea.asset_id, ea.creation_date,
            j.value AS role,
            r.content_type, r.path, r.filename,
            (r.device_id IS NOT NULL) AS present
     FROM event_assets ea
     JOIN json_each(ea.roles) j
     LEFT JOIN resources r
       ON r.event_id = ea.event_id AND r.device_id = ea.device_id AND r.asset_id = ea.asset_id
      AND r.role = j.value AND ${landed("r")}
     WHERE ea.event_id = ?${delta}
     -- Deterministic, and PRIMARY first within an asset: no consumer depends on the order, but an
     -- unordered join makes a response diff noise rather than signal. Plain ORDER BY role would put
     -- 'live' ahead of 'primary', which reads as a bug to anyone eyeballing a union.
     ORDER BY ea.device_id, ea.asset_id, (j.value <> 'primary'), j.value`,
    after === undefined ? [eventId] : [eventId, after],
  );
  return rows.map((r) => {
    const present = Number(r.present) === 1;
    const filename = String(r.filename ?? "");
    return {
      deviceId: String(r.device_id),
      assetId: String(r.asset_id),
      creationDate: String(r.creation_date),
      role: String(r.role),
      contentType: String(r.content_type ?? ""),
      key: present ? objectNameFor(String(r.asset_id), String(r.role), filename) : "",
      path: String(r.path ?? ""),
      filename,
      present,
    };
  });
}

/**
 * [rows] grouped into the event's COMPLETE assets, in row order: each asset's resources together, and any asset
 * naming a resource the backend has not recorded as uploaded dropped. That drop IS the completeness mechanism:
 * a manifest declares roles whose bytes may not have arrived, so the declaration supplies the expectation and
 * these rows supply the reality — it is what distinguishes "this photo is coming" from "this photo does not
 * exist". The device union and the event page's read (`/web/events/<id>/photos`) both go through it, so the two
 * cannot disagree about which photos are in the event.
 */
export function completeAssets(rows: UnionResourceRow[]): UnionResourceRow[][] {
  const byAsset = new Map<string, UnionResourceRow[]>();
  for (const r of rows) {
    const id = `${r.deviceId}/${r.assetId}`;
    const slot = byAsset.get(id) ?? [];
    slot.push(r);
    byAsset.set(id, slot);
  }
  return [...byAsset.values()].filter((resources) =>
    resources.length > 0 && resources.every((r) => r.present)
  );
}

/**
 * The stored object a download of ONE resource of an event's union resolves to (decision record
 * `changes/incremental-union`, D1): its storage path when the event still declares that asset with that role (in a
 * membership of either state, as the union does) and its bytes are recorded; `null` otherwise.
 *
 * Narrower than the union on purpose: it asks about one role, so a photo whose OTHER role has not landed
 * still serves the role that has. Nothing hands out such a link — the union serves only complete assets —
 * and a client that guesses one learns nothing the event's union would not tell it.
 */
export async function downloadStoragePath(
  db: Db,
  eventId: string,
  deviceId: string,
  assetId: string,
  role: string,
): Promise<string | null> {
  const { rows } = await db.execute(
    `SELECT r.path
       FROM event_assets ea
       JOIN resources r ON r.event_id = ea.event_id AND r.device_id = ea.device_id
                       AND r.asset_id = ea.asset_id AND r.role = ? AND ${landed("r")}
      WHERE ea.event_id = ? AND ea.device_id = ? AND ea.asset_id = ?
        AND EXISTS (SELECT 1 FROM json_each(ea.roles) j WHERE j.value = ?)`,
    [role, eventId, deviceId, assetId, role],
  );
  return rows.length === 0 ? null : String(rows[0].path);
}

/**
 * The position a union read answers for (decision record `changes/incremental-union`, D4): the event's
 * last `gained` log row, or 0 when it has none.
 *
 * READ BEFORE THE ROWS, never after. Every gain at or below this position committed before it was read,
 * so the rows read next include it; a gain committing in between is past the position and is served
 * again by the next delta, which costs a duplicate the client dedups — never a miss.
 */
export async function unionPosition(db: Db, eventId: string): Promise<number> {
  const { rows } = await db.execute(
    `SELECT COALESCE(MAX(seq), 0) AS pos FROM union_log WHERE event_id = ? AND kind = 'gained'`,
    [eventId],
  );
  return Number(rows[0].pos);
}

/**
 * The event's DECLARED total (capability `sync-status`): every asset the whole union serves — complete, in a
 * membership of either state, exactly as `unionRows` + `completeAssets` keep it — plus every asset a member still
 * in the event declares that is not complete yet. What a member's "received" total counts, before subtracting its
 * own declaration: a photo still uploading is on its way, while one declared by a member who has left never lands
 * and so is not counted.
 */
export async function declaredTotal(db: Db, eventId: string): Promise<number> {
  const { rows } = await db.execute(
    `SELECT COUNT(*) AS n FROM event_assets ea
      WHERE ea.event_id = ?
        AND (EXISTS (SELECT 1 FROM memberships m
                      WHERE m.event_id = ea.event_id AND m.device_id = ea.device_id AND m.state IN ${PRESENT})
             OR (json_array_length(ea.roles) > 0 AND NOT EXISTS (
                   SELECT 1 FROM json_each(ea.roles) j
                    WHERE NOT EXISTS (SELECT 1 FROM resources rs
                                       WHERE rs.event_id = ea.event_id AND rs.device_id = ea.device_id
                                         AND rs.asset_id = ea.asset_id AND rs.role = j.value
                                         AND ${landed("rs")}))))`,
    [eventId],
  );
  return Number(rows[0].n);
}

/** One read of the union, as the union log records it (capability `privacy-security`). */
export type UnionFetch = {
  eventId: string;
  /** The device whose token verified; `null` for every other reader, of whom nothing else is kept. */
  deviceId: string | null;
  /** Why the client read, as it says; `null` when it said nothing this backend knows. */
  trigger: string | null;
  /** The position the read started from; `null` for a full read. */
  from: number | null;
  to: number;
  served: number;
  at: string;
};

/** Record one union read (capability `privacy-security`). The caller treats a failure as best-effort. */
export async function logUnionFetch(db: Db, f: UnionFetch): Promise<void> {
  await db.execute(
    `INSERT INTO union_log (event_id, kind, device_id, trigger, cursor_from, cursor_to, served, at)
     VALUES (?, 'fetch', ?, ?, ?, ?, ?, ?)`,
    [f.eventId, f.deviceId, f.trigger, f.from, f.to, f.served, f.at],
  );
}

// ── Devices: the attestation group ────────────────────────────────────────────────────────────────
//
// The ONE writer of `attest_*` and `created_at`, and the only route that may create a row at all.

/** A device's attestation, as `/attest/token` records it and `/attest/renew` reads it. */
export type DeviceAttestation = {
  /** The attested public key, base64 — a raw uncompressed EC point. */
  publicKey: string;
  /** Which verifier accepted the attestation — what renewal verifies the device's signature by. */
  platform: AttestPlatform;
  /** App Attest's environment on `ios`; the Keystore's security level on `android`. */
  environment: string;
};

/** The platforms a device can have attested on (`attest_platform`). */
export type AttestPlatform = "ios" | "android";

/**
 * Record an attestation. Creates the row, or replaces the attestation on a device that already has one
 * (a reinstall mints a fresh Secure-Enclave key against the same `deviceId`).
 *
 * Names ONLY the attestation columns in the conflict clause, so a re-attestation cannot disturb a push
 * registration — and leaves `created_at` alone, so it keeps meaning *first* attested rather than *most
 * recently* attested.
 *
 * `appVersion` is the version the minting request declared ({@link recordAppVersion}), so a row is never
 * versionless between its mint and the device's first recording call. `null` (a mint that declares none)
 * keeps whatever the row already held.
 */
export async function putAttestation(
  db: Db,
  deviceId: string,
  attestation: DeviceAttestation,
  at: string,
  tokenExpiresAt: string,
  appVersion: string | null = null,
): Promise<void> {
  await db.execute(
    `INSERT INTO devices (device_id, created_at, attest_key, attest_platform, attest_env, attested_at,
                          attest_token_expires_at, app_version)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT (device_id) DO UPDATE SET
       attest_key              = excluded.attest_key,
       attest_platform         = excluded.attest_platform,
       attest_env              = excluded.attest_env,
       attested_at             = excluded.attested_at,
       attest_token_expires_at = excluded.attest_token_expires_at,
       app_version             = COALESCE(excluded.app_version, devices.app_version)`,
    [
      deviceId,
      at,
      attestation.publicKey,
      attestation.platform,
      attestation.environment,
      at,
      tokenExpiresAt,
      appVersion,
    ],
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
    `SELECT attest_key, attest_platform, attest_env FROM devices WHERE device_id = ?`,
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
  const { attest_key: key, attest_platform: platform, attest_env: env } = rows[0];
  if (platform !== "ios" && platform !== "android") {
    throw new Error(`device ${deviceId} has an attestation of unknown platform '${platform}'`);
  }
  return { publicKey: String(key), platform, environment: String(env) };
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

// ── Devices: the app version ──────────────────────────────────────────────────────────────────────

/**
 * Keep the version the device last declared (capability `app-update-required`). The `IS NOT` clause makes
 * an unchanged version a read, not a write, so the routes that call this on every visit rewrite the row
 * only when the device upgraded (or rolled back). A device with no row is left alone: only the mint
 * creates one.
 */
export async function recordAppVersion(
  db: Db,
  deviceId: string,
  appVersion: string,
): Promise<void> {
  await db.execute(
    `UPDATE devices SET app_version = ? WHERE device_id = ? AND app_version IS NOT ?`,
    [appVersion, deviceId, appVersion],
  );
}

/** One device row's version and platform — what the sweep's version table counts. */
export type DeviceVersion = { deviceId: string; appVersion: string | null; platform: string };

/** Every device row's version and platform. */
export async function deviceVersions(db: Db): Promise<DeviceVersion[]> {
  const { rows } = await db.execute(`SELECT device_id, app_version, attest_platform FROM devices`);
  return rows.map((r) => ({
    deviceId: String(r.device_id),
    appVersion: r.app_version === null ? null : String(r.app_version),
    platform: String(r.attest_platform),
  }));
}

// ── Devices: the push-registration group ──────────────────────────────────────────────────────────

/** A device's registered push token, as the notify fan-out needs it (capability `delivery`). */
export type DevicePushToken = { kind: string; token: string; env: string };

/**
 * Last-write-wins over the push columns alone. Passing `null` clears the registration, which is a real
 * state and distinct from "this device has no record at all".
 *
 * An UPDATE, never an upsert. A row exists only where a device has attested, and this route cannot attest
 * on the device's behalf — so `rowsAffected === 0` means the backend holds no attestation for this device,
 * which the caller answers with `401` (`docs/architecture.md`). Inserting a row here would fabricate
 * an enrolment, and the `NOT NULL` attestation columns make it impossible anyway.
 */
export async function putDeviceRecord(
  db: Db,
  deviceId: string,
  push: DevicePushToken | null,
  at: string,
): Promise<WriteResult> {
  const { rowsAffected } = await db.execute(
    `UPDATE devices SET push_kind       = ?,
                        push_token      = ?,
                        push_env        = ?,
                        push_updated_at = ?
      WHERE device_id = ?`,
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

// ── The nightly sweep's queries (capability `event-lifetime`) ───────────────────────────────────
//
// The sweep runs its EVENT phase first and deletes stale rows; every query below then reads the store
// as it stands, so "the surviving events" needs no id list threaded through — it is simply what is left.
// That is the relational form of the phase ordering the sweep has always had, and the reason the order
// still matters.

/**
 * Every event, with what the staleness decision reads about its members (`MembershipCounts` in `lifecycle.ts`) —
 * the whole input to it, in one read. [nowIso] is the sweep's own now, which "finished" is judged at.
 */
export async function eventsWithCounts(
  db: Db,
  nowIso: string,
): Promise<
  {
    event: EventRow;
    total: number;
    active: number;
    finished: boolean;
    lastJoinedAt: string | null;
  }[]
> {
  const { rows } = await db.execute(
    `SELECT e.*,
            (SELECT COUNT(*) FROM memberships m WHERE m.event_id = e.id) AS total,
            (SELECT COUNT(*) FROM memberships m WHERE m.event_id = e.id AND m.state IN ${PRESENT})
              AS active,
            (SELECT MAX(m.joined_at) FROM memberships m WHERE m.event_id = e.id) AS last_joined_at,
            CASE WHEN ${finishedEvent("e")} THEN 1 ELSE 0 END AS finished
     FROM events e
     ORDER BY e.id`,
    [nowIso],
  );
  return rows.map((r) => ({
    event: toEventRow(r),
    total: Number(r.total),
    active: Number(r.active),
    finished: Number(r.finished) === 1,
    lastJoinedAt: r.last_joined_at == null ? null : String(r.last_joined_at),
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
 * COMPLETE one event (capability `event-lifetime`): close it if it had not closed, stamp it completed,
 * and delete every membership — the cascade takes their assets and resources, and the byte phase then
 * deletes the event's folder exactly as for a deleted event. The ROW stays, name and range included, until the deadline drops
 * it, so a device still joined is told "completed" rather than a "not found" it would disbelieve.
 */
export async function completeEvent(tx: Db, eventId: string, at: string): Promise<void> {
  // Separate statements, not a batch: the sweep calls this INSIDE its interactive transaction (which
  // already makes them atomic), and a batch there would try to open a second one.
  await tx.execute(
    `UPDATE events SET closed_at = COALESCE(closed_at, ?), completed_at = ? WHERE id = ?`,
    [at, at, eventId],
  );
  await tx.execute(`DELETE FROM memberships WHERE event_id = ?`, [eventId]);
  // The union log goes with the photos (capability `privacy-security`, "The service records who reads an
  // event's photo list"): the row outlives them, the record of who read them must not.
  await tx.execute(`DELETE FROM union_log WHERE event_id = ?`, [eventId]);
}

/**
 * The events that still LIVE — present and not completed — whose byte folders the sweep keeps (change
 * `per-event-storage-layout`, D7). Read on the PRIMARY by its caller: a stale replica missing a new event
 * would delete that event's photos.
 */
export async function liveEventIds(db: Db): Promise<Set<string>> {
  const { rows } = await db.execute(`SELECT id FROM events WHERE completed_at IS NULL`);
  return new Set(rows.map((r) => String(r.id)));
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
 * Everything this device has had recorded as arrived IN ONE EVENT (`docs/architecture.md`, the v2 listing).
 * Never another event's: a client marks every listed resource uploaded, and one stored for another event
 * would then never reach this one.
 *
 * Answers in IDENTITY terms rather than by object name, because that is the question v2 asks: "what do
 * you hold for me?" A consumer comparing this against what it intends to contribute gets its pending set
 * as a plain difference — which is why per-resource upload state needs no column, no flag and no second
 * endpoint. No `url` is minted: this route's consumer fetches no bytes.
 */
export async function deviceResources(
  db: Db,
  eventId: string,
  deviceId: string,
): Promise<DeviceResourceRow[]> {
  const { rows } = await db.execute(
    `SELECT asset_id, role, filename FROM resources r
      WHERE event_id = ? AND device_id = ? AND ${landed("r")}
     ORDER BY asset_id, role`,
    [eventId, deviceId],
  );
  return rows.map((r) => ({
    assetId: String(r.asset_id),
    role: String(r.role),
    filename: String(r.filename),
  }));
}

/**
 * The event this device is PRESENT in (`sharing`/`settled`), or `null` — where an event-less byte route files
 * its upload, and what its listing answers for (change `per-event-storage-layout`, D4). Single active
 * membership is the current contract; should two ever be present, the one first joined latest is taken.
 */
/**
 * What a byte route needs to know about the event it files an upload under, in ONE statement (each is an
 * Edge subrequest the route's fan-out cannot spare): the device's membership state there, and the event's
 * key id — `null` for a plain event. `null` when the device holds no membership in it.
 */
export async function uploadMembership(
  db: Db,
  eventId: string,
  deviceId: string,
): Promise<{ state: MembershipState; keyId: string | null } | null> {
  const { rows } = await db.execute(
    `SELECT m.state, e.key_id FROM memberships m JOIN events e ON e.id = m.event_id
     WHERE m.event_id = ? AND m.device_id = ?`,
    [eventId, deviceId],
  );
  if (rows.length === 0) return null;
  return {
    state: String(rows[0].state) as MembershipState,
    keyId: rows[0].key_id == null ? null : String(rows[0].key_id),
  };
}

/** {@link presentMembership} with the event's key id, in one statement — the event-less byte routes' read. */
export async function presentUploadMembership(
  db: Db,
  deviceId: string,
): Promise<{ eventId: string; keyId: string | null } | null> {
  const { rows } = await db.execute(
    `SELECT m.event_id, e.key_id FROM memberships m JOIN events e ON e.id = m.event_id
     WHERE m.device_id = ? AND m.state IN ${PRESENT}
     ORDER BY m.joined_at DESC, m.event_id LIMIT 1`,
    [deviceId],
  );
  if (rows.length === 0) return null;
  return {
    eventId: String(rows[0].event_id),
    keyId: rows[0].key_id == null ? null : String(rows[0].key_id),
  };
}

export async function presentMembership(db: Db, deviceId: string): Promise<string | null> {
  const { rows } = await db.execute(
    `SELECT event_id FROM memberships WHERE device_id = ? AND state IN ${PRESENT}
     ORDER BY joined_at DESC, event_id LIMIT 1`,
    [deviceId],
  );
  return rows.length === 0 ? null : String(rows[0].event_id);
}

/** Whether this device holds a membership in this event, in any state. */
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
 *
 * At most [limit] of them, picked at RANDOM on every call (decision record `changes/remove-event-device-cap`,
 * D1): each push is an Edge subrequest, so the fan-out's cost is bounded here rather than by the event's size.
 * A member left out of one wake is as well off as one whose wake was lost — the next wake picks again, and
 * its own uploads, the heartbeat and the foreground all read the union regardless (capability `delivery`).
 */
export async function pushTokensForEvent(
  db: Db,
  eventId: string,
  excludeDeviceId: string | undefined,
  limit: number,
): Promise<DevicePushToken[]> {
  const { rows } = await db.execute(
    `SELECT dr.push_kind, dr.push_token, dr.push_env
     FROM memberships m
     JOIN devices dr ON dr.device_id = m.device_id
     WHERE m.event_id = ? AND m.state IN ${PRESENT}
       AND dr.push_token IS NOT NULL AND dr.push_kind IS NOT NULL AND dr.push_env IS NOT NULL
       AND m.device_id IS NOT ?
     ORDER BY RANDOM()
     LIMIT ?`,
    [eventId, excludeDeviceId ?? null, limit],
  );
  return rows.map((r) => ({
    kind: String(r.push_kind),
    token: String(r.push_token),
    env: String(r.push_env),
  }));
}
