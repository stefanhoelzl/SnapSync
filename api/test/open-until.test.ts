// MIGRATION 0014 — an event is open until a date, and a device says what it has received (capability
// `event-lifetime`; decision record `changes/back-dated-events`, D1/D4). What is asserted is the BACKFILL: every
// existing event is open until its range end, a closed one keeps its close, and no membership has said anything.

import { assertEquals } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { discoverMigrations, MIGRATIONS_DIR, replay } from "../src/dev/replay.ts";

Deno.test("0014 → every existing event is open until its end; no membership has reported", async () => {
  const dir = await Deno.makeTempDir();
  try {
    const all = discoverMigrations();
    for (const m of all.filter((m) => m.name < "0014")) {
      await Deno.copyFile(`${MIGRATIONS_DIR}/${m.name}`, `${dir}/${m.name}`);
    }
    const db = sqliteDb(":memory:");
    await replay(db, dir);

    const event = (id: string, endsAt: string, closed: string | null) =>
      db.execute(
        `INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, closed_at)
         VALUES (?, 'x', '2026-06-01T00:00:00Z', '2026-06-01T00:00:00Z', ?, 10, 1, ?)`,
        [id, endsAt, closed],
      );
    await event("open", "2026-06-05T00:00:00Z", null);
    await event("closed", "2026-06-03T00:00:00Z", "2026-06-04T00:00:00Z");
    await db.execute(
      `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES ('open', 'd', 'sharing', 'j')`,
    );

    const target = all.find((m) => m.name.startsWith("0014"))!;
    await Deno.copyFile(`${MIGRATIONS_DIR}/${target.name}`, `${dir}/${target.name}`);
    assertEquals(await replay(db, dir), [target.name]);

    const { rows } = await db.execute(`SELECT id, open_until, closed_at FROM events ORDER BY id`);
    assertEquals(rows.map((r) => [r.id, r.open_until, r.closed_at]), [
      ["closed", "2026-06-03T00:00:00Z", "2026-06-04T00:00:00Z"],
      ["open", "2026-06-05T00:00:00Z", null],
    ]);
    const m = await db.execute(`SELECT received_position FROM memberships`);
    assertEquals(m.rows.map((r) => r.received_position), [null]);
    db.close();
  } finally {
    await Deno.remove(dir, { recursive: true });
  }
});
