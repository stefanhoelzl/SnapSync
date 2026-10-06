import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  formatSummary,
  humanBytes,
  markdownSummary,
  runSweep,
  type SweepSummary,
  versionTable,
} from "../../src/scripts/sweep.ts";
import type { Config } from "../../src/config.ts";
import type { FetchLike } from "../../src/storage.ts";
import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { type Db, insertEvent, publishStatements, recordAppVersion } from "../../src/db.ts";
import { eventBytePath } from "../../src/storage.ts";
import { replay } from "../../src/dev/replay.ts";
import { DEAD_TOKEN, enrolDevice, LIVE_TOKEN } from "../support/db.ts";
import { NOW } from "../support/harness.ts";

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

// Staleness is decided by the DERIVED delete-by, `max(createdAt, startsAt) + lifetimeSeconds`. With
// `createdAt` pinned at 2026-06-01, a `startsAt` of 2026-06-10 lands the deadline on 2026-07-10 (before
// NOW → STALE) and one of 2026-07-01 lands it on 2026-07-31 (after NOW → LIVE). `endsAt` participates in
// staleness NOT AT ALL: it bounds only which captures may be uploaded (capability `event-lifetime`).
const STALE_STARTS = "2026-06-10T00:00:00Z";
const LIVE_STARTS = "2026-07-01T00:00:00Z";

function event(eventId: string, startsAt: string, overrides: Record<string, unknown> = {}) {
  return {
    eventId,
    name: "e",
    createdAt: "2026-06-01T00:00:00.000Z",
    startsAt,
    endsAt: "2026-08-03T00:00:00Z",
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
    // The fixture needs the resource rows too, which only the legacy (v1) publish writes — under v2 the
    // byte upload is the sole writer of that table. `legacy: true` keeps this a one-call fixture.
    { legacy: true, paths },
  ));
  // The legacy publish re-activates the membership, which a departed fixture must not be.
  if (state === "left") {
    await d.execute(
      `UPDATE memberships SET state = 'left' WHERE event_id = ? AND device_id = ?`,
      [eventId, deviceId],
    );
  }
  return [...paths.values()];
}

/** A sweep run against both doubles, pinned clock. */
function run(d: Db, store: ReturnType<typeof fake>, dryRun = false) {
  return runSweep({
    fetch: store.fetchImpl,
    config: CONFIG,
    db: d,
    now: () => NOW,
    dryRun,
    log: () => {},
  }).then((summary) => ({ summary }));
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

  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 1, completed: 0, kept: 1 });
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
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 1, kept: 0 });
  // The row stays until its deadline so a device still joined is told "completed", not "not found".
  assertEquals(await eventIds(d), [E]);
  const row = (await d.execute(`SELECT closed_at, completed_at FROM events`)).rows[0];
  assertEquals(row.completed_at, new Date(NOW).toISOString());
  assertEquals(row.closed_at, new Date(NOW).toISOString());
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
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 1, completed: 0, kept: 1 });
  assertEquals(await eventIds(d), [DONE]);
  d.close();
});

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
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 1, kept: 1 });
  const completed = (await d.execute(`SELECT id FROM events WHERE completed_at IS NOT NULL`)).rows;
  assertEquals(completed.map((r) => String(r.id)), [DUE]);
  d.close();
});

Deno.test("event phase → a late LANDING moves the clock: 3 days after the last arrival, not the end", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-05T00:00:00Z" }));
  await d.execute(`UPDATE events SET last_landed_at = ?`, ["2026-07-12T00:00:00.000Z"]);
  await member(d, E, D, []);
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 0, kept: 1 });
  d.close();
});

Deno.test("event phase → the clock never applies to a NEVER-JOINED event", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-02T00:00:00Z" }));
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 0, kept: 1 });
  d.close();
});

Deno.test("byte phase → a COMPLETED event's folder is deleted like a deleted event's", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  const [path] = await member(d, E, D, ["a"], "left");
  const store = fake({ [path]: { len: 4 } });
  const { summary } = await run(d, store);
  assertEquals(summary.events.completed, 1);
  assertEquals(summary.files.deleted, { count: 1, bytes: 4 });
  assertEquals(summary.dirs, { deleted: 1, kept: 0 });
  // ONE recursive delete of the event's folder.
  assertEquals(store.deletes, [`files/${E}/`]);
  d.close();
});

Deno.test("dry-run → a completion is counted and nothing is written", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  const [path] = await member(d, E, D, ["a"], "left");
  const store = fake({ [path]: { len: 4 } });
  const { summary } = await run(d, store, true);
  assertEquals(summary.events.completed, 1);
  assertEquals(summary.files.deleted, { count: 1, bytes: 4 });
  assertEquals(store.deletes, []);
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
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 0, kept: 1 });
  d.close();
});

