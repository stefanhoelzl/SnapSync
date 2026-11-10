import { assert, assertEquals } from "@std/assert";
import { runSweep, SWEEP_METHOD } from "../../src/scripts/sweep.ts";
import type { Config } from "../../src/config.ts";
import type { FetchLike } from "../../src/storage.ts";
import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { type Db, insertEvent, publishStatements } from "../../src/db.ts";
import { eventBytePath } from "../../src/storage.ts";
import { replay } from "../../src/dev/replay.ts";
import { DEAD_TOKEN, enrolDevice, LIVE_TOKEN } from "../support/db.ts";
import { NOW } from "../support/harness.ts";
import { canonicalFromMs } from "../../src/validators.ts";

// The sweep (capability `event-lifetime`) MARKS FROM THE DATABASE and DELETES FROM STORAGE. These
// tests therefore drive two doubles: a real in-process SQLite for the relational half (so cascades and
// the queries behave as SQL, not as our idea of SQL) and an in-memory object-store fake for the byte
// half. NOW is pinned. The sweep holds only the storage AccessKey and the store's credentials — it makes
// no request to the Edge Script.
const ZONE = "test-zone"; // a FIXTURE, deliberately not the real zone
const CONFIG = {
  zone: ZONE,
  host: "storage.invalid",
  accessKey: "k",
  eventLifetimeSeconds: 30 * 24 * 60 * 60,
  maintenance: false,
} as unknown as Config;

const D = "11111111-0000-4000-8000-000000000001";
const D2 = "22222222-0000-4000-8000-000000000002";
const ORPHAN = "99999999-0000-4000-8000-000000000009";
const LIFETIME = 30 * 24 * 60 * 60;

// Staleness is decided by the DERIVED delete-by, `max(createdAt, openUntil) + lifetimeSeconds`. With
// `createdAt` pinned at 2026-06-01, an open-until date of 2026-06-10 lands the deadline on 2026-07-10 (before
// NOW → STALE) and one of 2026-07-13 lands it on 2026-08-12 (after NOW → LIVE, and its 3-day clock not yet due
// either). An ordinary event is open until
// its range end, so the fixture's range ends on its open-until date unless a test says otherwise; `startsAt`
// participates in staleness NOT AT ALL any more (decision record `changes/back-dated-events`, D2).
const STALE_STARTS = "2026-06-10T00:00:00Z";
const LIVE_STARTS = "2026-07-13T00:00:00Z";

function event(eventId: string, openUntil: string, overrides: Record<string, unknown> = {}) {
  const endsAt = (overrides.endsAt as string | undefined) ?? openUntil;
  return {
    eventId,
    name: "e",
    createdAt: "2026-06-01T00:00:00.000Z",
    startsAt: "2026-05-01T00:00:00Z",
    endsAt,
    openUntil: (overrides.openUntil as string | undefined) ?? endsAt,
    capacity: 10,
    lifetimeSeconds: LIFETIME,
    ...overrides,
  };
}

/**
 * In-memory bunny native-Storage fake, covering what lives in storage: the photo BYTES and the
 * directories that hold them. GET a directory LIST of direct children with `LastChanged`; DELETE
 * (idempotent). As on bunny, a directory OUTLIVES its last object, and a DELETE of a directory key (a
 * trailing `/`) is RECURSIVE. Every DELETE is recorded in order, so a test can assert both what went and
 * the SEQUENCE.
 */
