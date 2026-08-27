import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  formatSummary,
  humanBytes,
  markdownSummary,
  runSweep,
  type SweepSummary,
} from "../../src/scripts/sweep.ts";
import type { Config } from "../../src/config.ts";
import type { FetchLike } from "../../src/storage.ts";
import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import { type Db, insertEvent, publishStatements } from "../../src/db.ts";
import { migrate } from "../../src/migrations.ts";
import { DEAD_TOKEN, enrolDevice, LIVE_TOKEN } from "../support/db.ts";

// The sweep (capability `scheduled-cleanup`) MARKS FROM THE DATABASE and DELETES FROM STORAGE. These
// tests therefore drive two doubles: a real in-process SQLite for the relational half (so cascades and
// the queries behave as SQL, not as our idea of SQL) and an in-memory object-store fake for the byte
// half. NOW is pinned. The sweep holds only the storage AccessKey and the store's credentials — it makes
// no request to the Edge Script.
const NOW = Date.parse("2026-07-14T12:00:00Z");
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
// staleness NOT AT ALL: it bounds only which captures may be uploaded (capability `event-limits`).
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
 * In-memory bunny native-Storage fake, now covering only what still lives in storage: the photo BYTES
 * and the attestation records. GET a directory LIST of direct children with `LastChanged`; DELETE
 * (idempotent). Every DELETE is recorded in order, so a test can assert both what went and the SEQUENCE.
 */
function fake(initial: Record<string, { lc?: string; len?: number }>) {
  const store = new Map<string, { lc: string; len: number }>();
  for (const [k, v] of Object.entries(initial)) {
    store.set(k, { lc: v.lc ?? "2026-07-01T00:00:00.000Z", len: v.len ?? 1 });
  }
  const deletes: string[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    const key = url.split(`/${ZONE}/`)[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) {
      const children = new Map<string, { name: string; dir: boolean; lc: string; len: number }>();
      let any = false;
      for (const [k, v] of store) {
        if (!k.startsWith(key)) continue;
        any = true;
        const rest = k.slice(key.length);
        const slash = rest.indexOf("/");
        if (slash === -1) children.set(rest, { name: rest, dir: false, lc: v.lc, len: v.len });
        else {
          const d = rest.slice(0, slash);
          if (!children.has(d)) children.set(d, { name: d, dir: true, lc: "", len: 0 });
        }
      }
      if (!any) return Promise.resolve(new Response("nf", { status: 404 }));
      const entries = [...children.values()].map((e) => ({
        ObjectName: e.name,
        IsDirectory: e.dir,
        Length: e.len,
        LastChanged: e.lc,
      }));
      return Promise.resolve(new Response(JSON.stringify(entries), { status: 200 }));
    }
    if (method === "GET") {
      const v = store.get(key);
      return Promise.resolve(
        v ? new Response("", { status: 200 }) : new Response("nf", { status: 404 }),
      );
    }
    if (method === "DELETE") {
      deletes.push(key);
      return Promise.resolve(new Response(null, { status: store.delete(key) ? 200 : 404 }));
    }
    return Promise.resolve(new Response(null, { status: 405 }));
  };
  return { store, deletes, fetchImpl };
}

/** A migrated store. */
async function db(): Promise<Db & { close(): void }> {
  const d = sqliteDb(":memory:");
  await migrate(d);
  return d;
}

/** Enroll `deviceId` in `eventId` and record the resources it shares there, by key. */
async function member(
  d: Db,
  eventId: string,
  deviceId: string,
  keys: string[],
  state: "active" | "departed" = "active",
) {
  await d.execute(
    `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES (?, ?, ?, '2026-07-01T00:00:00Z')`,
    [eventId, deviceId, state],
  );
  if (keys.length === 0) return;
  // One asset per key, its id derived from the filename. Distinct ids matter: `resources` is
  // DEVICE-scoped and joins to `event_assets` by `(device_id, asset_id)`, so two genuinely different
  // photos sharing an id would each pull in the other's resources — which is not a schema flaw but the
  // property that lets one uploaded byte serve two events. A fixture must not fake a collision.
  await d.batch(publishStatements(
    eventId,
    deviceId,
    keys.map((k) => {
      const filename = k.split("/").pop()!;
      return {
        assetId: filename.replace(/\.[^.]+$/, ""),
        creationDate: "2026-07-01T00:00:00Z",
        // `key` is the BARE stored object name; the full path is the storage fake's business.
        resources: [{ role: "primary", contentType: "image/heic", key: filename, filename }],
      };
    }),
    // The fixture needs the resource rows too, which only the legacy (v1) publish writes — under v2 the
    // byte upload is the sole writer of that table. `legacy: true` keeps this a one-call fixture.
    { legacy: true },
  ));
  // The legacy publish re-activates the membership, which a departed fixture must not be.
  if (state === "departed") {
    await d.execute(
      `UPDATE memberships SET state = 'departed' WHERE event_id = ? AND device_id = ?`,
      [
        eventId,
        deviceId,
      ],
    );
  }
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
  assertEquals(summary.events, { deleted: 1, kept: 1 });
  assertEquals(await eventIds(d), [LIVE]);
  d.close();
});

