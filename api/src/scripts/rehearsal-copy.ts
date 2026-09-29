// THE MIGRATION REHEARSAL'S COPY (`docs/deployment.md`, "Gates"): the deployed store, PSEUDONYMISED, into a
// local libSQL server, so api.yml's `migration-rehearsal` job can run the platform runner's real
// `bunny db migrations apply` over real rows before a migration change merges.
//
// WHAT IT CATCHES THAT NOTHING ELSE DOES. The tests replay migrations over fixtures; the deploy applies them
// over the real store, after merge, inside a maintenance window. Two failure classes live only in the second:
// ① HISTORY DRIFT — an applied file edited (measured: c19b6c4b retargeted comments in 0001–0003, and the next
// migration failed three deploys in a row) — which needs the store's recorded checksums, copied verbatim
// below; ② DATA — a narrowing precondition that aborts, a constraint the real rows violate, a rebuild that
// loses rows — which needs the real rows.
//
// PSEUDONYMISED, BECAUSE THE ROWS ARE SECRETS. Device ids, App Attest keys, push tokens, event ids (each one
// IS an event's upload capability), names, filenames. Every TEXT value is rewritten unless its column is on
// [VERBATIM] — structural values a migration may match on (`state`, `role`, timestamps), none of them
// secret. The list is an ALLOWLIST on purpose: a column a future migration adds is pseudonymised until
// someone decides otherwise, so the default can only err toward "rehearsal fails loudly", never "secret in a
// CI runner". Integers are copied as they are.
//
// The rewrite is a keyed, per-run, INJECTIVE token map (see [Pseudonymiser]), so what a migration can rely on
// survives it: equal values stay equal across tables (every foreign key and every id embedded in another
// value still joins), distinct values stay distinct (no primary key collides), and each token keeps its
// length and character class (a UUID is still a UUID-shaped string). The key is random per run and never
// leaves this process — the map cannot be re-derived, not even by the next run.
//
// NOTHING IS PRINTED BUT COUNTS, and nothing is written to disk. The copy goes store-to-store in memory;
// the one output line is counts; every error is reduced to one line with quoted literals stripped — an
// SQLite error quotes the failing statement, and an uncaught one printed production rows into a session
// once (2026-09-29), which is exactly the leak a CI log must not have.
//
// Out of the bundle: `main.ts` never reaches it.

import { type Client, createClient, type InStatement, type InValue } from "@libsql/client/web";

/**
 * The columns copied VERBATIM, per table. Everything else of type TEXT is pseudonymised — an unlisted table
 * or a newly added column included. Only list a column whose values are structural and public: an enum a
 * migration might match on, a timestamp, a declared-roles JSON array.
 */
export const VERBATIM: Readonly<Record<string, ReadonlySet<string>>> = {
  devices: new Set([
    "created_at",
    "attest_env",
    "attested_at",
    "attest_token_expires_at",
    "push_kind",
    "push_env",
    "push_updated_at",
  ]),
  events: new Set([
    "created_at",
    "starts_at",
    "ends_at",
    "closed_at",
    "completed_at",
    "last_landed_at",
  ]),
  memberships: new Set(["state", "joined_at"]),
  event_assets: new Set(["creation_date", "roles"]),
  resources: new Set(["role", "content_type"]),
};

/**
 * Tables copied whole. The platform runner's bookkeeping: its recorded names and checksums ARE what the
 * rehearsal checks the files against, so a single rewritten byte would read as drift on every run.
 */
export const VERBATIM_TABLES: ReadonlySet<string> = new Set(["__bunny_migrations"]);

const HEX = "0123456789abcdef";
const DIGITS = "0123456789";
const UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const LOWER = "abcdefghijklmnopqrstuvwxyz";

/** The alphabet a token's pseudonym is drawn from, per position: a class the original already belongs to. */
function alphabetsFor(token: string): string[] {
  if (/^[0-9]+$/.test(token)) return [...token].map(() => DIGITS);
  if (/^[0-9a-f]+$/.test(token)) return [...token].map(() => HEX);
  if (/^[0-9A-F]+$/.test(token)) return [...token].map(() => HEX.toUpperCase());
  return [...token].map((
    c,
  ) => (c >= "0" && c <= "9" ? DIGITS : c >= "A" && c <= "Z" ? UPPER : LOWER));
}

