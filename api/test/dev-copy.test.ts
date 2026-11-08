// A copy of the service with no reporting address (capability `privacy-security`, "The service reports its own
// failures to the operator"): what `main.ts` and the sweep start from a deployment's address. The resolver renders an
// address for the deployed `prod` and `maintenance` bundles alone (`scripts/resolve-deployment.py`, its own test), so
// every other copy — local development, the tests, the Kotlin suite's ephemeral api — starts from none.
//
// Every outbound fetch is watched, so "reports nothing" is "nothing left the process", not "no reporter object".

import { assert, assertEquals, assertRejects } from "@std/assert";
import { Hono } from "hono";
import { reporterFor } from "../src/error-report.ts";
import { errorHandler, requestLogMiddleware } from "../src/request-log.ts";
import { noSender, senderFor } from "../src/scripts/request-log-report.ts";
import { lineSink } from "./support/harness.ts";

/** Runs `body` with the process's `fetch` replaced by one that records and refuses; answers what was fetched. */
async function watchingFetch(body: () => Promise<void>): Promise<string[]> {
  const fetched: string[] = [];
  const real = globalThis.fetch;
  globalThis.fetch = (input) => {
    fetched.push(String(input instanceof Request ? input.url : input));
    return Promise.reject(new Error("no request may leave"));
  };
  try {
    await body();
  } finally {
    globalThis.fetch = real;
  }
  return fetched;
}

// @Verifies(spec = "privacy-security", requirement = "The service reports its own failures to the operator", scenario = "A development copy of the service fails")
// The edge's part (`main.ts`): no address starts no reporter, so an uncaught failure is answered and logged and reported
// nowhere. That a development copy has no address is the resolver's part, its own Python test.
Deno.test("dev copy → the edge with no reporting address reports an uncaught failure nowhere", async () => {
  const reporter = reporterFor("", { release: "dev-sha", environment: "production" });
  assertEquals(reporter, undefined, "no address starts no reporter");
  const lines: string[] = [];
  const app = new Hono();
  // Wired as `createApp` wires what `main.ts` hands it.
  app.use("*", requestLogMiddleware({ sink: lineSink(lines), around: reporter?.around }));
  app.onError(errorHandler(reporter?.report));
  app.get("/events/:eventId/boom", () => {
    throw new TypeError("x is undefined");
  });
  const fetched = await watchingFetch(async () => {
    const res = await app.request("/events/e/boom");
    assertEquals(res.status, 500);
    await res.text();
  });
  assertEquals(fetched, [], "nothing left the process");
  assertEquals(lines.length, 1);
  assert(lines[0].includes("uncaught") && !lines[0].includes("bugsink="), lines[0]);
});

// @Verifies(spec = "privacy-security", requirement = "The service reports its own failures to the operator", scenario = "A development copy of the service fails")
// The sweep's part (`sweep.ts`): with no address its run's failure report is sent nowhere — the run fails instead.
Deno.test("dev copy → the sweep with no reporting address sends its failure report nowhere", async () => {
  const send = senderFor("", "dev-sha");
  assertEquals(send, noSender, "no address, no sender");
  const fetched = await watchingFetch(async () => {
    await assertRejects(() =>
      send({ day: "2026-11-07", total: 1, serverErrors: 1, omitted: 0, lines: ["x"] })
    );
  });
  assertEquals(fetched, [], "nothing left the process");
});
