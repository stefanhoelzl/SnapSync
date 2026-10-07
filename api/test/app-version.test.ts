// The app version a device row keeps (capability `app-update-required`): recorded from the v2 version
// header by the mint, the join, the manifest publish and a union read whose token verifies — and by
// nothing else, since each statement is an Edge subrequest the byte route's fan-out cannot spare
// (`docs/deployment.md`). Best-effort throughout: recording never fails the request it rode in on.

import { assertEquals } from "@std/assert";
import type { Db } from "../src/db.ts";
import { putAttestation, recordAppVersion } from "../src/db.ts";
import { recordableVersion } from "../src/version.ts";
import {
  as,
  CONFIG,
  createApp,
  createRealApp,
  D,
  D2,
  E,
  enrolDevice,
  joinEvent,
  NOW,
  recorder,
  rows,
  storeWithEvent,
  VERSION_HEADER,
} from "./support/harness.ts";

const JOIN = `/api/v2/events/${E}/devices/${D}`;
const MANIFEST = `/api/v2/events/${E}/devices/${D}/manifest`;
const UNION = `/api/v2/events/${E}/files`;
const v = (version: string) => ({ [VERSION_HEADER]: version });

async function versionOf(db: Db, deviceId = D): Promise<unknown> {
  const r = await rows(db, `SELECT app_version FROM devices WHERE device_id = ?`, [deviceId]);
  return r[0]?.app_version;
}

async function enrolled() {
  const db = await storeWithEvent();
  await enrolDevice(db, D);
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  return { db, app };
}

Deno.test("app version → the join records it, and a later join records the upgrade", async () => {
  const { db, app } = await enrolled();
  assertEquals(await versionOf(db), null);
  assertEquals((await app.request(JOIN, { method: "PUT", headers: v("0.12") })).status, 200);
  assertEquals(await versionOf(db), "0.12");
  await app.request(JOIN, { method: "PUT", headers: v("0.13") });
  assertEquals(await versionOf(db), "0.13");
  db.close();
});

Deno.test("app version → the manifest publish records it", async () => {
  const { db, app } = await enrolled();
  await app.request(JOIN, { method: "PUT", headers: v("0.12") });
  const res = await app.request(MANIFEST, {
    method: "PUT",
    headers: v("0.14"),
    body: JSON.stringify({ deviceId: D, assets: [] }),
  });
  assertEquals(res.status, 200);
  assertEquals(await versionOf(db), "0.14");
  db.close();
});

Deno.test("app version → a union read records the token's device, and an anonymous one records nobody", async () => {
  const { db } = await enrolled();
  await enrolDevice(db, D2);
  const app = createRealApp({ config: CONFIG, db, fetch: recorder().fetchImpl, now: () => NOW });
  assertEquals(
    (await app.request(UNION, { headers: { ...v("0.12"), ...await as(D2) } })).status,
    200,
  );
  assertEquals(await versionOf(db, D2), "0.12");
  assertEquals((await app.request(UNION, { headers: v("0.13") })).status, 200);
  assertEquals(await versionOf(db, D), null);
  assertEquals(await versionOf(db, D2), "0.12");
  db.close();
});

Deno.test("app version → the byte route does not record it (no subrequest to spare)", async () => {
  const { db, app } = await enrolled();
  await joinEvent(db, E, D);
  const res = await app.request(`/api/v2/files/devices/${D}/A/primary?filename=IMG.HEIC`, {
    method: "PUT",
    headers: v("0.12"),
    body: "b",
  });
  assertEquals(res.status, 201);
  assertEquals(await versionOf(db), null);
  db.close();
});

Deno.test("app version → a request to the retired v1 records nothing", async () => {
  const { db, app } = await enrolled();
  const res = await app.request(`/api/v1/events/${E}/devices/${D}`, {
    method: "PUT",
    headers: v("0.12"),
    body: JSON.stringify({ deviceId: D, assets: [] }),
  });
  assertEquals(res.status, 426);
  assertEquals(await versionOf(db), null);
  db.close();
});

Deno.test("app version → a store failure is logged, and the join still answers", async () => {
  const { db } = await enrolled();
  const failing: Db = {
    ...db,
    execute: (sql, args) =>
      sql.includes("SET app_version")
        ? Promise.reject(new Error("db blinked"))
        : db.execute(sql, args),
    batch: (s) => db.batch(s),
    transaction: (fn) => db.transaction(fn),
  };
  const app = createApp({ config: CONFIG, db: failing, fetch: recorder().fetchImpl });
  assertEquals((await app.request(JOIN, { method: "PUT", headers: v("0.12") })).status, 200);
  assertEquals(await versionOf(db), null);
  db.close();
});

Deno.test("app version → a re-attestation that declares none keeps the recorded version", async () => {
  const db = await storeWithEvent();
  const att = { publicKey: "k", platform: "ios", environment: "production" } as const;
  await putAttestation(db, D, att, "t0", "e0", "0.12");
  assertEquals(await versionOf(db), "0.12");
  await putAttestation(db, D, att, "t1", "e1");
  assertEquals(await versionOf(db), "0.12");
  await putAttestation(db, D, att, "t2", "e2", "0.13");
  assertEquals(await versionOf(db), "0.13");
  db.close();
});

Deno.test("app version → recording for a device with no row creates none", async () => {
  const db = await storeWithEvent();
  await recordAppVersion(db, D, "0.12");
  assertEquals((await rows(db, `SELECT 1 FROM devices`)).length, 0);
  db.close();
});

Deno.test("recordableVersion → an X.Y… version of bounded length, trimmed; anything else is null", () => {
  assertEquals(recordableVersion("0.12"), "0.12");
  assertEquals(recordableVersion(" 1.2.3 "), "1.2.3");
  assertEquals(recordableVersion(undefined), null);
  assertEquals(recordableVersion(""), null);
  assertEquals(recordableVersion("0.12-beta"), null);
  assertEquals(recordableVersion("1." + "1".repeat(40)), null);
});