/**
 * A keyed, injective rewrite of TEXT values, token by token.
 *
 * A token is a maximal run of `[A-Za-z0-9]`; everything between tokens (`-`, `_`, `.`, `/`, `+`, spaces,
 * JSON punctuation) is kept, so a value keeps its shape. Each distinct token maps to one pseudonym for the
 * whole run — the same token inside an id and inside a key built from that id maps identically — and no two
 * tokens share one: a collision is resolved by re-deriving with a counter, so a primary key never collides.
 */
export class Pseudonymiser {
  readonly #key: CryptoKey;
  readonly #forward = new Map<string, string>();
  readonly #taken = new Set<string>();

  private constructor(key: CryptoKey) {
    this.#key = key;
  }

  /** A fresh map over [secret] — by default 32 random bytes that never leave this object. */
  static async create(
    secret: Uint8Array<ArrayBuffer> = crypto.getRandomValues(new Uint8Array(32)),
  ): Promise<Pseudonymiser> {
    const key = await crypto.subtle.importKey(
      "raw",
      secret,
      { name: "HMAC", hash: "SHA-256" },
      false,
      [
        "sign",
      ],
    );
    return new Pseudonymiser(key);
  }

  /** The pseudonym of [text]: every token rewritten, everything between tokens kept. */
  async value(text: string): Promise<string> {
    const parts = text.split(/([A-Za-z0-9]+)/);
    for (let i = 1; i < parts.length; i += 2) parts[i] = await this.#token(parts[i]);
    return parts.join("");
  }

  async #token(token: string): Promise<string> {
    const known = this.#forward.get(token);
    if (known !== undefined) return known;
    for (let attempt = 0;; attempt++) {
      const candidate = await this.#derive(token, attempt);
      if (this.#taken.has(candidate)) continue;
      this.#forward.set(token, candidate);
      this.#taken.add(candidate);
      return candidate;
    }
  }

  async #derive(token: string, attempt: number): Promise<string> {
    const alphabets = alphabetsFor(token);
    const bytes = await this.#stream(`${token}\u0000${attempt}`, alphabets.length);
    return alphabets.map((a, i) => a[bytes[i] % a.length]).join("");
  }

  /** [length] keyed bytes for [label]: HMAC blocks over (label, block index). */
  async #stream(label: string, length: number): Promise<Uint8Array> {
    const out = new Uint8Array(length);
    for (let block = 0; block * 32 < length; block++) {
      const mac = new Uint8Array(
        await crypto.subtle.sign(
          "HMAC",
          this.#key,
          new TextEncoder().encode(`${label}\u0000${block}`),
        ),
      );
      out.set(mac.subarray(0, Math.min(32, length - block * 32)), block * 32);
    }
    return out;
  }
}

/** Whether [column] of [table] is rewritten — see [VERBATIM]. */
export function isPseudonymised(table: string, column: string): boolean {
  if (VERBATIM_TABLES.has(table)) return false;
  return !(VERBATIM[table]?.has(column) ?? false);
}

/** One line, quoted literals removed: what an error may say in a CI log. See the header. */
export function safeMessage(e: unknown): string {
  const message = e instanceof Error ? e.message : String(e);
  return message.split("\n")[0].replace(/'[^']*'?/g, "'…'").replace(/"[^"]*"?/g, '"…"').slice(
    0,
    200,
  );
}

/** The target must be a loopback libSQL server: the copy writes, and it must never write anywhere real. */
export function isLoopbackTarget(url: string): boolean {
  try {
    const u = new URL(url);
    return (u.protocol === "http:" || u.protocol === "ws:") &&
      (u.hostname === "127.0.0.1" || u.hostname === "localhost" || u.hostname === "[::1]");
  } catch {
    return false;
  }
}

type SchemaObject = { type: string; name: string; sql: string };

