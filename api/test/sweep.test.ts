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
// uses, over an in-memory storage fake. NOW is pinned; the config carries the 30-day event lifetime.
// The sweep holds ONLY the storage AccessKey — it makes no request to the Edge Script.
const NOW = Date.parse("2026-07-14T12:00:00Z");
const ZONE = "snap-sync-dev";
const CONFIG = {
  zone: ZONE,
  host: "storage.bunnycdn.com",
  accessKey: "k",
  eventLifetimeSeconds: 30 * 24 * 60 * 60,
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

// `endsAt` no longer participates in staleness at all (capability `event-limits`: it bounds only which
// captures may be UPLOADED). What decides is the derived delete-by, `max(createdAt, startsAt) + lifetime`
// — with `createdAt` pinned at 2026-06-01 by `mkMarker`, a `startsAt` of 2026-06-10 lands the deadline on
// 2026-07-10 (before NOW → STALE) and one of 2026-07-01 lands it on 2026-07-31 (after NOW → LIVE).
const STALE_ENDS = "2026-07-10T00:00:00Z";
const LIVE_ENDS = "2026-08-03T00:00:00Z";
const LIVE_STARTS = "2026-07-01T00:00:00Z";

/**
 * In-memory bunny native-Storage fake: GET an object or (trailing slash) a directory LIST of direct
 * children with `LastChanged`; DELETE (idempotent). Seeded from `{ key: { json, lc } }`.
 *
 * [emptyDirs] seeds directories that EXIST WITH NO CHILDREN — which a pure key-value store cannot
 * otherwise represent, and which is exactly the state this capability turns on: bunny keeps a directory
 * after its last object is deleted (measured 2026-07-26; `events/<id>/` survives holding only an empty
 * `devices/`). Without this the fake makes a swept event's directory vanish, and the husk the sweep must
 * reclaim would be untestable.
 *
 * DELETE on a trailing-slash key is **RECURSIVE**, as bunny documents — it removes every object under the
 * prefix and the directory entries themselves. Every DELETE is recorded in `deletes`, in order, so a test
 * can assert both what was deleted and the SEQUENCE (manifests before the marker).
 */
function fake(
  initial: Record<string, { json?: unknown; lc?: string; len?: number }>,
  emptyDirs: string[] = [],
) {
  const store = new Map<string, { body: string; lc: string; len: number }>();
  for (const [k, v] of Object.entries(initial)) {
    store.set(k, {
      body: v.json === undefined ? "" : JSON.stringify(v.json),
      lc: v.lc ?? "2026-07-01T00:00:00.000Z",
      len: v.len ?? 1,
    });
  }
  const dirs = new Set(emptyDirs);
  const deletes: string[] = [];
  const fetchImpl: FetchLike = (url, init) => {
    const key = url.split(`/${ZONE}/`)[1] ?? "";
    const method = init.method ?? "GET";
    if (method === "GET" && key.endsWith("/")) {
      const children = new Map<string, { name: string; dir: boolean; lc: string; len: number }>();
      let any = dirs.has(key); // the directory itself exists → `200 []`, never a 404
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
      // A seeded empty directory nested directly under `key` is a child directory of it.
      for (const d of dirs) {
        if (d === key || !d.startsWith(key)) continue;
        any = true;
        const name = d.slice(key.length).replace(/\/$/, "");
        if (!name.includes("/") && !children.has(name)) {
          children.set(name, { name, dir: true, lc: "", len: 0 });
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
      deletes.push(key);
      if (key.endsWith("/")) {
        // Recursive, per bunny's contract: the subtree AND the directory entries go.
        let hit = dirs.delete(key);
        for (const k of [...store.keys()]) if (k.startsWith(key)) hit = store.delete(k) || hit;
        for (const d of [...dirs]) if (d.startsWith(key)) hit = dirs.delete(d) || hit;
        return Promise.resolve(new Response(null, { status: hit ? 200 : 404 }));
      }
      return Promise.resolve(new Response(null, { status: store.delete(key) ? 200 : 404 }));
    }
    return Promise.resolve(new Response(null, { status: 405 }));
  };
  return { store, dirs, deletes, fetchImpl };
}

/** A sweep run against the fake, pinned clock. */
function run(store: ReturnType<typeof fake>, dryRun = false) {
  return runSweep({
    fetch: store.fetchImpl,
    config: CONFIG,
    now: () => NOW,
    dryRun,
    log: () => {},
  }).then((summary) => ({ summary }));
}

Deno.test("event phase → an event past its deadline is deleted; one within it is untouched", async () => {
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
  const { summary } = await run(store);
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

Deno.test("event phase → an EMPTIED event is deleted early, before its deadline", async () => {
  // Every enrolled device has departed → opportunistic reclamation (capability `scheduled-cleanup`),
  // even though the deadline is weeks away.
  const E = "e0e0e0e0-0000-4000-8000-0000000000e0";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: mkMarker(E, LIVE_STARTS, LIVE_ENDS) },
    [`events/${E}/devices/${D}.left.json`]: { json: mkManifest(D, "a.heic") },
    [`events/${E}/devices/${D2}.left.json`]: { json: mkManifest(D2, "b.heic") },
  });
  const { summary } = await run(store);
  assert(!store.store.has(`events/${E}/metadata.json`));
  assert(!store.store.has(`events/${E}/devices/${D}.left.json`));
  assertEquals(summary.events.deleted, 1);
});

Deno.test("event phase → ONE active member keeps a within-deadline event alive", async () => {
  const E = "e1e1e1e1-0000-4000-8000-0000000000e1";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: mkMarker(E, LIVE_STARTS, LIVE_ENDS) },
    [`events/${E}/devices/${D}.left.json`]: { json: mkManifest(D, "a.heic") },
    [`events/${E}/devices/${D2}.json`]: { json: mkManifest(D2, "b.heic") }, // still active
  });
  const { summary } = await run(store);
  assert(store.store.has(`events/${E}/metadata.json`));
  assertEquals(summary.events.kept, 1);
});

