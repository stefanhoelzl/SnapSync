// A STORE'S SCHEMA AS A COMPARABLE VALUE (capability `database`).
//
// Shared by the deploy-time assertion, which compares a LIVE store against what the migrations build.
// The two sides run on different engines — the deployed store is libSQL, a replay is `node:sqlite` — so
// what is compared has to be the SHAPE and not the spelling.
//
// WHAT IS COMPARED: the SQL text of every table and index, normalised. Text rather than
// `PRAGMA table_info`, because the pragma cannot see the two things most worth catching: `STRICT` (a
// table that silently coerces where its twin rejects) and a `FOREIGN KEY … ON DELETE CASCADE` clause (an
// event whose deletion strands its memberships).
//
// WHAT IS NORMALISED AWAY, and why each one is spelling rather than shape:
//   · `--` comments — the two forms are free to be annotated differently;
//   · `IF NOT EXISTS` — SQLite strips it when it stores a statement, so only one side ever carries it;
//   · double quotes — `ALTER TABLE … RENAME TO` rewrites `sqlite_master` with the identifier quoted, so
//     a tightening migration, which necessarily ends in a rename, would otherwise differ on quotes alone;
//   · whitespace runs — layout.
//
// EXCLUDED: `sqlite_%` (SQLite's own) and `__bunny_migrations` (the runner's bookkeeping, which exists on
// a migrated store and not in a freshly-replayed one, and is not part of the schema either form describes).

/** Every object worth comparing, in a stable order. */
export const SCHEMA_OBJECTS_QUERY = `SELECT name, sql FROM sqlite_master
   WHERE type IN ('table', 'index') AND sql IS NOT NULL
     AND name NOT LIKE 'sqlite_%' AND name <> '__bunny_migrations'
   ORDER BY name`;

/** One object's defining SQL, stripped of everything that is spelling rather than shape. */
export function normalizeSql(sql: string): string {
  return sql
    .replace(/--[^\n]*/g, "")
    .replace(/\bIF NOT EXISTS\b/gi, "")
    .replace(/"/g, "")
    .replace(/\s+/g, " ")
    // Space around punctuation, which stripping a trailing comment also manufactures. Applied to BOTH
    // sides, so it can only ever remove a difference that was spelling — it cannot hide one that is shape.
    .replace(/\s*([(),])\s*/g, "$1")
    .trim();
}

export type SchemaObject = { name: string; sql: string };

/** Read a store's comparable shape. Works against any `Db` — the live one or an in-memory replay. */
export async function shapeOf(
  db: { execute(sql: string, args?: unknown[]): Promise<{ rows: Record<string, unknown>[] }> },
): Promise<SchemaObject[]> {
  const { rows } = await db.execute(SCHEMA_OBJECTS_QUERY);
  return rows.map((r) => ({ name: String(r.name), sql: normalizeSql(String(r.sql ?? "")) }));
}

/** Every object that differs, as a readable report. Empty means the two shapes are identical. */
export function shapeDifferences(live: SchemaObject[], expected: SchemaObject[]): string[] {
  const out: string[] = [];
  const names = [...new Set([...live, ...expected].map((o) => o.name))].sort();
  for (const name of names) {
    const l = live.find((o) => o.name === name);
    const e = expected.find((o) => o.name === name);
    if (!l) {
      out.push(`${name}: the migrations build it, the deployed store does not have it`);
    } else if (!e) {
      out.push(`${name}: the deployed store has it, no migration builds it`);
    } else if (l.sql !== e.sql) {
      out.push(`${name}: definitions differ\n     live: ${l.sql}\n  expected: ${e.sql}`);
    }
  }
  return out;
}
