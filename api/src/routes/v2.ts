// The v2 ONLY device API (`docs/architecture.md`): the byte upload by identity, the identity-terms
// listing, the join, and the manifest sub-resource whose publish carries the fan-out.

import {
  declaredAssets,
  deviceResources,
  enroll,
  type EventRow,
  eventsCompletedBy,
  gainedStatements,
  isMember,
  type ManifestAssetEntry,
  presentMembership,
  presentUploadMembership,
  publishStatements,
  publishUnionChanges,
  recordReceived,
  recordResourceStatement,
  stampLanded,
  uploadMembership,
} from "../db.ts";
import type { Handlers } from "../gen/wire.ts";
import { eventBytePath, storageKey } from "../storage.ts";
import type { Fault } from "../refusal.ts";
import type { RequestLog } from "../request-log.ts";
import { APP_VERSION_HEADER } from "../version.ts";
import { bodyToStore, type Upload, uploadOf } from "./encrypted-upload.ts";
import {
  closedRefusal,
  declaredAppVersion,
  enrollRefusal,
  gateEvent,
  NO_CACHE,
  noteAppVersion,
  notifyMembers,
  type NotifyOutcome,
  orUpstream502,
  ownDevice,
  recordNotify,
  type RouteDeps,
  streamPut,
  tryUpstream,
} from "./support.ts";

/** What storing one resource did beyond storing it: what it completed, whom it woke, what failed on the way. */
type StoreOutcome = { completed: number; woken: NotifyOutcome[]; errors: Fault[] };

/** Put a byte route's outcome on the request's line. */
function recordStore(log: RequestLog, outcome: StoreOutcome): void {
  log.field("completed", outcome.completed);
  for (const e of outcome.errors) log.error(e.tag, e.detail);
  for (const w of outcome.woken) recordNotify(log, w);
}

/** The operations only `/api/v2` serves — each one's input arrives validated (`openapi.yaml`). */
export function v2Handlers(
  deps: RouteDeps,
): Pick<
  Handlers,
  | "uploadResource"
  | "uploadResourcePreflight"
  | "uploadResourceEventless"
  | "uploadResourceEventlessPreflight"
  | "deviceFiles"
  | "deviceFilesEventless"
  | "joinEvent"
  | "publishManifest"
  | "reportReceived"