Deno.test("event phase → a MINTED-BUT-NEVER-JOINED event is not empty and survives", async () => {
  // Every fresh event is in this state: `POST /events` always produces a zero-device event, because the
  // creator confirms through the same join gate a scanned QR uses. Reaping it would delete a mint before
  // the host confirms.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 0, kept: 1 });
  d.close();
});

Deno.test("event phase → the deadline anchors at max(createdAt, startsAt), both directions", async () => {
  const d = await db();
  // Back-dated: startsAt long before createdAt → anchored at createdAt (2026-06-01) + 1 day → STALE.
  const BACK = "dddddddd-0000-4000-8000-000000000004";
  await insertEvent(d, event(BACK, "2026-01-01T00:00:00Z", { lifetimeSeconds: 24 * 60 * 60 }));
  // Created early: startsAt weeks after createdAt → anchored at startsAt (2026-07-13) + 1 day → LIVE.
  const EARLY = "eeeeeeee-0000-4000-8000-000000000005";
  await insertEvent(d, event(EARLY, "2026-07-13T18:00:00Z", { lifetimeSeconds: 24 * 60 * 60 }));
  await run(d, fake({}));
  assertEquals(await eventIds(d), [EARLY]);
  d.close();
});

Deno.test("event phase → an event past its WINDOW but within 3 days of it is untouched", async () => {
  // `endsAt` alone closes nothing — the window passing changes no lifecycle answer until the clock.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-12T00:00:00Z" }));
  await member(d, E, D, []);
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, completed: 0, kept: 1 });
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
  const { summary } = await run(d, store);
  assertEquals(store.deletes, [`files/${STALE}/`]);
  assert(store.store.has(kept));
  assertEquals(summary.files, { deleted: { count: 1, bytes: 2 }, kept: { count: 1, bytes: 3 } });
  assertEquals(summary.dirs, { deleted: 1, kept: 1 });
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
  const { summary } = await run(d, store);
  assertEquals(store.deletes.sort(), [`files/${E}/`, `files/${NO_ROW}/`].sort());
  assertEquals(summary.dirs.deleted, 2);
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
  const { summary } = await run(d, store);
  assertEquals(store.deletes, []);
  assertEquals(summary.dirs, { deleted: 0, kept: 0 });
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
  const { summary } = await runSweep({
    fetch: failing,
    config: CONFIG,
    db: d,
    now: () => NOW,
    dryRun: false,
    log: () => {},
  }).then((summary) => ({ summary }));
  assertEquals(summary.errors, 1);
  assert(!store.store.has(`files/${B}/y`), "the other folder still went");
  assert(store.store.has(`files/${A}/x`));
  d.close();
});

Deno.test("dry-run → deletes NOTHING, but counts the same candidates a real run would", async () => {
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  await insertEvent(d, event(STALE, STALE_STARTS));
  const [path] = await member(d, STALE, D, ["a"]);
  const store = fake({ [path]: { len: 6 } });
  const { summary } = await run(d, store, true);
  assertEquals(summary.dryRun, true);
  assertEquals(summary.events.deleted, 1);
  assertEquals(summary.files.deleted, { count: 1, bytes: 6 });
  assertEquals(summary.dirs.deleted, 1);
  // Nothing actually went, on either side.
  assertEquals(store.deletes, []);
  assertEquals(await eventIds(d), [STALE]);
  d.close();
});

