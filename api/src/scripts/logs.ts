// deno-lint-ignore-file no-console -- a command-line tool, never part of the edge script; its console is its interface.
// The api's request log, read (`docs/deployment.md`, "Reading the api's log"; dev infra, non-gating).
//
//   secrets-env -- deno task logs                          the last 24 h, oldest first, as console lines
//   secrets-env -- deno task logs --status 5xx --since 7d  filtered (all filters combine with AND)
//   secrets-env -- deno task logs --stats route            per-endpoint counts, status classes, latency, bytes
//   secrets-env -- deno task logs --live                   the script's console, streamed (bunny's last 100 lines first)
//   deno task logs --db .localstore/api.db                 a local rig's store instead of the deployed one
//
// THE STORE IS THE HISTORY. bunny keeps a ring of the script's last 100 console lines and nothing older, so
// every request is also a `request_log` row (migration 0012, kept 30 days). This reads them with the
// read-only token (`BUNNY_DB_URL` + `BUNNY_DB_READONLY_TOKEN`, as `.secrets.yaml` maps them). `--live` reads
// the console instead, through the dashboard's undocumented websocket with the account key
// (`BUNNY_API_KEY`) — found by reading the dashboard's bundle (2026-10-05); a 403 or an empty answer is the
// first sign it changed.

import type { Db } from "../db.ts";
import { libsqlDb } from "../db-libsql.ts";
import { ERROR_TAGS, type ErrorTag, formatLine } from "../request-log.ts";
import { fromRow, type StoredRecord } from "../request-log-store.ts";

const USAGE = `deno task logs [filters] [--stats <by>] [--json] [--limit <n>] [--db <file>] | --live

filters (AND):
  --since <30m|2h|7d|2026-10-07|ISO>   default 24h      --until <same>
  --status <502|5xx|4xx|2xx>          --method <GET>    --path <substring>    --route <pattern>
  --event <id>   --device <id>        (matched in the URL and the fields)
  --reqid <id>   --v <app version>    --error <tag>     --errors (any)        --slow <ms>
stats:  --stats <route|hour|day|error|status|version>
tags:   ${ERROR_TAGS.join(" ")}`;

/** What to read. */
export type Query = {
  since: string;
  until?: string;
  status?: string;
  method?: string;
  path?: string;
  route?: string;
  event?: string;
  device?: string;
  reqid?: string;
  version?: string;
  error?: ErrorTag;
  errors: boolean;
  slow?: number;
};

export const STATS_BY = ["route", "hour", "day", "error", "status", "version"] as const;
export type StatsBy = typeof STATS_BY[number];

/** A whole command line, parsed. */
export type Options = {
  query: Query;
  stats?: StatsBy;
  json: boolean;
  limit: number;
  db?: string;
  live: boolean;
  help: boolean;
};

const FLAGS = new Set(["--errors", "--json", "--live", "--help"]);

