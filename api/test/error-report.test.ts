// The backend's failure reports (`src/error-report.ts`; capability `privacy-security`, "The service reports
// its own failures to the operator"): an exception no route caught reaches the error tracker carrying the
// request and its tags — never the requester's address — and the request's line carries only its id.
//
// The SDK is global to the process, so this file starts it once, over a transport that keeps what would
// have been sent.

import { assert, assertEquals, assertMatch } from "@std/assert";
import * as Sentry from "@sentry/deno";
import { Hono } from "hono";
import { startErrorReporting, withoutAddress } from "../src/error-report.ts";
import { errorHandler, requestLogMiddleware } from "../src/request-log.ts";

type Sent = {
  event_id?: string;
  tags?: Record<string, string>;
  request?: Sentry.Event["request"];
  breadcrumbs?: Sentry.Breadcrumb[];
  exception?: Sentry.Event["exception"];
  user?: Sentry.User;
};
const sent: Sent[] = [];

const reporter = startErrorReporting({
  dsn: "https://public@ingest.invalid/1",
  release: "test-sha",
  environment: "test",
  transport: (options) =>
    Sentry.createTransport(options, (request) => {
      const text = typeof request.body === "string"
        ? request.body
        : new TextDecoder().decode(request.body);
      const lines = text.split("\n");
      for (let i = 1; i < lines.length; i += 2) {
        if (lines[i] && JSON.parse(lines[i]).type === "event") sent.push(JSON.parse(lines[i + 1]));
      }
      return Promise.resolve({ statusCode: 200 });
    }),
});

function reporting(build: (app: Hono) => void) {
  const lines: string[] = [];
  const app = new Hono();
  app.use("*", requestLogMiddleware({ sink: (l) => lines.push(l), around: reporter.around }));
  app.onError(errorHandler(reporter.report));
  build(app);
  return { app, lines };
}

Deno.test("report → a throw reaches the tracker with its tags and the request; the line carries only its id", async () => {
  sent.length = 0;
  const { app, lines } = reporting((a) =>
    a.get("/events/:eventId/boom", () => {
      throw new TypeError("x is undefined");
    })
  );
  const res = await app.request("/events/E1/boom?n=1", {
    headers: {
      "x-snapsync-app-version": "0.14",
      "user-agent": "SnapSync/0.14",
      "x-forwarded-for": "203.0.113.7",
      "cdn-requestcountrycode": "DE",
    },
  });
  assertEquals(res.status, 500);
  assertEquals(await res.text(), "Internal Server Error");
  assertEquals(sent.length, 1);
  const event = sent[0];
  assertEquals(event.exception?.values?.[0].type, "TypeError");
  assertEquals(event.tags?.platform, "api");
  assertEquals(event.tags?.route, "/events/:eventId/boom");
  assertEquals(event.tags?.v, "0.14");
  assertMatch(event.tags?.reqid ?? "", /^[0-9a-f]{6}$/);
  assertEquals(event.request?.url, "http://localhost/events/E1/boom?n=1");
  // The request's details are kept — the browser or app, the country — but never the address.
  const headers = event.request?.headers ?? {};
  assertEquals(headers["user-agent"], "SnapSync/0.14");
  assertEquals(headers["cdn-requestcountrycode"], "DE");
  assertEquals(headers["x-forwarded-for"], undefined);
  assertEquals(event.user?.ip_address, undefined);
  // The line: its reqid is the event's, and the event's id is all it says about the failure.
  assertEquals(lines.length, 1);
  assert(lines[0].includes(`[${event.tags?.reqid}]`), lines[0]);
  assert(lines[0].endsWith(` bugsink=${event.event_id}`), lines[0]);
  assert(!lines[0].includes("err="), lines[0]);
});

Deno.test("report → a refusal is an answer, not a failure: nothing is reported", async () => {
  sent.length = 0;
  const { Refusal } = await import("../src/refusal.ts");
  const { app } = reporting((a) =>
    a.get("/r", () => {
      throw new Refusal(502, "upstream error", { err: "store: down" });
    })
  );
  const res = await app.request("/r");
  assertEquals(res.status, 502);
  await res.text();
  assertEquals(sent.length, 0);
});

Deno.test("report → concurrent requests each carry only their own outbound calls", async () => {
  sent.length = 0;
  const { app } = reporting((a) =>
    a.get("/fetch-then-throw", async (c) => {
      const n = c.req.query("n");
      // No network in the tests: the call fails, and is still a breadcrumb of THIS request.
      await fetch(`https://example.invalid/?n=${n}`).catch(() => {});
      await new Promise((r) => setTimeout(r, 30));
      throw new Error(`boom ${n}`);
    })
  );
  await Promise.all([app.request("/fetch-then-throw?n=A"), app.request("/fetch-then-throw?n=B")]);
  assertEquals(sent.length, 2);
  for (const event of sent) {
    const n = event.exception?.values?.[0].value?.slice(-1);
    const urls = (event.breadcrumbs ?? []).filter((b) => b.category === "fetch").map((b) =>
      b.data?.url
    );
    assertEquals(urls, [`https://example.invalid/?n=${n}`]);
  }
});

Deno.test("withoutAddress → strips every address-bearing header and the user's address", () => {
  const event = withoutAddress<Sentry.Event>({
    user: { id: "u", ip_address: "203.0.113.7" },
    request: {
      headers: { "X-Real-IP": "203.0.113.7", "Forwarded": "for=203.0.113.7", "accept": "*/*" },
    },
  });
  assertEquals(event.user, { id: "u" });
  assertEquals(event.request?.headers, { accept: "*/*" });
});
