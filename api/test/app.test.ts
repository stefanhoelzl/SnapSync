// What belongs to no API version: the maintenance window, the root-mounted health route, the pure
// lifecycle derivation, and the `devices` table's two independently-written column groups.
//
// Every version-scoped route lives in that version's own file (`v1.test.ts`, `v2.test.ts`). Nothing here
// may reference a `/api/vN` path except where the subject IS the prefix — the maintenance gate matches
// `/api/*` deliberately, so its tests name versions to prove the prefix covers an unshipped one.

import { assert, assertEquals } from "@std/assert";
import { deleteByMs } from "../src/lifecycle.ts";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import type { Deps, FetchLike } from "../src/app.ts";
import {
  type Db,
  putAttestation,
  putDeviceRecord,
  readAttestation,
  readDeviceRecord,
  touchTokenExpiry,
} from "../src/db.ts";
import { MIGRATIONS } from "../src/migrations.ts";
import {
  CONFIG,
  createApp,
  createRealApp,
  D,
  E,
  NOW,
  recorder,
  rows,
  store,
  TOKEN,
  ZONE,
} from "./support/harness.ts";

// Two VERSIONED paths, named here on purpose. The maintenance gate's subject IS the `/api/*` prefix — it
// matches by prefix precisely so a route added later cannot land ungated — so proving it covers a real v1
// route, and an unshipped version, is the point of these tests rather than a leak of v1 detail into a
// version-neutral file.
const BYTE_PATH = `/api/v1/files/devices/${D}/IMG_0001-photo.jpg`;
const DEVLIST_PATH = `/api/v1/files/devices/${D}`;

// ── The lifecycle derivation (capability `event-limits`) ───────────────────────────────────────────

Deno.test("deleteByMs → anchors at max(createdAt, startsAt) and adds the stamped lifetime", () => {
  const day = 24 * 60 * 60;
  // Back-dated: `startsAt` weeks before `createdAt` → anchored at createdAt, so it is not born expired.
  assertEquals(
    deleteByMs({
      createdAt: "2026-07-01T00:00:00Z",
      startsAt: "2026-06-01T00:00:00Z",
      lifetimeSeconds: day,
    }),
    Date.parse("2026-07-02T00:00:00Z"),
  );
  // Created early: `startsAt` weeks after `createdAt` → anchored at startsAt, so it outlives its window.
  assertEquals(
    deleteByMs({
      createdAt: "2026-06-01T00:00:00Z",
      startsAt: "2026-07-01T00:00:00Z",
      lifetimeSeconds: day,
    }),
    Date.parse("2026-07-02T00:00:00Z"),
  );
});

Deno.test("deleteByMs → NaN when neither anchor parses", () => {
  assert(Number.isNaN(deleteByMs({ createdAt: "nope", startsAt: "nope", lifetimeSeconds: 60 })));
});

// ── The maintenance window (capability `backend-deployment`) ───────────────────────────────────────

/** The same app, built from a bundle that carries the maintenance flag. */
function windowOpen(deps: Omit<Deps, "config">) {
  return createRealApp({ ...deps, config: { ...CONFIG, maintenance: true }, now: () => NOW });
}

Deno.test("window: a device-API request is refused, and touches nothing", async () => {
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await windowOpen({ db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "bytes",
    headers: { authorization: `Bearer ${TOKEN}` },
  });
  assertEquals(res.status, 503);
  // A POLL INTERVAL, not a guess at the window's length: every 503 re-issues it, so a caller that
  // honours it asks again and converges. Under-estimating is the safer error — too high would keep a
  // caller away after the service is back.
  assertEquals(res.headers.get("Retry-After"), "30");
  // Load-bearing: the pull zone caches on this, and a cached 503 would outlive the window — turning a
  // bounded, deliberate outage into an unbounded accidental one.
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  assertEquals(calls.length, 0);
  const rows = await db.execute(`SELECT COUNT(*) AS n FROM resources`);
  assertEquals(Number(rows.rows[0].n), 0);
  db.close();
});

