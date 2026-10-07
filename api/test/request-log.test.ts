// The request log (`src/request-log.ts`): one line per request, its shape, and what it never carries.

import { assert, assertEquals, assertMatch } from "@std/assert";
import { Hono } from "hono";
import {
  encodeValue,
  errorHandler,
  formatLine,
  requestLog,
  requestLogMiddleware,
} from "../src/request-log.ts";
import { CONFIG, createRealApp, E, recorder, store, V2 } from "./support/harness.ts";

/** A bare app behind the middleware, collecting its lines. */
function logged(build: (app: Hono) => void) {
  const lines: string[] = [];
  const app = new Hono();
  app.use("*", requestLogMiddleware({ sink: (l) => lines.push(l) }));
  app.onError(errorHandler());
  build(app);
  return { app, lines };
}

/** Read a response to its end, so the line (written when the body finishes) is out. */
async function drain(res: Response): Promise<string> {
  return await res.text();
}

Deno.test("encodeValue → a plain token stays bare, anything else is JSON-quoted", () => {
  assertEquals(encodeValue("0.14"), "0.14");
  assertEquals(encodeValue("apns410:1"), "apns410:1");
  assertEquals(encodeValue(3), "3");
  assertEquals(encodeValue(true), "true");
  assertEquals(encodeValue("a b"), '"a b"');
  assertEquals(encodeValue('say "hi"'), '"say \\"hi\\""');
  assertEquals(encodeValue("k=v"), '"k=v"');
  assertEquals(encodeValue("two\nlines"), '"two\\nlines"');
  assertEquals(encodeValue(""), '""');
});

Deno.test("formatLine → the documented order, '-' for an absent version or request length", () => {
  const log = requestLog("a91f3c");
  log.field("pushed", 3);
  log.error("notify: Error: timed out");
  log.field("pushed", 2);
  assertEquals(
    formatLine({
      at: new Date("2026-10-07T09:14:02.311Z"),
      reqid: log.reqid,
      method: "PUT",
      url: "https://x/api/v2/a?filename=IMG 1.HEIC",
      status: 201,
      ms: 842,
      version: undefined,
      bytesIn: undefined,
      bytesOut: 0,
      fields: log.fields,
    }),
    "2026-10-07T09:14:02.311Z [a91f3c] PUT https://x/api/v2/a?filename=IMG 1.HEIC 201 842ms v=- in=- out=0 " +
      'pushed=3 err="notify: Error: timed out" pushed=2',
  );
});

Deno.test("middleware → exactly one line per request, out= the bytes sent", async () => {
  const { app, lines } = logged((a) => a.get("/x", (c) => c.text("héllo")));
  const res = await app.request("/x", { headers: { "x-snapsync-app-version": "0.14" } });
  await drain(res);
  assertEquals(lines.length, 1);
  assertMatch(
    lines[0],
    /^\S+Z \[[0-9a-f]{6}\] GET http:\/\/localhost\/x 200 \d+ms v=0.14 in=- out=6$/,
  );
});

Deno.test("middleware → a body-less answer writes at once", async () => {
  const { app, lines } = logged((a) => a.get("/x", (c) => c.body(null, 204)));
  await app.request("/x");
  assertEquals(lines.length, 1);
  assertMatch(lines[0], / 204 \d+ms v=- in=- out=0$/);
});

Deno.test("middleware → ms covers a slow body, and the line waits for its end", async () => {
  const { app, lines } = logged((a) =>
    a.get("/slow", () =>
      new Response(
        new ReadableStream({
          async start(controller) {
            await new Promise((r) => setTimeout(r, 60));
            controller.enqueue(new TextEncoder().encode("ab"));
            controller.close();
          },
        }),
      ))
  );
  const res = await app.request("/slow");
  assertEquals(lines.length, 0); // the handler returned; the body has not finished
  await drain(res);
  const ms = Number(lines[0].match(/ (\d+)ms /)![1]);
  assert(ms >= 50, `expected the transfer in ms, got ${ms}`);
  assertMatch(lines[0], / out=2$/);
});

Deno.test("middleware → a body the client abandons is written cut=true with what was sent", async () => {
  const { app, lines } = logged((a) =>
    a.get("/big", () =>
      new Response(
        new ReadableStream({
          pull(controller) {
            controller.enqueue(new Uint8Array(10));
          },
        }),
      ))
  );
  const res = await app.request("/big");
  const reader = res.body!.getReader();
  await reader.read();
  await reader.cancel();
  assertEquals(lines.length, 1);
  assertMatch(lines[0], / out=\d+ cut=true$/);
});

