// The wake fan-out's bound (decision record `changes/remove-event-device-cap`, D1): an event admits any number
// of devices, so one wake reaches at most FANOUT_MAX_RECIPIENTS of them, chosen at random — the push count,
// and with it the request's Edge subrequests, no longer grows with the event (capability `delivery`).
//
// Randomness is asserted only as SETS: who was picked is never pinned, only that each pick is the right size, drawn
// from the right members, and that repeated picks are not one fixed subset.

import { assert, assertEquals } from "@std/assert";
import { type Db, pushTokensForEvent } from "../src/db.ts";
import type { PushToken, SendOutcome } from "../src/push-send.ts";
import { FANOUT_MAX_RECIPIENTS, notifyMembers } from "../src/routes/support.ts";
import {
  apnsConfig,
  apnsRecorder,
  as,
  createApp,
  D,
  E,
  enrolDevice,
  joinEvent,
  recorder,
  storeWithEvent,
  V2,
} from "./support/harness.ts";

/** The `n`-th other member's device id — distinct, valid UUIDs. */
const member = (n: number) => `${String(n).padStart(8, "0")}-0000-4000-8000-00000000aaaa`;

/** [count] members besides [D], each joined and holding an APNs token equal to its device id. */
async function seedMembers(db: Db, count: number): Promise<string[]> {
  const ids = Array.from({ length: count }, (_, i) => member(i + 1));
  for (const id of ids) {
    await joinEvent(db, E, id);
    await enrolDevice(db, id);
    await db.execute(
      `UPDATE devices SET push_kind = 'apns', push_token = ?, push_env = 'sandbox',
                          push_updated_at = '2026-07-14T00:00:00Z'
        WHERE device_id = ?`,
      [id, id],
    );
  }
  return ids;
}

/** A store counting the statements that read push tokens — the fan-out must stay ONE. */
function countingTokenReads(db: Db) {
  let reads = 0;
  const counted: Db = {
    ...db,
    execute: (sql, args) => {
      if (sql.includes("push_token")) reads++;
      return db.execute(sql, args);
    },
  };
  return { db: counted, reads: () => reads };
}

/** A push sender that records whom each wake reached. */
function recordingSender() {
  const wakes: string[][] = [];
  return {
    wakes,
    sendSilent: (tokens: PushToken[]): Promise<SendOutcome[]> => {
      wakes.push(tokens.map((t) => t.token));
      return Promise.resolve(tokens.map((t) => ({ token: t.token, status: "sent" as const })));
    },
  };
}

const assertDistinctSubset = (picked: string[], of: string[], size: number) => {
  assertEquals(picked.length, size);
  assertEquals(new Set(picked).size, size, "nobody is woken twice by one wake");
  for (const p of picked) assert(of.includes(p), `${p} is a member of the event`);
};

Deno.test("fan-out bound → the token read answers at most the bound, never the excluded device", async () => {
  const db = await storeWithEvent();
  await joinEvent(db, E, D);
  const others = await seedMembers(db, 100);
  const picked = await pushTokensForEvent(db, E, D, FANOUT_MAX_RECIPIENTS);
  assertDistinctSubset(picked.map((t) => t.token), others, FANOUT_MAX_RECIPIENTS);
  db.close();
});

Deno.test("fan-out bound → an event within the bound wakes every other member, as before", async () => {
  const db = await storeWithEvent();
  await joinEvent(db, E, D);
  const others = await seedMembers(db, FANOUT_MAX_RECIPIENTS);
  const picked = await pushTokensForEvent(db, E, D, FANOUT_MAX_RECIPIENTS);
  assertEquals(picked.map((t) => t.token).sort(), [...others].sort());
  db.close();
});

Deno.test("fan-out bound → repeated wakes are not one fixed subset", async () => {
  const db = await storeWithEvent();
  await joinEvent(db, E, D);
  const others = await seedMembers(db, 100);
  const reached = new Set<string>();
  for (let i = 0; i < 5; i++) {
    const picked = await pushTokensForEvent(db, E, D, FANOUT_MAX_RECIPIENTS);
    assertDistinctSubset(picked.map((t) => t.token), others, FANOUT_MAX_RECIPIENTS);
    for (const t of picked) reached.add(t.token);
  }
  // Five fixed picks would reach exactly 40; five random ones reach more with overwhelming probability.
  assert(reached.size > FANOUT_MAX_RECIPIENTS, `five wakes reached only ${reached.size} members`);
  db.close();
});