Deno.test("event phase → a MINTED-BUT-NEVER-JOINED event is not empty and survives", async () => {
  // `POST /events` always produces a zero-device event (the creator confirms through the same join gate
  // a scanned QR uses), so an empty `devices/` listing is the NORMAL state of a fresh mint. Reaping it
  // would delete the event before the host confirms.
  const E = "e2e2e2e2-0000-4000-8000-0000000000e2";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: mkMarker(E, LIVE_STARTS, LIVE_ENDS) },
  });
  const { summary } = await run(store);
  assert(store.store.has(`events/${E}/metadata.json`));
  assertEquals(summary.events.kept, 1);
  assertEquals(summary.events.deleted, 0);
});

Deno.test("tombstone → a swept event's leftover directory is pruned and counted in NEITHER tier", async () => {
  // The husk a previous run left behind: no marker, no manifest, just `events/<id>/` holding an empty
  // `devices/`. Before this branch existed the sweep read the 404 marker, called it stale, and "deleted"
  // and COUNTED it again every night — 40 ids re-reported across five consecutive production nights.
  const GHOST = "0d553167-0000-4000-8000-00000000dead";
  const E_LIVE = "bbbbbbbb-0000-4000-8000-000000000002";
  const store = fake({
    [`events/${E_LIVE}/metadata.json`]: { json: mkMarker(E_LIVE, LIVE_STARTS, LIVE_ENDS) },
    [`events/${E_LIVE}/devices/${D2}.json`]: { json: mkManifest(D2, "l.heic") },
  }, [`events/${GHOST}/`, `events/${GHOST}/devices/`]);

  const { summary } = await run(store);

  // ONE delete, of the event directory — which takes the nested `devices/` husk with it.
  assertEquals(store.deletes.filter((k) => k.startsWith(`events/${GHOST}`)), [
    `events/${GHOST}/`,
  ]);
  assert(!store.dirs.has(`events/${GHOST}/`));
  assert(!store.dirs.has(`events/${GHOST}/devices/`));
  // Neither tier: the events tier counts EVENTS, and a tombstone is not one. Only the live event is kept.
  assertEquals(summary.events.deleted, 0);
  assertEquals(summary.events.kept, 1);
  assertEquals(summary.errors, 0);
  assert(store.store.has(`events/${E_LIVE}/metadata.json`)); // the live event is untouched
});