Deno.test("event phase → deleting an event CASCADES to its memberships and assets", async () => {
  // The invariant the object store could not express, and the reason two staleness classes are gone.
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  await insertEvent(d, event(STALE, STALE_STARTS));
  await member(d, STALE, D, [`files/devices/${D}/a.heic`]);
  assertEquals((await d.execute(`SELECT * FROM event_assets`)).rows.length, 1);

  await run(d, fake({}));
  assertEquals((await d.execute(`SELECT * FROM memberships`)).rows.length, 0);
  assertEquals((await d.execute(`SELECT * FROM event_assets`)).rows.length, 0);
  // The device-scoped resource row is NOT under the cascade — its bytes may still serve another event.
  assertEquals((await d.execute(`SELECT * FROM resources`)).rows.length, 1);
  d.close();
});

Deno.test("event phase → an EMPTIED event is deleted early, before its deadline", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [], "departed");
  await member(d, E, D2, [], "departed");
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 1, kept: 0 });
  assertEquals(await eventIds(d), []);
  d.close();
});

Deno.test("event phase → ONE active member keeps a within-deadline event alive", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [], "departed");
  await member(d, E, D2, []);
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, kept: 1 });
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
  assertEquals(summary.events, { deleted: 0, kept: 1 });
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

Deno.test("event phase → an event past its WINDOW but within its lifetime is untouched", async () => {
  // `endsAt` bounds uploads and closes nothing — the window passing must change no lifecycle answer.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS, { endsAt: "2026-07-10T00:00:00Z" }));
  await member(d, E, D, []);
  const { summary } = await run(d, fake({}));
  assertEquals(summary.events, { deleted: 0, kept: 1 });
  d.close();
});

// ── ASSET PHASE ────────────────────────────────────────────────────────────────────────────────────

Deno.test("asset phase → referenced kept; unreferenced-below-floor collected; above-floor kept", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [`files/devices/${D}/kept.heic`]);
  const store = fake({
    [`files/devices/${D}/kept.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 10 },
    // Unreferenced and uploaded BEFORE the floor (2026-07-01) → collected.
    [`files/devices/${D}/old.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 20 },
    // Unreferenced but uploaded AFTER the floor → a live upload, retained.
    [`files/devices/${D}/new.heic`]: { lc: "2026-07-05T00:00:00.000Z", len: 40 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.files.deleted, { count: 1, bytes: 20 });
  assertEquals(summary.files.kept, { count: 2, bytes: 50 });
  assert(!store.store.has(`files/devices/${D}/old.heic`));
  assert(store.store.has(`files/devices/${D}/kept.heic`));
  d.close();
});

