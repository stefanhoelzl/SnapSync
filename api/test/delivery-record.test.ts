// The DELIVERY RECORD's facts the routes add to the request log (capability `privacy-security`; decision record
// `changes/delivery-record`, D2; `docs/architecture.md`, "The delivery record"): a byte upload's attempt and the
// phone's stage times, the landing that completes a settled device's share, a download's start, a receiver's report
// of what it downloaded and added, and an event's creation. The context every app request carries is
// `request-log.test.ts`'s; the union read's and the upload probe's are `union-read.test.ts`'s and
// `resumable-upload.test.ts`'s.
//
// v2 paths and bodies are literal here, like every version test file.

import { assert, assertEquals, assertMatch } from "@std/assert";
import {
  as,
  CONFIG,
  createApp,
  D,
  D2,
  E,
  lineSink,
  recorder,
  rows,
  store,
  storeWithEvent,
  VERSION_HEADER,
} from "./support/harness.ts";

const JOIN = (d: string) => `/api/v2/events/${E}/devices/${d}`;
const MANIFEST = (d: string) => `/api/v2/events/${E}/devices/${d}/manifest`;
const RECEIVED = (d: string) => `/api/v2/events/${E}/devices/${d}/received`;
const BYTES = (asset: string) =>
  `/api/v2/events/${E}/files/devices/${D}/${asset}/primary?filename=IMG_${asset}.HEIC`;
const REDIRECT = (asset: string) => `/api/v2/events/${E}/files/devices/${D}/${asset}/primary`;
// Ended, and open until its end, before the harness's NOW (2026-07-14T12:00Z): a member may settle.
const ENDED = { endsAt: "2026-07-10T00:00:00Z", openUntil: "2026-07-10T00:00:00Z" };

const asset = (id: string) => ({
  assetId: id,
  creationDate: "2026-07-01T00:00:00Z",
  resources: [{
    role: "primary",
    contentType: "image/heic",
    key: `${id}-primary.heic`,
    filename: `IMG_${id}.HEIC`,
  }],
});
const manifest = (ids: string[], final = false) =>
  JSON.stringify({ version: 0, final, assets: ids.map(asset) });

/** The real app over `db`, every request declaring a version; `lines` collects the record of each. */
function world(db: Awaited<ReturnType<typeof store>>) {
  const lines: string[] = [];
  const app = createApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    logSink: lineSink(lines),
  });
  const request = async (path: string, init: RequestInit = {}) => {
    const res = await app.request(path, {
      ...init,
      headers: { [VERSION_HEADER]: "0.1", ...(init.headers ?? {}) },
    });
    await res.arrayBuffer(); // drained, not cancelled: a cancelled body is recorded `cut=true`
    return res;
  };
  return { request, lines, last: () => lines.at(-1) ?? "" };
}

// ── the byte upload ───────────────────────────────────────────────────────────────────────────────

Deno.test("upload → the attempt, its media and the phone's stage times, each by the entry points that ran it; backlog when taken before the join", async () => {
  const db = await storeWithEvent();
  const w = world(db);
  await w.request(JOIN(D), { method: "PUT" });
  await w.request(MANIFEST(D), { method: "PUT", body: manifest(["A"]) });
  const res = await w.request(BYTES("A"), {
    method: "PUT",
    body: "b",
    headers: {
      "content-type": "image/heic",
      "content-length": "1",
      "x-snapsync-taken": "2026-07-01T09:00:00Z",
      "x-snapsync-discovered": "2026-07-14T11:58:00Z",
      "x-snapsync-discovered-by": "OnSelectionAnswerable+OnForeground",
      "x-snapsync-queued": "2026-07-14T11:58:01Z",
      "x-snapsync-queued-by": "OnForeground",
    },
  });
  assertEquals(res.status, 201);
  assertMatch(
    w.last(),
    / kind=fresh from=0 size=1 media=photo taken=2026-07-01T09:00:00Z discovered=2026-07-14T11:58:00Z discovered_by="OnSelectionAnswerable\+OnForeground" queued=2026-07-14T11:58:01Z queued_by=OnForeground backlog=true completed=1 /,
  );
  db.close();
});

Deno.test("upload → an unreadable stage time is dropped, never refused; a photo taken after the join is no backlog", async () => {
  const db = await storeWithEvent();
  const w = world(db);
  await w.request(JOIN(D), { method: "PUT" });
  const res = await w.request(BYTES("A"), {
    method: "PUT",
    body: "b",
    headers: {
      "content-type": "video/quicktime",
      "x-snapsync-taken": "2026-07-14T12:30:00Z",
      "x-snapsync-discovered": "soon",
      "x-snapsync-queued-by": "on foreground!",
    },
  });
  assertEquals(res.status, 201);
  assert(w.last().includes(" media=video taken=2026-07-14T12:30:00Z "), w.last());
  for (const absent of ["discovered=", "queued_by=", "backlog="]) {
    assert(!w.last().includes(absent), w.last());
  }
  db.close();
});

Deno.test("upload → the landing of a settled device's last declared photo records that it shared everything", async () => {
  const db = await storeWithEvent(ENDED);
  const w = world(db);
  await w.request(JOIN(D), { method: "PUT" });
  await w.request(MANIFEST(D), { method: "PUT", body: manifest(["A", "B"], true) });
  await w.request(BYTES("A"), { method: "PUT", body: "a" });
  assert(!w.last().includes("uploaded_all"), "one photo is still owed");
  await w.request(BYTES("B"), { method: "PUT", body: "b" });
  assert(w.last().endsWith(" uploaded_all=true"), w.last());
  db.close();
});

