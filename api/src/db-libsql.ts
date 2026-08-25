// The DEPLOYED store's driver (capability `database`): the `Db` port over bunny Database's libSQL HTTP
// API. Imported ONLY by `main.ts`, so it is the sole module that speaks to a remote database.
//
// The `/web` entry point is deliberate: it is the fetch-based build, with no Node filesystem or socket
// dependency, which is what makes it linkable in an Edge Script at all.
//
// The local rig and the tests use a different implementation over `node:sqlite` (`src/dev/db-sqlite.ts`)
// — same port, no network, no credential. That split is what lets the whole relational layer be tested
// under `deno task test`, whose deliberate ABSENCE of `--allow-net` is the guarantee that no test can
// reach a live store.

import { createClient } from "@libsql/client/web";
import type { Db, Row, Statement } from "./db.ts";

/**
 * Build the deployed `Db`. Constructing the client performs no I/O, so a bad URL surfaces on the first
 * statement — which the boot probe issues before the deploy is called green (capability
 * `backend-deployment`).
 */
export function libsqlDb(url: string, authToken: string): Db {
  const client = createClient({ url, authToken });

  // libSQL's `Transaction` carries the same `execute`/`batch` surface as the client, so one adapter
  // serves both the connection and an open transaction.
  const wrap = (
    // deno-lint-ignore no-explicit-any
    handle: any,
    onTransaction: <T>(fn: (tx: Db) => Promise<T>) => Promise<T>,
  ): Db => ({
    async execute(sql: string, args: unknown[] = []) {
      const r = await handle.execute({ sql, args });
      return { rows: r.rows as unknown as Row[], rowsAffected: Number(r.rowsAffected ?? 0) };
    },
    async batch(statements: Statement[]) {
      // "write" is libSQL's transactional batch mode: the whole array applies or none of it does.
      await handle.batch(statements.map((s) => ({ sql: s.sql, args: s.args ?? [] })), "write");
    },
    transaction: onTransaction,
    async foreignKeysEnabled() {
      const r = await handle.execute({ sql: "PRAGMA foreign_keys", args: [] });
      const row = r.rows[0] as Row | undefined;
      return row !== undefined && Number(Object.values(row)[0]) === 1;
    },
  });

  const runTransaction = async <T>(fn: (tx: Db) => Promise<T>): Promise<T> => {
    const tx = await client.transaction("write");
    try {
      // A nested transaction would deadlock on the same baton, so the inner handle refuses one rather
      // than opening a second: an interactive session is a resource, not a re-entrant helper.
      const result = await fn(wrap(tx, () => {
        throw new Error("nested transaction");
      }));
      await tx.commit();
      return result;
    } catch (e) {
      await tx.rollback().catch(() => {});
      throw e;
    }
  };

  return wrap(client, runTransaction);
}