function fake(initial: Record<string, { lc?: string; len?: number }>) {
  const store = new Map<string, { lc: string; len: number }>();
  const dirs = new Set<string>();
  const put = (k: string, v: { lc?: string; len?: number } = {}) => {
    store.set(k, { lc: v.lc ?? "2026-07-01T00:00:00.000Z", len: v.len ?? 1 });
    for (let i = k.indexOf("/"); i !== -1; i = k.indexOf("/", i + 1)) dirs.add(k.slice(0, i + 1));
  };
  for (const [k, v] of Object.entries(initial)) put(k, v);
  const deletes: string[] = [];
  const list = (key: string): Response => {
    const children = new Map<string, { name: string; dir: boolean; lc: string; len: number }>();
    let any = false;
    for (const [k, v] of store) {
      if (!k.startsWith(key)) continue;
      any = true;
      const rest = k.slice(key.length);
      if (!rest.includes("/")) children.set(rest, { name: rest, dir: false, lc: v.lc, len: v.len });
    }
    for (const d of dirs) {
      if (!d.startsWith(key)) continue;
      any = true;
      const rest = d.slice(key.length, -1);
      if (rest !== "" && !rest.includes("/")) {
        children.set(rest, { name: rest, dir: true, lc: "", len: 0 });
      }
    }
    if (!any) return new Response("nf", { status: 404 });
    const entries = [...children.values()].map((e) => ({
      ObjectName: e.name,
      IsDirectory: e.dir,
      Length: e.len,
      LastChanged: e.lc,
    }));
    return new Response(JSON.stringify(entries), { status: 200 });
  };
  const remove = (key: string): boolean => {
    if (!key.endsWith("/")) return store.delete(key);
    let found = false;
    for (const k of [...store.keys()]) if (k.startsWith(key)) found = store.delete(k) || found;
    for (const d of [...dirs]) if (d.startsWith(key)) found = dirs.delete(d) || found;
    return found;
  };
  const fetchImpl: FetchLike = (url, init) => {
    // The S3 ListMultipartUploads the sweep asks first: no upload is open in these tests.
    if (url.includes("?uploads")) {
      return Promise.resolve(new Response("<ListMultipartUploadsResult/>", { status: 200 }));
    }
    const key = url.split(`/${ZONE}/`)[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) return Promise.resolve(list(key));
    if (method === "GET") {
      return Promise.resolve(
        store.has(key) ? new Response("", { status: 200 }) : new Response("nf", { status: 404 }),
      );
    }
    if (method === "DELETE") {
      deletes.push(key);
      return Promise.resolve(new Response(null, { status: remove(key) ? 200 : 404 }));
    }
    return Promise.resolve(new Response(null, { status: 405 }));
  };
  return { store, dirs, deletes, put, fetchImpl };
}

/** A migrated store. */
async function db(): Promise<Db & { close(): void }> {
  const d = sqliteDb(":memory:");
  await replay(d);
  return d;
}

/**
 * Enroll `deviceId` in `eventId` and record the resources it shares there, one per asset id — each row
 * pointing at the event's own path for it, where the byte phase looks. Returns those paths.
 */
async function member(
  d: Db,
  eventId: string,
  deviceId: string,
  assetIds: string[],
  state: "sharing" | "left" = "sharing",
): Promise<string[]> {
  await d.execute(
    `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES (?, ?, ?, '2026-07-01T00:00:00Z')`,
    [eventId, deviceId, state],
  );
  if (assetIds.length === 0) return [];
  const paths = new Map<string, string>();
  for (const id of assetIds) {
    paths.set(`${id} primary`, await eventBytePath(eventId, deviceId, id, "primary"));
  }
  await d.batch(publishStatements(
    eventId,
    deviceId,
    assetIds.map((id) => ({
      assetId: id,
      creationDate: "2026-07-01T00:00:00Z",
      resources: [{
        role: "primary",
        contentType: "image/heic",
        key: `${id}-primary.heic`,
        filename: `${id}.heic`,
      }],
    })),
    { version: 1 },
  ));
  // The resource rows the byte upload would write — under v2 the byte route is that table's sole writer, so
  // the fixture writes them directly. A departed member's publish leaves it departed (only a PRESENT
  // membership's state follows a publish).
  for (const id of assetIds) {
    await d.execute(
      `INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename, landed_at)
       VALUES (?, ?, ?, 'primary', ?, 'image/heic', ?, '2026-07-01T00:00:00.000Z')`,
      [eventId, deviceId, id, paths.get(`${id} primary`), `${id}.heic`],
    );
  }
  return [...paths.values()];
}