Deno.test("upload → a device still sharing never records that it shared everything", async () => {
  const db = await storeWithEvent();
  const w = world(db);
  await w.request(JOIN(D), { method: "PUT" });
  await w.request(MANIFEST(D), { method: "PUT", body: manifest(["A"]) });
  await w.request(BYTES("A"), { method: "PUT", body: "a" });
  assert(!w.last().includes("uploaded_all"), w.last());
  db.close();
});

// ── the download redirect: where a download starts ────────────────────────────────────────────────

/** D shared and uploaded A; D2 joined — at `joinedAt`, when given. */
async function shared(joinedAt?: string) {
  const db = await storeWithEvent();
  const w = world(db);
  await w.request(JOIN(D), { method: "PUT" });
  await w.request(MANIFEST(D), { method: "PUT", body: manifest(["A"]) });
  await w.request(BYTES("A"), { method: "PUT", body: "a" });
  await w.request(JOIN(D2), { method: "PUT", headers: await as(D2) });
  if (joinedAt) {
    await db.execute(`UPDATE memberships SET joined_at = ? WHERE device_id = ?`, [joinedAt, D2]);
  }
  return { db, w };
}

Deno.test("redirect → the receiver's queueing, and backlog when the photo landed before it joined", async () => {
  const { db, w } = await shared("2026-07-14T13:00:00.000Z");
  const res = await w.request(REDIRECT("A"), {
    headers: {
      "x-snapsync-context": `device=${D2}`,
      "x-snapsync-queued": "2026-07-14T13:05:00Z",
      "x-snapsync-queued-by": "OnSilentPush",
    },
  });
  assertEquals(res.status, 302);
  assertMatch(
    w.last(),
    new RegExp(
      ` 302 .* device=${D2} queued=2026-07-14T13:05:00Z queued_by=OnSilentPush backlog=true$`,
    ),
  );
  db.close();
});

Deno.test("redirect → a photo that landed after the receiver joined is no backlog; a browser names no one", async () => {
  const { db, w } = await shared("2026-07-14T11:00:00.000Z");
  await w.request(REDIRECT("A"), { headers: { "x-snapsync-context": `device=${D2}` } });
  assert(w.last().endsWith(" backlog=false"), w.last());
  await w.request(REDIRECT("A"));
  assertMatch(w.last(), / 302 \d+ms v=0.1 in=- out=0$/);
  db.close();
});

// ── the receiver's report ─────────────────────────────────────────────────────────────────────────

Deno.test("received → each photo's download and library times, by the entry points that ran them, and caught up at the union head", async () => {
  const { db, w } = await shared();
  const head = Number((await rows(db, `SELECT MAX(seq) AS s FROM union_log`))[0].s);
  const entry = {
    owner: D,
    asset: "A",
    downloaded: "2026-07-14T12:00:20Z",
    added: "2026-07-14T12:00:21Z",
    by: "OnDownloadStaged+OnDownloadSessionEvents",
  };
  const res = await w.request(RECEIVED(D2), {
    method: "PUT",
    headers: await as(D2),
    body: JSON.stringify({ position: head, delivered: [entry] }),
  });
  assertEquals(res.status, 200);
  assert(
    w.last().endsWith(` position=${head} delivered=${JSON.stringify(entry)} caught_up=true`),
    w.last(),
  );
  db.close();
});

Deno.test("received → a list that does not validate is dropped and the report still counts; behind the head is not caught up", async () => {
  const { db, w } = await shared();
  const res = await w.request(RECEIVED(D2), {
    method: "PUT",
    headers: await as(D2),
    body: JSON.stringify({ position: 0, delivered: [{ owner: "nope", asset: "A" }] }),
  });
  assertEquals(res.status, 200);
  assert(w.last().endsWith(" position=0"), w.last());
  assertEquals(
    (await rows(db, `SELECT received_position FROM memberships WHERE device_id = ?`, [D2]))[0],
    {
      received_position: 0,
    },
  );
  db.close();
});

Deno.test("received → an empty list records nothing; one over the report's bound is dropped whole, the report counts", async () => {
  const { db, w } = await shared();
  const report = async (delivered: unknown[]) =>
    (await w.request(RECEIVED(D2), {
      method: "PUT",
      headers: await as(D2),
      body: JSON.stringify({ position: 0, delivered }),
    })).status;
  assertEquals(await report([]), 200);
  assert(w.last().endsWith(" position=0"), w.last());
  const entry = { owner: D, asset: "A", added: "2026-07-14T12:00:21Z" };
  assertEquals(await report(Array.from({ length: 201 }, () => entry)), 200);
  assert(w.last().endsWith(" position=0"), w.last());
  assertEquals(await report(Array.from({ length: 200 }, () => entry)), 200);
  assertEquals(w.last().split(" delivered=").length - 1, 200);
  db.close();
});

// ── an event's creation ───────────────────────────────────────────────────────────────────────────

Deno.test("create → the new event, whether it is encrypted, its days, and whether it was created after its end", async () => {
  const db = await store();
  const w = world(db);
  await w.request("/api/v2/events", {
    method: "POST",
    body: JSON.stringify({
      name: "Party",
      startsAt: "2026-07-01T18:00:00Z",
      endsAt: "2026-07-04T18:00:00Z",
      openUntil: "2026-07-18T18:00:00Z",
    }),
  });
  assertMatch(
    w.last(),
    /^\S+ \[\w+\] POST .* 201 .* event=[0-9a-f-]{36} encrypted=false window_days=3 open_days=14 backdated=true$/,
  );
  db.close();
});
