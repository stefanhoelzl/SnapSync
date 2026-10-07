// Early event completion (capability `event-lifetime`; decision record `changes/early-event-completion`):
// a device declares its manifest FINAL after the range has ended, which makes its membership `settled`; the
// publish or leave that leaves no member `sharing` CLOSES the event and wakes its members once; a leave records
// `done` or `left`; a closed event refuses joins, renames and
// any change to a member's asset set; a COMPLETED event (the sweep's verdict) keeps its row and answers
// "completed". The sweep's own rules are in `scripts/sweep.test.ts`.
import { assert, assertEquals } from "@std/assert";
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
  VERSION_HEADER,
} from "./support/harness.ts";

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
  const lines: string[] = [];
  const app = v2({
    config: await apnsConfig(),
    db,
    fetch: fetchImpl,
    logSink: (l) => lines.push(l),
  });
  await app.request(JOIN(D), { method: "PUT" });
  await app.request(JOIN(D2), { method: "PUT", headers: await as(D2) });
  await enrolDevice(db, D2);
  await db.execute(
    `UPDATE devices SET push_kind = 'apns', push_token = 'recipient', push_env = 'sandbox',
                        push_updated_at = '2026-07-14T00:00:00Z'
      WHERE device_id = ?`,
    [D2],
  );
  return { app, pushed, lines };
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
  assertEquals((await rows(db, `SELECT state FROM memberships`))[0].state, "sharing");
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

Deno.test("close → the publish that settles the LAST sharing member closes the event and wakes once", async () => {
  const db = await storeWithEvent(ENDED);
  const { app, pushed, lines } = await twoMembers(db);

  // D2 settles first: D is still unsettled, so nothing closes.
  await app.request(MANIFEST(D2), {
    method: "PUT",
    body: body([], { final: true }),
    headers: await as(D2),
  });
  assertEquals(await closedAt(db), null);

  // D settles: no member is sharing any more → closed, and D2 (the other member) is woken once.
  const res = await app.request(MANIFEST(D), {
    method: "PUT",
    body: body([ASSET], { final: true }),
  });
  assertEquals(res.status, 200);
  assertEquals(await closedAt(db), new Date(NOW).toISOString());
  assertEquals(pushed, ["recipient"]);
  assert(lines.at(-1)!.endsWith(" closed=true recipients=1 pushed=1"), lines.at(-1));

  // The waiting line's counts.
  const details = await (await app.request(DETAILS)).json() as Record<string, unknown>;
  assertEquals(details.closedAt, new Date(NOW).toISOString());
  assertEquals(details.completedAt, null);
  assertEquals(details.members, { active: 2, final: 2 });
  db.close();
});

Deno.test("close → a member that left is not waited for", async () => {
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

Deno.test("rejoin → a settled member that left is back to sharing while the event is open", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(MANIFEST(D2), {
    method: "PUT",
    body: body([], { final: true }),
    headers: await as(D2),
  });
  await app.request(JOIN(D2), { method: "DELETE", headers: await as(D2) });
  await app.request(JOIN(D2), { method: "PUT", headers: await as(D2) });
  const r = await rows(db, `SELECT state FROM memberships WHERE device_id = ?`, [D2]);
  assertEquals(r[0].state, "sharing");
  db.close();
});

Deno.test("byte landing → stamps the clock's anchor on the event it completed an asset of", async () => {
  const db = await storeWithEvent(ENDED);
  const lines: string[] = [];
  const app = v2({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    logSink: (l) => lines.push(l),
  });
  await app.request(JOIN(D), { method: "PUT" });
  await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET]) });
  assertEquals((await rows(db, `SELECT last_landed_at FROM events`))[0].last_landed_at, null);
  assertEquals((await app.request(BYTE_PATH, { method: "PUT", body: "x" })).status, 201);
  // The landing completed the asset; no other member holds a token, so nobody is woken.
  assert(lines.at(-1)!.endsWith(" in=1 out=0 completed=1 recipients=0 pushed=0"), lines.at(-1));
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

// ── The leave: `done` or `left`, and the close it can make ────────────────────────────────────────

const LEAVE = (d: string, received?: boolean) =>
  received === undefined ? JOIN(d) : `${JOIN(d)}?received=${received}`;

async function stateOf(db: Store, d: string): Promise<unknown> {
  return (await rows(db, `SELECT state FROM memberships WHERE device_id = ?`, [d]))[0].state;
}

