import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import {
  formatSummary,
  humanBytes,
  markdownSummary,
  runSweep,
  type SweepSummary,
} from "../src/sweep.ts";
import type { Config } from "../src/config.ts";
import type { FetchLike } from "../src/storage.ts";

// The sweep (capability `scheduled-cleanup`) drives the SAME storage/lifecycle modules the Edge Script
// uses, over an in-memory storage fake. NOW is pinned; the config carries the 1-day grace period.
const NOW = Date.parse("2026-07-14T12:00:00Z");
const ZONE = "snap-sync-dev";
const CONFIG = {
  zone: ZONE,
  host: "storage.bunnycdn.com",
  accessKey: "k",
  eventGraceSeconds: 24 * 60 * 60,
  adminKey: "admin",
  linkDomain: "snapsync.test",
} as unknown as Config;

const D = "11111111-0000-4000-8000-000000000001";
const D2 = "22222222-0000-4000-8000-000000000002";
const ORPHAN = "99999999-0000-4000-8000-000000000009";

const mkMarker = (eventId: string, startsAt: string, endsAt: string) => ({
  eventId,
  name: "e",
  createdAt: "2026-06-01T00:00:00.000Z",
  startsAt,
  endsAt,
  capacity: 10,
});
const mkManifest = (deviceId: string, ...keys: string[]) => ({
  deviceId,
  assets: keys.map((k, i) => ({
    assetId: `A${i}`,
    creationDate: "2026-07-01T00:00:00Z",
    resources: [{ role: "primary", contentType: "image/heic", key: k, filename: k }],
  })),
});

// endsAt 4 days before NOW → past the 1-day grace → STALE; endsAt 20 days after NOW → LIVE.
const STALE_ENDS = "2026-07-10T00:00:00Z";
const LIVE_ENDS = "2026-08-03T00:00:00Z";

/**
 * In-memory bunny native-Storage fake: GET an object or (trailing slash) a directory LIST of direct
 * children with `LastChanged`; DELETE (idempotent). Seeded from `{ key: { json, lc } }`.
 */
function fake(initial: Record<string, { json?: unknown; lc?: string; len?: number }>) {
  const store = new Map<string, { body: string; lc: string; len: number }>();
  for (const [k, v] of Object.entries(initial)) {
    store.set(k, {
      body: v.json === undefined ? "" : JSON.stringify(v.json),
      lc: v.lc ?? "2026-07-01T00:00:00.000Z",
      len: v.len ?? 1,
    });
  }
  const fetchImpl: FetchLike = (url, init) => {
    const key = url.split(`/${ZONE}/`)[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) {
      const children = new Map<string, { name: string; dir: boolean; lc: string; len: number }>();
      let any = false;
      for (const [k, v] of store) {
        if (!k.startsWith(key)) continue;
        any = true;
        const rest = k.slice(key.length);
        const slash = rest.indexOf("/");
        if (slash === -1) children.set(rest, { name: rest, dir: false, lc: v.lc, len: v.len });
        else {
          const d = rest.slice(0, slash);
          if (!children.has(d)) children.set(d, { name: d, dir: true, lc: "", len: 0 });
        }
      }
      if (!any) return Promise.resolve(new Response("nf", { status: 404 }));
      const entries = [...children.values()].map((e) => ({
        ObjectName: e.name,
        IsDirectory: e.dir,
        Length: e.len,
        LastChanged: e.lc,
      }));
      return Promise.resolve(new Response(JSON.stringify(entries), { status: 200 }));
    }
    if (method === "GET") {
      const v = store.get(key);
      return Promise.resolve(
        v ? new Response(v.body, { status: 200 }) : new Response("nf", { status: 404 }),
      );
    }
    if (method === "DELETE") {
      return Promise.resolve(new Response(null, { status: store.delete(key) ? 200 : 404 }));
    }
    return Promise.resolve(new Response(null, { status: 405 }));
  };
  return { store, fetchImpl };
}

/** A sweep run with a notify spy, pinned clock. */
function run(store: ReturnType<typeof fake>, dryRun = false) {
  const notified: string[] = [];
  return runSweep({
    fetch: store.fetchImpl,
    config: CONFIG,
    now: () => NOW,
    dryRun,
    notify: (id) => {
      notified.push(id);
      return Promise.resolve();
    },
    log: () => {},
  }).then((summary) => ({ summary, notified }));
}

