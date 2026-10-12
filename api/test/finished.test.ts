// DONE AND FINISHED (capability `event-lifetime`; decision record `changes/back-dated-events`, D3). An event is
// finished once its open-until date has passed and every member still in it has shared and received everything —
// computed on every read, so each way it stops being true is asserted here: an own photo declared but not landed, a
// new gain past a member's received position, a late join. A member who left holds up nothing.

import { assert, assertEquals, assertFalse } from "@std/assert";
import {
  type Db,
  enroll,
  eventFinished,
  insertEvent,
  memberCounts,
  recordReceived,
} from "../src/db.ts";
import { emptyStore } from "./support/db.ts";

const E = "7a3f9c21-0000-4000-8000-0000000000f1";
const OPEN_UNTIL = "2026-06-05T00:00:00Z";
const BEFORE = "2026-06-04T00:00:00Z";
const AFTER = "2026-06-06T00:00:00Z";

async function event(db: Db) {
  await insertEvent(db, {
    eventId: E,
    name: "x",
    createdAt: "2026-06-01T00:00:00Z",
    startsAt: "2026-06-01T00:00:00Z",
    endsAt: "2026-06-03T00:00:00Z",
    openUntil: OPEN_UNTIL,
    capacity: 10,
    lifetimeSeconds: 30 * 86400,
  });
}

/** A member that settled with one landed photo [asset] (two roles). */
async function sharedMember(db: Db, d: string, asset = `${d}-A`) {
  await enroll(db, E, d, "2026-06-02T00:00:00Z");
  await db.execute(
    `UPDATE memberships SET state = 'settled' WHERE event_id = ? AND device_id = ?`,
    [E, d],
  );
  await declare(db, d, asset);
  await land(db, d, asset, "primary");
  await land(db, d, asset, "live");
}

async function declare(db: Db, d: string, asset: string) {
  await db.execute(
    `INSERT INTO event_assets (event_id, device_id, asset_id, creation_date, roles)
     VALUES (?, ?, ?, 'c', '["primary","live"]')`,
    [E, d, asset],
  );
}

async function land(db: Db, d: string, asset: string, role: string) {
  await db.execute(
    `INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename)
     VALUES (?, ?, ?, ?, 'p', 't', 'f')`,
    [E, d, asset, role],
  );
}

/** A gain in the union log; answers its position. */
async function gain(db: Db, d: string, asset: string): Promise<number> {
  await db.execute(
    `INSERT INTO union_log (event_id, kind, device_id, asset_id, at) VALUES (?, 'gained', ?, ?, 'at')`,
    [E, d, asset],
  );
  const { rows } = await db.execute(`SELECT MAX(seq) AS s FROM union_log`);
  return Number(rows[0].s);
}

Deno.test("finished → everyone shared and received, after the open-until date; never before it", async () => {
  const db = await emptyStore();
  await event(db);
  await sharedMember(db, "a");
  await sharedMember(db, "b");
  const head = await gain(db, "a", "a-A");
  await gain(db, "b", "b-A");
  const last = await gain(db, "b", "b-B");
  await recordReceived(db, E, "a", last);
  await recordReceived(db, E, "b", last);
  assertFalse(await eventFinished(db, E, BEFORE), "never before the open-until date");
  assert(await eventFinished(db, E, AFTER));
  assert(head < last);
});

Deno.test("finished → a member that never said what it received is not done", async () => {
  const db = await emptyStore();
  await event(db);
  await sharedMember(db, "a");
  await gain(db, "a", "a-A");
  assertFalse(await eventFinished(db, E, AFTER));
});

Deno.test("finished → an own photo declared but not landed makes it unfinished", async () => {
  const db = await emptyStore();
  await event(db);
  await sharedMember(db, "a");
  const p = await gain(db, "a", "a-A");
  await recordReceived(db, E, "a", p);
  assert(await eventFinished(db, E, AFTER));
  await declare(db, "a", "a-NEW");
  assertFalse(await eventFinished(db, E, AFTER));
  assertEquals((await memberCounts(db, E)).waiting, 1);
});

Deno.test("finished → a new gain past a member's received position makes it unfinished", async () => {
  const db = await emptyStore();
  await event(db);
  await sharedMember(db, "a");
  await sharedMember(db, "b");
  const p = await gain(db, "a", "a-A");
  await recordReceived(db, E, "a", p);
  await recordReceived(db, E, "b", p);
  assert(await eventFinished(db, E, AFTER));
  await gain(db, "b", "b-A");
  assertFalse(await eventFinished(db, E, AFTER));
});

Deno.test("finished → a late join makes it unfinished; a member who left holds up nothing", async () => {
  const db = await emptyStore();
  await event(db);
  await sharedMember(db, "a");
  const p = await gain(db, "a", "a-A");
  await recordReceived(db, E, "a", p);
  await enroll(db, E, "late", "2026-06-07T00:00:00Z");
  assertFalse(await eventFinished(db, E, AFTER));
  assertEquals(await memberCounts(db, E), { active: 2, final: 1, waiting: 1 });
  await db.execute(`UPDATE memberships SET state = 'left' WHERE device_id = 'late'`);
  assert(await eventFinished(db, E, AFTER));
});

Deno.test("finished → an event nobody joined is never finished", async () => {
  const db = await emptyStore();
  await event(db);
  assertFalse(await eventFinished(db, E, AFTER));
});

Deno.test("recordReceived → monotonic, and only for a member still in the event", async () => {
  const db = await emptyStore();
  await event(db);
  await enroll(db, E, "a", "2026-06-02T00:00:00Z");
  assert(await recordReceived(db, E, "a", 7));
  assert(await recordReceived(db, E, "a", 3));
  const { rows } = await db.execute(`SELECT received_position FROM memberships`);
  assertEquals(rows[0].received_position, 7);
  assertFalse(await recordReceived(db, E, "stranger", 9));
  await db.execute(`UPDATE memberships SET state = 'left'`);
  assertFalse(await recordReceived(db, E, "a", 9));
});