/** D shares ASSET and settles; with `landed`, its bytes have arrived. D2 stays sharing, so nothing closes. */
async function settledD(db: Store, landed: boolean) {
  const ctx = await twoMembers(db);
  await ctx.app.request(MANIFEST(D), { method: "PUT", body: body([ASSET], { final: true }) });
  if (landed) await ctx.app.request(BYTE_PATH, { method: "PUT", body: "x" });
  assertEquals(await stateOf(db, D), "settled");
  return ctx;
}

Deno.test("leave → settled, every declared role landed, and everything received → done", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await settledD(db, true);
  assertEquals((await app.request(LEAVE(D, true), { method: "DELETE" })).status, 200);
  assertEquals(await stateOf(db, D), "done");
  db.close();
});

Deno.test("leave → a declared role that never landed makes it left, whatever the device says", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await settledD(db, false);
  await app.request(LEAVE(D, true), { method: "DELETE" });
  assertEquals(await stateOf(db, D), "left");
  db.close();
});

Deno.test("leave → without received=true it is left, even with its share delivered", async () => {
  for (const received of [false, undefined]) {
    const db = await storeWithEvent(ENDED);
    const { app } = await settledD(db, true);
    await app.request(LEAVE(D, received), { method: "DELETE" });
    assertEquals(await stateOf(db, D), "left");
    db.close();
  }
});

Deno.test("leave → a member still sharing is left, whatever the device says", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(LEAVE(D, true), { method: "DELETE" });
  assertEquals(await stateOf(db, D), "left");
  db.close();
});

Deno.test("leave → a repeated leave keeps the state the first one recorded", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await settledD(db, true);
  await app.request(LEAVE(D, true), { method: "DELETE" });
  assertEquals((await app.request(LEAVE(D, false), { method: "DELETE" })).status, 200);
  assertEquals(await stateOf(db, D), "done");
  db.close();
});

Deno.test("close → the leave that takes away the LAST sharing member closes the event and wakes once", async () => {
  const db = await storeWithEvent(ENDED);
  const { app, pushed, lines } = await twoMembers(db);
  // D2 settles; D is still sharing, so nothing closes.
  await app.request(MANIFEST(D2), {
    method: "PUT",
    body: body([], { final: true }),
    headers: await as(D2),
  });
  assertEquals(await closedAt(db), null);
  // D leaves without settling: every member still in the event has settled → closed, D2 woken once.
  assertEquals((await app.request(LEAVE(D), { method: "DELETE" })).status, 200);
  assertEquals(await closedAt(db), new Date(NOW).toISOString());
  assertEquals(pushed, ["recipient"]);
  assert(lines.at(-1)!.endsWith(" closed=true recipients=1 pushed=1"), lines.at(-1));
  const details = await (await app.request(DETAILS)).json() as Record<string, unknown>;
  assertEquals(details.members, { active: 1, final: 1 });
  db.close();
});

Deno.test("close → a leave closes nothing while a member is still sharing, or before the end", async () => {
  // Ended, but D2 is still sharing after D leaves.
  const ended = await storeWithEvent(ENDED);
  const one = await settledD(ended, true);
  const before = one.pushed.length; // the landing woke D2 already
  await one.app.request(LEAVE(D, true), { method: "DELETE" });
  assertEquals(await closedAt(ended), null);
  assertEquals(one.pushed.length, before);
  ended.close();

  // Not ended: nobody can have settled, so nothing closes even when everyone is gone.
  const open = await storeWithEvent();
  const two = await twoMembers(open);
  await two.app.request(LEAVE(D), { method: "DELETE" });
  await two.app.request(LEAVE(D2), { method: "DELETE", headers: await as(D2) });
  assertEquals(await closedAt(open), null);
  open.close();
});

Deno.test("publish → the settled state follows the publish both ways until the close", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await settledD(db, false);
  await app.request(MANIFEST(D), { method: "PUT", body: body([ASSET], { final: false }) });
  assertEquals(await stateOf(db, D), "sharing");
  db.close();
});

Deno.test("publish → a member that left stays gone, whatever it declares", async () => {
  const db = await storeWithEvent(ENDED);
  const { app } = await twoMembers(db);
  await app.request(LEAVE(D), { method: "DELETE" });
  await app.request(MANIFEST(D), { method: "PUT", body: body([], { final: true }) });
  assertEquals(await stateOf(db, D), "left");
  assertEquals(await closedAt(db), null);
  db.close();
});