Deno.test("event phase → a stale event is notified then deleted; a live event is untouched", async () => {
  const E_STALE = "aaaaaaaa-0000-4000-8000-000000000001";
  const E_LIVE = "bbbbbbbb-0000-4000-8000-000000000002";
  const store = fake({
    [`events/${E_STALE}/metadata.json`]: {
      json: mkMarker(E_STALE, "2026-06-10T00:00:00Z", STALE_ENDS),
    },
    [`events/${E_STALE}/devices/${D}.json`]: { json: mkManifest(D, "s.heic") },
    [`events/${E_LIVE}/metadata.json`]: {
      json: mkMarker(E_LIVE, "2026-07-01T00:00:00Z", LIVE_ENDS),
    },
    [`events/${E_LIVE}/devices/${D2}.json`]: { json: mkManifest(D2, "l.heic") },
  });
  const { summary, notified } = await run(store);
  assertEquals(notified, [E_STALE]); // the stale event's members were notified (not the live one)
  assert(!store.store.has(`events/${E_STALE}/metadata.json`)); // marker deleted
  assert(!store.store.has(`events/${E_STALE}/devices/${D}.json`)); // manifest deleted
  assert(store.store.has(`events/${E_LIVE}/metadata.json`)); // live event kept
  assert(store.store.has(`events/${E_LIVE}/devices/${D2}.json`));
  assertEquals(summary.events.deleted, 1);
  assertEquals(summary.events.kept, 1); // the live event survived
});

Deno.test("event phase → a legacy marker (no endsAt) is stale and deleted", async () => {
  const E = "cccccccc-0000-4000-8000-000000000003";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: { eventId: E, name: "old", createdAt: "t" } },
  });
  const { summary } = await run(store);
  assert(!store.store.has(`events/${E}/metadata.json`));
  assertEquals(summary.events.deleted, 1);
});

Deno.test("asset phase → referenced kept; unreferenced-below-floor collected; unreferenced-above-floor kept", async () => {
  const E = "dddddddd-0000-4000-8000-000000000004";
  const store = fake({
    // Live event, device D active, startsAt 2026-07-01 → D's floor is 2026-07-01.
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-07-01T00:00:00Z", LIVE_ENDS) },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "ref.heic") },
    [`files/devices/${D}/ref.heic`]: { json: {}, lc: "2026-07-05T00:00:00.000Z" }, // REFERENCED
    [`files/devices/${D}/old.heic`]: { json: {}, lc: "2026-06-01T00:00:00.000Z" }, // unref, BEFORE floor
    [`files/devices/${D}/new.heic`]: { json: {}, lc: "2026-07-10T00:00:00.000Z" }, // unref, AFTER floor
  });
  const { summary } = await run(store);
  assert(store.store.has(`files/devices/${D}/ref.heic`)); // referenced by the live manifest → kept
  assert(!store.store.has(`files/devices/${D}/old.heic`)); // unreferenced + pre-floor → collected
  assert(store.store.has(`files/devices/${D}/new.heic`)); // unreferenced but post-floor (live) → kept
  assertEquals(summary.files.deleted.count, 1);
  assertEquals(summary.files.kept.count, 2);
});

Deno.test("asset phase → a device in NO surviving event loses all bytes + config + attestation", async () => {
  const E = "eeeeeeee-0000-4000-8000-000000000005";
  const store = fake({
    // A surviving event keeps D2 alive — but ORPHAN is in no event, so its floor is +∞.
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-07-01T00:00:00Z", LIVE_ENDS) },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "keep.heic") },
    [`files/devices/${D2}/keep.heic`]: { json: {}, lc: "2026-07-05T00:00:00.000Z" },
    // ORPHAN: even a RECENT byte is collected (+∞ floor), plus its config + attest record.
    [`files/devices/${ORPHAN}/x.heic`]: { json: {}, lc: "2026-07-13T00:00:00.000Z" },
    [`devices/${ORPHAN}.json`]: { json: { pushToken: {} } },
    [`devices/${ORPHAN}.attest.json`]: { json: { publicKey: "AA==" } },
    [`devices/${D2}.json`]: { json: { pushToken: {} } },
  });
  const { summary } = await run(store);
  assert(!store.store.has(`files/devices/${ORPHAN}/x.heic`)); // recent, but no surviving event → collected
  assert(!store.store.has(`devices/${ORPHAN}.json`)); // config collected
  assert(!store.store.has(`devices/${ORPHAN}.attest.json`)); // attestation collected
  assert(store.store.has(`files/devices/${D2}/keep.heic`)); // D2 is in a surviving event → kept
  assert(store.store.has(`devices/${D2}.json`)); // …so its config is kept
  assertEquals(summary.devices.deleted, 1); // ORPHAN — one device (its config + attest are its 2 records)
  assertEquals(summary.devices.kept, 1); // D2 appears in the surviving event
});

