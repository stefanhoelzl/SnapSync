// The SHARED device API: routes whose contract is identical under every version, mounted into each
// version's router, so there is one implementation and no possibility of the two drifting apart.

import { Hono } from "hono";
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
import { RESOURCE_ROLES } from "../object-names.ts";
import { APP_VERSION_HEADER, splitVersion } from "../version.ts";
import { refuse } from "../refusal.ts";
import {
  canonicalPlusSeconds,
  validateEndsAt,
  validateEventName,
  validateFilename,
  validateKeyId,
  validateStartsAt,
  validateUUID,
  validateZone,
} from "../validators.ts";
import {
  closedRefusal,
  declaredAppVersion,
  deviceOrigin,
  downloadPath,
  eventAndOwnDeviceParams,
  eventParam,
  gateEvent,
  NO_CACHE,
  noteAppVersion,
  notifyMembers,
  orUpstream502,
  ownDeviceParam,
  presignDownloadUrl,
  publicEvent,
  readJson,
  recordNotify,
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

/** The routes both versions serve, built over `deps`. */
export function sharedRoutes(deps: RouteDeps): Hono {
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
  // SHARED: routes whose contract is identical under every version. Mounted into each version's router,
  // so there is one implementation and no possibility of the two drifting apart.
  const deviceApi = new Hono();

  // Create an event (capabilities `docs/architecture.md`, `event-lifetime`). GATED by the device token (`app.ts`) (an
  // ungated create let a stranger mint unbounded events). Beyond that gate it stays a
  // possession-is-capability model. Validates the name, mints a server-side UUID, and INSERTs the row.
  // Faithful outcome: 201 only after the store confirms the write; any failure → 502.
  deviceApi.post("/events", async (c) => {
    const body = await readJson(c.req);
    const name = validateEventName((body as { name?: unknown } | null)?.name);
    if (name === null) {
      return c.text("invalid name", 400); // missing/empty/whitespace/too long
    }
    const startsAt = validateStartsAt((body as { startsAt?: unknown } | null)?.startsAt);
    if (startsAt === null) {
      return c.text("invalid startsAt", 400); // missing/empty/non-canonical/not a real instant
    }
    // `endsAt` is CREATOR-SUPPLIED at mint (capability `event-lifetime`) and bounds ONLY which captures may
    // be uploaded — it is not a lifetime. When the body carries one it is validated (canonical instant,
    // strictly after `startsAt`, and no longer than the configured WINDOW MAXIMUM) and stamped; when
    // ABSENT it falls back to `startsAt + windowMax`, so old clients that send only `startsAt` keep
    // working. A present-but-invalid `endsAt` is a 400. `capacity` and `lifetimeSeconds` stay
    // server-resolved — a client-supplied `capacity` (and `eventId`) is still ignored. The config values
    // are consulted HERE ONLY; enforcement reads the row's own stamped fields.
    const rawEndsAt = (body as { endsAt?: unknown } | null)?.endsAt;
    let endsAt: string;
    if (rawEndsAt === undefined || rawEndsAt === null) {
      endsAt = canonicalPlusSeconds(startsAt, config.eventWindowMaxSeconds); // absent-endsAt fallback
    } else {
      const validated = validateEndsAt(rawEndsAt, startsAt, config.eventWindowMaxSeconds);
      if (validated === null) {
        // non-canonical / not a real instant / not after startsAt / longer than the window maximum
        return c.text("invalid endsAt", 400);
      }
      endsAt = validated;
    }
    // An ENCRYPTED event names its key's id (the encrypted file format, `docs/architecture.md`); the key
    // itself never reaches this backend. Optional — absent creates a plain event — but a present invalid
    // one is a 400, never silently a plain event.
    const rawKeyId = (body as { keyId?: unknown } | null)?.keyId;
    const keyId = rawKeyId === undefined || rawKeyId === null ? null : validateKeyId(rawKeyId);
    if (rawKeyId !== undefined && rawKeyId !== null && keyId === null) {
      return c.text("invalid keyId", 400);
    }
    const event: EventRow = {
      eventId: crypto.randomUUID(),
      name,
      createdAt: new Date(now()).toISOString(),
      startsAt,
      endsAt,
      capacity: config.eventCapacity,
      lifetimeSeconds: config.eventLifetimeSeconds,
      // The host's zone, which only the event page reads (capability `event-site`). Optional and never a
      // refusal: absent or unusable stores null, and the page renders the dates in UTC (`validateZone`).
      zone: validateZone((body as { zone?: unknown } | null)?.zone),
      keyId,
    };

    await tryUpstream(
      `create: event insert failed for ${event.eventId}`,
      () => insertEvent(db, event),
    );
    // The row exists — only now is the event created.
    return c.json(publicEvent(event), 201);
  });

  // Event metadata / existence (`docs/architecture.md`). Returns the event — always carrying
  // `startsAt`, `endsAt`, `capacity`, and the derived `deletesAt`, because those are `NOT NULL` columns
  // — or 404 when the event was never created or the sweep deleted it; a read failure → 502. This is the
  // canonical existence check the device-manifest write gate relies on. There is no third answer: the
  // INCOMPLETE case the object era had to carry is unstateable now (see `gateEvent`).
  //
  // An event past its WINDOW (`endsAt`) serves normally — the window closes nothing. An event past its
  // derived `deletesAt` ALSO serves normally until the nightly sweep removes it: no route deletes on
  // touch. The 404 a client acts on is therefore always a real deletion, which is what makes it safe as
  // one of the two witnesses the client's self-leave requires (capability `manage-membership`).
  //
  // A bearer token is OPTIONAL, as on the union: present, it must verify (`401` otherwise, so the app re-attests);
  // absent, the read is served as before. The app sends one on every read of an event; builds that predate it
  // send none, and are served until a minimum app version retires them
  // (decision record `changes/separate-event-page-from-device-api`, D7).
  deviceApi.get("/events/:eventId", async (c) => {
    const eventId = eventParam(c.req.param("eventId"));
    await optionalReader(c.req.header("authorization"));
    const event = await gateEvent(db, eventId, "metadata");
    // `members` feeds the ended event's waiting line (capability `sync-status`): how many of the active
    // members have settled what they share. A completed event has none left.
    return await orUpstream502(
      `metadata: event read failed for ${eventId}`,
      async () => c.json({ ...publicEvent(event), members: await memberCounts(db, eventId) }),
    );
  });

  // Rename an event (capability `manage-membership`). The ONLY route that writes an existing event row, and
  // it writes exactly ONE column. `name` is the single exception to the row's write-once rule
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
  deviceApi.patch("/events/:eventId", async (c) => {
    const eventId = eventParam(c.req.param("eventId"));
    const body = await readJson(c.req);
    // The SAME validator the create route uses — one rule for what an event may be called.
    const name = validateEventName((body as { name?: unknown } | null)?.name);
    if (name === null) {
      return c.text("invalid name", 400); // missing/empty/whitespace/too long
    }

    // The same existence gate the metadata route serves from: absent → 404 (never a partial
    // rewrite of a row the sweep is about to delete); a transport failure → 502, so a
    // transient fault is never mistaken for absence.
    const current = await gateEvent(db, eventId, "rename");
    // A closed event does not change any more, its name included (capability `event-lifetime`).
    if (current.closedAt) closedRefusal();

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
  });

  // Leave an event (capability `manage-membership`). A STATE CHANGE, and non-destructive: the membership becomes
  // `done` or `left` (see `leaveStatements`) — `done` only when it had settled, every role it declared has landed,
  // and the device says `?received=true` (it holds every photo of the others; anything but `true` is "no"). GATED
  // on the event row (absent → 404; read failure → 502). The membership's assets are RETAINED, so the union still
  // serves what the device shared, and the route returns 200 REGARDLESS of remaining membership — the event
  // survives until the nightly sweep completes or deletes it (capability `event-lifetime`), which also collects
  // the bytes. Idempotent: a repeated leave, or one naming a membership that never existed, changes nothing and
  // is not an error, so a retried DELETE re-runs harmlessly. Any transport failure → 502.
  //
  // After the event's range has ended, the leave that takes away the last member still `sharing` CLOSES the
  // event (capability `event-lifetime`, "A finished event closes": every member still in it has settled), and
  // wakes the members still in it once, as the publish that closes does.
  deviceApi.delete("/events/:eventId/devices/:deviceId", async (c) => {
    const { eventId, deviceId } = eventAndOwnDeviceParams(
      c.req.param(),
      c.get("tokenDeviceId"),
      "invalid key",
    );
    const received = c.req.query("received") === "true";

    // The lifecycle gate (capability `event-lifetime`): an absent event 404s, which the client already
    // treats as "nothing to leave". A leave DURING grace proceeds: members may still
    // depart an over-but-not-yet-swept event.
    const event = await gateEvent(db, eventId, "leave");

    const nowMs = now();
    // A closed event cannot close again, and before the end nothing closes.
    const closeAt = !event.closedAt && nowMs > Date.parse(event.endsAt)
      ? new Date(nowMs).toISOString()
      : null;
    const closed = await tryUpstream(
      `leave: depart failed for ${eventId}/${deviceId}`,
      async () => {
        // ONE column, one batch. Membership is a `state`, so leaving cannot leave a half-applied pair behind
        // and cannot be double-counted; the close stamp runs last and sees it.
        const results = await db.batch(leaveStatements(eventId, deviceId, received, closeAt));
        return closeAt !== null && results[results.length - 1].rowsAffected > 0;
      },
    );
    // AFTER the commit, and best-effort: the members still in it must learn the close to finish and leave.
    if (closed) {
      c.var.log.field("closed", true);
      recordNotify(c.var.log, await notifyMembers(deps, eventId, deviceId, "close"));
    }
    // Always succeed: the event persists (rejoinable while open) regardless of how many members remain,
    // and a leave naming a membership that never existed changes nothing rather than failing.
    return c.body(null, 200);
  });

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
  deviceApi.get("/events/:eventId/files", async (c) => {
    const eventId = eventParam(c.req.param("eventId"));

    const query = new URL(c.req.url).searchParams;
    const rawCursor = query.get("cursor");
    if (rawCursor !== null && !/^\d{1,15}$/.test(rawCursor)) return c.text("invalid cursor", 400);
    const after = rawCursor === null ? undefined : Number(rawCursor);
    const withUrls = query.get("urls") !== "false";

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
  });

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
  deviceApi.get("/events/:eventId/files/devices/:deviceId/:assetId/:role", async (c) => {
    const eventId = eventParam(c.req.param("eventId"));
    const deviceId = c.req.param("deviceId");
    const assetId = c.req.param("assetId");
    const role = c.req.param("role");
    if (!validateUUID(deviceId) || !validateFilename(assetId) || !RESOURCE_ROLES.includes(role)) {
      return c.text("not found", 404);
    }
    return await orUpstream502(`download: lookup failed for ${eventId}`, async () => {
      const path = await downloadStoragePath(db, eventId, deviceId, assetId, role);
      c.header("Cache-Control", NO_CACHE);
      if (path === null) return c.text("not found", 404);
      return c.redirect(await presignDownloadUrl(aws, config, path), 302);
    });
  });

  // Write a device's config document (`docs/architecture.md`). Gated by DEVICE-ID possession alone
  // (no event) — the same capability model as the byte upload. The document is recorded against the
  // device (`docs/architecture.md`); last-write-wins, and it is not a resource, so it never appears in the
  // per-device listing or the union.
  deviceApi.put("/devices/:deviceId", async (c) => {
    const deviceId = ownDeviceParam(
      c.req.param("deviceId"),
      c.get("tokenDeviceId"),
      "invalid device",
    );
    const body = await readJson(c.req);
    // A JSON `null` body is no document at all — `invalid body`, like one that is not JSON.
    if (body === null) return c.text("invalid body", 400);
    const pt = (body as { pushToken?: Record<string, unknown> }).pushToken;
    let push: { kind: string; token: string; env: string } | null;
    if (pt === undefined || pt === null) {
      // An explicit absence: the device is telling us it has no registration. Distinct from a
      // malformed body, and a legitimate thing to record.
      push = null;
    } else if (
      typeof pt.kind === "string" && typeof pt.token === "string" && typeof pt.env === "string"
    ) {
      push = { kind: pt.kind, token: pt.token, env: pt.env };
    } else {
      return c.text("invalid pushToken", 400);
    }
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
  });
  return deviceApi;
}