/** A sweep run against both doubles, pinned clock; `log` is what it said. */
async function run(d: Db, store: ReturnType<typeof fake>, dryRun = false) {
  const log: string[] = [];
  const outcome = await runSweep({
    fetch: store.fetchImpl,
    config: CONFIG,
    db: d,
    now: () => NOW,
    dryRun,
    log: (m) => log.push(m),
  });
  return { outcome, log };
}

/**
 * What the sweep did to each event, as its delivery records say — `[eventId, "completed" | "deleted"]`, by event id.
 * A kept event leaves none (decision record `changes/delivery-record`, D9).
 */
async function outcomes(d: Db): Promise<[string, string][]> {
  const { rows } = await d.execute(
    `SELECT json_extract(fields, '$.event') AS e, json_extract(fields, '$.outcome') AS o
       FROM request_log WHERE method = ? ORDER BY e`,
    [SWEEP_METHOD],
  );
  return rows.map((r) => [String(r.e), String(r.o)]);
}

async function eventIds(d: Db): Promise<string[]> {
  return (await d.execute(`SELECT id FROM events ORDER BY id`)).rows.map((r) => String(r.id));
}

// ── EVENT PHASE ────────────────────────────────────────────────────────────────────────────────────

Deno.test("event phase → an event past its deadline is deleted; one within it is untouched", async () => {
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  const LIVE = "bbbbbbbb-0000-4000-8000-000000000002";
  await insertEvent(d, event(STALE, STALE_STARTS));
  await insertEvent(d, event(LIVE, LIVE_STARTS));
  await member(d, STALE, D, []);
  await member(d, LIVE, D, []);

  await run(d, fake({}));
  assertEquals(await outcomes(d), [[STALE, "deleted"]]);
  assertEquals(await eventIds(d), [LIVE]);
  d.close();
});

Deno.test("event phase → deleting an event CASCADES to its memberships, assets and resources", async () => {
  // The invariant the object store could not express. Resources are the event's own now (change
  // `per-event-storage-layout`), so their rows go with it — before the byte phase deletes the bytes.
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  await insertEvent(d, event(STALE, STALE_STARTS));
  await member(d, STALE, D, ["a"]);
  assertEquals((await d.execute(`SELECT * FROM event_assets`)).rows.length, 1);
  assertEquals((await d.execute(`SELECT * FROM resources`)).rows.length, 1);

  await run(d, fake({}));
  assertEquals((await d.execute(`SELECT * FROM memberships`)).rows.length, 0);
  assertEquals((await d.execute(`SELECT * FROM event_assets`)).rows.length, 0);
  assertEquals((await d.execute(`SELECT * FROM resources`)).rows.length, 0);
  d.close();
});

Deno.test("event phase → an EMPTIED event is COMPLETED early: memberships gone, the row kept", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, ["a"], "left");
  await member(d, E, D2, [], "left");
  await run(d, fake({}));
  assertEquals(await outcomes(d), [[E, "completed"]]);
  // The row stays until its deadline so a device still joined is told "completed", not "not found".
  assertEquals(await eventIds(d), [E]);
  const row = (await d.execute(`SELECT closed_at, completed_at FROM events`)).rows[0];
  assertEquals(row.completed_at, canonicalFromMs(NOW));
  assertEquals(row.closed_at, canonicalFromMs(NOW));
  assertEquals((await d.execute(`SELECT * FROM memberships`)).rows.length, 0);
  assertEquals((await d.execute(`SELECT * FROM event_assets`)).rows.length, 0);
  assertEquals((await d.execute(`SELECT * FROM resources`)).rows.length, 0);
  d.close();
});

