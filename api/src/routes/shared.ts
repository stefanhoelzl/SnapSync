// The SHARED device API: routes whose contract is identical under every version, mounted into each
// version's router, so there is one implementation and no possibility of the two drifting apart.

import { verifyToken } from "../attest.ts";
import {
  completeAssets,
  downloadStoragePath,
  type EventRow,
  insertEvent,
  leaveStatements,
  logUnionFetch,
  memberCounts,
  putDeviceRecord,
  renameEvent,
  unionPosition,
  unionRows,
} from "../db.ts";
import type { Handlers } from "../gen/wire.ts";
import { APP_VERSION_HEADER, splitVersion } from "../version.ts";
import { refuse } from "../refusal.ts";
import { canonicalFromMs, canonicalPlusSeconds } from "../validators.ts";
import { openUntilAdmitted } from "../lifecycle.ts";
import {
  closedForOldBuilds,
  closedRefusal,
  declaredAppVersion,
  deviceOrigin,
  downloadPath,
  gateEvent,
  NO_CACHE,
  noteAppVersion,
  orUpstream502,
  ownDevice,
  presignDownloadUrl,
  publicEvent,
  type RouteDeps,
  tryUpstream,
} from "./support.ts";

// One asset in the event-wide union: the owning `deviceId` (own-vs-foreign skip is the client's
// concern), the device-local `assetId`, the capture `creationDate`, and the complete set of resources.
type UnionResource = {
  role: string;
  contentType: string;
  key: string;
  filename: string;
  /** Absent under `urls=false`: the client builds the stable address itself (D1). */
  url?: string;
};

/**
 * Why a client read the union, as it says in `SnapSync-Trigger` (capability `privacy-security`, "The service
 * records who reads an event's photo list"). A value outside this list is recorded as unknown, never
 * refused: a future client must not lose its reads to a vocabulary this backend has not learnt yet.
 */
const UNION_TRIGGERS = [
  "push",
  "wake",
  "foreground",
  "join",
  "grant",
  "reconfigure",
  "leave-check",
] as const;

/** The response header naming the position a union answer covers (decision record D3). */
export const CURSOR_HEADER = "SnapSync-Cursor";
/** The request header naming why the client reads (decision record D5). */
export const TRIGGER_HEADER = "SnapSync-Trigger";
type UnionAsset = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  resources: UnionResource[];
};

/** The operations the device API shares across versions — each one's input arrives validated (`openapi.yaml`). */
export function sharedHandlers(
  deps: RouteDeps,
): Pick<
  Handlers,
  | "createEvent"
  | "getEvent"
  | "changeEvent"
  | "leaveEvent"
  | "eventFiles"
  | "downloadResource"
  | "putDevice"
