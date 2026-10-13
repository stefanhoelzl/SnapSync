// The union read's DECLARED total (capability `sync-status`, change `count-declared-photos`): the
// `SnapSync-Declared` header every union answer carries — every asset the whole union serves, plus every asset a
// member still in the event declares that is not complete yet. What a member's "received" total counts.

import { assertEquals } from "@std/assert";
import type { Db } from "../src/db.ts";
import {
  as,
  CONFIG,
  createApp,
  createRealApp,
  D,
  D2,
  E,
  lineSink,
  NOW,
  recorder,
  storeWithEvent,
  VERSION_HEADER,
} from "./support/harness.ts";

const JOIN = (d: string) => `/api/v2/events/${E}/devices/${d}`;
const MANIFEST = (d: string) => `/api/v2/events/${E}/devices/${d}/manifest`;
const UNION = `/api/v2/events/${E}/files`;

const asset = (id: string) => ({
  assetId: id,
  creationDate: "2026-07-01T00:00:00Z",
  resources: [{
    role: "primary",
    contentType: "image/heic",
    key: `${id}-primary`,
    filename: `IMG_${id}.HEIC`,
  }],
});

/** D and D2 joined; each device's requests carry its own identity. */
async function twoMembers() {
  const db = await storeWithEvent();
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  const send = async (device: string, path: string, init: RequestInit = {}) =>
    app.request(path, {
      ...init,
      headers: {
        [VERSION_HEADER]: "0.1",
        ...(device === D ? {} : await as(device)),
        ...(init.headers ?? {}),
      },
    });
  await send(D, JOIN(D), { method: "PUT" });
  await send(D2, JOIN(D2), { method: "PUT" });
  let version = 0;
  const declare = (device: string, ids: string[]) =>
    send(device, MANIFEST(device), {
      method: "PUT",
      body: JSON.stringify({ version: version++, final: false, assets: ids.map(asset) }),
    });
  const land = (device: string, id: string) =>
    send(device, `/api/v2/files/devices/${device}/${id}/primary?filename=IMG_${id}.HEIC`, {
      method: "PUT",
      body: "b",
    });
  const leave = (device: string) => send(device, JOIN(device), { method: "DELETE" });
  return { db, declare, land, leave };
}

/** The union as an anonymous reader reads it: no token. */
const read = async (db: Db, query = "") => {
  const res = await createRealApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    now: () => NOW,
    buildSha: "x",
    logSink: lineSink([]),
  }).request(`${UNION}${query}`, { headers: { [VERSION_HEADER]: "0.1" } });
  await res.body?.cancel();
  return {
    declared: res.headers.get("SnapSync-Declared"),
    cursor: res.headers.get("SnapSync-Cursor"),
  };
};

Deno.test("declared → every declared asset counts, landed or not", async () => {
  const { db, declare, land } = await twoMembers();
  await declare(D2, ["A", "B", "C"]);
  await land(D2, "A");
  assertEquals((await read(db)).declared, "3");
  db.close();
});

Deno.test("declared → the reader's own declaration is in it too (the device subtracts its own)", async () => {
  const { db, declare, land } = await twoMembers();
  await declare(D, ["X", "Y"]);
  await declare(D2, ["A"]);
  await land(D2, "A");
  assertEquals((await read(db)).declared, "3");
  db.close();
});

Deno.test("declared → a departed member keeps what landed and drops what never will", async () => {
  const { db, declare, land, leave } = await twoMembers();
  await declare(D2, ["A", "B", "C"]);
  await land(D2, "A");
  await leave(D2);
  assertEquals((await read(db)).declared, "1");
  db.close();
});

Deno.test("declared → a withdrawn declaration drops out", async () => {
  const { db, declare } = await twoMembers();
  await declare(D2, ["A", "B"]);
  await declare(D2, ["A"]);
  assertEquals((await read(db)).declared, "1");
  db.close();
});

Deno.test("declared → the same on a delta read as on a full one", async () => {
  const { db, declare, land } = await twoMembers();
  await declare(D2, ["A", "B", "C"]);
  await land(D2, "A");
  const full = await read(db);
  await land(D2, "B");
  const delta = await read(db, `?cursor=${full.cursor}`);
  assertEquals(full.declared, "3");
  assertEquals(delta.declared, "3");
  db.close();
});

Deno.test("declared → an event with nothing declared answers 0", async () => {
  const { db } = await twoMembers();
  assertEquals((await read(db)).declared, "0");
  db.close();
});
