// The request log (`docs/architecture.md`, "The request log"): exactly ONE record per request, taken when
// its response body has finished, and the only thing the edge script ever writes. Its sink prints the
// record as one console line and keeps it in the store's `request_log` table (`request-log-store.ts`).
//
//   <ISO> [<reqid>] <METHOD> <url> <status> <ms>ms v=<declared version|-> in=<n|-> out=<n> <fields…> [errors=<tags>]
//
// A route RECORDS onto its request's record through `c.var.log`; it never writes. Nothing is written until
// the middleware hands the finished record to the sink, so a request's facts, refusals and faults arrive
// together. The cost is stated in the decision record: a request that hangs, or that the platform kills at
// its time limit, leaves no record.
//
// The record never carries the caller's address or User-Agent (capability `privacy-security`, "A web
// visitor leaves no trace in the event"). The URL is kept whole, query included — the operator's log, read
// by the operator only.

import type { Context, ErrorHandler, MiddlewareHandler } from "hono";
import { Refusal } from "./refusal.ts";
import { APP_VERSION_HEADER } from "./version.ts";

/** A field's value as the line spells it. */
export type FieldValue = string | number | boolean;

/**
 * Every fault a request can record, as a short tag — a CLOSED list, so `log.error` compiles only with a
 * known one and a search (`deno task logs --error <tag>`) cannot miss a misspelt variant. The fault's
 * detail (the exception text) rides beside it, in the record's fields under the tag's own name.
 */
export const ERROR_TAGS = [
  /** A call to the store or to storage threw; the request was refused `502`. */
  "upstream",
  /** Storage answered a non-2xx; the request was refused `502`. */
  "upstream-rejected",
  /** An exception no route caught, where nothing reports it (a dev rig, a test). */
  "uncaught",
  /** The response body broke off mid-stream (`cut=true`). */
  "body-cut",
  /** The byte route could not ask which events this upload completed; it wakes nobody. */
  "completion-lookup",
  /** The byte route could not stamp the events' landing time (capability `event-lifetime`). */
  "landing-stamp",
  /** A wake could not read the union position it announces; it was sent without one. */
  "union-position",
  /** A wake's fan-out failed or timed out; the write it announces stands. */
  "fanout",
  /** The device's declared app version was not recorded. */
  "app-version",
  /** The manifest publish could not look up which declared assets are fetchable. */
  "fetchability",
  /** A union read was served but its row in the union log was not written. */
  "read-log",
  /** The site's event page could not read its event. */
  "event-page",
  /** `/health`: the relational store did not answer. */
  "store-down",
  /** `/health`: the storage zone did not answer. */
  "zone-down",
] as const;

/** One of {@link ERROR_TAGS}. */
export type ErrorTag = typeof ERROR_TAGS[number];

/** What a route records onto its request's record. Recording only buffers; the middleware writes. */
export type RequestLog = {
  /** The request's correlation id, also the error tracker's `reqid` tag. */
  readonly reqid: string;
  /** Record `key=value`. A key recorded again keeps every value, in order, as an array. */
  field(key: string, value: FieldValue): void;
  /** Record a server fault: its tag joins the record's `errors`, its detail is kept under the tag. */
  error(tag: ErrorTag, detail: string): void;
};

/** The fields a request recorded: a key recorded once holds its value, one recorded again an array. */
export type Fields = Record<string, FieldValue | FieldValue[]>;

/** One request, finished: what the sink is handed. */
export type RequestRecord = {
  at: Date;
  reqid: string;
  method: string;
  url: string;
  status: number;
  ms: number;
  version: string | undefined;
  bytesIn: number | undefined;
  bytesOut: number;
  fields: Fields;
  /** The tags of the faults recorded, each once, in the order first recorded. */
  errors: ErrorTag[];
};

/**
 * Where a finished record goes. The response's end waits for a returned promise — a store write is a
 * subrequest the platform may cut off once the response is out, and Bunny offers no `waitUntil`.
 */
export type LogSink = (record: RequestRecord) => void | Promise<void>;

declare module "hono" {
  interface ContextVariableMap {
    log: RequestLog;
  }
}

/** Write one console line — the edge script's one console write (`no-console` everywhere else). */
export function writeLine(line: string): void {
  // deno-lint-ignore no-console
  console.log(line);
}

/** The record as its console line. The sink of a runtime with no store; a test collects these. */
export const consoleSink: LogSink = (record) => writeLine(formatLine(record));

const BARE = /^[A-Za-z0-9._:/-]+$/;

/** A value bare when it is one plain token, else JSON-quoted — so a line parses unambiguously. */
export function encodeValue(value: FieldValue): string {
  const text = String(value);
  return BARE.test(text) ? text : JSON.stringify(text);
}

/** A fresh request log over `reqid`, and what it recorded so far. */
export function requestLog(
  reqid: string,
): RequestLog & { readonly fields: Fields; readonly errors: ErrorTag[] } {
  const fields: Fields = {};
  const errors: ErrorTag[] = [];
  const field = (key: string, value: FieldValue) => {
    const known = fields[key];
    if (known === undefined) fields[key] = value;
    else if (Array.isArray(known)) known.push(value);
    else fields[key] = [known, value];
  };
  return {
    reqid,
    fields,
    errors,
    field,
    error: (tag, detail) => {
      if (!errors.includes(tag)) errors.push(tag);
      field(tag, detail);
    },
  };
}