Deno.test("event phase → a COMPLETED event is left alone until its deadline drops the row", async () => {
  const d = await db();
  const DONE = "cccccccc-0000-4000-8000-000000000003";
  const EXPIRED = "aaaaaaaa-0000-4000-8000-000000000001";
  await insertEvent(d, event(DONE, LIVE_STARTS));
  await insertEvent(d, event(EXPIRED, STALE_STARTS));
  await d.execute(`UPDATE events SET closed_at = ?, completed_at = ?`, [
    "2026-07-12T00:00:00.000Z",
    "2026-07-12T00:00:00.000Z",
  ]);
  await run(d, fake({}));
  assertEquals(
    await outcomes(d),
    [[EXPIRED, "deleted"]],
    "a completed event is dropped at its deadline, never completed again",
  );
  assertEquals(await eventIds(d), [DONE]);
  d.close();
});

// @Verifies(spec = "event-lifetime", requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest", scenario = "A member who never comes back")
Deno.test("event phase → the CLOCK completes an ended event with a silent member 3 days after its end", async () => {
  const d = await db();
  const DUE = "cccccccc-0000-4000-8000-000000000003";
  const NOT_YET = "dddddddd-0000-4000-8000-000000000004";
  // Ended 2026-07-11T11:00 → clock 07-14T11:00, one hour before NOW.
  await insertEvent(d, event(DUE, LIVE_STARTS, { endsAt: "2026-07-11T11:00:00Z" }));
  // Ended 2026-07-11T13:00 → clock 07-14T13:00, one hour after NOW.
  await insertEvent(d, event(NOT_YET, LIVE_STARTS, { endsAt: "2026-07-11T13:00:00Z" }));
  await member(d, DUE, D, []);
  await member(d, NOT_YET, D, []);
  await run(d, fake({}));
  assertEquals(await outcomes(d), [[DUE, "completed"]]);
  const completed = (await d.execute(`SELECT id FROM events WHERE completed_at IS NOT NULL`)).rows;
  assertEquals(completed.map((r) => String(r.id)), [DUE]);
  d.close();
});

// @Verifies(spec = "event-lifetime", requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest", scenario = "Photos still arriving push deletion back")
Deno.test("event phase → a late LANDING moves the clock: 3 days after the last arrival, not the end", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-05T00:00:00Z" }));
  await d.execute(`UPDATE events SET last_landed_at = ?`, ["2026-07-12T00:00:00.000Z"]);
  await member(d, E, D, []);
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

Deno.test("event phase → the clock never applies to a NEVER-JOINED event", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-02T00:00:00Z" }));
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

Deno.test("byte phase → a COMPLETED event's folder is deleted like a deleted event's", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  const [path] = await member(d, E, D, ["a"], "left");
  const store = fake({ [path]: { len: 4 } });
  await run(d, store);
  assertEquals(await outcomes(d), [[E, "completed"]]);
  // ONE recursive delete of the event's folder.
  assertEquals(store.deletes, [`files/${E}/`]);
  d.close();
});

Deno.test("dry-run → a completion is said and nothing is written", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  const [path] = await member(d, E, D, ["a"], "left");
  const store = fake({ [path]: { len: 4 } });
  const { log } = await run(d, store, true);
  assert(log.some((m) => m.startsWith(`[dry-run] would complete event ${E}`)), log.join("\n"));
  assert(log.includes(`[dry-run] would delete files/${E}/`), log.join("\n"));
  assertEquals(store.deletes, []);
  assertEquals(await outcomes(d), [], "a dry run records nothing");
  assertEquals((await d.execute(`SELECT * FROM memberships`)).rows.length, 1);
  assertEquals((await d.execute(`SELECT completed_at FROM events`)).rows[0].completed_at, null);
  d.close();
});

Deno.test("event phase → ONE active member keeps a within-deadline event alive", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [], "left");
  await member(d, E, D2, []);
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

