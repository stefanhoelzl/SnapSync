// MIGRATION 0010 — a resource belongs to ONE event (change `per-event-storage-layout`, D3): each
// device-scoped row becomes one row per event it belongs to — every event whose membership declares the
// asset, and the device's present membership — keeping its pre-0010 path, so no photo already shared stops
// being served and no byte moves.
import { assertEquals } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { discoverMigrations, MIGRATIONS_DIR, replay } from "../src/dev/replay.ts";

const E1 = "7a3f9c21-0000-4000-8000-000000000001"; // declares D's asset A; D left it
const E2 = "7a3f9c21-0000-4000-8000-000000000002"; // declares A too, and is D's present event
const E3 = "7a3f9c21-0000-4000-8000-000000000003"; // D2's present event, declaring nothing
const D = "11111111-0000-4000-8000-000000000001";
const D2 = "22222222-0000-4000-8000-000000000002";
const D3 = "33333333-0000-4000-8000-000000000003"; // in no event at all

Deno.test("0010 → each resource row is expanded to the events it belongs to, keeping its old path", async () => {
  const dir = await Deno.makeTempDir();
  try {
    const all = discoverMigrations();
    for (const m of all.filter((m) => m.name < "0010")) {
      await Deno.copyFile(`${MIGRATIONS_DIR}/${m.name}`, `${dir}/${m.name}`);
    }
    const db = sqliteDb(":memory:");
    await replay(db, dir);

    for (const e of [E1, E2, E3]) {
      await db.execute(
        `INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds)
         VALUES (?, 'x', 'c', 's', 'e', 10, 1)`,
        [e],
      );
    }
    const member = (e: string, d: string, state: string) =>
      db.execute(
        `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES (?, ?, ?, 'j')`,
        [e, d, state],
      );
    await member(E1, D, "left");
    await member(E2, D, "sharing");
    await member(E3, D2, "settled");
    for (const e of [E1, E2]) {
      await db.execute(
        `INSERT INTO event_assets (event_id, device_id, asset_id, creation_date, roles)
         VALUES (?, ?, 'A', 'c', '["primary","live"]')`,
        [e, D],
      );
    }
    const resource = (d: string, asset: string, role: string) =>
      db.execute(
        `INSERT INTO resources (device_id, asset_id, role, key, content_type, filename)
         VALUES (?, ?, ?, ?, 'image/heic', ?)`,
        [d, asset, role, `${asset}-${role}.heic`, `IMG_${asset}.HEIC`],
      );
    await resource(D, "A", "primary");
    await resource(D, "A", "live");
    await resource(D, "B", "primary"); // declared nowhere: the present membership keeps it (bytes first)
    await resource(D2, "C", "primary"); // declared nowhere: D2's present event keeps it
    await resource(D3, "X", "primary"); // in no event: dropped

    const target = all.find((m) => m.name.startsWith("0010"))!;
    await Deno.copyFile(`${MIGRATIONS_DIR}/${target.name}`, `${dir}/${target.name}`);
    assertEquals(await replay(db, dir), [target.name]);

    const rows = (await db.execute(
      `SELECT event_id, device_id, asset_id, role, path FROM resources
        ORDER BY event_id, device_id, asset_id, role`,
    )).rows;
    const path = (d: string, asset: string, role: string) =>
      `files/devices/${d}/${asset}-${role}.heic`;
    assertEquals(rows, [
      // E1 declared A, and keeps serving it though D left.
      { event_id: E1, device_id: D, asset_id: "A", role: "live", path: path(D, "A", "live") },
      { event_id: E1, device_id: D, asset_id: "A", role: "primary", path: path(D, "A", "primary") },
      // E2 both declared A and is D's present event — one row each, never two.
      { event_id: E2, device_id: D, asset_id: "A", role: "live", path: path(D, "A", "live") },
      { event_id: E2, device_id: D, asset_id: "A", role: "primary", path: path(D, "A", "primary") },
      { event_id: E2, device_id: D, asset_id: "B", role: "primary", path: path(D, "B", "primary") },
      {
        event_id: E3,
        device_id: D2,
        asset_id: "C",
        role: "primary",
        path: path(D2, "C", "primary"),
      },
    ]);
    db.close();
  } finally {
    await Deno.remove(dir, { recursive: true });
  }
});

Deno.test("0010 → a resource is deleted with its membership", async () => {
  const db = sqliteDb(":memory:");
  await replay(db);
  await db.execute(
    `INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds)
     VALUES (?, 'x', 'c', 's', 'e', 10, 1)`,
    [E1],
  );
  await db.execute(
    `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES (?, ?, 'sharing', 'j')`,
    [E1, D],
  );
  await db.execute(
    `INSERT INTO resources (event_id, device_id, asset_id, role, path, content_type, filename)
     VALUES (?, ?, 'A', 'primary', 'files/x/y', 'image/heic', 'IMG.HEIC')`,
    [E1, D],
  );
  await db.execute(`DELETE FROM memberships WHERE event_id = ?`, [E1]);
  assertEquals((await db.execute(`SELECT * FROM resources`)).rows, []);
  db.close();
});