/** The line, in the documented order: a repeated key is written once per value. */
export function formatLine(r: RequestRecord): string {
  const head = `${r.at.toISOString()} [${r.reqid}] ${r.method} ${r.url} ${r.status} ${r.ms}ms`;
  const sizes = `v=${encodeValue(r.version ?? "-")} in=${r.bytesIn ?? "-"} out=${r.bytesOut}`;
  const fields = Object.entries(r.fields).flatMap(([key, value]) =>
    (Array.isArray(value) ? value : [value]).map((v) => `${key}=${encodeValue(v)}`)
  );
  const errors = r.errors.length > 0 ? [`errors=${r.errors.join(",")}`] : [];
  return [head, sizes, ...fields, ...errors].join(" ");
}

/** Six lowercase hex characters: enough to tell apart the requests one log window interleaves. */
export function newReqid(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(3));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** How a counted stream ended: `null` at its end, else cut — with the error that cut it, if any. */
type Cut = { error?: unknown } | null;

/**
 * `body` passed through unchanged, counting what crosses it; `onEnd` once, however it ends — and the
 * stream's own end waits for it.
 */
function counted(
  body: ReadableStream<Uint8Array>,
  onEnd: (bytes: number, cut: Cut) => void | Promise<void>,
  onChunk?: (bytes: number) => void,
): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  let bytes = 0;
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } catch (error) {
        await onEnd(bytes, { error });
        controller.error(error);
        return;
      }
      if (chunk.done) {
        await onEnd(bytes, null);
        controller.close();
        return;
      }
      bytes += chunk.value.byteLength;
      onChunk?.(bytes);
      controller.enqueue(chunk.value);
    },
    async cancel(reason) {
      await onEnd(bytes, {});
      return reader.cancel(reason);
    },
  });
}

/** How the middleware is built: where records go, and (D4) what runs each request inside an isolation scope. */
export type RequestLogOptions = {
  sink?: LogSink;
  /** Runs the rest of the request; the error tracker passes its per-request isolation here. */
  around?: (reqid: string, c: Context, run: () => Promise<void>) => Promise<void>;
};

/**
 * The request-log middleware — registered FIRST in `createApp`, so it sees every request and every answer,
 * the gates' refusals included.
 */
export function requestLogMiddleware(
  { sink = consoleSink, around = (_r, _c, run) => run() }: RequestLogOptions = {},
): MiddlewareHandler {
  return async (c, next) => {
    const started = performance.now();
    const log = requestLog(newReqid());
    c.set("log", log);
    const bytesIn = countRequestBody(c);
    await around(log.reqid, c, next);

    let written = false;
    const write = async (bytesOut: number, cut: Cut) => {
      if (written) return;
      written = true;
      if (cut) {
        log.field("cut", true);
        if (cut.error !== undefined) log.error("body-cut", `response body: ${cut.error}`);
      }
      await sink({
        at: new Date(),
        reqid: log.reqid,
        method: c.req.method,
        url: c.req.url,
        status: c.res.status,
        ms: Math.round(performance.now() - started),
        version: c.req.header(APP_VERSION_HEADER),
        bytesIn: bytesIn(),
        bytesOut,
        fields: log.fields,
        errors: log.errors,
      });
    };

    const res = c.res;
    if (!res.body) return await write(0, null);
    c.res = new Response(counted(res.body, write), res);
  };
}

/**
 * Swap the request for one whose body counts what the routes read, and answer the count — or, when no route
 * read the body, its declared `Content-Length` (`undefined` when it has none). Before routing, so every route
 * is covered without one of them knowing.
 */
function countRequestBody(c: Context): () => number | undefined {
  const raw = c.req.raw;
  const declared = raw.headers.get("content-length");
  const fallback = declared === null ? undefined : Number(declared);
  if (!raw.body) return () => fallback;
  let bytes: number | undefined;
  const seen = (n: number) => void (bytes = n);
  const body = counted(raw.body, seen, seen);
  c.req.raw = new Request(raw, { body, duplex: "half" } as RequestInit);
  return () => bytes ?? fallback;
}

/** Report an exception no route caught; answers the report's id. */
export type ReportError = (e: Error, c: Context) => Promise<string>;

/**
 * Every thrown failure ends here. A {@link Refusal} is an answer a helper chose: it is answered exactly as
 * the route answered before (through `c`, so headers a handler already set still apply) and its fault and
 * fields go on the record. Anything else is an exception no route caught, answered as Hono's default did —
 * `500 Internal Server Error` — and either reported (the record then carries only the report's id,
 * `bugsink=`) or, where nothing reports, recorded as an `uncaught` fault.
 */
export function errorHandler(report?: ReportError): ErrorHandler {
  return async (e, c) => {
    const log = c.var.log;
    if (e instanceof Refusal) {
      for (const [key, value] of Object.entries(e.fields)) log?.field(key, value);
      if (e.fault !== undefined) log?.error(e.fault.tag, e.fault.detail);
      return typeof e.body === "string" ? c.text(e.body, e.status) : c.json(e.body, e.status);
    }
    if (report) log?.field("bugsink", await report(e, c));
    else log?.error("uncaught", `${e.name}: ${e.message}`);
    return c.text("Internal Server Error", 500);
  };
}