/** `2h` → that long before `now`; anything else is a date or an instant. */
export function instant(value: string, now: number): string {
  const ago = value.match(/^(\d+)(m|h|d)$/);
  if (ago) {
    const unit = { m: 60_000, h: 3_600_000, d: 86_400_000 }[ago[2] as "m" | "h" | "d"];
    return new Date(now - Number(ago[1]) * unit).toISOString();
  }
  const at = Date.parse(/^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00Z` : value);
  if (Number.isNaN(at)) throw new Error(`not a time: ${value} (30m, 2h, 7d, a date or an instant)`);
  return new Date(at).toISOString();
}

/** `--key value` and `--key=value` into a map, every key checked against what the tool knows. */
function pairs(args: readonly string[]): Map<string, string> {
  const known = new Set([
    ...FLAGS,
    ...[
      "since",
      "until",
      "status",
      "method",
      "path",
      "route",
      "event",
      "device",
      "reqid",
      "v",
      "error",
      "slow",
      "stats",
      "limit",
      "db",
    ].map((k) => `--${k}`),
  ]);
  const out = new Map<string, string>();
  for (let i = 0; i < args.length; i++) {
    const [key, inline] = args[i].split(/=(.*)/s, 2);
    if (!known.has(key)) throw new Error(`unknown option: ${args[i]}\n\n${USAGE}`);
    if (FLAGS.has(key)) out.set(key, "true");
    else if (inline !== undefined) out.set(key, inline);
    else if (i + 1 < args.length) out.set(key, args[++i]);
    else throw new Error(`${key} needs a value`);
  }
  return out;
}

/** The command line, parsed and checked. */
export function parseOptions(args: readonly string[], now: number): Options {
  const p = pairs(args);
  const error = p.get("--error");
  if (error !== undefined && !(ERROR_TAGS as readonly string[]).includes(error)) {
    throw new Error(`unknown error tag: ${error} (one of: ${ERROR_TAGS.join(" ")})`);
  }
  const stats = p.get("--stats");
  if (stats !== undefined && !(STATS_BY as readonly string[]).includes(stats)) {
    throw new Error(`--stats takes one of: ${STATS_BY.join(" ")}`);
  }
  const status = p.get("--status");
  if (status !== undefined && !/^(\d{3}|[1-5]xx)$/.test(status)) {
    throw new Error(`--status takes a code or a class: 502, 5xx`);
  }
  const until = p.get("--until");
  const slow = p.get("--slow");
  return {
    query: {
      since: instant(p.get("--since") ?? "24h", now),
      until: until === undefined ? undefined : instant(until, now),
      status,
      method: p.get("--method")?.toUpperCase(),
      path: p.get("--path"),
      route: p.get("--route"),
      event: p.get("--event"),
      device: p.get("--device"),
      reqid: p.get("--reqid"),
      version: p.get("--v"),
      error: error as ErrorTag | undefined,
      errors: p.has("--errors"),
      slow: slow === undefined ? undefined : Number(slow),
    },
    stats: stats as StatsBy | undefined,
    json: p.has("--json"),
    limit: Number(p.get("--limit") ?? 1000),
    db: p.get("--db"),
    live: p.has("--live"),
    help: p.has("--help"),
  };
}

/** The query's SQL filter. `--route` is applied after the read, on the route derived from each URL. */
export function whereOf(q: Query): { sql: string; args: unknown[] } {
  const clauses: string[] = ["at >= ?"];
  const args: unknown[] = [q.since];
  const add = (clause: string, ...values: unknown[]) => {
    clauses.push(clause);
    args.push(...values);
  };
  if (q.until !== undefined) add("at < ?", q.until);
  if (q.status !== undefined) {
    if (q.status.endsWith("xx")) {
      const base = Number(q.status[0]) * 100;
      add("status BETWEEN ? AND ?", base, base + 99);
    } else add("status = ?", Number(q.status));
  }
  if (q.method !== undefined) add("method = ?", q.method);
  if (q.path !== undefined) add("instr(url, ?) > 0", q.path);
  for (const id of [q.event, q.device]) {
    if (id !== undefined) add("(instr(url, ?) > 0 OR instr(fields, ?) > 0)", id, id);
  }
  if (q.reqid !== undefined) add("reqid = ?", q.reqid);
  if (q.version !== undefined) add("version = ?", q.version);
  if (q.error !== undefined) add("instr(' ' || errors || ' ', ?) > 0", ` ${q.error} `);
  if (q.errors) add("errors IS NOT NULL");
  if (q.slow !== undefined) add("ms >= ?", q.slow);
  return { sql: clauses.join(" AND "), args };
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The route a URL was served by, derived: every UUID becomes `:id`, the byte routes' asset and role
 * `:asset/:role`, and a site asset `/_astro/*`. Close to the route patterns without the tool knowing them.
 */
export function routeOf(url: string): string {
  const path = new URL(url).pathname;
  if (path.startsWith("/_astro/")) return "/_astro/*";
  const parts = path.split("/").map((s) => (UUID.test(s) ? ":id" : s));
  const device = parts.findIndex((p, i) =>
    p === ":id" && parts[i - 1] === "devices" && parts[i - 2] === "files"
  );
  if (device >= 0 && parts.length === device + 3) parts.splice(device + 1, 2, ":asset", ":role");
  return parts.join("/") || "/";
}

/** The rows of the window, oldest first: all of them for statistics, else the newest `limit`. */
export async function readRecords(db: Db, q: Query, limit?: number): Promise<StoredRecord[]> {
  const where = whereOf(q);
  const { rows } = await db.execute(
    `SELECT * FROM request_log WHERE ${where.sql} ORDER BY at DESC, id DESC${
      limit === undefined ? "" : " LIMIT ?"
    }`,
    limit === undefined ? where.args : [...where.args, limit],
  );
  const records = rows.map(fromRow).reverse();
  return q.route === undefined ? records : records.filter((r) => routeOf(r.url) === q.route);
}

/** One group of `--stats`. */
export type StatsRow = {
  key: string;
  count: number;
  ok: number;
  clientErrors: number;
  serverErrors: number;
  p50: number;
  p95: number;
  max: number;
  bytesIn: number;
  bytesOut: number;
  /** For `--stats error`: the routes the tag fired on, most first. */
  routes?: string[];
};

/** The `p`-th percentile of ascending `sorted`, nearest rank. */
export function percentile(sorted: readonly number[], p: number): number {
  if (sorted.length === 0) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

/** The keys a record counts under: one per group, several for `error`. */
function keysOf(r: StoredRecord, by: StatsBy): string[] {
  switch (by) {
    case "route":
      return [`${r.method} ${routeOf(r.url)}`];
    case "hour":
      return [`${r.at.toISOString().slice(0, 13)}:00`];
    case "day":
      return [r.at.toISOString().slice(0, 10)];
    case "error":
      return r.errors;
    case "status":
      return [String(r.status)];
    case "version":
      return [r.version ?? "-"];
  }
}

/** Group `records` by `by`; time groups in time order, every other by count, most first. */
export function stats(records: readonly StoredRecord[], by: StatsBy): StatsRow[] {
  const groups = new Map<string, StoredRecord[]>();
  for (const r of records) {
    for (const key of keysOf(r, by)) groups.set(key, [...(groups.get(key) ?? []), r]);
  }
  const rows = [...groups].map(([key, rs]) => {
    const ms = rs.map((r) => r.ms).sort((a, b) => a - b);
    const row: StatsRow = {
      key,
      count: rs.length,
      ok: rs.filter((r) => r.status < 400).length,
      clientErrors: rs.filter((r) => r.status >= 400 && r.status < 500).length,
      serverErrors: rs.filter((r) => r.status >= 500).length,
      p50: percentile(ms, 50),
      p95: percentile(ms, 95),
      max: ms.at(-1) ?? 0,
      bytesIn: rs.reduce((n, r) => n + (r.bytesIn ?? 0), 0),
      bytesOut: rs.reduce((n, r) => n + r.bytesOut, 0),
    };
    if (by === "error") row.routes = topRoutes(rs);
    return row;
  });
  return by === "hour" || by === "day"
    ? rows.sort((a, b) => a.key.localeCompare(b.key))
    : rows.sort((a, b) => b.count - a.count || a.key.localeCompare(b.key));
}

/** The routes of `records`, most first, each with its count. */
function topRoutes(records: readonly StoredRecord[]): string[] {
  const counts = new Map<string, number>();
  for (const r of records) {
    const route = `${r.method} ${routeOf(r.url)}`;
    counts.set(route, (counts.get(route) ?? 0) + 1);
  }
  return [...counts].sort((a, b) => b[1] - a[1]).map(([route, n]) => `${route} (${n})`);
}

/** A byte count, short. */
function bytes(n: number): string {
  if (n < 1024) return `${n}B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}K`;
  return `${(n / 1024 / 1024).toFixed(1)}M`;
}

/** `--stats` as an aligned table. */
export function formatStats(rows: readonly StatsRow[], by: StatsBy): string {
  const header = [by, "count", "2xx/3xx", "4xx", "5xx", "p50ms", "p95ms", "maxms", "in", "out"];
  const body = rows.map((r) => [
    r.key,
    String(r.count),
    String(r.ok),
    String(r.clientErrors),
    String(r.serverErrors),
    String(r.p50),
    String(r.p95),
    String(r.max),
    bytes(r.bytesIn),
    bytes(r.bytesOut),
  ]);
  const widths = header.map((h, i) => Math.max(h.length, ...body.map((b) => b[i].length)));
  const line = (cells: string[]) =>
    cells.map((c, i) => (i === 0 ? c.padEnd(widths[i]) : c.padStart(widths[i]))).join("  ");
  const out = [line(header), ...body.map(line)];
  for (const r of rows) if (r.routes) out.push(`  ${r.key}: ${r.routes.join(", ")}`);
  return out.join("\n");
}

// ── --live: the script's console, through the dashboard's websocket ─────────────────────────────────

const API = "https://api.bunny.net";
const SCRIPT_NAME = "snap-sync";
const QUIET_MS = 5000; // without a message for this long, the history replay is complete

type LiveRecord = { timestamp: number; log: string; labels?: { ServerZone?: string } };

async function scriptId(key: string): Promise<number> {
  const res = await fetch(`${API}/compute/script?page=1&perPage=1000`, {
    headers: { AccessKey: key },
  });
  if (!res.ok) throw new Error(`bunny answered ${res.status} listing the scripts`);
  const items = (await res.json()).Items as { Id: number; Name: string; Deleted?: boolean }[];
  const ids = items.filter((s) => s.Name === SCRIPT_NAME && !s.Deleted).map((s) => s.Id);
  if (ids.length !== 1) {
    throw new Error(`expected one script named ${SCRIPT_NAME}, found ${ids.length}`);
  }
  return ids[0];
}

function renderLive(r: LiveRecord): string {
  const inner = JSON.parse(r.log) as { level: string; script_version: string; message: string };
  const at = new Date(r.timestamp).toISOString().slice(0, 19).replace("T", " ");
  const zone = (r.labels?.ServerZone ?? "").padEnd(3);
  return `${at} ${zone} ${
    inner.level.padEnd(5)
  } v${inner.script_version} ${inner.message.trimEnd()}`;
}

/** The retained lines in time order (the replay arrives newest first), then every new one until ^C. */
async function live(key: string): Promise<void> {
  const url = `wss://scripting-logging.bunny.net/${await scriptId(key)}?history=100`;
  // @ts-ignore: Deno's WebSocket takes headers, which the DOM type does not declare.
  const ws = new WebSocket(url, { headers: { AccessKey: key } });
  const history: LiveRecord[] = [];
  let replaying = true;
  let quiet = setTimeout(flush, QUIET_MS);
  function flush() {
    replaying = false;
    for (const r of history.sort((a, b) => a.timestamp - b.timestamp)) console.log(renderLive(r));
    console.error(`logs: ${history.length} retained line(s) — following, ^C to stop`);
  }
  ws.onmessage = (e) => {
    const r = JSON.parse(String(e.data)) as LiveRecord;
    if (!replaying) return console.log(renderLive(r));
    history.push(r);
    clearTimeout(quiet);
    quiet = setTimeout(flush, QUIET_MS);
  };
  await new Promise<void>((resolve, reject) => {
    ws.onclose = () => resolve();
    ws.onerror = (e) => reject(new Error(`the log websocket failed: ${(e as ErrorEvent).message}`));
  });
}

// ── Entry point ───────────────────────────────────────────────────────────────────────────────────

/** The store to read: a rig's SQLite file, or the deployed one over the read-only token. */
async function openStore(file: string | undefined): Promise<Db> {
  if (file !== undefined) {
    const { sqliteDb } = await import("../dev/db-sqlite.ts");
    return sqliteDb(file);
  }
  const url = Deno.env.get("BUNNY_DB_URL") ?? Deno.env.get("BUNNY_DATABASE_URL");
  const token = Deno.env.get("BUNNY_DB_READONLY_TOKEN") ??
    Deno.env.get("BUNNY_DATABASE_AUTH_TOKEN");
  if (!url || !token) {
    throw new Error(
      "no store: run under secrets-env (BUNNY_DB_URL + BUNNY_DB_READONLY_TOKEN), or pass --db",
    );
  }
  return libsqlDb(url, token);
}

async function main(args: string[]): Promise<void> {
  const o = parseOptions(args, Date.now());
  if (o.help) return console.log(USAGE);
  if (o.live) {
    const key = Deno.env.get("BUNNY_API_KEY");
    if (!key) throw new Error("--live needs BUNNY_API_KEY: run under secrets-env");
    return await live(key);
  }
  const db = await openStore(o.db);
  if (o.stats !== undefined) {
    const records = await readRecords(db, o.query);
    console.log(formatStats(stats(records, o.stats), o.stats));
    return console.error(`logs: ${records.length} request(s) since ${o.query.since}`);
  }
  const records = await readRecords(db, o.query, o.limit);
  for (const r of records) console.log(o.json ? JSON.stringify(r) : formatLine(r));
  console.error(
    `logs: ${records.length} request(s) since ${o.query.since}${
      records.length === o.limit ? ` (the newest ${o.limit}; --limit for more)` : ""
    }`,
  );
}

if (import.meta.main) {
  try {
    await main(Deno.args);
  } catch (e) {
    console.error(`logs: ${e instanceof Error ? e.message : e}`);
    Deno.exit(1);
  }
}
