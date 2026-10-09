// MIGRATION 0013 — every instant the backend serves is whole-second UTC (`api/openapi.yaml`, `Timestamp`).
// What is asserted is the REWRITE: a millisecond `toISOString()` value of `created_at`, `closed_at` and
// `completed_at` loses its fraction, and every other value — whole-second, NULL, or a shape the file does not
// recognise — is left exactly as it was.

import { assertEquals } from "@std/assert";
import { sqliteDb } from "../src/dev/db-sqlite.ts";
import { discoverMigrations, MIGRATIONS_DIR, replay } from "../src/dev/replay.ts";

Deno.test("0013 → a millisecond instant loses its fraction; any other value is left as it was", async () => {
  const dir = await Deno.makeTempDir();
  try {
    const all = discoverMigrations();
    for (const m of all.filter((m) => m.name < "0013")) {
      await Deno.copyFile(`${MIGRATIONS_DIR}/${m.name}`, `${dir}/${m.name}`);
    }
    const db = sqliteDb(":memory:");
    await replay(db, dir);

    const event = (id: string, created: string, closed: string | null, completed: string | null) =>
      db.execute(
        `INSERT INTO events (id, name, created_at, starts_at, ends_at, capacity, lifetime_seconds, closed_at,
                             completed_at)
         VALUES (?, 'x', ?, 's', 'e', 10, 1, ?, ?)`,
        [id, created, closed, completed],
      );
    await event(
      "millis",
      "2026-07-09T19:24:17.182Z",
      "2026-07-10T08:00:00.000Z",
      "2026-07-13T08:00:01.999Z",
    );
    await event("seconds", "2026-07-09T19:24:17Z", "2026-07-10T08:00:00Z", null);
    await event("other", "not a date", null, null);

    const target = all.find((m) => m.name.startsWith("0013"))!;
    await Deno.copyFile(`${MIGRATIONS_DIR}/${target.name}`, `${dir}/${target.name}`);
    assertEquals(await replay(db, dir), [target.name]);

    const { rows } = await db.execute(
      `SELECT id, created_at, closed_at, completed_at FROM events ORDER BY id`,
    );
    assertEquals(rows.map((r) => [r.id, r.created_at, r.closed_at, r.completed_at]), [
      ["millis", "2026-07-09T19:24:17Z", "2026-07-10T08:00:00Z", "2026-07-13T08:00:01Z"],
      ["other", "not a date", null, null],
      ["seconds", "2026-07-09T19:24:17Z", "2026-07-10T08:00:00Z", null],
    ]);
    db.close();
  } finally {
    await Deno.remove(dir, { recursive: true });
  }
});
