// The v2 ONLY device API (`docs/architecture.md`): the byte upload by identity, the identity-terms
// listing, the join, and the manifest sub-resource whose publish carries the fan-out.

import { type Context, Hono } from "hono";
import {
  declaredAssets,
  deviceResources,
  enroll,
  type EventRow,
  eventsCompletedBy,
  isMember,
  type ManifestAssetEntry,
  publishAddsFetchableAsset,
  publishStatements,
  pushTokensForEvent,
  recordResource,
  stampLanded,
} from "../db.ts";
import { legacyKeyFor, RESOURCE_ROLES } from "../legacy-v1.ts";
import { unsentSummary } from "../push.ts";
import { byteKey } from "../storage.ts";
import { validateFilename } from "../validators.ts";
import { parseManifestBody } from "./manifest.ts";
import {
  closedRefusal,
  enrollRefusal,
  eventAndOwnDeviceParams,
  gateEvent,
  NO_CACHE,
  orUpstream502,
  ownDeviceParam,
  readJson,
  type RouteDeps,
  streamPut,
  tryUpstream,
  upstream502,
} from "./support.ts";

// The v2 fan-out's own bound. Generous next to the work (capacity is 10, and the sends are parallel over
// one HTTP/2 connection) but well inside the device's 12-second budget for the publish that carries it —
// so a stalled APNs socket costs a notification, never the write.
const FANOUT_TIMEOUT_MS = 4000;