> {
  const { config, db, now, aws } = deps;

  /**
   * The device a PUBLIC read's optional bearer token names: `null` when none is sent, refused `401` when one is
   * sent and does not verify — so the app reads that `401` as a verdict on its token and re-attests.
   */
  const optionalReader = async (authorization: string | undefined): Promise<string | null> => {
    const auth = authorization ?? "";
    if (auth === "") return null;
    const token = auth.startsWith("Bearer ") ? auth.slice("Bearer ".length).trim() : "";
    const reader = token ? await verifyToken(config, token, now()) : null;
    return reader ?? refuse(401, "unattested");
  };
  return {
    // Create an event (capabilities `docs/architecture.md`, `event-lifetime`). GATED by the device token (`app.ts`)
    // (an ungated create let a stranger mint unbounded events). Beyond that gate it stays a possession-is-capability
    // model. The body arrives validated (`EventCreate`): a trimmed name, a canonical `startsAt`, and an `endsAt`
    // strictly after it and within the window maximum — each refused with its own text before this runs. Mints a
    // server-side UUID and INSERTs the row. Faithful outcome: 201 only after the store confirms the write; any
    // failure → 502.
    //
    // `endsAt` is CREATOR-SUPPLIED and bounds ONLY which captures may be uploaded — it is not a lifetime. ABSENT, it
    // falls back to `startsAt + windowMax`, so old clients that send only `startsAt` keep working. `capacity` and
    // `lifetimeSeconds` stay server-resolved. An ENCRYPTED event names its key's id (the encrypted file format,
    // `docs/architecture.md`); the key itself never reaches this backend. The host's `zone` is only the event
    // page's (capability `event-site`): one this runtime cannot use arrives absent and is stored null.
    //
    // `openUntil` is until when the event stays open — the date every lifecycle rule counts from (decision record
    // `changes/back-dated-events`, D1). ABSENT it is `endsAt`, which is what every build that predates it gets. Its
    // bound reads the clock, which the declared validation cannot, so it is checked here: at least `endsAt`, and
    // at most the window maximum after the later of `endsAt` and now — room for a host who creates an event that
    // is already over to keep it open for the guests still to scan its invite, and no more.
    async createEvent(c, { body }) {
      const { name, startsAt } = body;
      const endsAt = body.endsAt ?? canonicalPlusSeconds(startsAt, config.eventWindowMaxSeconds);
      const openUntil = body.openUntil ?? endsAt;
      if (!openUntilAdmitted(endsAt, openUntil, now(), config.eventWindowMaxSeconds)) {
        refuse(400, "invalid openUntil");
      }
      const event: EventRow = {
        eventId: crypto.randomUUID(),
        name,
        createdAt: canonicalFromMs(now()),
        startsAt,
        endsAt,
        openUntil,
        capacity: config.eventCapacity,
        lifetimeSeconds: config.eventLifetimeSeconds,
        zone: body.zone ?? null,
        keyId: body.keyId ?? null,
      };

      await tryUpstream(
        `create: event insert failed for ${event.eventId}`,
        () => insertEvent(db, event),
      );
      // The row exists — only now is the event created.
      return c.json(publicEvent(event), 201);
    },

    // Event metadata / existence (`docs/architecture.md`). Returns the event — always carrying
    // `startsAt`, `endsAt`, `capacity`, and the derived `deletesAt`, because those are `NOT NULL` columns
    // — or 404 when the event was never created or the sweep deleted it; a read failure → 502. This is the
    // canonical existence check the device-manifest write gate relies on. There is no third answer: the
    // INCOMPLETE case the object era had to carry is unstateable now (see `gateEvent`).
    //
    // An event past its WINDOW (`endsAt`) serves normally — the window closes nothing. An event past its
    // derived `deletesAt` ALSO serves normally until the nightly sweep removes it: no route deletes on
    // touch. The 404 a client acts on is therefore always a real deletion, which is what makes it safe as
    // one of the two witnesses the client's self-leave requires (capability `event-lifetime`).
    //
    // A bearer token is OPTIONAL, as on the union: present, it must verify (`401` otherwise, so the app re-attests);
    // absent, the read is served as before. The app sends one on every read of an event; builds that predate it
    // send none, and are served until a minimum app version retires them
    // (decision record `changes/separate-event-page-from-device-api`, D7).
    async getEvent(c, { params: { eventId } }) {
      await optionalReader(c.req.header("authorization"));
      const event = await gateEvent(db, eventId, "metadata");
      // `members` feeds the joined screen (capability `sync-status`): how many are in the event, and how many of
      // them are still sharing — the ended event's waiting line. A completed event has none left.
      return await orUpstream502(
        `metadata: event read failed for ${eventId}`,
        async () =>
          c.json({
            ...publicEvent(event, await closedForOldBuilds(db, event, now())),
            members: await memberCounts(db, eventId),
          }),
      );
    },

    // Change an event — today, rename it (capability `manage-membership`). The ONLY route that writes an existing
    // event row, and it writes exactly ONE column. `name` is the single exception to the row's write-once rule
    // (capability `event-lifetime`) because it touches neither threat that rule names: a name cannot
    // retroactively widen a joiner's capture scope and cannot extend an event's limits. It is cosmetic to
    // the upload gate, cosmetic to the extension, and load-bearing for display alone.
    //
    // GATED by the device token like `POST /events`, and BEYOND that gate there is no ownership check —
    // there is no owner field, and possession of the event id already authorizes uploading into the event
    // and listing every photo in it, so a rename is strictly weaker than what a holder already has.
    //
    // ⚠️ Every other field is written back VERBATIM — never restamped, never recomputed. That is what
    // makes a race with the nightly sweep (capability `event-lifetime`) self-defusing: a rename that
    // re-creates a row the sweep has just deleted re-creates it carrying its ORIGINAL `createdAt`,
    // `startsAt`, and `lifetimeSeconds`, so its derived delete-by is still in the past and the next sweep
    // reaps it again. Restamping any of those would resurrect the event for a fresh lifetime.
    //
    // Concurrent renames are last-write-wins: bunny has no compare-and-set (the same constraint the
    // device-manifest capacity gate reads and writes under). No ordering guarantee is available or claimed.
    // The body arrives validated (`EventChange`): the same `EventName` the create takes — one rule for what an event
    // may be called — and at least one field to change.
    async changeEvent(c, { params: { eventId }, body: { name } }) {
      // The same existence gate the metadata route serves from: absent → 404 (never a partial
      // rewrite of a row the sweep is about to delete); a transport failure → 502, so a
      // transient fault is never mistaken for absence.
      const current = await gateEvent(db, eventId, "rename");
      // A closed event does not change any more, its name included (capability `event-lifetime`).
      if (current.closedAt) closedRefusal();
      // `name` is the one field a change may carry today, so a valid change always carries it.
      if (name === undefined) return c.json(publicEvent(current));

      // ONE column. `renameEvent` is a `SET name = ?` and nothing else — see `db.ts`, where the statement
      // is spelled out in one place precisely because widening it is now a one-word edit.
      const written = await tryUpstream(
        `rename: update failed for ${eventId}`,
        () => renameEvent(db, eventId, name),
      );
      // Zero rows means the event was deleted between the gate and the write. Report the absence rather
      // than a success that renamed nothing.
      if (written.rowsAffected === 0) return c.text("event not found", 404);
      return c.json(publicEvent({ ...current, name }));
    },

    // Leave an event (capability `manage-membership`). A STATE CHANGE, and non-destructive: the membership becomes
    // `done` or `left` (see `leaveStatements`) — `done` only when it had settled, every role it declared has landed,
    // and the device says `?received=true` (it holds every photo of the others; anything but `true` is "no"). GATED
    // on the event row (absent → 404; read failure → 502). The membership's assets are RETAINED, so the union still
    // serves what the device shared, and the route returns 200 REGARDLESS of remaining membership — the event
    // survives until the nightly sweep completes or deletes it (capability `event-lifetime`), which also collects
    // the bytes. Idempotent: a repeated leave, or one naming a membership that never existed, changes nothing and
    // is not an error, so a retried DELETE re-runs harmlessly. Any transport failure → 502.
    //
    // A leave closes nothing and wakes nobody: events no longer close (decision record `changes/back-dated-events`,
    // D2). Whether a departure leaves the event finished is computed by the sweep and the event read.
    async leaveEvent(c, { params }) {
      const { eventId } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const received = params.received === true;

      // The lifecycle gate (capability `event-lifetime`): an absent event 404s, which the client already
      // treats as "nothing to leave". A leave DURING grace proceeds: members may still
      // depart an over-but-not-yet-swept event.
      await gateEvent(db, eventId, "leave");

      // ONE column, one batch. Membership is a `state`, so leaving cannot leave a half-applied pair behind and
      // cannot be double-counted.
      await tryUpstream(
        `leave: depart failed for ${eventId}/${deviceId}`,
        () => db.batch(leaveStatements(eventId, deviceId, received)),
      );
      // Always succeed: the event persists (rejoinable while open) regardless of how many members remain,
      // and a leave naming a membership that never existed changes nothing rather than failing.
      return c.body(null, 200);
    },

    // Event-wide UNION read (`docs/architecture.md`). UNGATED by the token — the no-app download page
    // fetches it from a browser that holds no attestation, so eventId-possession IS the read capability
    // (`privacy-security`'s closed list, entry 8) — but still gated on event EXISTENCE: absent → 404,
    // read failure → 502.
    //
    // ONE QUERY, no fan-out: the event's assets joined to their resources across ACTIVE and DEPARTED
    // memberships, so a member who has left keeps contributing what it already shared. An asset naming a
    // resource with no recorded upload is dropped — the PRIMARY completeness mechanism (capability
    // `docs/architecture.md`), since a manifest DECLARES what its device will provide rather than what it has
    // already uploaded. Each kept asset is flattened into one array, tagged with its
    // owning deviceId (the endpoint is identity-blind — own-vs-foreign skip is the client's concern). The
    // published manifest is already the event's date-filtered projection, so its asset list is trusted
    // as-is (no re-filtering). Faithful: any read failure → 502, never a partial union. Non-cacheable.
    //
    // INCREMENTAL (decision record `changes/incremental-union`, D3–D5), additively — a caller that sends
    // nothing new gets the answer it always got:
    //   * `cursor=<n>` serves only assets the union log says were gained after position `n`; without it, the
    //     whole union. Either way `SnapSync-Cursor` names the position the answer covers.
    //   * `urls=false` omits every `url`: the client builds the stable address itself. Otherwise `url` is
    //     that stable address (it redirects to a presign per download).
    //   * a bearer token is OPTIONAL: present, it must verify (`401` otherwise, so the app re-attests) and
    //     names the reading device in the log; absent, the read is anonymous and nothing about the reader
    //     is kept (capability `privacy-security`, "A web visitor leaves no trace in the event").
    async eventFiles(c, { params }) {
      const { eventId } = params;
      const after = params.cursor === undefined ? undefined : Number(params.cursor);
      const withUrls = params.urls !== false;

      const reader = await optionalReader(c.req.header("authorization"));
      const said = c.req.header(TRIGGER_HEADER) ?? "";
      const trigger = (UNION_TRIGGERS as readonly string[]).includes(said) ? said : null;

      // Gate on the event row (`docs/architecture.md`): absent → 404; a store failure → 502. An event past
      // its window still serves its union — the window closes nothing.
      await gateEvent(db, eventId, "union");

      const version = splitVersion(new URL(c.req.url).pathname).version;
      return await orUpstream502(`union: assembly failed for event ${eventId}`, async () => {
        // The position FIRST, then the rows (`unionPosition`): a gain that commits in between is served
        // again by the next delta — a duplicate the client dedups — never skipped.
        const position = await unionPosition(db, eventId);
        // ONE query, spanning the event's memberships in BOTH states: a member who has left keeps
        // contributing the photos it already shared, until the event itself is deleted. What this replaces
        // is a fan-out — one directory listing to discover members, then a manifest read AND a byte listing
        // per member — whose cost grew with the event and which no index could help.
        const rows = await unionRows(db, eventId, after);

        // Each COMPLETE asset (`completeAssets`): an asset naming a resource whose bytes have not arrived is
        // dropped. The sweep still protects a referenced byte from collection.
        const urlOf = (r: typeof rows[number]): string | undefined =>
          withUrls
            ? `${deviceOrigin(config)}/api/v${version}${
              downloadPath(eventId, r.deviceId, r.assetId, r.role)
            }`
            : undefined;
        const assets: UnionAsset[] = completeAssets(rows).map((resources) => ({
          deviceId: resources[0].deviceId,
          assetId: resources[0].assetId,
          creationDate: resources[0].creationDate,
          resources: resources.map((r) => {
            const url = urlOf(r);
            return {
              role: r.role,
              contentType: r.contentType,
              key: r.key,
              filename: r.filename,
              ...(url === undefined ? {} : { url }),
            };
          }),
        }));

        // Best-effort: the record of a read never costs the read (capability `privacy-security`).
        try {
          await logUnionFetch(db, {
            eventId,
            deviceId: reader,
            trigger,
            from: after ?? null,
            to: position,
            served: assets.length,
            at: new Date(now()).toISOString(),
          });
        } catch (e) {
          c.var.log.error(
            "read-log",
            `union: could not log the read of ${eventId} (best-effort): ${e}`,
          );
        }
        c.var.log.field("served", assets.length);
        c.var.log.field("trigger", trigger ?? "-");
        if (reader !== null) {
          const declared = declaredAppVersion(c.req.url, c.req.header(APP_VERSION_HEADER));
          const failed = await noteAppVersion(db, reader, declared, "union");
          if (failed) c.var.log.error("app-version", failed);
        }

        c.header("Cache-Control", NO_CACHE); // a `url` may be a time-limited presign; a cursor is a moment
        c.header(CURSOR_HEADER, String(position));
        return c.json(assets);
      });
    },

    // DOWNLOAD one resource of an event's union (decision record `changes/incremental-union`, D1–D2): `302`
    // to a freshly presigned S3 GET, so no union read signs anything and a signature is minted per download
    // started. UNGATED like the union — the event id is the read capability — and exempt from the version
    // gate (`app.ts`): the OS download transports that fetch it send no app header. `404` when the event no
    // longer declares the asset with this role or its bytes are not recorded (a withdrawal stops the link,
    // capability `privacy-security`). The redirect is never cached: bunny's pull zone fronts this script,
    // and a cached 302 would hand out a stale signature.
    //
    // The signature keeps S3's 7-day maximum on purpose (D2): an iOS background session resumes from the
    // redirect TARGET and never revisits this route (measured), so a shorter one turns a resume after an
    // outage into a restart.
    async downloadResource(c, { params: { eventId, deviceId, assetId, role } }) {
      return await orUpstream502(`download: lookup failed for ${eventId}`, async () => {
        const path = await downloadStoragePath(db, eventId, deviceId, assetId, role);
        c.header("Cache-Control", NO_CACHE);
        if (path === null) return c.text("not found", 404);
        return c.redirect(await presignDownloadUrl(aws, config, path), 302);
      });
    },

    // Write a device's config document (`docs/architecture.md`). Gated by DEVICE-ID possession alone
    // (no event) — the same capability model as the byte upload. The document is recorded against the
    // device (`docs/architecture.md`); last-write-wins, and it is not a resource, so it never appears in the
    // per-device listing or the union.
    // The body arrives validated (`DeviceConfig`): a registration, or `null` — an explicit absence, the device telling
    // us it has none, which is a legitimate thing to record.
    async putDevice(c, { params, body }) {
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const push = body.pushToken ?? null;
      // An UPDATE, never an insert: a `devices` row exists only where the device has attested, and this
      // route cannot attest on its behalf (capability `privacy-security`).
      const written = await tryUpstream(
        `config: device record write failed for ${deviceId}`,
        () => putDeviceRecord(db, deviceId, push, new Date(now()).toISOString()),
      );
      if (written.rowsAffected === 0) {
        // The token verified — it is ours and unexpired — but we hold no attestation for this device.
        // `401` is the answer because the remedy is the same one a rejected token has, and the shipped
        // client already takes it: drop the token, attest afresh (which CREATES the row), and re-send
        // this registration when a new token arrives. A `201` here would be a silent absence — the device
        // would believe it is reachable while no push could ever reach it, and it writes its registration
        // once per OS-delivered token, so nothing would retry.
        c.var.log.field("refused", "not-attested");
        return c.text("unattested", 401);
      }
      return c.body(null, 201);
    },
  };
}
