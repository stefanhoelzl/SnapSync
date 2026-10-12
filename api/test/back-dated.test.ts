// Events that stay open until a date, and devices that say what they received (capability `event-lifetime`;
// decision record `changes/back-dated-events`). The create's open-until date and its bound, the received report,
// and — once the lifecycle no longer closes — what the event read serves.

import { assert, assertEquals } from "@std/assert";
import {
  as,
  CONFIG,
  createApp,
  D,
  D2,
  E,
  ENDS_AT,
  NOW,
  recorder,
  rows,
  STARTS_AT,
  store,
  storeWithEvent,
  V2,
} from "./support/harness.ts";
import { canonicalFromMs } from "../src/validators.ts";

const DAY = 86_400_000;
const JOIN = (d: string) => `/api/v2/events/${E}/devices/${d}`;
const RECEIVED = (d: string) => `/api/v2/events/${E}/devices/${d}/received`;

function v2(deps: Parameters<typeof createApp>[0]) {
  const app = createApp(deps);
  const request = app.request.bind(app);
  return {
    request: (path: string, init: RequestInit = {}) =>
      request(path, { ...init, headers: { ...V2, ...(init.headers ?? {}) } }),
  };
}

async function create(db: Awaited<ReturnType<typeof store>>, body: Record<string, unknown>) {
  return await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request("/api/v2/events", {
    method: "POST",
    body: JSON.stringify({ name: "Party", ...body }),
  });
}

// ── POST /events: the open-until date ───────────────────────────────────────────────────────────────

Deno.test("create → a build that sends no openUntil gets an event open until its end", async () => {
  const db = await store();
  const res = await create(db, { startsAt: STARTS_AT, endsAt: ENDS_AT });
  assertEquals(res.status, 201);
  assertEquals((await res.json()).openUntil, ENDS_AT);
  assertEquals((await rows(db, `SELECT open_until FROM events`))[0].open_until, ENDS_AT);
  db.close();
});

Deno.test("create → an event that is already over is stored open until the date its host chose", async () => {
  const db = await store();
  const openUntil = canonicalFromMs(NOW + 7 * DAY);
  const res = await create(db, {
    startsAt: "2026-07-01T18:00:00Z",
    endsAt: "2026-07-03T18:00:00Z",
    openUntil,
  });
  assertEquals(res.status, 201);
  const body = await res.json();
  assertEquals(body.openUntil, openUntil);
  assertEquals(body.endsAt, "2026-07-03T18:00:00Z");
  // The latest it lives counts from the open-until date, not from its long-past start.
  assertEquals(body.deletesAt, canonicalFromMs(NOW + 7 * DAY + CONFIG.eventLifetimeSeconds * 1000));
  db.close();
});

Deno.test("create → an open-until date before the end, or past the window after now, is refused", async () => {
  const db = await store();
  const ended = { startsAt: "2026-07-01T18:00:00Z", endsAt: "2026-07-03T18:00:00Z" };
  for (
    const openUntil of [
      "2026-07-03T17:59:59Z", // before the end
      canonicalFromMs(NOW + CONFIG.eventWindowMaxSeconds * 1000 + 1000), // past the window after now
      "2026-07-03", // not an instant
    ]
  ) {
    const res = await create(db, { ...ended, openUntil });
    assertEquals(res.status, 400, openUntil);
    assertEquals(await res.text(), "invalid openUntil", openUntil);
  }
  // The edges are admitted: exactly the end, and exactly the window after now.
  assertEquals((await create(db, { ...ended, openUntil: ended.endsAt })).status, 201);
  assertEquals(
    (await create(db, {
      ...ended,
      openUntil: canonicalFromMs(NOW + CONFIG.eventWindowMaxSeconds * 1000),
    })).status,
    201,
  );
  assertEquals((await rows(db, `SELECT * FROM events`)).length, 2);
  db.close();
});

Deno.test("create → an event ahead of now may stay open at most the window after its end", async () => {
  const db = await store();
  const future = { startsAt: STARTS_AT, endsAt: ENDS_AT };
  const max = canonicalFromMs(Date.parse(ENDS_AT) + CONFIG.eventWindowMaxSeconds * 1000);
  assertEquals((await create(db, { ...future, openUntil: max })).status, 201);
  const over = canonicalFromMs(Date.parse(ENDS_AT) + CONFIG.eventWindowMaxSeconds * 1000 + 1000);
  assertEquals((await create(db, { ...future, openUntil: over })).status, 400);
  db.close();
});

// ── PUT …/received ──────────────────────────────────────────────────────────────────────────────────

Deno.test("received → a member's position is recorded, and only ever raised", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  assertEquals((await app.request(JOIN(D), { method: "PUT" })).status, 200);
  const put = (position: unknown) =>
    app.request(RECEIVED(D), { method: "PUT", body: JSON.stringify({ position }) });
  assertEquals((await put(12)).status, 200);
  assertEquals((await put(4)).status, 200);
  assertEquals(
    (await rows(db, `SELECT received_position FROM memberships`))[0].received_position,
    12,
  );
  for (const bad of [-1, "7", 1.5, null]) {
    const res = await put(bad);
    assertEquals(res.status, 400, String(bad));
    assertEquals(await res.text(), "invalid position");
  }
  db.close();
});

Deno.test("received → a device that is not in the event is told so; another device's path is refused", async () => {
  const db = await storeWithEvent();
  const app = v2({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const body = JSON.stringify({ position: 3 });
  const res = await app.request(RECEIVED(D), { method: "PUT", body });
  assertEquals(res.status, 409);
  assertEquals(await res.text(), "not a member");
  // D's token naming D2's membership.
  assertEquals((await app.request(RECEIVED(D2), { method: "PUT", body })).status, 403);
  // A member that left no longer reports.
  await app.request(JOIN(D2), { method: "PUT", headers: await as(D2) });
  await app.request(JOIN(D2), { method: "DELETE", headers: await as(D2) });
  assertEquals(
    (await app.request(RECEIVED(D2), { method: "PUT", body, headers: await as(D2) })).status,
    409,
  );
  db.close();
});

Deno.test("received → an event that does not exist is 404", async () => {
  const db = await store();
  const res = await v2({ config: CONFIG, db, fetch: recorder().fetchImpl }).request(RECEIVED(D), {
    method: "PUT",
    body: JSON.stringify({ position: 1 }),
  });
  assertEquals(res.status, 404);
  assert((await rows(db, `SELECT * FROM memberships`)).length === 0);
  db.close();
});