> {
  const { fetchImpl, config, db, now } = deps;
  /**
   * A publish to an event closed before events stopped closing (`PENDING_CLEANUPS.md`): the set it already
   * declared is answered as published and changes nothing, so a device whose publish raced that close is not
   * left retrying; any other set is refused `409 closed`, as it was then. Answers whether it is the set already
   * declared.
   */
  async function declaresSameSet(
    eventId: string,
    deviceId: string,
    assets: ManifestAssetEntry[],
  ): Promise<boolean> {
    const stored = await tryUpstream(
      `v2 manifest: declared-set read failed for ${eventId}/${deviceId}`,
      () => declaredAssets(db, eventId, deviceId),
    );
    return stored.size === assets.length &&
      assets.every((a) =>
        stored.get(a.assetId) === a.resources.map((r) => r.role).sort().join(",")
      );
  }

  /**
   * Apply a v2 publish as ONE batch (see `publishStatements`) and read its verdict: whether the version
   * comparison admitted it — the first statement's count.
   *
   * Only after the range has ended may a publish settle anything: before it, the device may still take photos
   * that belong in the event (capability `photo-sharing`). Settling closes nothing — events no longer close
   * (decision record `changes/back-dated-events`, D2); whether everyone is done is computed on every read.
   */
  async function applyPublish(
    event: EventRow,
    deviceId: string,
    assets: ManifestAssetEntry[],
    version: number,
    final: boolean,
    changes: { gained: string[]; removed: string[] },
  ): Promise<boolean> {
    const nowMs = now();
    const ended = nowMs > Date.parse(event.endsAt);
    const results = await tryUpstream(
      `v2 manifest: publish failed for ${event.eventId}/${deviceId}`,
      () =>
        db.batch(
          publishStatements(event.eventId, deviceId, assets, {
            version,
            final: final && ended,
            log: { ...changes, at: new Date(nowMs).toISOString() },
          }),
        ),
    );
    return results[0].rowsAffected > 0;
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

  /**
   * Store one resource's bytes for `eventId` and record them (change `per-event-storage-layout`, D4) — the
   * write both v2 byte routes share once each has settled WHICH event the bytes belong to. The object lands
   * at the event's deterministic path, so a re-upload overwrites it; then the record and the union-log
   * gain commit as one batch, and the event's other members are woken when this byte completed an asset.
   */
  async function storeResource(
    route: string,
    r: {
      eventId: string;
      deviceId: string;
      assetId: string;
      role: string;
      filename: string;
      /** The event's key id, `null` for a plain event — what may be stored (`bodyToStore`). */
      keyId: string | null;
    },
    upload: Upload,
    contentType: string,
  ): Promise<StoreOutcome> {
    const { eventId, deviceId, assetId, role, filename } = r;
    const errors: Fault[] = [];
    const path = await eventBytePath(eventId, deviceId, assetId, role);
    const body = await bodyToStore(upload, r.keyId);
    await streamPut(fetchImpl, config, route, storageKey(path), contentType, body);
    // NOT best-effort, unlike v1. v1's manifest publish re-creates a missing row on its next cycle, and
    // that repair is what makes swallowing this failure safe there. v2's manifest writes no resource row
    // at all, so nothing would repair it: the bytes would be stored, the backend would not know, the
    // device would be told it succeeded, and the resource would be absent from every union forever. A
    // visible retry costs one re-upload; the silence costs a photo.
    // Whether this write would complete an asset — asked BEFORE the record, because afterwards a completion
    // is indistinguishable from a re-upload of a role that was already stored (see `eventsCompletedBy`). A
    // read failure here must not fail an upload whose bytes are already stored, so it degrades to "wake
    // nobody": the recipient's next foreground reconciles regardless.
    let completed: string[] = [];
    try {
      completed = await eventsCompletedBy(db, { eventId, deviceId, assetId, role });
    } catch (e) {
      errors.push({
        tag: "completion-lookup",
        detail:
          `${route}: completion lookup failed for ${eventId}/${deviceId}/${assetId}/${role}: ${e}`,
      });
    }
    // The record and the union-log rows it causes commit as ONE batch (decision record
    // `changes/incremental-union`, D4): a gain logged for bytes never recorded would announce what no read
    // can serve, and a record whose gain was lost would leave the asset out of every delta.
    const landedAt = new Date(now()).toISOString();
    await tryUpstream(
      `${route}: could not record ${path}`,
      () =>
        db.batch([
          recordResourceStatement({
            eventId,
            deviceId,
            assetId,
            role,
            path,
            contentType,
            filename,
          }),
          ...gainedStatements(completed, deviceId, assetId, landedAt),
        ]),
    );
    // AFTER the commit, and best-effort: this asset is now servable, so the event's other members are
    // woken to come and fetch it (capability `delivery`). The manifest publish cannot
    // announce this — a declaration and its later completion project identical manifest fields, so the
    // publish does not change when the bytes land. A byte that completed nothing wakes nobody.
    // The clock's landing anchor (capability `event-lifetime`): an event is completed 3 days after its
    // last arrival at the latest. Best-effort like the wake — the bytes are stored and recorded, and a
    // lost stamp only lets the clock run from an earlier arrival or the range end.
    try {
      await stampLanded(db, completed, landedAt);
    } catch (e) {
      errors.push({
        tag: "landing-stamp",
        detail: `${route}: could not stamp the landing for ${completed.join(",")}: ${e}`,
      });
    }
    // ONE wake at most: `eventsCompletedBy` asks about this request's own event (bytes are stored per event), so
    // it answers that event or none — the fan-out's subrequest budget is one `notifyMembers` per request
    // (decision record `changes/remove-event-device-cap`, D6).
    const woken: NotifyOutcome[] = [];
    if (completed.length > 0) woken.push(await notifyMembers(deps, eventId, deviceId));
    return { completed: completed.length, woken, errors };
  }

  // Upload one resource's bytes INTO AN EVENT. Identity comes from the PATH — the same path the download
  // redirect answers GET on, which this PUT shares and the token gate still gates (only the redirect's
  // GET/HEAD is exempt) — and the capture name from a REQUIRED query parameter, which never reaches the
  // storage key. Only a member still IN the event (`sharing`/`settled`) writes into it: a closed event still
  // takes the bytes its members declared, since settling never waits for uploads, while a completed one has
  // no members left.
  return {
    async uploadResource(c, { params }) {
      const { eventId, assetId, role, filename } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const identity = { assetId, role, filename };
      const membership = await tryUpstream(
        `v2 upload: membership read failed for ${eventId}/${deviceId}`,
        () => uploadMembership(db, eventId, deviceId),
      );
      const state = membership?.state;
      if (state !== "sharing" && state !== "settled") return c.text("not a member", 403);
      const stored = await storeResource(
        "v2 upload",
        { eventId, deviceId, ...identity, keyId: membership?.keyId ?? null },
        uploadOf(c.req),
        c.req.header("content-type") ?? "application/octet-stream",
      );
      recordStore(c.var.log, stored);
      return c.body(null, 201);
    },

    uploadResourcePreflight(c) {
      c.header("Allow", "GET, HEAD, PUT, OPTIONS");
      return c.body(null, 204);
    },

    // The EVENT-LESS upload, kept for the builds that predate the event-scoped one (change
    // `per-event-storage-layout`, D4). Its path names no event, so the bytes are filed under the device's ONE
    // present membership — single active membership is the current contract, and concurrent membership would
    // have to retire this route first. With none, there is nothing these bytes could belong to: `409`.
    async uploadResourceEventless(c, { params }) {
      const { assetId, role, filename } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const identity = { assetId, role, filename };
      const target = await tryUpstream(
        `v2 upload: membership read failed for ${deviceId}`,
        () => presentUploadMembership(db, deviceId),
      );
      if (target === null) return c.text("no event", 409);
      const stored = await storeResource(
        "v2 upload",
        { ...target, deviceId, ...identity },
        uploadOf(c.req),
        c.req.header("content-type") ?? "application/octet-stream",
      );
      recordStore(c.var.log, stored);
      return c.body(null, 201);
    },

    uploadResourceEventlessPreflight(c) {
      c.header("Allow", "PUT, OPTIONS");
      return c.body(null, 204);
    },

    // What the backend holds for this device IN ONE EVENT, in the terms v2 addresses resources by — the
    // join-time load's answer. No `url`: this route answers "what have you recorded", and its consumer fetches
    // no bytes. Never another event's resources: the device marks every listed one uploaded, and one stored
    // for another event would then never reach this one.
    async deviceFiles(c, { params }) {
      const { eventId } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      return await orUpstream502(
        `v2 list: device listing failed for ${eventId}/${deviceId}`,
        async () => {
          const held = await deviceResources(db, eventId, deviceId);
          c.header("Cache-Control", NO_CACHE);
          return c.json(held);
        },
      );
    },

    // The EVENT-LESS listing, for the builds that predate the one above: it answers for the device's present
    // membership — the event such a build has just joined when it asks — and `[]` with none.
    async deviceFilesEventless(c, { params }) {
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      return await orUpstream502(`v2 list: device listing failed for ${deviceId}`, async () => {
        const eventId = await presentMembership(db, deviceId);
        const held = eventId === null ? [] : await deviceResources(db, eventId, deviceId);
        c.header("Cache-Control", NO_CACHE);
        return c.json(held);
      });
    },

    // JOIN — the only route that creates or reactivates a membership, and the only one that decides
    // admission (an open event admits any device). In v1 the manifest publish did both, which meant a document describing what a device SHARES
    // also decided whether it was a member: a departed device rejoined merely by publishing.
    async joinEvent(c, { params }) {
      const { eventId } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const declared = declaredAppVersion(c.req.url, c.req.header(APP_VERSION_HEADER));
      const notRecorded = await noteAppVersion(db, deviceId, declared, "v2 join");
      if (notRecorded) c.var.log.error("app-version", notRecorded);
      enrollRefusal(
        await tryUpstream(
          `v2 join: enrollment failed for ${eventId}/${deviceId}`,
          () => enroll(db, eventId, deviceId, new Date(now()).toISOString()),
        ),
      );
      return c.body(null, 200);
    },

    // The manifest — CONTRIBUTION ONLY. It replaces the membership's asset set and does nothing else: it
    // enrolls nobody (join owns that) and records no upload (the byte route owns that).
    // The body arrives validated (`Manifest`): the asset set, its `version` and its `final` declaration, each refused
    // with its own text before this runs.
    async publishManifest(c, { params, body }) {
      const { eventId } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const declared = declaredAppVersion(c.req.url, c.req.header(APP_VERSION_HEADER));
      const notRecorded = await noteAppVersion(db, deviceId, declared, "v2 manifest");
      if (notRecorded) c.var.log.error("app-version", notRecorded);
      const { assets, version, final } = body;
      const event = await gateEvent(db, eventId, "v2 manifest");
      // A manifest from a NON-MEMBER is refused rather than silently joining — the inverse of v1, where
      // publishing was enrolling.
      // A completed event has no members left; its devices are told so, and leave (capability
      // `manage-membership`).
      if (event.completedAt) closedRefusal();
      const member = await tryUpstream(
        `v2 manifest: membership read failed for ${eventId}/${deviceId}`,
        () => isMember(db, eventId, deviceId),
      );
      if (!member) return c.text("not a member", 409);
      // An event closed before events stopped closing keeps its fixed asset sets (`PENDING_CLEANUPS.md`): a
      // publish naming the set it already declared changes nothing and is answered as published, so a device
      // whose publish raced that close is not left retrying; any other set is refused. `publishStatements`
      // gates the batch on the stamp too, for a completion racing this check.
      if (event.closedAt) {
        return await declaresSameSet(eventId, deviceId, assets)
          ? c.body(null, 200)
          : c.json({ error: "closed" }, 409);
      }
      // Does this publish make anything FETCHABLE that was not before? Asked before the replace, for the
      // same reason the byte route asks before its write. Under a manifest that declares intent most
      // publishes name assets whose bytes have not arrived, and waking members for those would announce a
      // photo they cannot fetch while spending an allowance APNs caps at two or three per hour. The case
      // that does earn a wake is a WIDENING: a membership re-admits assets it already uploaded, so the union
      // grows with no byte moving. A read failure degrades to "wake nobody" rather than failing the publish.
      // The same comparison also yields what this publish REMOVES from the union; both are written into the
      // union log inside the publish batch (decision record `changes/incremental-union`, D4). A read failure
      // logs nothing: the members' next full read still finds the asset.
      let changes: { gained: string[]; removed: string[] } = { gained: [], removed: [] };
      try {
        changes = await publishUnionChanges(
          db,
          eventId,
          deviceId,
          assets.map((a) => ({ assetId: a.assetId, roles: a.resources.map((r) => r.role) })),
        );
      } catch (e) {
        c.var.log.error(
          "fetchability",
          `v2 manifest: fetchability lookup failed for ${eventId}/${deviceId}: ${e}`,
        );
      }
      const addsFetchable = changes.gained.length > 0;
      // ORDERED by the body's manifest version, inside the one transaction (see `publishStatements`): the
      // first statement's count is the verdict. A refused publish is an ORDINARY outcome of the app and the
      // upload extension publishing at once — a snapshot at least as new is already stored — so it answers
      // `200` like a won one, and the device treats it as published.
      const won = await applyPublish(event, deviceId, assets, version, final, changes);
      if (!won) {
        c.var.log.field("refused", "older-version");
        c.var.log.field("version", version ?? "-");
      }
      // AFTER THE COMMIT, never inside it: a recipient woken before the write is visible would read the
      // union and find the very state the notification announced to be missing. Best-effort — the response
      // is the transaction's outcome and is never changed by a push that failed, the same split the byte
      // route already draws for its database write. Only a publish that WON wakes anyone: a refused one
      // changed nothing the union serves. There is no close wake any more (capability `delivery`): events no
      // longer close, and a member that finished sharing wakes nobody.
      if (won && addsFetchable) {
        recordNotify(c.var.log, await notifyMembers(deps, eventId, deviceId));
      }
      return c.body(null, 200);
    },

    // What the device has RECEIVED (decision record `changes/back-dated-events`, D4): the union-log position up to
    // which it holds every photo of the others — the half of "done" only the device can see, since only it sees its
    // library. Monotonic (`recordReceived`): a lower position than the one stored changes nothing and is answered
    // like a recorded one, so an out-of-order retry is harmless. Only a member still IN the event reports; any other
    // device is told it is not one, like a manifest from a non-member.
    async reportReceived(c, { params, body: { position } }) {
      const { eventId } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      await gateEvent(db, eventId, "v2 received");
      const member = await tryUpstream(
        `v2 received: record failed for ${eventId}/${deviceId}`,
        () => recordReceived(db, eventId, deviceId, position),
      );
      if (!member) return c.text("not a member", 409);
      return c.body(null, 200);
    },
  };
}
