// Early event completion (capability `event-lifetime`; decision record `changes/early-event-completion`):
// a device declares its manifest FINAL after the range has ended; the publish that leaves every active
// membership final CLOSES the event and wakes its members once; a closed event refuses joins, renames and
// any change to a member's asset set; a COMPLETED event (the sweep's verdict) keeps its row and answers
// "completed". The sweep's own rules are in `scripts/sweep.test.ts`.
import { assertEquals } from "@std/assert";
import {
  apnsConfig,
  apnsRecorder,
  as,
  CONFIG,
  createApp,
  D,
  D2,
  E,
  enrolDevice,
  NOW,
  recorder,
  rows,
  storeWithEvent,
} from "./support/harness.ts";

const VERSION_HEADER = "x-snapsync-app-version";
const JOIN = (d: string) => `/api/v2/events/${E}/devices/${d}`;
const MANIFEST = (d: string) => `/api/v2/events/${E}/devices/${d}/manifest`;
const DETAILS = `/api/v2/events/${E}`;
const BYTE_PATH = `/api/v2/files/devices/${D}/ASSET1/primary?filename=IMG_0001.HEIC`;
const D3 = "33333333-0000-4000-8000-000000000004";
const ENDED = { endsAt: "2026-07-10T00:00:00Z" }; // before the harness's NOW (2026-07-14T12:00Z)

type Store = Awaited<ReturnType<typeof storeWithEvent>>;

function v2(deps: Parameters<typeof createApp>[0]) {
  const app = createApp(deps);
  const request = app.request.bind(app);
  return {
    request: (path: string, init: RequestInit = {}) =>
      request(path, { ...init, headers: { [VERSION_HEADER]: "0.1", ...(init.headers ?? {}) } }),
  };
}

const ASSET = {
  assetId: "ASSET1",
  creationDate: "2026-07-01T00:00:00Z",
  resources: [{
    role: "primary",
    contentType: "image/heic",
    key: "ASSET1-primary.heic",
    filename: "IMG_0001.HEIC",
  }],
};

const body = (assets: unknown[], extra: Record<string, unknown> = {}) =>
  JSON.stringify({ assets, ...extra });

/** D and D2 both joined; D2 holds a registered push token (see `v2.test.ts` `withRecipient`). */
async function twoMembers(db: Store) {
  const { pushed, fetchImpl } = apnsRecorder(200);
  const app = v2({ config: await apnsConfig(), db, fetch: fetchImpl });
  await app.request(JOIN(D), { method: "PUT" });
  await app.request(JOIN(D2), { method: "PUT", headers: await as(D2) });
  await enrolDevice(db, D2);
  await db.execute(
    `UPDATE devices SET push_kind = 'apns', push_token = 'recipient', push_env = 'sandbox',
                        push_updated_at = '2026-07-14T00:00:00Z'
      WHERE device_id = ?`,
    [D2],
  );
  return { app, pushed };
}

async function closedAt(db: Store): Promise<unknown> {
  return (await rows(db, `SELECT closed_at FROM events`))[0].closed_at;
}

Deno.test("final → before the range has ended a final flag is ignored and nothing closes", async () => {
  const db = await storeWithEvent(); // ends 2026-07-27, after NOW
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN(D), { method: "PUT" });
  const res = await app.request(MANIFEST(D), {
    method: "PUT",
    body: body([ASSET], { final: true }),
  });
  assertEquals(res.status, 200);
  assertEquals((await rows(db, `SELECT final FROM memberships`))[0].final, 0);
  assertEquals(await closedAt(db), null);
  db.close();
});

Deno.test("final → a non-boolean final is 400 and writes nothing", async () => {
  const db = await storeWithEvent(ENDED);
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN(D), { method: "PUT" });
  const res = await app.request(MANIFEST(D), {
    method: "PUT",
    body: body([ASSET], { final: "yes" }),
  });
  assertEquals(res.status, 400);
  assertEquals((await rows(db, `SELECT * FROM event_assets`)).length, 0);
  db.close();
});

Deno.test("close → the publish that makes the LAST active member final closes the event and wakes once", async () => {
  const db = await storeWithEvent(ENDED);
  const { app, pushed } = await twoMembers(db);

  // D2 settles first: D is still unsettled, so nothing closes.
  await app.request(MANIFEST(D2), {
    method: "PUT",
    body: body([], { final: true }),
    headers: await as(D2),
  });
  assertEquals(await closedAt(db), null);

  // D settles: every active member is final → closed, and D2 (the other member) is woken once.
  const res = await app.request(MANIFEST(D), {
    method: "PUT",
    body: body([ASSET], { final: true }),
  });
  assertEquals(res.status, 200);
  assertEquals(await closedAt(db), new Date(NOW).toISOString());
  assertEquals(pushed, ["recipient"]);

  // The waiting line's counts.
  const details = await (await app.request(DETAILS)).json() as Record<string, unknown>;
  assertEquals(details.closedAt, new Date(NOW).toISOString());
  assertEquals(details.completedAt, null);
  assertEquals(details.members, { active: 2, final: 2 });
  db.close();
});

