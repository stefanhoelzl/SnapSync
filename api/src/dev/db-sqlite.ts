// The `Db` port over Deno's built-in `node:sqlite` — the implementation the TESTS and the local rig run
// against (capability `database`). Dev infrastructure: `main.ts` never imports `src/dev/`, and
// `deno bundle` roots the deployed bundle at `main.ts`, so none of this can ship.
//
// WHY A REAL SQLITE RATHER THAN A FAKE. Every invariant this change moves into the database — the
// two-level cascade, the atomic publish, the exact capacity gate, `uploaded`'s monotonicity — is a
// property of SQL, not of our code. A hand-written in-memory double would assert our understanding of
// those statements rather than their behaviour, which is precisely the thing worth testing. `node:sqlite`
// is in-process: no network (the test task deliberately withholds `--allow-net`), no FFI flag, no server.
//
// It is NOT the deployed engine, so it settles semantics and not platform behaviour: whether bunny
// Database enforces foreign keys by default, accepts `STRICT`, or holds a batch atomic are questions only
// a measurement against it answers (`PROBE-FINDINGS.md`).

import { DatabaseSync } from "node:sqlite";
import type { Db, Row, Statement } from "../db.ts";

/**
 * Wrap an open `node:sqlite` handle as a `Db`.
 *
 * `node:sqlite` is synchronous; the port is async because the deployed driver is. The adapter therefore
 * resolves immediately rather than deferring — a test observes the same ordering the production driver
 * would impose, without a scheduler in between.
 */
export function sqliteDb(path = ":memory:"): Db & { close(): void } {
  const handle = new DatabaseSync(path);
  // Enforcement is ON in this build, but state it rather than inherit it: the deployed store is a
  // different engine, and a schema that relies on cascades must not depend on which one it meets.
  handle.exec("PRAGMA foreign_keys = ON");

  const run = (sql: string, args: unknown[]) => {
    const stmt = handle.prepare(sql);
    // `all()` is valid for a write too (it simply yields no rows), which keeps one code path for both.
    const rows = stmt.all(...(args as never[])) as unknown as Row[];
    const changes = handle.prepare("SELECT changes() AS c").get() as { c: number };
    return { rows, rowsAffected: Number(changes.c) };
  };

  const db: Db & { close(): void } = {
    execute(sql: string, args: unknown[] = []) {
      return Promise.resolve(run(sql, args));
    },
    async batch(statements: Statement[]) {
      handle.exec("BEGIN");
      try {
        for (const s of statements) run(s.sql, s.args ?? []);
        handle.exec("COMMIT");
      } catch (e) {
        handle.exec("ROLLBACK");
        throw e;
      }
      await Promise.resolve();
    },
    async transaction<T>(fn: (tx: Db) => Promise<T>): Promise<T> {
      handle.exec("BEGIN");
      try {
        const result = await fn(db);
        handle.exec("COMMIT");
        return result;
      } catch (e) {
        handle.exec("ROLLBACK");
        throw e;
      }
    },
    close() {
      handle.close();
    },
  };
  return db;
}
