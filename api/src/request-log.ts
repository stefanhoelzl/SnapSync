// The request log (`docs/architecture.md`, "The request log"): exactly ONE line per request, written when
// its response body has finished, and the only thing the edge script ever writes.
//
//   <ISO> [<reqid>] <METHOD> <url> <status> <ms>ms v=<declared version|-> in=<n|-> out=<n> <fields…>
//
// A route RECORDS onto the line through `c.var.log`; it never writes. Nothing is written until the
// middleware writes the line, so a request's facts, refusals and faults sit on one line in the order they
// were recorded. The cost is stated in the decision record: a request that hangs, or that the platform
// kills at its time limit, leaves no line.
//
// The line never carries the caller's address or User-Agent (capability `privacy-security`, "A web
// visitor leaves no trace in the event"). The URL is kept whole, query included — the operator's log, read
// by the operator only.

import type { Context, ErrorHandler, MiddlewareHandler } from "hono";
import { Refusal } from "./refusal.ts";
import { APP_VERSION_HEADER } from "./version.ts";

/** A field's value as the line spells it. */
export type FieldValue = string | number | boolean;

/** What a route records onto its request's line. Recording only buffers; the middleware writes. */
export type RequestLog = {
  /** The request's correlation id, also the error tracker's `reqid` tag. */
  readonly reqid: string;
  /** Append `key=value`. A repeated key is written again, in order. */
  field(key: string, value: FieldValue): void;
  /** Append a server fault as `err="…"`. */
  error(message: string): void;
};

/** Where a finished line goes: the console in every runtime, a collector in a test. */
export type LogSink = (line: string) => void;

declare module "hono" {
  interface ContextVariableMap {
    log: RequestLog;
  }
}

/** The one write the edge script makes (`no-console` everywhere else). */
export const consoleSink: LogSink = (line) => {
  // deno-lint-ignore no-console
  console.log(line);
};

const BARE = /^[A-Za-z0-9._:/-]+$/;

/** A value bare when it is one plain token, else JSON-quoted — so a line parses unambiguously. */
export function encodeValue(value: FieldValue): string {
  const text = String(value);
  return BARE.test(text) ? text : JSON.stringify(text);
}

/** A fresh request log over `reqid`, and the fields recorded so far, in order. */
export function requestLog(reqid: string): RequestLog & { readonly fields: string[] } {
  const fields: string[] = [];
  return {
    reqid,
    fields,
    field: (key, value) => void fields.push(`${key}=${encodeValue(value)}`),
    error: (message) => void fields.push(`err=${encodeValue(message)}`),
  };
}

/** What one request's line is built from. */
export type LineFacts = {
  at: Date;
  reqid: string;
  method: string;
  url: string;
  status: number;
  ms: number;
  version: string | undefined;
  bytesIn: number | undefined;
  bytesOut: number;
  fields: readonly string[];
};

/** The line, in the documented order. */
export function formatLine(f: LineFacts): string {
  const head = `${f.at.toISOString()} [${f.reqid}] ${f.method} ${f.url} ${f.status} ${f.ms}ms`;
  const sizes = `v=${encodeValue(f.version ?? "-")} in=${f.bytesIn ?? "-"} out=${f.bytesOut}`;
  return [head, sizes, ...f.fields].join(" ");
}

/** Six lowercase hex characters: enough to tell apart the requests one log window interleaves. */
export function newReqid(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(3));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** `body` passed through unchanged, counting what crosses it; `onEnd` once, however it ends. */
function counted(
  body: ReadableStream<Uint8Array>,
  onEnd: (bytes: number, cut: { error?: unknown } | null) => void,
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
        onEnd(bytes, { error });
        controller.error(error);
        return;
      }
      if (chunk.done) {
        onEnd(bytes, null);
        controller.close();
        return;
      }
      bytes += chunk.value.byteLength;
      onChunk?.(bytes);
      controller.enqueue(chunk.value);
    },
    cancel(reason) {
      onEnd(bytes, {});
      return reader.cancel(reason);
    },
  });
}

/** How the middleware is built: where lines go, and (D4) what runs each request inside an isolation scope. */
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
    const write = (bytesOut: number, cut: { error?: unknown } | null) => {
      if (written) return;
      written = true;
      if (cut) {
        log.field("cut", true);
        if (cut.error !== undefined) log.error(`response body: ${cut.error}`);
      }
      sink(formatLine({
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
      }));
    };

    const res = c.res;
    if (!res.body) return write(0, null);
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
 * fields go on the line. Anything else is an exception no route caught, answered as Hono's default did —
 * `500 Internal Server Error` — and either reported (the line then carries only the report's id,
 * `bugsink=`) or, where nothing reports, recorded on the line as `err=`.
 */
export function errorHandler(report?: ReportError): ErrorHandler {
  return async (e, c) => {
    const log = c.var.log;
    if (e instanceof Refusal) {
      for (const [key, value] of Object.entries(e.fields)) log?.field(key, value);
      if (e.err !== undefined) log?.error(e.err);
      return typeof e.body === "string" ? c.text(e.body, e.status) : c.json(e.body, e.status);
    }
    if (report) log?.field("bugsink", await report(e, c));
    else log?.error(`${e.name}: ${e.message}`);
    return c.text("Internal Server Error", 500);
  };
}