/** The routes only `/api/v2` serves, built over `deps`. */
export function v2Routes({ fetchImpl, config, db, now, pushSender }: RouteDeps): Hono {
  /**
   * A publish to a CLOSED event (capability `photo-sharing`, "What a member shares is fixed once the event
   * has closed"): the set it already declared is answered as published and changes nothing, so a device
   * whose publish raced the close is not left retrying; any other set is refused `409 closed`.
   */
  async function publishToClosed(
    c: Context,
    eventId: string,
    deviceId: string,
    assets: ManifestAssetEntry[],
  ): Promise<Response> {
    try {
      const stored = await declaredAssets(db, eventId, deviceId);
      const same = stored.size === assets.length &&
        assets.every((a) =>
          stored.get(a.assetId) === a.resources.map((r) => r.role).sort().join(",")
        );
      return same ? c.body(null, 200) : c.json({ error: "closed" }, 409);
    } catch (e) {
      return upstream502(c, `v2 manifest: declared-set read failed for ${eventId}/${deviceId}`, e);
    }
  }

  /**
   * Apply a v2 publish as ONE batch (see `publishStatements`) and read its two verdicts: `won` — the first
   * statement's count, whether the version comparison admitted it — and `closed` — the close stamp's
   * count, whether THIS publish left every active membership final and so closed the event.
   *
   * Only after the range has ended may a publish settle anything, or close the event: before it, the
   * device may still take photos that belong in the event (capability `photo-sharing`).
   */
  async function applyPublish(
    c: Context,
    event: EventRow,
    deviceId: string,
    assets: ManifestAssetEntry[],
    version: number | null,
    final: boolean,
  ): Promise<{ won: boolean; closed: boolean } | Response> {
    const nowMs = now();
    const ended = nowMs > Date.parse(event.endsAt);
    const closeAt = ended ? new Date(nowMs).toISOString() : null;
    try {
      const results = await db.batch(
        publishStatements(event.eventId, deviceId, assets, {
          legacy: false,
          version,
          final: final && ended,
          closeAt,
        }),
      );
      return {
        won: results[0].rowsAffected > 0,
        // The close stamp is the batch's last statement when present.
        closed: closeAt !== null && results[results.length - 1].rowsAffected > 0,
      };
    } catch (e) {
      return upstream502(c, `v2 manifest: publish failed for ${event.eventId}/${deviceId}`, e);
    }
  }

  /**
   * The v2 fan-out: wake the event's other active members so they read the union.
   *
   * BEST-EFFORT inside a faithful write. The caller's response is its transaction's outcome and is never
   * changed by a push that failed, was skipped for a member with no token, or timed out — the same split
   * the byte route already draws for its database write. A failed notification costs one member a delayed
   * download, which the next foreground read repairs; failing the publish would cost the manifest itself,
   * and the device's skip-if-unchanged would then not retry it.
   *
   * BOUNDED, because bounded member count does not bound a stalled socket. The publish runs synchronously
   * inside the device's own upload cycle under an OS deadline, so a hung APNs connection here would make
   * the device time out a write that actually committed — and the next cycle would skip it as unchanged.
   */
  async function notifyMembers(eventId: string, publisherId: string): Promise<void> {
    try {
      const tokens = await pushTokensForEvent(db, eventId, publisherId);
      if (tokens.length === 0) return;
      const outcomes = await Promise.race([
        pushSender.sendSilent(tokens, eventId),
        new Promise<never>((_, reject) =>
          setTimeout(() => reject(new Error("fan-out timed out")), FANOUT_TIMEOUT_MS)
        ),
      ]);
      const sent = outcomes.filter((o) => o.status === "sent").length;
      console.info(
        `v2 notify: event ${eventId} — ${tokens.length} recipients, ${sent} pushed${
          unsentSummary(outcomes)
        }`,
      );
    } catch (e) {
      console.error(`v2 notify: fan-out failed for ${eventId} (best-effort, publish stands): ${e}`);
    }
  }

  // ── THE v2 DEVICE API ───────────────────────────────────────────────────────────────────────────
  //
  // v2 exists because v1's shapes are frozen by the shipped install base, and the structural corrections
  // this backend needs cannot be made additively. What differs is small and deliberate:
  //
  //   * the byte upload names its resource by IDENTITY in the path, not by a synthetic object name;
  //   * the per-device listing answers "what do you hold for me" in those same terms, and mints no url;
  //   * joining is its OWN route, so `memberships` has one writer;
  //   * the manifest is a sub-resource that only replaces the asset set — it enrolls nothing and records
  //     no upload, so `resources` has one writer too;
  //   * there is no notify route: the fan-out is an effect of the manifest publish, where its ordering
  //     against the union is guaranteed by construction rather than by a comment.
  //
  // Everything else is the SHARED router — one implementation, mounted into both versions.
  const v2Only = new Hono();

  // Upload one resource's bytes. Identity comes from the PATH (`<assetId>/<role>`) and the capture name
  // from a REQUIRED query parameter, which is what keeps caller-supplied bytes out of the storage key
  // entirely: v1 had to validate a filename segment for traversal, and here the value never reaches the
  // key, so the rule is not relaxed but made unnecessary.
  v2Only.put("/files/devices/:deviceId/:assetId/:role", async (c) => {
    const deviceId = ownDeviceParam(c, "invalid device");
    if (deviceId instanceof Response) return deviceId;
    const assetId = c.req.param("assetId");
    const role = c.req.param("role");
    const filename = new URL(c.req.url).searchParams.get("filename");
    if (!validateFilename(assetId)) return c.text("invalid asset", 400);
    // The role vocabulary is CLOSED, and the route validates it rather than storing whatever it is given.
    // v1 could not — its role arrived inside an opaque object name — which is why an unknown role is a
    // narrowing v2 gets for free from naming identity in the path.
    if (!RESOURCE_ROLES.includes(role)) return c.text("invalid role", 400);
    if (filename === null || filename === "") return c.text("missing filename", 400);

    // The stored object name is composed HERE, and is byte-identical to what v1 composes for the same
    // resource. That is load-bearing rather than tidy: a device moving from v1 to v2 must find its bytes
    // where it left them, and an event with a member on each version must address one photo one way —
    // otherwise the first v2 build re-uploads every library it meets.
    const key = legacyKeyFor(assetId, role, filename);
    const contentType = c.req.header("content-type") ?? "application/octet-stream";
    const refused = await streamPut(
      fetchImpl,
      config,
      c,
      "v2 upload",
      byteKey(deviceId, key),
      contentType,
    );
    if (refused) return refused;
    // NOT best-effort, unlike v1. v1's manifest publish re-creates a missing row on its next cycle, and
    // that repair is what makes swallowing this failure safe there. v2's manifest writes no resource row
    // at all, so nothing would repair it: the bytes would be stored, the backend would not know, the
    // device would be told it succeeded, and the resource would be absent from every union forever. A
    // visible retry costs one re-upload; the silence costs a photo.
    // Which events this write would complete an asset for — asked BEFORE the record, because afterwards a
    // completion is indistinguishable from a re-upload of a role that was already stored (see
    // `eventsCompletedBy`). A read failure here must not fail an upload whose bytes are already stored, so
    // it degrades to "wake nobody": the recipient's next foreground reconciles regardless.
    let completed: string[] = [];
    try {
      completed = await eventsCompletedBy(db, { deviceId, assetId, role });
    } catch (e) {
      console.error(`v2 upload: completion lookup failed for ${deviceId}/${assetId}/${role}: ${e}`);
    }
    const recorded = await tryUpstream(
      c,
      `v2 upload: could not record ${byteKey(deviceId, key)}`,
      () => recordResource(db, { deviceId, assetId, role, key, contentType, filename }),
    );
    if (recorded instanceof Response) return recorded;
    // AFTER the commit, and best-effort: this asset is now servable, so the event's other members are
    // woken to come and fetch it (capability `receiving-photos`). The manifest publish cannot
    // announce this — a declaration and its later completion project identical manifest fields, so the
    // publish does not change when the bytes land. A byte that completed nothing wakes nobody.
    // The clock's landing anchor (capability `event-lifetime`): an event is completed 3 days after its
    // last arrival at the latest. Best-effort like the wake — the bytes are stored and recorded, and a
    // lost stamp only lets the clock run from an earlier arrival or the range end.
    try {
      await stampLanded(db, completed, new Date(now()).toISOString());
    } catch (e) {
      console.error(`v2 upload: could not stamp the landing for ${completed.join(",")}: ${e}`);
    }
    for (const eventId of completed) await notifyMembers(eventId, deviceId);
    return c.body(null, 201);
  });

  v2Only.options("/files/devices/:deviceId/:assetId/:role", (c) => {
    c.header("Allow", "PUT, OPTIONS");
    return c.body(null, 204);
  });

  // What the backend holds for this device, in the terms v2 addresses resources by. No `url`: this route
  // answers "what have you recorded", and its consumer fetches no bytes — minting a presigned link would
  // cost a signature per row for a field nobody follows.
  v2Only.get("/files/devices/:deviceId", async (c) => {
    const deviceId = ownDeviceParam(c, "invalid device");
    if (deviceId instanceof Response) return deviceId;
    return await orUpstream502(c, `v2 list: device listing failed for ${deviceId}`, async () => {
      const held = await deviceResources(db, deviceId);
      c.header("Cache-Control", NO_CACHE);
      return c.json(held);
    });
  });

  // JOIN — the only route that creates or reactivates a membership, and the only one that decides
  // capacity. In v1 the manifest publish did both, which meant a document describing what a device SHARES
  // also decided whether it was a member: a departed device rejoined merely by publishing.
  v2Only.put("/events/:eventId/devices/:deviceId", async (c) => {
    const ids = eventAndOwnDeviceParams(c, "invalid id");
    if (ids instanceof Response) return ids;
    const { eventId, deviceId } = ids;
    const outcome = await tryUpstream(
      c,
      `v2 join: enrollment failed for ${eventId}/${deviceId}`,
      () => enroll(db, eventId, deviceId, new Date(now()).toISOString()),
    );
    return enrollRefusal(c, outcome) ?? c.body(null, 200);
  });

  // The manifest — CONTRIBUTION ONLY. It replaces the membership's asset set and does nothing else: it
  // enrolls nobody (join owns that) and records no upload (the byte route owns that).
  v2Only.put("/events/:eventId/devices/:deviceId/manifest", async (c) => {
    const ids = eventAndOwnDeviceParams(c, "invalid id");
    if (ids instanceof Response) return ids;
    const { eventId, deviceId } = ids;
    const json = await readJson(c);
    if (json instanceof Response) return json;
    const parsed = parseManifestBody(json.body);
    if ("invalid" in parsed) return c.text(parsed.invalid, 400);
    const { assets, version, final } = parsed;
    const event = await gateEvent(db, c, eventId, "v2 manifest");
    if (event instanceof Response) return event;
    // A manifest from a NON-MEMBER is refused rather than silently joining — the inverse of v1, where
    // publishing was enrolling.
    // A completed event has no members left; its devices are told so, and leave (capability
    // `manage-membership`).
    if (event.completedAt) return closedRefusal(c);
    const member = await tryUpstream(
      c,
      `v2 manifest: membership read failed for ${eventId}/${deviceId}`,
      () => isMember(db, eventId, deviceId),
    );
    if (member instanceof Response) return member;
    if (!member) return c.text("not a member", 409);
    // A CLOSED event's asset sets are fixed (capability `photo-sharing`, "What a member shares is fixed
    // once the event has closed"): a publish naming the set it already declared changes nothing and is
    // answered as published, so a device whose publish raced the close is not left retrying; any other set
    // is refused. `publishStatements` gates the batch on the close too, for the race after this check.
    if (event.closedAt) return await publishToClosed(c, eventId, deviceId, assets);
    // Does this publish make anything FETCHABLE that was not before? Asked before the replace, for the
    // same reason the byte route asks before its write. Under a manifest that declares intent most
    // publishes name assets whose bytes have not arrived, and waking members for those would announce a
    // photo they cannot fetch while spending an allowance APNs caps at two or three per hour. The case
    // that does earn a wake is a WIDENING: a membership re-admits assets it already uploaded, so the union
    // grows with no byte moving. A read failure degrades to "wake nobody" rather than failing the publish.
    let addsFetchable = false;
    try {
      addsFetchable = await publishAddsFetchableAsset(
        db,
        eventId,
        deviceId,
        assets.map((a) => ({ assetId: a.assetId, roles: a.resources.map((r) => r.role) })),
      );
    } catch (e) {
      console.error(`v2 manifest: fetchability lookup failed for ${eventId}/${deviceId}: ${e}`);
    }
    // ORDERED by the body's manifest version, inside the one transaction (see `publishStatements`): the
    // first statement's count is the verdict. A refused publish is an ORDINARY outcome of the app and the
    // upload extension publishing at once — a snapshot at least as new is already stored — so it answers
    // `200` like a won one, and the device treats it as published.
    const applied = await applyPublish(c, event, deviceId, assets, version, final);
    if (applied instanceof Response) return applied;
    const { won, closed } = applied;
    if (!won) {
      console.log(`v2 manifest: older version ${version} refused for ${eventId}/${deviceId}`);
    }
    // AFTER THE COMMIT, never inside it: a recipient woken before the write is visible would read the
    // union and find the very state the notification announced to be missing. Best-effort — the response
    // is the transaction's outcome and is never changed by a push that failed, the same split the byte
    // route already draws for its database write. Only a publish that WON wakes anyone: a refused one
    // changed nothing the union serves.
    // The close wakes every member once (capability `receiving-photos`): every byte may already have
    // landed, so no landing would wake anyone, and each device must learn the close to finish and leave.
    // The publisher is skipped like any fan-out — it made the close and learns it from its own next read.
    if ((won && addsFetchable) || closed) await notifyMembers(eventId, deviceId);
    if (closed) console.info(`v2 manifest: event ${eventId} closed by ${deviceId}`);
    return c.body(null, 200);
  });
  return v2Only;
}