// @Verifies(spec = "event-lifetime", requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest", scenario = "Created but never joined")
Deno.test("event phase → a MINTED-BUT-NEVER-JOINED event is not empty and survives", async () => {
  // Every fresh event is in this state: `POST /events` always produces a zero-device event, because the
  // creator confirms through the same join gate a scanned QR uses. Reaping it would delete a mint before
  // the host confirms.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

// @Verifies(spec = "event-lifetime", requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest", scenario = "An event created ahead of its start")
Deno.test("event phase → the deadline anchors at max(createdAt, openUntil), both directions", async () => {
  const d = await db();
  // Open until long before its creation (no create admits it; a row that says so) → anchored at createdAt
  // (2026-06-01) + 1 day → STALE.
  const BACK = "dddddddd-0000-4000-8000-000000000004";
  await insertEvent(d, event(BACK, "2026-01-01T00:00:00Z", { lifetimeSeconds: 24 * 60 * 60 }));
  // Open until after its creation → anchored at the open-until date (2026-07-13T18:00) + 1 day → LIVE.
  const EARLY = "eeeeeeee-0000-4000-8000-000000000005";
  await insertEvent(d, event(EARLY, "2026-07-13T18:00:00Z", { lifetimeSeconds: 24 * 60 * 60 }));
  await run(d, fake({}));
  assertEquals(await eventIds(d), [EARLY]);
  d.close();
});

Deno.test("event phase → a back-dated event lives 30 days past the date its host chose, not past its range", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  // Its range ended in May; created 2026-06-01 and kept open until 2026-06-20 → deadline 2026-07-20 → LIVE.
  await insertEvent(
    d,
    event(E, "2026-05-03T00:00:00Z", { openUntil: "2026-06-20T00:00:00Z", lastLandedAt: null }),
  );
  await run(d, fake({}));
  assertEquals(await eventIds(d), [E]);
  d.close();
});

/** Mark [deviceId] as having shared everything it declared and received up to the event's union head. */
async function done(d: Db, eventId: string, deviceId: string) {
  await d.execute(
    `UPDATE memberships SET state = 'settled', received_position = 1000000 WHERE event_id = ? AND device_id = ?`,
    [eventId, deviceId],
  );
}

// @Verifies(spec = "event-lifetime", requirement = "An event stays open until the date its host chose", scenario = "The host alone does not finish it")
Deno.test("event phase → a FINISHED event is completed at once; not before its open-until date", async () => {
  const d = await db();
  const FINISHED = "cccccccc-0000-4000-8000-000000000003";
  const STILL_OPEN = "dddddddd-0000-4000-8000-000000000004";
  // Open until yesterday, its one member done → finished → completed without waiting for the clock.
  await insertEvent(d, event(FINISHED, "2026-07-13T12:00:00Z"));
  // A back-dated event still open for a week: its host alone is done at once, and that finishes nothing.
  await insertEvent(
    d,
    event(STILL_OPEN, "2026-07-10T00:00:00Z", { openUntil: "2026-07-21T12:00:00Z" }),
  );
  for (const e of [FINISHED, STILL_OPEN]) {
    await member(d, e, D, ["a"]);
    await done(d, e, D);
  }
  await run(d, fake({}));
  assertEquals(await outcomes(d), [[FINISHED, "completed"]]);
  const completed = (await d.execute(`SELECT id FROM events WHERE completed_at IS NOT NULL`)).rows;
  assertEquals(completed.map((r) => String(r.id)), [FINISHED]);
  d.close();
});

Deno.test("event phase → a member who has not received everything keeps a finished-looking event", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, "2026-07-13T12:00:00Z"));
  await member(d, E, D, ["a"]);
  await member(d, E, D2, ["b"]);
  await done(d, E, D);
  // D2 settled with its photo landed, but never said what it received.
  await d.execute(`UPDATE memberships SET state = 'settled' WHERE device_id = ?`, [D2]);
  await d.execute(
    `INSERT INTO union_log (event_id, kind, device_id, asset_id) VALUES (?, 'gained', ?, 'a')`,
    [E, D],
  );
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

