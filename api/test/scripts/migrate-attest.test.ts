// The one-time attestation migration. It runs ONCE against a live store holding real users' data and then
// never again, which is exactly why it is tested here rather than trusted: there is no second run to catch
// what the first one got wrong, and the objects it reads are deleted at the end.
//
// The two doubles are the sweep's: a real in-process SQLite for the relational half and an in-memory
// object store for the byte half. Unlike the sweep's fake, this one serves BODIES — the whole point is
// what the objects contain.

import { assert, assertEquals } from "@std/assert";
import { runAttestMigration } from "../../src/scripts/migrate-attest.ts";
import type { Config } from "../../src/config.ts";
import type { FetchLike } from "../../src/storage.ts";
import { sqliteDb } from "../../src/dev/db-sqlite.ts";
import type { Db } from "../../src/db.ts";
import { MIGRATIONS } from "../../src/migrations.ts";

const NOW = Date.parse("2026-07-14T12:00:00Z");
const TTL = 30 * 24 * 60 * 60;
const ZONE = "test-zone";
const CONFIG = {
  zone: ZONE,
  host: "storage.invalid",
  accessKey: "k",
  attestTokenTtlSeconds: TTL,
} as unknown as Config;

const D1 = "11111111-0000-4000-8000-000000000001";
const D2 = "22222222-0000-4000-8000-000000000002";
const D3 = "33333333-0000-4000-8000-000000000003";

const attestObject = (publicKey: string, attestedAt = "2026-06-01T00:00:00.000Z") =>
  JSON.stringify({ publicKey, environment: "production", attestedAt });