Deno.test("asset phase → a collected byte's ROW is deleted BEFORE the byte", async () => {
  // The order is load-bearing. Row-then-byte leaves, on a crash, an orphan byte that is still
  // unreferenced and still below the floor — so the next run collects it. Byte-then-row would leave a row
  // still asserting, by its existence, that bytes are stored which are gone — silently suppressing a
  // needed re-upload the moment anything reads that row for dedup.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, []); // active, so the device has a floor, but references nothing
  await d.execute(
    `INSERT INTO resources (device_id, asset_id, role, key, content_type, filename)
     VALUES (?, 'A', 'primary', 'old.heic', 'image/heic', 'Capture old.heic')`,
    [D],
  );
  const order: string[] = [];
  const store = fake({
    [`files/devices/${D}/old.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 5 },
  });
  const observing: Db = {
    execute: (sql, args) => {
      if (sql.startsWith("DELETE FROM resources")) order.push("row");
      return d.execute(sql, args);
    },
    batch: (st) => d.batch(st),
    transaction: (fn) => d.transaction(fn),
  };
  await runSweep({
    fetch: (url, init) => {
      if ((init.method ?? "GET") === "DELETE" && !url.endsWith("/")) order.push("byte");
      return store.fetchImpl(url, init);
    },
    config: CONFIG,
    db: observing,
    now: () => NOW,
    dryRun: false,
    log: () => {},
  });
  assertEquals(order, ["row", "byte"]);
  assertEquals((await d.execute(`SELECT * FROM resources`)).rows.length, 0);
  d.close();
});

Deno.test("asset phase → a device in NO surviving event loses its bytes and its whole record", async () => {
  const d = await db();
  await enrolDevice(d, ORPHAN, DEAD_TOKEN);
  const store = fake({
    [`files/devices/${ORPHAN}/a.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 7 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.devices, { deleted: 1, kept: 0 });
  assertEquals(summary.files.deleted.count, 1);
  // One row, attestation included — there is no second object beside it any more.
  assertEquals((await d.execute(`SELECT * FROM devices`)).rows.length, 0);
  d.close();
});

Deno.test("asset phase → an orphan that may still hold a WORKING token keeps its row", async () => {
  // The expiry clause, and it is forcing rather than tidy. A device token is verified from its own
  // signature, so it keeps working whether or not this row survives. Collect the row while the token
  // lives and the device's next config write is refused, and it recovers by minting a fresh
  // Secure-Enclave key and completing a full Apple attestation — the throttled path — which this nightly
  // run then re-arms the following night, once per launch-day, for as long as it stays orphaned.
  const d = await db();
  await enrolDevice(d, ORPHAN, LIVE_TOKEN);
  const store = fake({
    [`files/devices/${ORPHAN}/a.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 7 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.devices, { deleted: 0, kept: 1 });
  // Its BYTES are still collected — that rule keys on membership alone and is unchanged.
  assertEquals(summary.files.deleted.count, 1);
  assertEquals((await d.execute(`SELECT * FROM devices`)).rows.length, 1);
  d.close();
});

Deno.test("asset phase → a DEPARTED member of a surviving event keeps its bytes and its record", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [`files/devices/${D}/a.heic`], "departed");
  // A second, ACTIVE member: an event whose every member has departed is EMPTY and would be reclaimed,
  // taking the departed member's bytes with it — which is a different rule than the one under test.
  await member(d, E, D2, []);
  await enrolDevice(d, D, DEAD_TOKEN);
  const store = fake({ [`files/devices/${D}/a.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 3 } });
  const { summary } = await run(d, store);
  assertEquals(summary.devices, { deleted: 0, kept: 1 });
  assertEquals(summary.files.kept.count, 1);
  assert(store.store.has(`files/devices/${D}/a.heic`));
  d.close();
});

Deno.test("switch → leftovers from a swept prior event are collected while the device stays active in a newer one", async () => {
  const d = await db();
  const OLD = "aaaaaaaa-0000-4000-8000-000000000001";
  const NEW = "bbbbbbbb-0000-4000-8000-000000000002";
  await insertEvent(d, event(OLD, STALE_STARTS)); // past its deadline → swept
  await insertEvent(d, event(NEW, LIVE_STARTS));
  await member(d, OLD, D, [`files/devices/${D}/old.heic`]);
  await member(d, NEW, D, [`files/devices/${D}/new.heic`]);
  const store = fake({
    [`files/devices/${D}/old.heic`]: { lc: "2026-06-15T00:00:00.000Z", len: 9 },
    [`files/devices/${D}/new.heic`]: { lc: "2026-07-05T00:00:00.000Z", len: 9 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.files.deleted.count, 1);
  assert(!store.store.has(`files/devices/${D}/old.heic`));
  assert(store.store.has(`files/devices/${D}/new.heic`));
  d.close();
});

Deno.test("asset phase → a referenced byte with a percent-encoded filename is matched and kept", async () => {
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [`files/devices/${D}/a b.heic`]);
  const store = fake({
    [`files/devices/${D}/a%20b.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 4 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.files.kept.count, 1);
  assertEquals(summary.files.deleted.count, 0);
  d.close();
});

Deno.test("dry-run → deletes NOTHING, but counts the same candidates a real run would", async () => {
  const d = await db();
  const STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  await insertEvent(d, event(STALE, STALE_STARTS));
  await member(d, STALE, D, [`files/devices/${D}/a.heic`]);
  const store = fake({ [`files/devices/${D}/a.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 6 } });
  const { summary } = await run(d, store, true);
  assertEquals(summary.dryRun, true);
  assertEquals(summary.events.deleted, 1);
  assertEquals(summary.files.deleted, { count: 1, bytes: 6 });
  // Nothing actually went, on either side.
  assertEquals(store.deletes, []);
  assertEquals(await eventIds(d), [STALE]);
  d.close();
});

Deno.test("summary → file bytes are the SUM of each entry's Length, not the object count", async () => {
  const d = await db();
  await enrolDevice(d, ORPHAN, DEAD_TOKEN);
  const store = fake({
    [`files/devices/${ORPHAN}/a.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 1500 },
    [`files/devices/${ORPHAN}/b.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 2500 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.files.deleted, { count: 2, bytes: 4000 });
  d.close();
});

Deno.test("an empty store with an EMPTY zone sweeps normally", async () => {
  // An empty world is an ordinary world: nothing referenced, nothing stored, nothing to do. (There is no
  // longer a refusal for the empty-store-with-bytes case — see this change's design.md D9.)
  const d = await db();
  const { summary } = await run(d, fake({}));
  assertEquals(summary.errors, 0);
  assertEquals(summary.files.deleted.count, 0);
  d.close();
});

Deno.test("a POPULATED store still collects an orphaned device's bytes", async () => {
  // The byte rule keys on membership and the retention floor, independently of whether the device's own
  // row survives — an orphan with a live token keeps its row and still loses its bytes.
  const d = await db();
  const E = "cccccccc-0000-4000-8000-000000000003";
  await insertEvent(d, event(E, LIVE_STARTS));
  await member(d, E, D, [`files/devices/${D}/keep.heic`]);
  const store = fake({
    [`files/devices/${D}/keep.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 1 },
    [`files/devices/${ORPHAN}/gone.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 1 },
  });
  const { summary } = await run(d, store);
  assertEquals(summary.files.deleted.count, 1);
  assert(!store.store.has(`files/devices/${ORPHAN}/gone.heic`));
  assert(store.store.has(`files/devices/${D}/keep.heic`));
  d.close();
});

Deno.test("site/ prefix is never touched by the sweep", async () => {
  // The storage zone is a co-tenant: the public `site/` prefix lives beside private user data
  // (capability `backend-deployment`). The sweep enumerates `files/devices/` and nothing else.
  const d = await db();
  await enrolDevice(d, ORPHAN, DEAD_TOKEN);
  const store = fake({
    "site/index.html": {},
    "site/_astro/app.abc123.js": {},
    [`files/devices/${ORPHAN}/a.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 1 },
  });
  await run(d, store);
  assert(store.store.has("site/index.html"));
  assert(store.store.has("site/_astro/app.abc123.js"));
  assert(!store.deletes.some((k) => k.startsWith("site/")));
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
    events: { deleted: 40, kept: 1 },
    devices: { deleted: 20, kept: 2 },
    files: { deleted: { count: 107, bytes: 12_900_000 }, kept: { count: 10, bytes: 3_100_000 } },
    errors: 0,
    dryRun: true,
  };
  const out = formatSummary(s);
  assertStringIncludes(out, "sweep summary (dry-run):");
  assertStringIncludes(out, "events    40 deleted   1 kept");
  assertStringIncludes(out, "devices   20 deleted   2 kept");
  assertStringIncludes(out, "files     107 (12.3 MB) deleted   10 (3.0 MB) kept");
  assertStringIncludes(out, "errors    0");
  // A real (non-dry) run drops the suffix.
  assertStringIncludes(formatSummary({ ...s, dryRun: false }), "sweep summary:");
});

Deno.test("markdownSummary → a GFM table with a row per tier and an errors line", () => {
  const s: SweepSummary = {
    events: { deleted: 40, kept: 1 },
    devices: { deleted: 20, kept: 2 },
    files: { deleted: { count: 107, bytes: 12_900_000 }, kept: { count: 10, bytes: 3_100_000 } },
    errors: 3,
    dryRun: false,
  };
  const md = markdownSummary(s);
  assertStringIncludes(md, "## Nightly cleanup sweep");
  assertStringIncludes(md, "| tier | deleted | kept |");
  assertStringIncludes(md, "| events | 40 | 1 |");
  assertStringIncludes(md, "| devices | 20 | 2 |");
  assertStringIncludes(md, "| files | 107 (12.3 MB) | 10 (3.0 MB) |");
  assertStringIncludes(md, "**errors:** 3");
  // Dry-run is flagged in the heading.
  assertStringIncludes(markdownSummary({ ...s, dryRun: true }), "(dry-run — nothing deleted)");
});

// The browser-facing site (capability `web-site`) lives under a `site/` prefix, co-tenant with the private
// data in the same zone. The sweep is PREFIX-SCOPED — it enumerates only `events/`, `files/devices/` and
// `devices/` — so `site/` is invisible to it, and its hygiene is the mirror-deploy's job, not the sweep's.
// This pins that load-bearing invariant: a future "simplify the sweep to a whole-zone walk" would delete
// the live site, and this test would catch it.