Deno.test("asset phase → a departed-in-surviving-event device keeps its referenced bytes + records", async () => {
  const E = "ffffffff-0000-4000-8000-000000000006";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-07-01T00:00:00Z", LIVE_ENDS) },
    // D has LEFT E (its .left.json references dep.heic), but E survives — so D still appears in it.
    [`events/${E}/devices/${D}.left.json`]: { json: mkManifest(D, "dep.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "act.heic") },
    [`files/devices/${D}/dep.heic`]: { json: {}, lc: "2026-06-15T00:00:00.000Z" }, // referenced by .left
    [`devices/${D}.json`]: { json: { pushToken: {} } },
  });
  const { summary } = await run(store);
  assert(store.store.has(`files/devices/${D}/dep.heic`)); // referenced by the surviving .left manifest → kept
  assert(store.store.has(`devices/${D}.json`)); // D appears in a surviving event → config kept
  assertEquals(summary.devices.deleted, 0);
  assertEquals(summary.devices.kept, 1); // D is kept (departed, but in a surviving event)
});

Deno.test("switch → leftover bytes from a swept prior event are collected while the device stays active in a newer one", async () => {
  const E_NEW = "12121212-0000-4000-8000-000000000007";
  const store = fake({
    // The prior (Jan) event is already gone; D is active in the NEW (July) event. Its floor is July.
    [`events/${E_NEW}/metadata.json`]: { json: mkMarker(E_NEW, "2026-07-01T00:00:00Z", LIVE_ENDS) },
    [`events/${E_NEW}/devices/${D}.json`]: { json: mkManifest(D, "july.heic") },
    [`files/devices/${D}/july.heic`]: { json: {}, lc: "2026-07-05T00:00:00.000Z" }, // referenced → kept
    [`files/devices/${D}/jan.heic`]: { json: {}, lc: "2026-01-10T00:00:00.000Z" }, // pre-July leftover
  });
  const { summary } = await run(store);
  assert(store.store.has(`files/devices/${D}/july.heic`));
  assert(!store.store.has(`files/devices/${D}/jan.heic`)); // uploaded before the new event's start → collected
  assertEquals(summary.files.deleted.count, 1);
});

Deno.test("asset phase → a referenced byte with a percent-encoded filename is matched (decoded) and kept", async () => {
  // The byte is STORED under its encoded name (`IMG%20001.jpg`) but the manifest names the DECODED key
  // (`IMG 001.jpg`), exactly as the union's completeness check compares them. Its upload time is before
  // the floor, so a naive raw-name compare would wrongly collect it — the decode is what saves it.
  const E = "56565656-0000-4000-8000-000000000009";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-07-01T00:00:00Z", LIVE_ENDS) },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "IMG 001.jpg") },
    [`files/devices/${D}/IMG%20001.jpg`]: { json: {}, lc: "2026-06-01T00:00:00.000Z" }, // pre-floor
  });
  const { summary } = await run(store);
  assert(store.store.has(`files/devices/${D}/IMG%20001.jpg`)); // decoded match → referenced → kept
  assertEquals(summary.files.deleted.count, 0);
  assertEquals(summary.files.kept.count, 1);
});

Deno.test("dry-run → deletes NOTHING, notifies NOTHING, but counts the candidates", async () => {
  const E_STALE = "34343434-0000-4000-8000-000000000008";
  const store = fake({
    [`events/${E_STALE}/metadata.json`]: {
      json: mkMarker(E_STALE, "2026-06-10T00:00:00Z", STALE_ENDS),
    },
    [`files/devices/${ORPHAN}/x.heic`]: { json: {}, lc: "2026-07-13T00:00:00.000Z" },
    [`devices/${ORPHAN}.json`]: { json: {} },
  });
  const before = store.store.size;
  const { summary, notified } = await run(store, true);
  assertEquals(store.store.size, before); // nothing deleted
  assertEquals(notified, []); // dry-run never notifies (a side effect)
  assertEquals(summary.dryRun, true);
  assertEquals(summary.events.deleted, 1); // …but the candidate counts are reported
  assertEquals(summary.files.deleted.count, 1);
  assertEquals(summary.devices.deleted, 1);
});