Deno.test("window: it is a PREFIX, so an unshipped /api/v2 is refused too", async () => {
  // The property a closed list cannot have. `/api/v2` has no routes at all today; the point is that when
  // it does, it is gated because of where it is mounted, not because someone remembered to list it.
  const db = await store();
  const res = await windowOpen({ db, fetch: recorder().fetchImpl }).request(
    `/api/v2/events/${E}`,
    { headers: { authorization: `Bearer ${TOKEN}` } },
  );
  assertEquals(res.status, 503);
  db.close();
});

Deno.test("window: maintenance wins over the token gate", async () => {
  // Ordering, pinned in both directions. Without the flag this same request is a 401; with it the answer
  // is the truthful one — the service is down, not the caller unauthorized.
  const db = await store();
  const open = await windowOpen({ db, fetch: recorder().fetchImpl }).request(DEVLIST_PATH);
  assertEquals(open.status, 503);
  const closed = await createRealApp({ config: CONFIG, db, fetch: recorder().fetchImpl })
    .request(DEVLIST_PATH);
  assertEquals(closed.status, 401);
  db.close();
});

Deno.test("window: the root routes keep serving", async () => {
  // They read only the public `site/` prefix or nothing at all, so a schema migration has no bearing on
  // them — and taking the marketing page down for a database change would be an outage chosen for free.
  const db = await store();
  const { fetchImpl } = recorder();
  const site: FetchLike = (url, init) =>
    url.includes("/site/")
      ? Promise.resolve(new Response("<html></html>", { status: 200 }))
      : fetchImpl(url, init);
  const app = windowOpen({ db, fetch: site });
  for (
    const path of ["/", "/join", "/_astro/app.abc123.js", "/.well-known/apple-app-site-association"]
  ) {
    assertEquals((await app.request(path)).status, 200, path);
  }
  db.close();
});

Deno.test("window: /health still answers, and says the window is open", async () => {
  // Gating this would blind the step that LIFTS the window: the probe reads exactly this to tell the
  // maintenance publish from the ordinary one, which carry the same commit.
  const db = await store();
  const res = await windowOpen({ db, fetch: recorder().fetchImpl, buildSha: "same" })
    .request("/health");
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { sha: "same", maintenance: true });
  db.close();
});

Deno.test("window: with the flag off, the device API is untouched", async () => {
  // The default every non-migrating deploy ships. If this ever fails, `main` is serving 503s.
  const db = await store();
  const { calls, fetchImpl } = recorder();
  const res = await createApp({ config: CONFIG, db, fetch: fetchImpl }).request(BYTE_PATH, {
    method: "PUT",
    body: "bytes",
  });
  assertEquals(res.status, 201);
  assertEquals(calls.filter((c) => c.init.method === "PUT").length, 1);
  db.close();
});

// ── GET /health (the deploy boot probe's target) ───────────────────────────────────────────────────

Deno.test("health → reports the commit, uncacheable", async () => {
  const db = await store();
  const { fetchImpl } = recorder();
  const app = createRealApp({ config: CONFIG, db, fetch: fetchImpl, buildSha: "abc123" });
  const res = await app.request("/health");
  assertEquals(res.status, 200);
  assertEquals(await res.json(), { sha: "abc123" });
  assertEquals(res.headers.get("Cache-Control"), "no-store, no-cache, max-age=0");
  db.close();
});

Deno.test("health → the window field appears only when the window is OPEN", async () => {
  // Absence means closed, and that collapse is safe because both causes it absorbs are the same answer:
  // a bundle whose flag is false, and a bundle predating the flag — which was built before maintenance
  // mode existed, so it is necessarily serving the device API.
  const db = await store();
  const open = { ...CONFIG, maintenance: true };
  const res = await createRealApp({ config: open, db, fetch: recorder().fetchImpl, buildSha: "p" })
    .request("/health");
  assertEquals(await res.json(), { sha: "p", maintenance: true });
  db.close();
});

Deno.test("health → it reaches BOTH dependencies, not just the store", async () => {
  // The storage call is the new coverage: a BUNNY_STORAGE_ZONE that is present but names a zone that
  // does not exist used to boot and probe green.
  const db = await store();
  const { calls, fetchImpl } = recorder();
  await createRealApp({ config: CONFIG, db, fetch: fetchImpl }).request("/health");
  assertEquals(calls.length, 1);
  assertEquals(calls[0].url, `${ZONE}/`);
  assertEquals(calls[0].init.method, "GET");
  db.close();
});

