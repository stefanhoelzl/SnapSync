import { emptyStore } from "./support/db.ts";
import { assert, assertEquals } from "@std/assert";
import { createApp, type FetchLike } from "../src/app.ts";
import { NO_CACHE } from "../src/routes/support.ts";
import { type Db, insertEvent } from "../src/db.ts";
import { readConfig } from "../src/config.ts";

// The event page (capability `event-site`, built by the `site/` Astro module) is ONE built object,
// `site/join/index.html`, read from storage as a TEMPLATE and filled per request (`event-page.ts` decides the
// words, pinned in event-page.test.ts). These tests exercise the ROUTES — `/join` (a fragment invite, filled
// generically) and `/join/<eventId>` (the event's own page, filled from its row) — their statuses, headers and
// faithful 404/502, against an injected fake storage, so they stay offline.
//
// The gate interaction (both served without a token) lives in attest.test.ts.

const CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  BUNNY_DATABASE_URL: "libsql://example.invalid",
  BUNNY_DATABASE_AUTH_TOKEN: "dbt",
  ADMIN_NOTIFY_KEY: "a",
});

const JOIN_HTML = "<!doctype html><title>SnapSync — event photos</title><body>join</body>";

// The built page's tokens, as join.astro carries them.
const TEMPLATE = `<!doctype html><title>%%TITLE%%</title>
<meta property="og:title" content="%%OG_TITLE%%"><meta name="description" content="%%DESCRIPTION%%">
<meta property="og:description" content="%%DESCRIPTION%%"><meta property="og:url" content="%%URL%%">
<div class="join" data-view="%%VIEW%%">%%PILL%%<h1>%%HEADING%%</h1>%%FACTS%%</div>`;

// A fake storage serving only site/join/index.html.
function fakeStorage(html = JOIN_HTML): { fetch: FetchLike; keys: string[] } {
  const keys: string[] = [];
  const fetch: FetchLike = (url, init) => {
    assertEquals(init.method, "GET");
    // Addressed against the RESOLVED zone, not a pinned literal: this asserts the proxy targets the
    // zone it was configured with, which stays true for any deployment.
    const key = url.match(new RegExp(`/${CONFIG.zone}/(site/.*)$`))?.[1] ?? "";
    keys.push(key);
    if (key === "site/join/index.html") {
      return Promise.resolve(new Response(html, { status: 200 }));
    }
    return Promise.resolve(new Response("not found", { status: 404 }));
  };
  return { fetch, keys };
}

const DB = await emptyStore();
const app = (f: FetchLike) => createApp({ config: CONFIG, db: DB, fetch: f });

Deno.test("download: GET /join serves the built site/join/index.html as never-cached HTML", async () => {
  const s = fakeStorage();
  const res = await app(s.fetch).request("/join");
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), NO_CACHE);
  assertEquals(res.headers.get("Referrer-Policy"), "no-referrer");
  // The page reads an encrypted event's key from its own address: only its own scripts run there, and it talks to
  // this origin and the storage host alone.
  const policy = res.headers.get("Content-Security-Policy") ?? "";
  assert(policy.includes("script-src 'self'") && !policy.includes("unsafe-eval"), policy);
  assert(policy.includes(`connect-src 'self' ${CONFIG.s3Scheme}://${CONFIG.s3Host}`), policy);
  assert(
    policy.includes("default-src 'none'") && policy.includes("frame-ancestors 'none'"),
    policy,
  );
  assertEquals(await res.text(), JOIN_HTML);
  assert(s.keys.includes("site/join/index.html"), "the proxy read the constant join page");
});

Deno.test("download: /join reads the SAME constant object for different links (no per-event state)", async () => {
  // The payload rides in the fragment (never sent), so the backend serves byte-identical bytes and makes
  // the same single storage read regardless of which event link is opened.
  const s = fakeStorage();
  const a = await (await app(s.fetch).request("/join")).text();
  const b = await (await app(s.fetch).request("/join")).text();
  assertEquals(a, b);
  assertEquals(s.keys.every((k) => k === "site/join/index.html"), true);
});

Deno.test("download: HEAD /join returns the headers with no body", async () => {
  const res = await app(fakeStorage().fetch).request("/join", { method: "HEAD" });
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Cache-Control"), NO_CACHE);
  assertEquals(await res.text(), "");
});

Deno.test("download: an upstream storage failure is a 502", async () => {
  const failing: FetchLike = () => Promise.resolve(new Response("boom", { status: 500 }));
  assertEquals((await app(failing).request("/join")).status, 502);
});

// ── /join/<eventId>: the event's own page ───────────────────────────────────────────────────────────

type Store = Awaited<ReturnType<typeof emptyStore>>;
const EVENT = "3f2c0000-0000-4000-8000-00000000e91a";
const NOW = Date.parse("2026-10-04T12:00:00Z"); // inside the Berlin event's window