Deno.test("summary → file bytes are the SUM of each entry's Length, not the object count", async () => {
  const E = "78787878-0000-4000-8000-00000000000a";
  const store = fake({
    // Device D active, floor = 2026-07-01.
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-07-01T00:00:00Z", LIVE_ENDS) },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "ref.heic") },
    // KEPT — 2 objects, 1000 + 2048 bytes: ref.heic (referenced) + new.heic (unref but post-floor).
    [`files/devices/${D}/ref.heic`]: { json: {}, lc: "2026-07-05T00:00:00.000Z", len: 1000 },
    [`files/devices/${D}/new.heic`]: { json: {}, lc: "2026-07-10T00:00:00.000Z", len: 2048 },
    // COLLECTED — 2 objects, 500 + 1500 bytes: both unreferenced + pre-floor.
    [`files/devices/${D}/a.heic`]: { json: {}, lc: "2026-06-01T00:00:00.000Z", len: 500 },
    [`files/devices/${D}/b.heic`]: { json: {}, lc: "2026-06-02T00:00:00.000Z", len: 1500 },
  });
  const { summary } = await run(store);
  assertEquals(summary.files.deleted.count, 2);
  assertEquals(summary.files.deleted.bytes, 2000); // 500 + 1500 — NOT the count (2)
  assertEquals(summary.files.kept.count, 2);
  assertEquals(summary.files.kept.bytes, 3048); // 1000 + 2048
});

Deno.test("humanBytes → renders IEC-ish sizes; < 1024 stays bytes", () => {
  assertEquals(humanBytes(0), "0 B");
  assertEquals(humanBytes(512), "512 B");
  assertEquals(humanBytes(1024), "1.0 KB");
  assertEquals(humanBytes(1536), "1.5 KB");
  assertEquals(humanBytes(5 * 1024 * 1024), "5.0 MB");
  assertEquals(humanBytes(3 * 1024 * 1024 * 1024), "3.0 GB");
});

Deno.test("formatSummary → one line per tier, files show count and reclaimed size, dry-run flagged", () => {
  const s: SweepSummary = {
    events: { deleted: 40, kept: 1 },
    devices: { deleted: 20, kept: 2 },
    files: { deleted: { count: 107, bytes: 12_900_000 }, kept: { count: 10, bytes: 3_100_000 } },
    errors: 0,
    dryRun: true,
  };
  const out = formatSummary(s);
  assertStringIncludes(out, "sweep summary (dry-run):");
  assertStringIncludes(out, "events    40 deleted   1 kept");
  assertStringIncludes(out, "devices   20 deleted   2 kept");
  assertStringIncludes(out, "files     107 (12.3 MB) deleted   10 (3.0 MB) kept");
  assertStringIncludes(out, "errors    0");
  // A real (non-dry) run drops the suffix.
  assertStringIncludes(formatSummary({ ...s, dryRun: false }), "sweep summary:");
});

Deno.test("markdownSummary → a GFM table with a row per tier and an errors line", () => {
  const s: SweepSummary = {
    events: { deleted: 40, kept: 1 },
    devices: { deleted: 20, kept: 2 },
    files: { deleted: { count: 107, bytes: 12_900_000 }, kept: { count: 10, bytes: 3_100_000 } },
    errors: 3,
    dryRun: false,
  };
  const md = markdownSummary(s);
  assertStringIncludes(md, "## Nightly cleanup sweep");
  assertStringIncludes(md, "| tier | deleted | kept |");
  assertStringIncludes(md, "| events | 40 | 1 |");
  assertStringIncludes(md, "| devices | 20 | 2 |");
  assertStringIncludes(md, "| files | 107 (12.3 MB) | 10 (3.0 MB) |");
  assertStringIncludes(md, "**errors:** 3");
  // Dry-run is flagged in the heading.
  assertStringIncludes(markdownSummary({ ...s, dryRun: true }), "(dry-run — nothing deleted)");
});

// The browser-facing site (capability `web-site`) lives under a `site/` prefix, co-tenant with the private
// data in the same zone. The sweep is PREFIX-SCOPED — it enumerates only `events/`, `files/devices/` and
// `devices/` — so `site/` is invisible to it, and its hygiene is the mirror-deploy's job, not the sweep's.
// This pins that load-bearing invariant: a future "simplify the sweep to a whole-zone walk" would delete
// the live site, and this test would catch it.
Deno.test("site/ prefix is never touched by the sweep", async () => {
  const E = "aaaaaaaa-0000-4000-8000-0000000000ff";
  const store = fake({
    // A stale event so the sweep actually runs both phases (and would delete broadly if mis-scoped).
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-06-01T00:00:00Z", STALE_ENDS) },
    // Built-site objects that MUST survive.
    ["site/index.html"]: { lc: "2026-06-01T00:00:00.000Z" },
    ["site/join/index.html"]: { lc: "2026-06-01T00:00:00.000Z" },
    ["site/_astro/app.abcdef12.js"]: { lc: "2026-06-01T00:00:00.000Z" },
  });
  await run(store);
  assert(store.store.has("site/index.html"), "the sweep deleted the landing page");
  assert(store.store.has("site/join/index.html"), "the sweep deleted the join page");
  assert(store.store.has("site/_astro/app.abcdef12.js"), "the sweep deleted a site asset");
});