Deno.test("tombstone → a marker-less directory that STILL HOLDS manifests is incomplete, not a tombstone", async () => {
  // Same `marker === null` condition, different situation: objects remain, so this is the spec's
  // INCOMPLETE case. It must take the careful object-by-object path — never the recursive delete.
  const E = "c0c0c0c0-0000-4000-8000-0000000000c0";
  const store = fake({
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "a.heic") },
    [`events/${E}/devices/${D2}.left.json`]: { json: mkManifest(D2, "b.heic") },
  });

  const { summary } = await run(store);

  // No directory-shaped delete was issued for it…
  assertEquals(store.deletes.filter((k) => k.endsWith("/")), []);
  // …and its manifests went individually, with the (absent) marker attempted LAST.
  assertEquals(store.deletes, [
    `events/${E}/devices/${D}.json`,
    `events/${E}/devices/${D2}.left.json`,
    `events/${E}/metadata.json`,
  ]);
  assertEquals(summary.events.deleted, 1); // counted as a deleted EVENT, unlike a tombstone
  assertEquals(summary.errors, 0); // the marker DELETE 404s, and that is success
});

Deno.test("tombstone → dry-run names each husk it would prune and deletes nothing", async () => {
  const G1 = "0fe23ac3-0000-4000-8000-00000000dea1";
  const G2 = "10816a75-0000-4000-8000-00000000dea2";
  const lines: string[] = [];
  const store = fake({}, [`events/${G1}/`, `events/${G1}/devices/`, `events/${G2}/`]);

  const summary = await runSweep({
    fetch: store.fetchImpl,
    config: CONFIG,
    now: () => NOW,
    dryRun: true,
    log: (m) => lines.push(m),
  });

  assertEquals(store.deletes, []); // dry-run deletes NOTHING
  assert(store.dirs.has(`events/${G1}/`));
  assert(store.dirs.has(`events/${G2}/`));
  // Each husk is named individually, so the directories a real run would remove can be inspected first.
  const pruneLines = lines.filter((l) => l.includes("would prune empty directory"));
  assertEquals(pruneLines.length, 2);
  assertStringIncludes(pruneLines.join("\n"), `events/${G1}/`);
  assertStringIncludes(pruneLines.join("\n"), `events/${G2}/`);
  assertEquals(summary.events.deleted, 0); // still neither tier, even in dry-run
  assertEquals(summary.events.kept, 0);
});

Deno.test("tombstone → a real stale event deletes manifests BEFORE its marker, and no directory", async () => {
  // The marker is what makes an event exist, so it goes LAST: an interrupted run leaves a still-existing
  // event the next run reclaims cleanly. Pinned as an ORDER, because a recursive delete would hand the
  // ordering to bunny, which documents no atomicity.
  const E = "aaaaaaaa-0000-4000-8000-00000000aaaa";
  const store = fake({
    [`events/${E}/metadata.json`]: { json: mkMarker(E, "2026-06-10T00:00:00Z", STALE_ENDS) },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "s.heic") },
  });

  const { summary } = await run(store);

  assertEquals(store.deletes, [
    `events/${E}/devices/${D}.json`, // manifests first…
    `events/${E}/metadata.json`, // …marker LAST
  ]);
  assertEquals(store.deletes.filter((k) => k.endsWith("/")), []); // never recursive
  assertEquals(summary.events.deleted, 1);
});

Deno.test("tombstone → a fully-orphaned device's byte DIRECTORY is deliberately left in place", async () => {
  // The byte upload is UNGATED, so a recursive delete of the partition could destroy an upload that
  // landed after the listing and was therefore never seen — a photo the device's ledger already records
  // as uploaded and will never send again. The husk is retained on purpose (design D3).
  const store = fake({
    [`files/devices/${ORPHAN}/old.heic`]: { lc: "2026-06-01T00:00:00.000Z", len: 10 },
    [`devices/${ORPHAN}.json`]: { json: { pushToken: "t" } },
  });

  const { summary } = await run(store);

  assert(!store.store.has(`files/devices/${ORPHAN}/old.heic`)); // the byte object IS collected
  // …but no directory delete was issued anywhere, for the partition or above it.
  assertEquals(store.deletes.filter((k) => k.endsWith("/")), []);
  assertEquals(summary.files.deleted.count, 1);
});