// @Verifies(spec = "event-lifetime", requirement = "An event's photos are deleted once it is finished, and after 30 days at the latest", scenario = "A late guest keeps the event alive")
Deno.test("event phase → a late JOIN moves the clock: 3 days after the last join", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  // Open until 07-05 → clock 07-08, long past — but a guest joined 07-13 → clock 07-16, after NOW.
  await insertEvent(d, event(E, "2026-07-05T00:00:00Z"));
  await member(d, E, D, []);
  await d.execute(
    `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES (?, ?, 'sharing', ?)`,
    [E, D2, "2026-07-13T00:00:00.000Z"],
  );
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

Deno.test("event phase → an event past its WINDOW but within 3 days of it is untouched", async () => {
  // The open-until date passing changes no lifecycle answer by itself — only the clock, or everyone being done.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-12T00:00:00Z" }));
  await member(d, E, D, []);
  await run(d, fake({}));
  assertEquals(await outcomes(d), [], "kept");
  d.close();
});

// ── BYTE PHASE ─────────────────────────────────────────────────────────────────────────────────────

Deno.test("byte phase → a LIVE event's folder is kept, a dropped one's deleted", async () => {
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  const LIVE = "bbbbbbbb-0000-4000-8000-000000000002";
  await insertEvent(d, event(STALE, STALE_STARTS));
  await insertEvent(d, event(LIVE, LIVE_STARTS));
  const [gone] = await member(d, STALE, D, ["a"]);
  const [kept] = await member(d, LIVE, D, ["b"]);
  const store = fake({ [gone]: { len: 2 }, [kept]: { len: 3 } });
  await run(d, store);
  assertEquals(store.deletes, [`files/${STALE}/`]);
  assert(store.store.has(kept));
  assert(!store.store.has(gone));
  d.close();
});

Deno.test("byte phase → a folder with NO event row is deleted: a crash, or a byte landing after the end", async () => {
  // A run that crashed between the event phase and the folder delete, or a PUT that passed the membership
  // check just before completion and landed after it, leaves a folder whose event no longer lives. Every
  // run deletes every such folder it finds, so the next one heals it.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await d.execute(`UPDATE events SET closed_at = ?, completed_at = ?`, [
    "2026-07-12T00:00:00.000Z",
    "2026-07-12T00:00:00.000Z",
  ]);
  const NO_ROW = "dddddddd-0000-4000-8000-000000000004";
  const store = fake({
    [`files/${E}/late`]: { len: 1 },
    [`files/${NO_ROW}/orphan`]: { len: 1 },
  });
  await run(d, store);
  assertEquals(store.deletes.sort(), [`files/${E}/`, `files/${NO_ROW}/`].sort());
  d.close();
});

Deno.test("byte phase → files/devices/ and anything not named by an event id are never touched", async () => {
  // `files/devices/` holds the bytes written before migration 0010; rows still point into it per event,
  // and it is deleted outright once copied out — never by this sweep.
  const d = await db();
  const store = fake({
    [`files/devices/${D}/a-primary.heic`]: { len: 1 },
    [`files/not-an-event/x`]: { len: 1 },
  });
  await run(d, store);
  assertEquals(store.deletes, []);
  d.close();
});

Deno.test("byte phase → a failed folder delete is counted as an error and the run continues", async () => {
  const d = await db();
  const A = "aaaaaaaa-0000-4000-8000-000000000001";
  const B = "bbbbbbbb-0000-4000-8000-000000000002";
  const store = fake({ [`files/${A}/x`]: { len: 1 }, [`files/${B}/y`]: { len: 1 } });
  const failing: FetchLike = (url, init) =>
    init.method === "DELETE" && url.endsWith(`/files/${A}/`)
      ? Promise.resolve(new Response("boom", { status: 500 }))
      : store.fetchImpl(url, init);
  await runSweep({
    fetch: failing,
    config: CONFIG,
    db: d,
    now: () => NOW,
    dryRun: false,
    log: () => {},
  });
  assert(!store.store.has(`files/${B}/y`), "the other folder still went");
  assert(store.store.has(`files/${A}/x`));
  d.close();
});