Deno.test("fan-out bound → a wake reaches the bound, from ONE token read", async () => {
  const base = await storeWithEvent();
  await joinEvent(base, E, D);
  const others = await seedMembers(base, 100);
  const { db, reads } = countingTokenReads(base);
  const pushSender = recordingSender();
  const outcome = await notifyMembers({ db, pushSender }, E, D);
  assertEquals(outcome.recipients, FANOUT_MAX_RECIPIENTS);
  assertEquals(pushSender.wakes.length, 1);
  assertDistinctSubset(pushSender.wakes[0], others, FANOUT_MAX_RECIPIENTS);
  assertEquals(reads(), 1, "the recipients are one statement, whatever the event's size");
  base.close();
});

const BYTE_PATH = `/api/v2/events/${E}/files/devices/${D}/ASSET1/primary?filename=IMG_0001.HEIC`;
const MANIFEST_PATH = `/api/v2/events/${E}/devices/${D}/manifest`;
const DECLARE_ONE = JSON.stringify({
  deviceId: D,
  version: 0,
  final: false,
  assets: [{
    assetId: "ASSET1",
    creationDate: "2026-07-01T00:00:00Z",
    resources: [{
      role: "primary",
      contentType: "image/heic",
      key: "ASSET1-primary.heic",
      filename: "IMG",
    }],
  }],
});

/** A 101-device event over a config whose APNs key really signs, so every push is really attempted. */
async function bigEvent() {
  const db = await storeWithEvent();
  await joinEvent(db, E, D);
  const others = await seedMembers(db, 100);
  const { pushed, fetchImpl } = apnsRecorder();
  const app = createApp({ config: await apnsConfig(), db, fetch: fetchImpl });
  const request = (path: string, init: RequestInit) =>
    app.request(path, { ...init, headers: { ...V2, ...(init.headers ?? {}) } });
  return { db, others, pushed, request };
}

Deno.test("fan-out bound → the byte that completes an asset in a 101-device event wakes 40", async () => {
  const { db, others, pushed, request } = await bigEvent();
  await request(MANIFEST_PATH, { method: "PUT", body: DECLARE_ONE });
  assertEquals(pushed, [], "declaring intent alone wakes nobody");
  assertEquals((await request(BYTE_PATH, { method: "PUT", body: "x" })).status, 201);
  assertDistinctSubset(pushed, others, FANOUT_MAX_RECIPIENTS);
  db.close();
});

Deno.test("fan-out bound → a publish that makes a stored asset fetchable in a 101-device event wakes 40", async () => {
  const { db, others, pushed, request } = await bigEvent();
  await request(BYTE_PATH, { method: "PUT", body: "x" });
  assertEquals(pushed, [], "an undeclared byte completes no asset");
  assertEquals((await request(MANIFEST_PATH, { method: "PUT", body: DECLARE_ONE })).status, 200);
  assertDistinctSubset(pushed, others, FANOUT_MAX_RECIPIENTS);
  db.close();
});

Deno.test("fan-out bound → an event admits more devices than one wake reaches", async () => {
  // The join no longer counts (D3), even on a row stamped with a capacity from before: the large events above
  // are reachable through the route itself.
  const db = await storeWithEvent({ capacity: 1 });
  const app = createApp({ config: await apnsConfig(), db, fetch: recorder().fetchImpl });
  for (let i = 1; i <= FANOUT_MAX_RECIPIENTS + 5; i++) {
    const id = member(i);
    const res = await app.request(`/api/v2/events/${E}/devices/${id}`, {
      method: "PUT",
      headers: { ...V2, ...await as(id) },
    });
    assertEquals(res.status, 200, `device ${i} joins`);
  }
  db.close();
});
