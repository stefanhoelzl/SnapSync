// The request log, KEPT (`docs/architecture.md`, "The request log"; migration 0012): every request's record
// as one `request_log` row beside its console line, because bunny keeps only the script's last 100 lines.
// The edge writes through {@link storeSink}; the nightly sweep ages rows out and reports a day's 5xx rows;
// `deno task logs` reads them.

import type { Db, Row } from "./db.ts";
import {
  ERROR_TAGS,
  type ErrorTag,
  type Fields,
  formatLine,
  type LogSink,
  type RequestRecord,
  writeLine,
} from "./request-log.ts";

/** How long a row is kept. The nightly sweep deletes older ones. */
export const REQUEST_LOG_RETENTION_DAYS = 30;

const COLUMNS = "at, reqid, method, url, status, ms, version, bytes_in, bytes_out, fields, errors";

/** Keep one record. */
export async function insertRequestRecord(db: Db, r: RequestRecord): Promise<void> {
  await db.execute(
    `INSERT INTO request_log (${COLUMNS}) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    [
      r.at.toISOString(),
      r.reqid,
      r.method,
      r.url,
      r.status,
      r.ms,
      r.version ?? null,
      r.bytesIn ?? null,
      r.bytesOut,
      JSON.stringify(r.fields),
      r.errors.length > 0 ? r.errors.join(" ") : null,
    ],
  );
}

/**
 * The deployed sink: the console line first — the record that survives a store that is down — then the
 * row. A row the store refused is said on a second line, `logdb=failed`, the one exception to one line
 * per request; it never fails the request.
 */
export function storeSink(db: Db, line: (text: string) => void = writeLine): LogSink {
  return async (record) => {
    line(formatLine(record));
    try {
      await insertRequestRecord(db, record);
    } catch (e) {
      line(
        `${new Date().toISOString()} [${record.reqid}] logdb=failed detail=${
          JSON.stringify(String(e))
        }`,
      );
    }
  };
}

/** A kept row, read back. */
export type StoredRecord = RequestRecord & { id: number };

/** A row as {@link insertRequestRecord} wrote it. */
export function fromRow(row: Row): StoredRecord {
  const errors = typeof row.errors === "string" ? row.errors.split(" ") : [];
  return {
    id: Number(row.id),
    at: new Date(String(row.at)),
    reqid: String(row.reqid),
    method: String(row.method),
    url: String(row.url),
    status: Number(row.status),
    ms: Number(row.ms),
    version: row.version === null ? undefined : String(row.version),
    bytesIn: row.bytes_in === null ? undefined : Number(row.bytes_in),
    bytesOut: Number(row.bytes_out),
    fields: JSON.parse(String(row.fields)) as Fields,
    errors: errors.filter((t): t is ErrorTag => (ERROR_TAGS as readonly string[]).includes(t)),
  };
}

/** Delete every row older than `before` (an ISO instant); answers how many went. */
export async function deleteRequestLogBefore(db: Db, before: string): Promise<number> {
  return (await db.execute(`DELETE FROM request_log WHERE at < ?`, [before])).rowsAffected;
}

/** A window's traffic, counted. */
export type RequestCounts = {
  total: number;
  clientErrors: number;
  serverErrors: number;
  withErrors: number;
};

/** Count the rows of `[from, to)`: all, 4xx, 5xx, and those that recorded a fault. */
export async function requestCounts(db: Db, from: string, to: string): Promise<RequestCounts> {
  const { rows } = await db.execute(
    `SELECT count(*) AS total,
            count(*) FILTER (WHERE status BETWEEN 400 AND 499) AS client,
            count(*) FILTER (WHERE status >= 500) AS server,
            count(errors) AS faulted
       FROM request_log WHERE at >= ? AND at < ?`,
    [from, to],
  );
  const r = rows[0] ?? {};
  return {
    total: Number(r.total ?? 0),
    clientErrors: Number(r.client ?? 0),
    serverErrors: Number(r.server ?? 0),
    withErrors: Number(r.faulted ?? 0),
  };
}

/** The 5xx rows of `[from, to)`, newest first. */
export async function serverErrorRecords(
  db: Db,
  from: string,
  to: string,
): Promise<StoredRecord[]> {
  const { rows } = await db.execute(
    `SELECT id, ${COLUMNS} FROM request_log
      WHERE at >= ? AND at < ? AND status >= 500 ORDER BY at DESC, id DESC`,
    [from, to],
  );
  return rows.map(fromRow);
}

/** How big the table is: its rows, and the bytes its values hold (an estimate: no page overhead). */
export async function requestLogSize(db: Db): Promise<{ rows: number; bytes: number }> {
  const { rows } = await db.execute(
    `SELECT count(*) AS n,
            coalesce(sum(length(at) + length(reqid) + length(method) + length(url) + length(fields)
                         + coalesce(length(version), 0) + coalesce(length(errors), 0) + 32), 0) AS bytes
       FROM request_log`,
  );
  return { rows: Number(rows[0]?.n ?? 0), bytes: Number(rows[0]?.bytes ?? 0) };
}
