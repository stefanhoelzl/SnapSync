// The v1 ONLY device API: the routes v2 replaces or drops. v1 is spoken by builds that cannot be updated,
// so its behaviour is frozen rather than corrected (`docs/architecture.md`).

import { Hono } from "hono";
import {
  deviceFiles,
  enroll,
  presentMembers,
  publishStatements,
  pushTokensForEvent,
  recordResource,
} from "../db.ts";
import { identityFromLegacyKey } from "../legacy-v1.ts";
import { unsentSummary } from "../push.ts";
import { byteKey } from "../storage.ts";
import { validateFilename, validateUUID } from "../validators.ts";
import { parseManifestAssets } from "./manifest.ts";
import {
  actsFor,
  enrollRefusal,
  eventAndOwnDeviceParams,
  eventParam,
  gateEvent,
  NO_CACHE,
  notThisDevice,
  orUpstream502,
  ownDeviceParam,
  presignDownloadUrl,
  readJson,
  type RouteDeps,
  streamPut,
  tryUpstream,
} from "./support.ts";

// One file in the per-device listing response — exactly `filename` and `url` (a closed shape).
//
// `size` USED TO BE HERE and is gone. It had no reader: the iOS `UnionResource` model omits it,
// `HttpDeviceFilesSource` documents it as an ignored unknown key, and the web zip page reads only
// `role`/`url`/`filename`/`key`. Dropping it is what makes the byte route's database write safe to LOSE
// (`docs/architecture.md`): `size` was the one field sourced from storage rather than the manifest,
// so carrying it would have forced a column only that best-effort write could fill — and one lost write
// would then leave a NULL, make this closed shape unemittable, and silently drop the asset.
type FileEntry = {
  filename: string;
  url: string;
};