Deno.test("dry-run → deletes NOTHING, but says the same candidates a real run would take", async () => {
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  await insertEvent(d, event(STALE, STALE_STARTS));
  const [path] = await member(d, STALE, D, ["a"]);
  const store = fake({ [path]: { len: 6 } });
  const { log } = await run(d, store, true);
  assert(log.some((m) => m.startsWith(`[dry-run] would delete event ${STALE}`)), log.join("\n"));
  assert(log.includes(`[dry-run] would delete files/${STALE}/`), log.join("\n"));
  assertEquals(await outcomes(d), [], "a dry run records nothing");
  // Nothing actually went, on either side.
  assertEquals(store.deletes, []);
  assertEquals(await eventIds(d), [STALE]);
  d.close();
});

Deno.test("an empty store with an EMPTY zone sweeps normally", async () => {
  // An empty world is an ordinary world: nothing referenced, nothing stored, nothing to do.
  const d = await db();
  const store = fake({});
  const { outcome } = await run(d, store);
  assertEquals(outcome, {});
  assertEquals(store.deletes, []);
  assertEquals(await outcomes(d), []);
  d.close();
});

Deno.test("site/ prefix is never touched by the sweep", async () => {
  // The storage zone is a co-tenant: the public `site/` prefix lives beside private user data
  // (`docs/deployment.md`). The sweep enumerates `files/` and deletes only event folders.
  const d = await db();
  const NO_ROW = "dddddddd-0000-4000-8000-000000000004";
  const store = fake({
    "site/index.html": {},
    "site/_astro/app.abc123.js": {},
    [`files/${NO_ROW}/a`]: { len: 1 },
  });
  await run(d, store);
  assert(store.store.has("site/index.html"));
  assert(store.store.has("site/_astro/app.abc123.js"));
  assert(!store.deletes.some((k) => k.startsWith("site/")));
  d.close();
});

// ── DEVICE RECORDS ─────────────────────────────────────────────────────────────────────────────────

Deno.test("devices → a device in NO surviving event, whose token expired, loses its whole record", async () => {
  const d = await db();
  await enrolDevice(d, ORPHAN, DEAD_TOKEN);
  await run(d, fake({}));
  // One row, attestation included — there is no second object beside it any more.
  assertEquals((await d.execute(`SELECT * FROM devices`)).rows.length, 0);
  d.close();
});

Deno.test("devices → an orphan that may still hold a WORKING token keeps its row", async () => {
  // The expiry clause, and it is forcing rather than tidy. A device token is verified from its own
  // signature, so it keeps working whether or not this row survives. Collect the row while the token
  // lives and the device's next config write is refused, and it recovers by minting a fresh
  // Secure-Enclave key and completing a full Apple attestation — the throttled path — which this nightly
  // run then re-arms the following night, once per launch-day, for as long as it stays orphaned.
  const d = await db();
  await enrolDevice(d, ORPHAN, LIVE_TOKEN);
  await run(d, fake({}));
  assertEquals((await d.execute(`SELECT * FROM devices`)).rows.length, 1);
  d.close();
});

Deno.test("devices → a DEPARTED member of a surviving event keeps its record and its photos", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await enrolDevice(d, D, DEAD_TOKEN);
  const [path] = await member(d, E, D, ["a"], "left");
  await member(d, E, D2, []);
  const store = fake({ [path]: { len: 1 } });
  await run(d, store);
  assertEquals(
    (await d.execute(`SELECT device_id FROM devices`)).rows.map((r) => String(r.device_id)),
    [D],
  );
  assert(store.store.has(path));
  d.close();
});

// The browser-facing site (capability `web-site`) lives under a `site/` prefix, co-tenant with the private
// data in the same zone. The sweep is PREFIX-SCOPED — it enumerates only `files/` and deletes only the
// folders named by an event id — so `site/` is invisible to it, and its hygiene is the mirror-deploy's job.
// This pins that load-bearing invariant: a future "simplify the sweep to a whole-zone walk" would delete
// the live site, and this test would catch it.