Deno.test("close → a departed member is not waited for", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(JOIN(D2), { method: "DELETE", headers: await as(D2) });
  await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET], { final: true }) });
  assertEquals(await closedAt(db), new Date(NOW).toISOString());
  db.close();
});

Deno.test("closed → join, rejoin and rename are refused 410; the name stays", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(JOIN(D2), { method: "DELETE", headers: await as(D2) });
  await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET], { final: true }) });

  const join = await app.request(JOIN(D3), { method: "PUT", headers: await as(D3) });
  assertEquals(join.status, 410);
  assertEquals(await join.json(), { error: "closed" });
  // A device that left cannot come back into a closed event either.
  assertEquals((await app.request(JOIN(D2), { method: "PUT", headers: await as(D2) })).status, 410);
  const rename = await app.request(DETAILS, {
    method: "PATCH",
    body: JSON.stringify({ name: "Renamed" }),
  });
  assertEquals(rename.status, 410);
  assertEquals((await rows(db, `SELECT name FROM events`))[0].name, "Party");
  db.close();
});

Deno.test("closed → the same asset set is a 200 no-op; a changed one is refused 409 and changes nothing", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(JOIN(D2), { method: "DELETE", headers: await as(D2) });
  await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET], { final: true }) });

  const same = await app.request(MANIFEST(D), {
    method: "PUT",
    body: body([ASSET], { final: true, version: 9 }),
  });
  assertEquals(same.status, 200);
  // A deleted photo no longer withdraws it once closed (capability `photo-sharing`).
  const retract = await app.request(MANIFEST(D), {
    method: "PUT",
    body: body([], { final: true }),
  });
  assertEquals(retract.status, 409);
  assertEquals(await retract.json(), { error: "closed" });
  assertEquals((await rows(db, `SELECT asset_id FROM event_assets`)).length, 1);
  db.close();
});

Deno.test("rejoin → clears the final flag while the event is open", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(MANIFEST(D2), {
    method: "PUT",
    body: body([], { final: true }),
    headers: await as(D2),
  });
  await app.request(JOIN(D2), { method: "DELETE", headers: await as(D2) });
  await app.request(JOIN(D2), { method: "PUT", headers: await as(D2) });
  const r = await rows(db, `SELECT final FROM memberships WHERE device_id = ?`, [D2]);
  assertEquals(r[0].final, null);
  db.close();
});

Deno.test("byte landing → stamps the clock's anchor on the event it completed an asset of", async () => {
  const db = await storeWithEvent(ENDED);
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN(D), { method: "PUT" });
  await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET]) });
  assertEquals((await rows(db, `SELECT last_landed_at FROM events`))[0].last_landed_at, null);
  assertEquals((await app.request(BYTE_PATH, { method: "PUT", body: "x" })).status, 201);
  assertEquals(
    (await rows(db, `SELECT last_landed_at FROM events`))[0].last_landed_at,
    new Date(NOW).toISOString(),
  );
  db.close();
});

Deno.test("completed → details answer 200 with completedAt; a publish is 410; a leave still succeeds", async () => {
  const db = await storeWithEvent(ENDED);
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  await app.request(JOIN(D), { method: "PUT" });
  // What the sweep's completion leaves behind: the row, stamped, and no memberships.
  await db.execute(`UPDATE events SET closed_at = ?, completed_at = ?`, [
    "2026-07-13T00:00:00.000Z",
    "2026-07-13T00:00:00.000Z",
  ]);
  await db.execute(`DELETE FROM memberships`);

  const details = await app.request(DETAILS);
  assertEquals(details.status, 200);
  const json = await details.json() as Record<string, unknown>;
  assertEquals(json.completedAt, "2026-07-13T00:00:00.000Z");
  assertEquals(json.members, { active: 0, final: 0 });
  assertEquals(
    (await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET]) })).status,
    410,
  );
  // A pending leave retried against a completed event is answered, so the device stops retrying.
  assertEquals((await app.request(JOIN(D), { method: "DELETE" })).status, 200);
  db.close();
});