Deno.test("middleware → a body that errors mid-stream is written cut=true with its error", async () => {
  const { app, lines } = logged((a) =>
    a.get("/broken", () =>
      new Response(
        new ReadableStream({
          pull(controller) {
            controller.error(new Error("upstream reset"));
          },
        }),
      ))
  );
  const res = await app.request("/broken");
  await drain(res).catch(() => {});
  assertEquals(lines.length, 1);
  assertMatch(lines[0], / cut=true err="response body: Error: upstream reset"$/);
});

Deno.test("middleware → in= counts a chunked body a route read, with no Content-Length", async () => {
  const { app, lines } = logged((a) =>
    a.put("/up", async (c) => c.text(String((await c.req.text()).length)))
  );
  const body = new ReadableStream({
    start(controller) {
      controller.enqueue(new TextEncoder().encode("12345"));
      controller.enqueue(new TextEncoder().encode("678"));
      controller.close();
    },
  });
  const res = await app.request("/up", { method: "PUT", body, duplex: "half" } as RequestInit);
  assertEquals(await drain(res), "8");
  assertMatch(lines[0], / in=8 out=1$/);
});

Deno.test("middleware → in= is the declared Content-Length when no route read the body", async () => {
  const { app, lines } = logged((a) => a.put("/refuse", (c) => c.text("no", 403)));
  await drain(await app.request("/refuse", { method: "PUT", body: "0123456789" }));
  assertMatch(lines[0], / 403 \d+ms v=- in=10 out=2$/);
});

Deno.test("middleware → routes record fields in order, onto the one line", async () => {
  const { app, lines } = logged((a) =>
    a.get("/f", (c) => {
      c.var.log.field("closed", true);
      c.var.log.field("served", 212);
      c.var.log.error("store: Error: down");
      return c.text("ok");
    })
  );
  await drain(await app.request("/f"));
  assertMatch(lines[0], / out=2 closed=true served=212 err="store: Error: down"$/);
});

Deno.test("onError → an uncaught throw answers 500 as Hono did, recorded on the line", async () => {
  const { app, lines } = logged((a) =>
    a.get("/boom", () => {
      throw new TypeError("x is undefined");
    })
  );
  const res = await app.request("/boom");
  assertEquals(res.status, 500);
  assertEquals(await drain(res), "Internal Server Error");
  assertEquals(lines.length, 1);
  assertMatch(lines[0], / 500 \d+ms .* err="TypeError: x is undefined"$/);
});

Deno.test("the line never carries the caller's address or User-Agent", async () => {
  const { app, lines } = logged((a) => a.get("/x", (c) => c.text("ok")));
  await drain(
    await app.request("/x", {
      headers: {
        "user-agent": "Mozilla/5.0 SecretBrowser",
        "x-forwarded-for": "203.0.113.7",
        "cdn-clientip": "203.0.113.7",
      },
    }),
  );
  assert(!lines[0].includes("SecretBrowser"));
  assert(!lines[0].includes("203.0.113.7"));
});

Deno.test("createApp → every request writes one line, a gate's refusal included", async () => {
  const lines: string[] = [];
  const db = await store();
  const app = createRealApp({
    config: CONFIG,
    db,
    fetch: recorder().fetchImpl,
    logSink: (l) => lines.push(l),
  });
  // No token: the token gate's 401, before any route.
  await drain(
    await app.request(`/api/v2/events`, { method: "POST", body: "{}", headers: V2 }),
  );
  assertEquals(lines.length, 1);
  assertMatch(lines[0], / POST http:\/\/localhost\/api\/v2\/events 401 \d+ms v=99.0 in=2 out=10$/);
  db.close();
});

Deno.test("createApp → a store failure behind a gate answers 502 as before, its cause on the line", async () => {
  const lines: string[] = [];
  const broken = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  const app = createRealApp({
    config: CONFIG,
    db: broken,
    fetch: recorder().fetchImpl,
    logSink: (l) => lines.push(l),
  });
  const res = await app.request(`/api/v2/events/${E}`, { headers: V2 });
  assertEquals(res.status, 502);
  assertEquals(await res.text(), "upstream error");
  assertEquals(lines.length, 1);
  assertMatch(lines[0], / 502 .* err="metadata: event read failed for [^"]*: Error: store down"$/);
});

Deno.test("createApp → a site object storage refuses is 502, its cause on the line", async () => {
  const lines: string[] = [];
  const db = await store();
  const app = createRealApp({
    config: CONFIG,
    db,
    fetch: () => Promise.resolve(new Response(null, { status: 500 })),
    logSink: (l) => lines.push(l),
  });
  const res = await app.request("/");
  assertEquals(res.status, 502);
  assertEquals(await res.text(), "upstream error");
  assertMatch(lines[0], / 502 .* err="site: bunny returned 500 for site\/index.html"$/);
  db.close();
});
