import { assert, assertEquals } from "@std/assert";
import { runSweep } from "../src/sweep.ts";
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
function fake(initial: Record<string, { json?: unknown; lc?: string }>) {
  const store = new Map<string, { body: string; lc: string }>();
  for (const [k, v] of Object.entries(initial)) {
    store.set(k, {
      body: v.json === undefined ? "" : JSON.stringify(v.json),
      lc: v.lc ?? "2026-07-01T00:00:00.000Z",
    });
  }
  const fetchImpl: FetchLike = (url, init) => {
    const key = url.split(`/${ZONE}/`)[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) {
      const children = new Map<string, { name: string; dir: boolean; lc: string }>();
      let any = false;
      for (const [k, v] of store) {
        if (!k.startsWith(key)) continue;
        any = true;
        const rest = k.slice(key.length);
        const slash = rest.indexOf("/");
        if (slash === -1) children.set(rest, { name: rest, dir: false, lc: v.lc });
        else {
          const d = rest.slice(0, slash);
          if (!children.has(d)) children.set(d, { name: d, dir: true, lc: "" });
        }
      }
      if (!any) return Promise.resolve(new Response("nf", { status: 404 }));
      const entries = [...children.values()].map((e) => ({
        ObjectName: e.name,
        IsDirectory: e.dir,
        Length: 1,
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
  assertEquals(summary.eventsDeleted, 1);
});

Deno.test("event phase → a legacy marker (no endsAt) is stale and deleted", async () => {
  const E = "cccccccc-0000-4000-8000-000000000003";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: { eventId: E, name: "old", createdAt: "t" } },
  });
  const { summary } = await run(store);
  assert(!store.store.has(`events/${E}/metadata.json`));
  assertEquals(summary.eventsDeleted, 1);
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
  assertEquals(summary.bytesCollected, 1);
  assertEquals(summary.bytesRetained, 2);
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
  assertEquals(summary.deviceRecordsCollected, 2); // ORPHAN's config + attest
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
  assertEquals(summary.deviceRecordsCollected, 0);
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
  assertEquals(summary.bytesCollected, 1);
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
  assertEquals(summary.bytesCollected, 0);
  assertEquals(summary.bytesRetained, 1);
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
  assertEquals(summary.eventsDeleted, 1); // …but the candidate counts are reported
  assertEquals(summary.bytesCollected, 1);
  assertEquals(summary.deviceRecordsCollected, 1);
});