Deno.test("health → an unreachable store is a bare non-success, not a 200 describing itself", async () => {
  const down: Db = {
    execute: () => Promise.reject(new Error("no route to host")),
    batch: () => Promise.reject(new Error("no route to host")),
    transaction: () => Promise.reject(new Error("no route to host")),
  };
  const res = await createRealApp({ config: CONFIG, db: down, fetch: recorder().fetchImpl })
    .request("/health");
  assertEquals(res.status, 503);
  assertEquals(await res.text(), "");
});

Deno.test("health → an unreachable storage zone is a bare non-success too", async () => {
  // A wrong zone answers 404 through the native storage API. It must NOT read as an empty zone: that is
  // exactly the tolerance `storageReachable` refuses to inherit from `listDir`.
  const db = await store();
  const res = await createRealApp({
    config: CONFIG,
    db,
    fetch: recorder({ status: 404 }).fetchImpl,
  })
    .request("/health");
  assertEquals(res.status, 503);
  db.close();
});

Deno.test("health → a storage call that throws is unreachable, never an exception", async () => {
  const db = await store();
  const res = await createRealApp({
    config: CONFIG,
    db,
    fetch: recorder({ throws: true }).fetchImpl,
  })
    .request("/health");
  assertEquals(res.status, 503);
  db.close();
});

Deno.test("health → HEAD returns the headers with no body", async () => {
  const db = await store();
  const res = await createRealApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/health",
    { method: "HEAD" },
  );
  assertEquals(res.status, 200);
  assertEquals(await res.text(), "");
  db.close();
});