/** Every schema object of [db] with DDL, tables first, then indexes, views and triggers, in creation order. */
async function schemaOf(db: Client): Promise<SchemaObject[]> {
  const { rows } = await db.execute(
    `SELECT type, name, sql FROM sqlite_master
      WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%'
      ORDER BY CASE type WHEN 'table' THEN 0 WHEN 'index' THEN 1 WHEN 'view' THEN 2 ELSE 3 END, rowid`,
  );
  return rows.map((r) => ({ type: String(r.type), name: String(r.name), sql: String(r.sql) }));
}

type TableCopy = { inserts: InStatement[]; rewritten: number };

/** [table]'s rows as parameterised INSERTs, each TEXT value pseudonymised unless its column is verbatim. */
async function copyOf(source: Client, table: string, p: Pseudonymiser): Promise<TableCopy> {
  const result = await source.execute(`SELECT * FROM "${table}"`);
  const columns = result.columns;
  const rewrite = columns.map((c) => isPseudonymised(table, c));
  const sql = `INSERT INTO "${table}" (${columns.map((c) => `"${c}"`).join(", ")}) VALUES (${
    columns.map(() => "?").join(", ")
  })`;
  const inserts: InStatement[] = [];
  let rewritten = 0;
  for (const row of result.rows) {
    const args: InValue[] = [];
    for (let i = 0; i < columns.length; i++) {
      const v = row[i] as InValue;
      if (rewrite[i] && typeof v === "string") {
        args.push(await p.value(v));
        rewritten++;
      } else args.push(v);
    }
    inserts.push({ sql, args });
  }
  return { inserts, rewritten };
}

async function countOf(db: Client, table: string): Promise<number> {
  return Number((await db.execute(`SELECT count(*) AS n FROM "${table}"`)).rows[0].n);
}

/**
 * Copy [source] into the empty [target]: schema, then rows, with foreign keys off (`migrate`), then check that
 * every table holds as many rows as its source. Returns the summary line.
 */
export async function copyStore(source: Client, target: Client, p: Pseudonymiser): Promise<string> {
  if ((await schemaOf(target)).length > 0) {
    throw new Error("the rehearsal target is not empty — refusing to copy");
  }
  const schema = await schemaOf(source);
  await target.migrate(schema.map((o) => o.sql));
  const tables = schema.filter((o) => o.type === "table").map((o) => o.name);
  let rows = 0;
  let rewritten = 0;
  for (const table of tables) {
    const copy = await copyOf(source, table, p);
    for (let i = 0; i < copy.inserts.length; i += 500) {
      await target.migrate(copy.inserts.slice(i, i + 500));
    }
    const [expected, actual] = [copy.inserts.length, await countOf(target, table)];
    if (expected !== actual) {
      throw new Error(`table ${table}: copied ${actual} of ${expected} rows`);
    }
    rows += expected;
    rewritten += copy.rewritten;
  }
  const others = schema.length - tables.length;
  return `REHEARSAL COPY: ${tables.length} tables, ${others} other objects, ${rows} rows — ${rewritten} values pseudonymised`;
}

if (import.meta.main) {
  const url = Deno.env.get("BUNNY_DATABASE_URL");
  const token = Deno.env.get("BUNNY_DATABASE_AUTH_TOKEN");
  const targetUrl = Deno.env.get("REHEARSAL_DATABASE_URL");
  if (!url || !token || !targetUrl) {
    console.error(
      "missing configuration: BUNNY_DATABASE_URL, BUNNY_DATABASE_AUTH_TOKEN, REHEARSAL_DATABASE_URL",
    );
    Deno.exit(1);
  }
  if (!isLoopbackTarget(targetUrl)) {
    console.error(
      "::error::REHEARSAL_DATABASE_URL must be a loopback http:// libSQL server — refusing to write",
    );
    Deno.exit(1);
  }
  try {
    const source = createClient({ url, authToken: token, intMode: "bigint" });
    const target = createClient({ url: targetUrl, intMode: "bigint" });
    console.log(await copyStore(source, target, await Pseudonymiser.create()));
  } catch (e) {
    console.error(`::error::the rehearsal copy failed: ${safeMessage(e)}`);
    Deno.exit(1);
  }
}