/** The routes only `/api/v1` serves, built over `deps`. */
export function v1Routes({ fetchImpl, config, db, now, aws, pushSender }: RouteDeps): Hono {
  // v1 ONLY: the routes v2 replaces or drops. Kept on their own router so each version's route table is
  // CLOSED — a v1-only path under `/api/v2` must be a 404, not an accident of shared mounting.
  const v1Only = new Hono();

  // Per-device byte WRITE route (`docs/architecture.md`). Mounted under
  // `/files/devices/:deviceId/:filename`, so the handlers read `deviceId`/`filename` from the mount.
  // (Downloads are no longer proxied here — the listing hands out a presigned S3 GET URL the device
  // fetches directly from bunny's S3 endpoint.)
  const byteFile = new Hono();

  // Upload — GATED by the device token like every other route, but it reads NO EVENT: bytes are
  // device-partitioned and event-independent, so there is nothing to resolve. Stream the body straight
  // into one bunny native PUT at `files/devices/<deviceId>/<filename>`, then best-effort record the
  // resource row. Faithful: 201 only on a confirmed store; last-write-wins (no existence check on the key).
  byteFile.put("/", async (c) => {
    const deviceId = c.req.param("deviceId");
    const filename = c.req.param("filename");
    if (
      !deviceId || !filename ||
      !validateUUID(deviceId) || !validateFilename(filename)
    ) {
      return c.text("invalid key", 400);
    }
    if (!actsFor(c, deviceId)) return notThisDevice(c);

    const contentType = c.req.header("content-type") ?? "application/octet-stream";
    const refused = await streamPut(
      fetchImpl,
      config,
      c,
      "upload",
      byteKey(deviceId, filename),
      contentType,
    );
    if (refused) return refused;
    // Bunny confirmed the stored object. Record the upload (`docs/architecture.md`) — BEST-EFFORT: this
    // route's success is "the bytes landed", and failing it because a bookkeeping row did not land would
    // turn a successful upload into a retried one.
    //
    // The collapse is safe because the record is REPAIRED, not lost. The device manifest publish is a
    // full-state document listing only uploaded resources, it upserts each resource's `uploaded` as true
    // when the entry does not say otherwise, and it fires in the SAME cycle that produced these bytes.
    // That repair in turn rests on `photo-sharing`'s rule that an unchanged manifest may be skipped
    // only when the LAST WRITE SUCCEEDED — without that word a doubly-failed write would strand
    // `uploaded` at 0 while the device believed it had published, and the photo would be invisible to
    // every other member with no error anywhere. Do not edit one of those two rules alone.
    try {
      // `resources` is keyed by IDENTITY now, and this URL carries only the object NAME — so v1 recovers
      // the identity by parsing it (`docs/architecture.md`; the parse is v1-only and is deleted with v1).
      // A name that is not the client's key shape has no identity to be filed under: the route refuses it
      // rather than inventing one. Every key in the deployed store parses, so this narrowing affects
      // inputs no shipped client produces.
      const identity = identityFromLegacyKey(filename);
      if (identity) {
        await recordResource(db, {
          deviceId,
          assetId: identity.assetId,
          role: identity.role,
          key: filename,
          contentType: c.req.header("content-type") ?? "",
          // v1's URL does not carry the capture name; the object name is the honest stand-in, and the
          // manifest publish overwrites it with the real one on the same cycle.
          filename,
        });
      } else {
        console.error(
          `upload: unparseable legacy key, not recorded: ${byteKey(deviceId, filename)}`,
        );
      }
    } catch (e) {
      console.error(`upload: could not record ${byteKey(deviceId, filename)}: ${e}`);
    }
    return c.body(null, 201);
  });

  // OPTIONS: do NOT advertise resumable uploads → the iOS uploader falls back to a plain PUT.
  byteFile.options("/", (c) => {
    c.header("Allow", "PUT, OPTIONS");
    return c.body(null, 204);
  });

  // Write a device's per-event manifest (capabilities `docs/architecture.md`, `photo-sharing`).
  // GATED on event existence AND capacity (capability `event-lifetime`) by ONE conditional statement: it
  // admits the device when the event exists AND (it already holds a membership — a rejoin reuses its own
  // slot — OR the event has fewer than `capacity` memberships of ANY state, because leaving frees none).
  // The count and the insert are evaluated together, so concurrent first enrollments cannot overshoot.
  // A zero-row outcome CONFLATES at-capacity with no-such-event, so it is disambiguated by a follow-up
  // existence read rather than guessed: absent → 404, otherwise → 409. The body then publishes as ONE
  // atomic batch (see `publishStatements`). Any transport failure → 502 (never mistaken for absent or
  // full).
  //
  // CAPACITY IS THE ONLY REFUSAL. There is no time-based rejection: a device may enroll for as long as
  // the event exists, however long after `endsAt` that is, because a guest who scans days late still
  // holds in-window captures that belong in the event. (The former 410 "event over" is deleted with the
  // grace period.)
  //
  // The count is read-then-write without coordination (bunny has no compare-and-set): concurrent first
  // enrollments may transiently overshoot, accepted — what is guaranteed is that a request OBSERVING the
  // event at capacity admits no new device.
  v1Only.put("/events/:eventId/devices/:deviceId", async (c) => {
    const ids = eventAndOwnDeviceParams(c, "invalid key");
    if (ids instanceof Response) return ids;
    const { eventId, deviceId } = ids;

    // The manifest is a WIRE FORMAT now, not an object: parse it here rather than streaming it to
    // storage. It is bounded by the device's own library, and the whole point of reading it is that the
    // backend records what it says.
    const json = await readJson(c);
    if (json instanceof Response) return json;
    const assets = parseManifestAssets(json.body as { assets?: unknown });
    if (assets === null) return c.text("invalid manifest", 400);

    // Enrollment IS the capacity gate, evaluated and applied in ONE conditional statement so concurrent
    // first enrollments cannot overshoot (capability `event-lifetime`). Its zero-row outcome is resolved to
    // `full` or `no-such-event` inside `enroll`, never collapsed into one status.
    const outcome = await tryUpstream(
      c,
      `device-manifest: enrollment failed for ${eventId}/${deviceId}`,
      () => enroll(db, eventId, deviceId, new Date(now()).toISOString()),
    );
    const refused = enrollRefusal(c, outcome);
    if (refused) return refused;

    // ONE atomic unit: the membership becomes active, the event's asset set for this device is REPLACED
    // wholesale, and every listed resource is upserted. A partial replace must never be observable by
    // the union — which is exactly what a half-applied full-state write would produce.
    return await orUpstream502(
      c,
      `device-manifest: publish failed for ${eventId}/${deviceId}`,
      async () => {
        await db.batch(publishStatements(eventId, deviceId, assets, { legacy: true }));
        return c.body(null, 201);
      },
    );
  });

  // List a device's stored resources (`docs/architecture.md`). Served from the backend's own record
  // of what it accepted — one query — rather than by enumerating storage. Each entry is
  // `{ filename, url }` where `url` is a presigned S3 GET the device fetches directly.
  //
  // Reading the RECORD rather than the byte store is the correct direction for this route's main
  // consumer: the rejoin reconcile seeds `COMPLETED` rows from it (capability
  // `photo-sharing`), and seeding from bytes the backend cannot vouch for would suppress
  // an upload that never happened.
  v1Only.get("/files/devices/:deviceId", async (c) => {
    const deviceId = ownDeviceParam(c, "invalid device");
    if (deviceId instanceof Response) return deviceId;
    return await orUpstream502(c, `list: device listing failed for ${deviceId}`, async () => {
      const stored = await deviceFiles(db, deviceId);
      c.header("Cache-Control", NO_CACHE); // each `url` is a time-limited presigned S3 URL
      const files: FileEntry[] = await Promise.all(stored.map(async (e) => ({
        // `filename` on this route is the STORED OBJECT NAME, not the capture name — that is what the
        // rejoin reconciler matches its ledger keys against.
        filename: e.key,
        url: await presignDownloadUrl(aws, config, deviceId, e.key),
      })));
      return c.json(files);
    });
  });

  // Notify an event's members (capabilities `docs/architecture.md`, `receiving-photos`). GATED on the event row
  // (absent → 404, read failure → 502). Enumerate the PRESENT members with one query — members who left
  // are skipped, which is what makes leaving stop the pushes without stopping the union; a read failure
  // → 502 (nothing enumerable). Then BEST-EFFORT: read every active member's registered push token in ONE
  // statement (no registration → skipped; a failed read → nobody pushed) and send a silent
  // (content-available) push carrying the route's `eventId` in its payload to the rest. One statement,
  // not one per member, because each is a subrequest and Edge allows 50 per request: per-member reads
  // put this route over that wall at about 24 members (`docs/deployment.md`). Read/send failures never fail the request
  // — always a bare 202 once the gate passed and members were enumerated. Server-chosen payload
  // (the path event id), all members, no exclusion; the uploader fires this via `receiving-photos`.
  v1Only.post("/events/:eventId/notify", async (c) => {
    const eventId = eventParam(c);
    if (eventId instanceof Response) return eventId;

    // The lifecycle gate (capability `event-lifetime`): an absent event 404s; an event past its window
    // still notifies — the window closes nothing.
    const event = await gateEvent(db, c, eventId, "notify");
    if (event instanceof Response) return event;

    // Enumerate PRESENT members only — one `state` read. A device that left is not notified.
    const memberIds = await tryUpstream(
      c,
      `notify: member read failed for ${eventId}`,
      () => presentMembers(db, eventId),
    );
    if (memberIds instanceof Response) return memberIds;

    // Best-effort token read — one statement for every member (skips members without a registered
    // token), then fan out.
    const tokens = await pushTokensForEvent(db, eventId).catch(() => []);
    const outcomes = await pushSender.sendSilent(tokens, eventId);
    const sent = outcomes.filter((o) => o.status === "sent").length;
    console.info(
      `notify: event ${eventId} — ${memberIds.length} members, ${tokens.length} with a token, ${sent} pushed` +
        unsentSummary(outcomes),
    );

    return c.body(null, 202);
  });

  // Mount the per-device byte object routes; any unmatched path or wrong method → Hono's 404.
  v1Only.route("/files/devices/:deviceId/:filename", byteFile);
  return v1Only;
}