Deno.test("summary → file bytes are the SUM of each entry's Length, not the object count", async () => {
  const d = await db();
  const NO_ROW = "dddddddd-0000-4000-8000-000000000004";
  const store = fake({
    [`files/${NO_ROW}/a`]: { len: 1500 },
    [`files/${NO_ROW}/b`]: { len: 2500 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.files.deleted, { count: 2, bytes: 4000 });
  d.close();
});

Deno.test("an empty store with an EMPTY zone sweeps normally", async () => {
  // An empty world is an ordinary world: nothing referenced, nothing stored, nothing to do.
  const d = await db();
  const { summary } = await run(d, fake({}));
  assertEquals(summary.errors, 0);
  assertEquals(summary.files.deleted.count, 0);
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
  const { summary } = await run(d, fake({}));
  assertEquals(summary.devices, { deleted: 1, kept: 0 });
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
  const { summary } = await run(d, fake({}));
  assertEquals(summary.devices, { deleted: 0, kept: 1 });
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
  const { summary } = await run(d, store);
  assertEquals(summary.devices.deleted, 0);
  assert(store.store.has(path));
  d.close();
});

Deno.test("humanBytes → renders IEC-ish sizes; < 1024 stays bytes", () => {
  assertEquals(humanBytes(0), "0 B");
  assertEquals(humanBytes(512), "512 B");
  assertEquals(humanBytes(1024), "1.0 KB");
  assertEquals(humanBytes(1536), "1.5 KB");
  assertEquals(humanBytes(5 * 1024 * 1024), "5.0 MB");
  assertEquals(humanBytes(3 * 1024 * 1024 * 1024), "3.0 GB");
});

Deno.test("formatSummary → one line per tier, files show count and reclaimed size, dry-run flagged", () => {
  const s: SweepSummary = {
    events: { deleted: 40, completed: 5, kept: 1 },
    devices: { deleted: 20, kept: 2 },
    versions: [{ version: "0.12", ios: 31, android: 4 }, { version: null, ios: 9, android: 0 }],
    files: { deleted: { count: 107, bytes: 12_900_000 }, kept: { count: 10, bytes: 3_100_000 } },
    dirs: { deleted: 15, kept: 7 },
    errors: 0,
    dryRun: true,
  };
  const out = formatSummary(s);
  assertStringIncludes(out, "sweep summary (dry-run):");
  assertStringIncludes(out, "events    40 deleted   5 completed   1 kept");
  assertStringIncludes(out, "devices   20 deleted   2 kept");
  assertStringIncludes(out, "files     107 (12.3 MB) deleted   10 (3.0 MB) kept");
  assertStringIncludes(out, "dirs      15 deleted   7 kept");
  assertStringIncludes(out, "errors    0");
  assertStringIncludes(out, "    version      ios  android");
  assertStringIncludes(out, "    0.12          31        4");
  assertStringIncludes(out, "    unknown        9        0");
  assertStringIncludes(out, "    total         40        4");
  // A real (non-dry) run drops the suffix.
  assertStringIncludes(formatSummary({ ...s, dryRun: false }), "sweep summary:");
});

Deno.test("markdownSummary → a GFM table with a row per tier and an errors line", () => {
  const s: SweepSummary = {
    events: { deleted: 40, completed: 5, kept: 1 },
    devices: { deleted: 20, kept: 2 },
    versions: [{ version: "0.12", ios: 31, android: 4 }, { version: null, ios: 9, android: 0 }],
    files: { deleted: { count: 107, bytes: 12_900_000 }, kept: { count: 10, bytes: 3_100_000 } },
    dirs: { deleted: 15, kept: 7 },
    errors: 3,
    dryRun: false,
  };
  const md = markdownSummary(s);
  assertStringIncludes(md, "## Nightly cleanup sweep");
  assertStringIncludes(md, "| tier | deleted | completed | kept |");
  assertStringIncludes(md, "| events | 40 | 5 | 1 |");
  assertStringIncludes(md, "| devices | 20 | — | 2 |");
  assertStringIncludes(md, "| files | 107 (12.3 MB) | — | 10 (3.0 MB) |");
  assertStringIncludes(md, "| dirs | 15 | — | 7 |");
  assertStringIncludes(md, "**errors:** 3");
  assertStringIncludes(md, "| version | ios | android |");
  assertStringIncludes(md, "| 0.12 | 31 | 4 |");
  assertStringIncludes(md, "| unknown | 9 | 0 |");
  assertStringIncludes(md, "| **total** | **40** | **4** |");
  // Dry-run is flagged in the heading.
  assertStringIncludes(markdownSummary({ ...s, dryRun: true }), "(dry-run — nothing deleted)");
});

Deno.test("versionTable → newest version first by number, unknown last, counted per platform", () => {
  assertEquals(
    versionTable([
      { appVersion: "0.9", platform: "ios" },
      { appVersion: null, platform: "ios" },
      { appVersion: "0.10", platform: "android" },
      { appVersion: "0.10", platform: "ios" },
      { appVersion: "0.9", platform: "ios" },
    ]),
    [
      // 0.10 above 0.9: a string sort would put it below.
      { version: "0.10", ios: 1, android: 1 },
      { version: "0.9", ios: 2, android: 0 },
      { version: null, ios: 1, android: 0 },
    ],
  );
  assertEquals(versionTable([]), []);
});

for (const dryRun of [false, true]) {
  Deno.test(`runSweep → the version table counts only the devices it keeps (dryRun=${dryRun})`, async () => {
    const d = await db();
    await enrolDevice(d, D, LIVE_TOKEN);
    await recordAppVersion(d, D, "0.12");
    await enrolDevice(d, D2, LIVE_TOKEN);
    // A device the sweep collects: absent from the table in a real run and a dry one alike.
    await enrolDevice(d, ORPHAN, DEAD_TOKEN);
    await recordAppVersion(d, ORPHAN, "0.11");
    const { summary } = await run(d, fake({}), dryRun);
    assertEquals(summary.devices, { deleted: 1, kept: 2 });
    assertEquals(summary.versions, [
      { version: "0.12", ios: 1, android: 0 },
      { version: null, ios: 1, android: 0 },
    ]);
    d.close();
  });
}

// The browser-facing site (capability `web-site`) lives under a `site/` prefix, co-tenant with the private
// data in the same zone. The sweep is PREFIX-SCOPED — it enumerates only `files/` and deletes only the
// folders named by an event id — so `site/` is invisible to it, and its hygiene is the mirror-deploy's job.
// This pins that load-bearing invariant: a future "simplify the sweep to a whole-zone walk" would delete
// the live site, and this test would catch it.