Deno.test("event phase → the deadline anchors at max(createdAt, startsAt), both directions", async () => {
  // BACK-DATED: startsAt five weeks before createdAt. Anchoring on startsAt alone would stamp the event
  // dead on arrival; anchoring at the max gives it createdAt + 30d = 2026-07-01 … still before NOW here,
  // so pair it with a fresh createdAt to show it survives.
  const BACKDATED = "e3e3e3e3-0000-4000-8000-0000000000e3";
  // CREATED-EARLY: startsAt three weeks AFTER createdAt → anchored on startsAt, it outlives the window
  // it declares rather than dying nine days in.
  const EARLY = "e4e4e4e4-0000-4000-8000-0000000000e4";
  const store = fake({
    [`events/${BACKDATED}/metadata.json`]: {
      json: {
        eventId: BACKDATED,
        name: "e",
        createdAt: "2026-07-13T00:00:00.000Z", // yesterday
        startsAt: "2026-06-05T00:00:00Z", // the trip was five weeks ago
        endsAt: "2026-06-12T00:00:00Z",
        capacity: 10,
      },
    },
    [`events/${EARLY}/metadata.json`]: {
      json: {
        eventId: EARLY,
        name: "e",
        createdAt: "2026-06-20T00:00:00.000Z",
        startsAt: "2026-07-11T00:00:00Z", // three weeks after creation
        endsAt: "2026-07-18T00:00:00Z",
        capacity: 10,
      },
    },
  });
  const { summary } = await run(store);
  // Back-dated: anchor = createdAt (2026-07-13) + 30d → 2026-08-12, well after NOW. NOT dead on arrival.
  assert(store.store.has(`events/${BACKDATED}/metadata.json`));
  // Created-early: anchor = startsAt (2026-07-11) + 30d → 2026-08-10. Had it anchored on createdAt it
  // would be 2026-07-20 — still alive here, but it would die mid-window for a longer lead time.
  assert(store.store.has(`events/${EARLY}/metadata.json`));
  assertEquals(summary.events.kept, 2);
});

Deno.test("event phase → a marker's OWN stamped lifetime wins over the configured fallback", async () => {
  // The stamped value is what makes a config change unable to reach a live event. Here a 1-day lifetime
  // is stamped on an event the 30-day fallback would have kept alive.
  const SHORT = "e5e5e5e5-0000-4000-8000-0000000000e5";
  const LONG = "e6e6e6e6-0000-4000-8000-0000000000e6";
  const store = fake({
    [`events/${SHORT}/metadata.json`]: {
      json: { ...mkMarker(SHORT, LIVE_STARTS, LIVE_ENDS), lifetimeSeconds: 24 * 60 * 60 },
    },
    [`events/${LONG}/metadata.json`]: {
      json: { ...mkMarker(LONG, LIVE_STARTS, LIVE_ENDS), lifetimeSeconds: 365 * 24 * 60 * 60 },
    },
  });
  const { summary } = await run(store);
  assert(!store.store.has(`events/${SHORT}/metadata.json`)); // 2026-07-01 + 1d → long past NOW
  assert(store.store.has(`events/${LONG}/metadata.json`)); // 2026-07-01 + 365d → far future
  assertEquals(summary.events.deleted, 1);
  assertEquals(summary.events.kept, 1);
});

Deno.test("event phase → an event past its WINDOW but within its lifetime is untouched", async () => {
  // The whole point of the decoupling: `endsAt` closes nothing. Members keep syncing their backlog and
  // late guests can still join, for as long as the event exists.
  const E = "e7e7e7e7-0000-4000-8000-0000000000e7";
  const store = fake({
    // startsAt 2026-07-01 → deadline 2026-07-31; endsAt 2026-07-05 → the window closed 9 days ago.
    [`events/${E}/metadata.json`]: { json: mkMarker(E, LIVE_STARTS, "2026-07-05T00:00:00Z") },
    [`events/${E}/devices/${D}.json`]: { json: mkManifest(D, "a.heic") },
  });
  const { summary } = await run(store);
  assert(store.store.has(`events/${E}/metadata.json`));
  assertEquals(summary.events.kept, 1);
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

Deno.test("dry-run → deletes NOTHING, but counts the candidates", async () => {
  const E_STALE = "34343434-0000-4000-8000-000000000008";
  const store = fake({
    [`events/${E_STALE}/metadata.json`]: {
      json: mkMarker(E_STALE, "2026-06-10T00:00:00Z", STALE_ENDS),
    },
    [`files/devices/${ORPHAN}/x.heic`]: { json: {}, lc: "2026-07-13T00:00:00.000Z" },
    [`devices/${ORPHAN}.json`]: { json: {} },
  });
  const before = store.store.size;
  const { summary } = await run(store, true);
  assertEquals(store.store.size, before); // nothing deleted
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