/** An object store that serves bodies and records its deletes. */
function fake(initial: Record<string, string>) {
  const store = new Map(Object.entries(initial));
  const deletes: string[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    const key = url.split(`/${ZONE}/`)[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) {
      const entries = [...store.keys()]
        .filter((k) => k.startsWith(key) && !k.slice(key.length).includes("/"))
        .map((k) => ({
          ObjectName: k.slice(key.length),
          IsDirectory: false,
          Length: store.get(k)!.length,
          LastChanged: "2026-06-01T00:00:00.000Z",
        }));
      return Promise.resolve(new Response(JSON.stringify(entries), { status: 200 }));
    }
    if (method === "GET") {
      const v = store.get(key);
      return Promise.resolve(
        v === undefined ? new Response("nf", { status: 404 }) : new Response(v, { status: 200 }),
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

/** A store at the DEPLOYED store's exact starting position: v1's tables, no version record. */
async function legacyStore(): Promise<Db & { close(): void }> {
  const db = sqliteDb(":memory:");
  for (const sql of MIGRATIONS.find((m) => m.version === 1)!.statements) await db.execute(sql);
  return db;
}

const legacyRow = (db: Db, deviceId: string, token: string | null) =>
  db.execute(
    `INSERT INTO device_records (device_id, push_kind, push_token, push_env, updated_at)
     VALUES (?, ?, ?, ?, '2026-07-01T00:00:00Z')`,
    [deviceId, token === null ? null : "apns", token, token === null ? null : "sandbox"],
  );

const run = (db: Db, store: ReturnType<typeof fake>, dryRun = false) =>
  runAttestMigration({
    fetch: store.fetchImpl,
    config: CONFIG,
    db,
    now: () => NOW,
    dryRun,
    log: () => {},
  });

const rows = async (db: Db, sql: string, args: unknown[] = []) =>
  (await db.execute(sql, args)).rows;

Deno.test("carries every attestation into a row, with its push registration", async () => {
  const db = await legacyStore();
  await legacyRow(db, D1, "tok-1");
  const store = fake({
    [`devices/${D1}.attest.json`]: attestObject("KEY1"),
    [`devices/${D1}.json`]: `{"pushToken":{}}`, // the LEGACY CONFIG object — must survive
  });

  const summary = await run(db, store);

  assertEquals(summary.rowsWritten, 1);
  assertEquals(summary.pushCarried, 1);
  const [row] = await rows(db, `SELECT * FROM devices WHERE device_id = ?`, [D1]);
  assertEquals(row.attest_key, "KEY1");
  assertEquals(row.attest_env, "production");
  assertEquals(row.created_at, "2026-06-01T00:00:00.000Z"); // first attested, from the object
  assertEquals(row.push_token, "tok-1");
  db.close();
});

Deno.test("the seeded expiry is an UPPER BOUND on any token that could still be outstanding", async () => {
  // A token minted at any instant T ≤ now expires at T + ttl ≤ now + ttl. Seeding `now + ttl` therefore
  // cannot let the sweep collect a device while it may still hold a working credential — which is the one
  // property the sweep depends on. The object records no minted expiry to recover.
  const db = await legacyStore();
  const store = fake({ [`devices/${D1}.attest.json`]: attestObject("KEY1") });
  await run(db, store);
  const [row] = await rows(db, `SELECT attest_token_expires_at FROM devices`);
  assertEquals(row.attest_token_expires_at, new Date(NOW + TTL * 1000).toISOString());
  db.close();
});

Deno.test("a device with an attestation but no legacy row still gets one", async () => {
  // The leak this change closes: such a device had an object and no row, so no sweep could ever see it.
  const db = await legacyStore();
  const store = fake({ [`devices/${D3}.attest.json`]: attestObject("KEY3") });

  const summary = await run(db, store);

  assertEquals(summary.rowsWritten, 1);
  const [row] = await rows(db, `SELECT * FROM devices WHERE device_id = ?`, [D3]);
  assertEquals(row.attest_key, "KEY3");
  assertEquals(row.push_token, null);
  db.close();
});

Deno.test("a legacy row with no attestation object is dropped, and counted", async () => {
  const db = await legacyStore();
  await legacyRow(db, D2, "tok-2"); // no object for D2
  const store = fake({ [`devices/${D1}.attest.json`]: attestObject("KEY1") });
  await legacyRow(db, D1, "tok-1");

  const summary = await run(db, store);

  assertEquals(summary.rowsDropped, 1);
  assertEquals((await rows(db, `SELECT device_id FROM devices`)).map((r) => r.device_id), [D1]);
  db.close();
});

Deno.test("only .attest.json objects are deleted — never the legacy config objects beside them", async () => {
  // The `devices/` prefix is shared, and `deleteObject` deletes a DIRECTORY recursively. An over-broad
  // match here would take the config objects with it, and a prefix or trailing slash would take the lot.
  const db = await legacyStore();
  const store = fake({
    [`devices/${D1}.attest.json`]: attestObject("KEY1"),
    [`devices/${D1}.json`]: `{"pushToken":{}}`,
    [`devices/${D2}.json`]: `{"pushToken":{}}`,
  });

  const summary = await run(db, store);

  assertEquals(summary.objectsDeleted, 1);
  assertEquals(store.deletes, [`devices/${D1}.attest.json`]);
  assert(store.store.has(`devices/${D1}.json`));
  assert(store.store.has(`devices/${D2}.json`));
  db.close();
});

Deno.test("a dry run writes nothing, deletes nothing, and leaves the schema alone", async () => {
  const db = await legacyStore();
  await legacyRow(db, D1, "tok-1");
  const store = fake({ [`devices/${D1}.attest.json`]: attestObject("KEY1") });

  const summary = await run(db, store, true);

  assertEquals(summary.objectsFound, 1);
  assertEquals(summary.rowsWritten, 0);
  assertEquals(store.deletes, []);
  // The schema is untouched: v2 has not been applied, so the legacy table is still there.
  assertEquals(
    (await rows(db, `SELECT name FROM sqlite_master WHERE name = 'device_records'`)).length,
    1,
  );
  db.close();
});

Deno.test("re-running finishes a half-done run rather than doubling it", async () => {
  const db = await legacyStore();
  await legacyRow(db, D1, "tok-1");
  const store = fake({ [`devices/${D1}.attest.json`]: attestObject("KEY1") });

  await run(db, store);
  const second = await run(db, store); // objects gone, v2 recorded, table already renamed

  assertEquals(second.errors, 0);
  assertEquals(second.objectsFound, 0);
  assertEquals((await rows(db, `SELECT * FROM devices`)).length, 1);
  db.close();
});

Deno.test("an unreadable object is counted and does not abort the run", async () => {
  const db = await legacyStore();
  const store = fake({
    [`devices/${D1}.attest.json`]: "not json",
    [`devices/${D2}.attest.json`]: attestObject("KEY2"),
  });

  const summary = await run(db, store);

  assertEquals(summary.errors, 1);
  assertEquals(summary.rowsWritten, 1);
  assertEquals((await rows(db, `SELECT device_id FROM devices`)).map((r) => r.device_id), [D2]);
  db.close();
});