Deno.test("health → a mutating method is not served by this route", async () => {
  const db = await store();
  // Driven WITH a token on purpose. The gate admits `/health` for GET/HEAD only, so an unauthenticated
  // POST stops at the token check (401) and never reaches routing — which would test the gate, not this
  // route. Past the gate, the answer is the route table's: no such entry.
  const res = await createApp({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(
    "/health",
    { method: "POST" },
  );
  assertEquals(res.status, 404);
  db.close();
});

// ── The devices table's two writers (capability `database`) ────────────────────────────────────────
//
// One row, two independently-written column groups. The property under test is that neither writer can
// disturb the other's fact — which no route test can show, because each route exercises only its own half.
//
// NOT COVERED HERE, and stated rather than left to be assumed: the `502` branches of `/attest/token` and
// `/attest/renew` (a verified attestation or assertion whose record cannot be persisted). Reaching either
// needs a valid App Attest attestation minted against this deployment's app id and a live challenge, and
// the committed fixture is a real device's attestation for a DIFFERENT app — so the verifier refuses it
// long before persistence is attempted. The statements below are what those branches call.

Deno.test("devices → attestation creates the row; a re-attestation leaves the push registration alone", async () => {
  const db = await store();
  await putAttestation(db, D, { publicKey: "k1", environment: "development" }, "t0", "e0");
  await putDeviceRecord(db, D, { kind: "apns", token: "tok", env: "sandbox" }, "t1");

  await putAttestation(db, D, { publicKey: "k2", environment: "production" }, "t2", "e2");

  const [row] = await rows(db, `SELECT * FROM devices WHERE device_id=?`, [D]);
  assertEquals(row.attest_key, "k2");
  assertEquals(row.attest_env, "production");
  assertEquals(row.attest_token_expires_at, "e2");
  assertEquals(row.push_token, "tok"); // untouched by the re-attestation
  assertEquals(row.created_at, "t0"); // FIRST attested, not most recently
  db.close();
});

Deno.test("devices → a push registration leaves the attestation alone", async () => {
  const db = await store();
  await putAttestation(db, D, { publicKey: "k1", environment: "development" }, "t0", "e0");
  await putDeviceRecord(db, D, { kind: "apns", token: "tok", env: "sandbox" }, "t1");

  const [row] = await rows(db, `SELECT * FROM devices WHERE device_id=?`, [D]);
  assertEquals(row.attest_key, "k1");
  assertEquals(row.attest_token_expires_at, "e0");
  assertEquals(row.created_at, "t0");
  assertEquals(row.push_updated_at, "t1");
  db.close();
});

Deno.test("devices → a registration for an unattested device affects no row", async () => {
  // What the route turns into its 401. An insert here would fabricate an enrolment.
  const db = await store();
  const { rowsAffected } = await putDeviceRecord(db, D, null, "t1");
  assertEquals(rowsAffected, 0);
  assertEquals((await rows(db, `SELECT * FROM devices`)).length, 0);
  db.close();
});

Deno.test("devices → the expiry bump reports a vanished row rather than recreating it", async () => {
  // Renewal reads, verifies, then writes. The sweep can collect the row in between; minting against a
  // record that is gone would hand out a credential nothing knows about.
  const db = await store();
  assertEquals((await touchTokenExpiry(db, D, "e9")).rowsAffected, 0);
  await putAttestation(db, D, { publicKey: "k", environment: "production" }, "t0", "e0");
  assertEquals((await touchTokenExpiry(db, D, "e9")).rowsAffected, 1);
  assertEquals(
    (await rows(db, `SELECT attest_token_expires_at FROM devices WHERE device_id=?`, [D]))[0],
    { attest_token_expires_at: "e9" },
  );
  db.close();
});

Deno.test("devices → readAttestation tells absence from a stored key", async () => {
  const db = await store();
  assertEquals(await readAttestation(db, D), null);
  await putAttestation(db, D, { publicKey: "k", environment: "development" }, "t0", "e0");
  assertEquals(await readAttestation(db, D), { publicKey: "k", environment: "development" });
  db.close();
});

// ── The cutover window: a row that exists but has not attested ─────────────────────────────────────
//
// Migration v2 carries every legacy row across WITH its push registration and leaves the attestation
// columns NULL, because their values live in the storage zone and SQL cannot reach them. That state is
// real, temporary, and legible — and both readers below would answer it wrongly without care.
//
// These run against a store stopped at v2, because that is the ONLY place the state exists: v3 tightens
// the columns to NOT NULL, so afterwards an unattested row is not merely absent but unrepresentable. A
// test written against the fully-migrated store cannot even construct the case.

/** A store at the state migration v2 leaves behind — v3 not yet applied. */
async function storeAtV2() {
  const db = sqliteDb(":memory:");
  for (const m of MIGRATIONS.filter((m) => m.version <= 2)) {
    for (const sql of m.statements) await db.execute(sql);
  }
  return db;
}

Deno.test("devices → a carried row with no attestation reads as NOT attested, not as a garbage key", async () => {
  // `String(null)` is the literal "null". Handing renewal that as a public key fails to verify and reads
  // as a REFUSED ASSERTION — blaming the device's Secure Enclave for something the backend never had.
  const db = await storeAtV2();
  await db.execute(
    `INSERT INTO devices (device_id, created_at, push_kind, push_token, push_env)
     VALUES (?, 't0', 'apns', 'tok', 'sandbox')`,
    [D],
  );
  assertEquals(await readAttestation(db, D), null);
  db.close();
});

Deno.test("devices → a carried row keeps its push token but cannot register again until it attests", async () => {
  // The 401 must keep meaning "this device has not attested" rather than decaying into "no row exists".
  // The token it already had survives — so notify still reaches it — and the next write is refused until
  // the device attests, which fills the columns and lets the write through.
  const db = await storeAtV2();
  await db.execute(
    `INSERT INTO devices (device_id, created_at, push_kind, push_token, push_env)
     VALUES (?, 't0', 'apns', 'carried', 'sandbox')`,
    [D],
  );

  assertEquals(
    (await putDeviceRecord(db, D, { kind: "apns", token: "new", env: "sandbox" }, "t1"))
      .rowsAffected,
    0,
  );
  assertEquals(await readDeviceRecord(db, D), { kind: "apns", token: "carried", env: "sandbox" });

  await putAttestation(db, D, { publicKey: "k", environment: "production" }, "t2", "e2");
  assertEquals(
    (await putDeviceRecord(db, D, { kind: "apns", token: "new", env: "sandbox" }, "t3"))
      .rowsAffected,
    1,
  );
  db.close();
});