async function storeWith(
  extra: { completedAt?: string; closedAt?: string; members?: [string, string][] } = {},
): Promise<Store> {
  const db = await emptyStore();
  await insertEvent(db, {
    eventId: EVENT,
    name: `Anna's <40th>`,
    createdAt: "2026-10-01T10:00:00.000Z",
    startsAt: "2026-10-03T22:00:00Z",
    endsAt: "2026-10-05T18:00:00Z",
    capacity: 10,
    lifetimeSeconds: 30 * 86400,
    zone: "Europe/Berlin",
  });
  for (const [device, state] of extra.members ?? []) {
    await db.execute(
      `INSERT INTO memberships (event_id, device_id, state, joined_at) VALUES (?, ?, ?, '2026-10-04T00:00:00Z')`,
      [EVENT, device, state],
    );
  }
  if (extra.closedAt) await db.execute(`UPDATE events SET closed_at = ?`, [extra.closedAt]);
  if (extra.completedAt) {
    await db.execute(`UPDATE events SET completed_at = ?`, [extra.completedAt]);
  }
  return db;
}

const eventApp = (db: Db, f: FetchLike = fakeStorage(TEMPLATE).fetch, now = NOW) =>
  createApp({ config: CONFIG, db, fetch: f, now: () => now });

Deno.test("event page: a live event is rendered into the page, escaped, never cached, no referrer", async () => {
  const db = await storeWith({ members: [["d1", "sharing"], ["d2", "sharing"]] });
  const res = await eventApp(db).request(`/join/${EVENT}`);
  assertEquals(res.status, 200);
  assertEquals(res.headers.get("Content-Type"), "text/html; charset=utf-8");
  assertEquals(res.headers.get("Cache-Control"), NO_CACHE);
  assertEquals(res.headers.get("Referrer-Policy"), "no-referrer");
  const html = await res.text();
  assert(!html.includes("%%"), "every token filled");
  assert(html.includes(`<title>Anna&#39;s &lt;40th&gt; — SnapSync</title>`));
  assert(html.includes(`content="Anna&#39;s &lt;40th&gt;"`)); // og:title
  assert(html.includes(`content="Sun 4 Oct – Mon 5 Oct 2026 · 2 members"`)); // the host's dates
  assert(html.includes(`content="https://${CONFIG.linkDomain}/join/${EVENT}"`)); // og:url is the event's own
  assert(html.includes(`data-view="event"`));
  assert(html.includes("Happening now"));
  db.close();
});

Deno.test("event page: a closed event still has its photos, so it renders as ended, not invalid", async () => {
  const db = await storeWith({ closedAt: "2026-10-06T00:00:00Z", members: [["d1", "settled"]] });
  const res = await eventApp(db, undefined, Date.parse("2026-10-06T12:00:00Z")).request(
    `/join/${EVENT}`,
  );
  assertEquals(res.status, 200);
  const html = await res.text();
  assert(html.includes(`data-view="event"`));
  assert(html.includes("The member is done sharing"));
  db.close();
});

Deno.test("event page: unknown, malformed and completed links render the invalid view and name no event", async () => {
  const empty = await emptyStore();
  const completed = await storeWith({ completedAt: "2026-10-07T00:00:00Z" });
  const cases: [Db, string, number][] = [
    [empty, `/join/${EVENT}`, 404],
    [empty, "/join/not-a-uuid", 404],
    [completed, `/join/${EVENT}`, 410],
  ];
  for (const [db, path, status] of cases) {
    const res = await eventApp(db).request(path);
    assertEquals(res.status, status, path);
    assertEquals(res.headers.get("Cache-Control"), NO_CACHE);
    const html = await res.text();
    assert(html.includes(`data-view="invalid"`), path);
    assert(!html.includes("Anna"), path);
    assert(html.includes("SnapSync — link expired"), path);
  }
  empty.close();
  completed.close();
});

Deno.test("event page: a store failure is a 502, never the invalid view", async () => {
  const broken: Db = {
    execute: () => Promise.reject(new Error("store down")),
    batch: () => Promise.reject(new Error("store down")),
    transaction: () => Promise.reject(new Error("store down")),
  };
  const res = await eventApp(broken).request(`/join/${EVENT}`);
  assertEquals(res.status, 502);
  assert(!(await res.text()).includes("expired"));
});

Deno.test("event page: a storage failure for the template is a 502; HEAD has no body", async () => {
  const db = await storeWith();
  const failing: FetchLike = () => Promise.resolve(new Response("boom", { status: 500 }));
  assertEquals((await eventApp(db, failing).request(`/join/${EVENT}`)).status, 502);
  const head = await eventApp(db).request(`/join/${EVENT}`, { method: "HEAD" });
  assertEquals(head.status, 200);
  assertEquals(await head.text(), "");
  db.close();
});
