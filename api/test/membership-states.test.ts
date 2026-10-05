// MIGRATION 0008 — a membership's `active`/`departed` + `final` become one state of four (capabilities
// `event-lifetime`, `manage-membership`). What is asserted is the BACKFILL: each old combination lands in the
// state that names it, and the rows that hang off a membership survive its rebuild.

import { assertEquals } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { discoverMigrations, MIGRATIONS_DIR, replay } from "../src/dev/replay.ts";

const E = "7a3f9c21-0000-4000-8000-000000000001";

Deno.test("0008 → active/final become sharing/settled; a departure is done only when settled and landed", async () => {
  const dir = await Deno.makeTempDir();
  try {
    const all = discoverMigrations();
    const before = all.filter((m) => m.name < "0008");
    for (const m of before) await Deno.copyFile(`${MIGRATIONS_DIR}/${m.name}`, `${dir}/${m.name}`);
    const db = sqliteDb(":memory:");
    await replay(db, dir);

    await db.execute(
      `INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds)
       VALUES (?, 'x', 'c', 's', 'e', 10, 1)`,
      [E],
    );
    const member = (d: string, state: string, final: number | null) =>
      db.execute(
        `INSERT INTO memberships (event_id, device_id, state, joined_at, manifest_version, final)
         VALUES (?, ?, ?, 'j', 7, ?)`,
        [E, d, state, final],
      );
    await member("sharing", "active", null);
    await member("settled", "active", 1);
    await member("done", "departed", 1);
    await member("left-unlanded", "departed", 1);
    await member("left-unsettled", "departed", 0);
    // Every member declares one asset of two roles; all but `left-unlanded` have both landed.
    for (const d of ["sharing", "settled", "done", "left-unlanded", "left-unsettled"]) {
      await db.execute(
        `INSERT INTO event_assets (event_id, device_id, asset_id, creation_date, roles)
         VALUES (?, ?, 'A', 'c', '["primary","live"]')`,
        [E, d],
      );
      for (const role of d === "left-unlanded" ? ["primary"] : ["primary", "live"]) {
        await db.execute(
          `INSERT INTO resources (device_id, asset_id, role, key, content_type, filename)
           VALUES (?, 'A', ?, ?, 't', 'f')`,
          [d, role, `A-${role}`],
        );
      }
    }

    const target = all.find((m) => m.name.startsWith("0008"))!;
    await Deno.copyFile(`${MIGRATIONS_DIR}/${target.name}`, `${dir}/${target.name}`);
    assertEquals(await replay(db, dir), [target.name]);

    const { rows } = await db.execute(
      `SELECT device_id, state, manifest_version, joined_at FROM memberships ORDER BY device_id`,
    );
    assertEquals(rows.map((r) => [r.device_id, r.state]), [
      ["done", "done"],
      ["left-unlanded", "left"],
      ["left-unsettled", "left"],
      ["settled", "settled"],
      ["sharing", "sharing"],
    ]);
    // The other columns are carried as they were.
    for (const r of rows) assertEquals([r.manifest_version, r.joined_at], [7, "j"]);
    // The rebuild cascaded nothing into the rows that hang off a membership.
    assertEquals((await db.execute(`SELECT * FROM event_assets`)).rows.length, 5);
    db.close();
  } finally {
    await Deno.remove(dir, { recursive: true });
  }
});
