// The backend's failure reports (capability `privacy-security`, "The service reports its own failures to the
// operator"; decision record `changes/api-request-log`, D4): an exception no route caught is reported to the
// operator's error tracker — the Bugsink instance the apps report to, through the Sentry protocol — with its
// stack, the request as it arrived (URL and headers), the outbound calls the request made, and the tags that
// tie it to the request's log line. The line itself carries only the report's id (`bugsink=`).
//
// ONLY THE DEPLOYED SERVICE REPORTS. `main.ts` starts this only when the bundle carries a DSN, which the
// resolver renders for `prod` and `maintenance` alone; a dev rig, the ephemeral test backend and the tests
// compose `createApp` with no reporter and report nothing.
//
// Measured on Bunny Edge Scripting before it was adopted (2026-10-07, the decision record's D4): a capture
// and its flush complete inside the request, the per-request isolation keeps concurrent requests'
// breadcrumbs apart under `Bunny.v1.serve`, and a floating rejection is captured without stopping the
// script.

import * as Sentry from "@sentry/deno";
import type { Context } from "hono";
import type { RequestLogOptions } from "./request-log.ts";
import { APP_VERSION_HEADER } from "./version.ts";

/** How long a 500 waits for its report to leave — the isolate may freeze once the response is out. */
const FLUSH_MS = 2000;

/**
 * Headers that would carry the requester's network address. None was observed on bunny (the probe of
 * 2026-10-07 saw no address at all), but the promise never to send one must not rest on bunny never
 * adding one.
 */
const ADDRESS_HEADERS = new Set([
  "x-forwarded-for",
  "x-real-ip",
  "forwarded",
  "true-client-ip",
  "cf-connecting-ip",
  "cdn-clientip",
  "cdn-client-ip",
  "fastly-client-ip",
]);

/** What `createApp` reports through. */
export type ErrorReporter = {
  /** Runs one request inside its own reporting scope, tagged with its `reqid`. */
  around: NonNullable<RequestLogOptions["around"]>;
  /** Report `e`, raised while serving `c`; answers the report's id once it has left (or timed out). */
  report: (e: Error, c: Context) => Promise<string>;
};

export type ReporterOptions = {
  dsn: string;
  release: string;
  environment: string;
  /** A transport for tests; the SDK's own `fetch` transport otherwise. */
  transport?: Sentry.DenoOptions["transport"];
};

/** Remove anything that could carry the requester's address from an outgoing event. */
export function withoutAddress<E extends Sentry.Event>(event: E): E {
  if (event.user) delete event.user.ip_address;
  const headers = event.request?.headers;
  if (headers) {
    for (const name of Object.keys(headers)) {
      if (ADDRESS_HEADERS.has(name.toLowerCase())) delete headers[name];
    }
  }
  return event;
}

/** Start reporting for this process. Default integrations, no tracing. */
export function startErrorReporting(options: ReporterOptions): ErrorReporter {
  Sentry.init({
    dsn: options.dsn,
    release: options.release,
    environment: options.environment,
    ...(options.transport ? { transport: options.transport } : {}),
    initialScope: { tags: { platform: "api" } },
    beforeSend: (event) => withoutAddress(event),
  });
  return {
    around: (reqid, c, run) =>
      Sentry.withIsolationScope(async (scope) => {
        scope.setTag("reqid", reqid);
        scope.setTag("v", c.req.header(APP_VERSION_HEADER) ?? "-");
        // The request as it arrived, whichever server the runtime handed it to: the SDK fills it in only
        // for a `Deno.serve` it instrumented itself.
        scope.setSDKProcessingMetadata({
          normalizedRequest: {
            url: c.req.url,
            method: c.req.method,
            headers: Object.fromEntries(c.req.raw.headers),
          },
        });
        await run();
      }),
    report: async (e, c) => {
      const id = Sentry.withScope((scope) => {
        scope.setTag("route", c.req.routePath);
        return Sentry.captureException(e);
      });
      await Sentry.flush(FLUSH_MS);
      return id;
    },
  };
}
