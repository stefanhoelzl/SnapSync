// The SHARED device API: routes whose contract is identical under every version, mounted into each
// version's router, so there is one implementation and no possibility of the two drifting apart.

import { Hono } from "hono";
import {
  departMembership,
  type EventRow,
  insertEvent,
  memberCounts,
  putDeviceRecord,
  renameEvent,
  unionRows,
} from "../db.ts";
import {
  canonicalPlusSeconds,
  validateEndsAt,
  validateEventName,
  validateStartsAt,
} from "../validators.ts";
import {
  closedRefusal,
  eventAndOwnDeviceParams,
  eventParam,
  gateEvent,
  NO_CACHE,
  orUpstream502,
  ownDeviceParam,
  presignDownloadUrl,
  publicEvent,
  readJson,
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
  url: string;
};
type UnionAsset = {
  deviceId: string;
  assetId: string;
  creationDate: string;
  resources: UnionResource[];
};

/** The routes both versions serve, built over `deps`. */
export function sharedRoutes({ config, db, now, aws }: RouteDeps): Hono {
  // SHARED: routes whose contract is identical under every version. Mounted into each version's router,
  // so there is one implementation and no possibility of the two drifting apart.
  const deviceApi = new Hono();

  // Create an event (capabilities `docs/architecture.md`, `event-lifetime`). GATED by the device token (`app.ts`) (an
  // ungated create let a stranger mint unbounded events). Beyond that gate it stays a
  // possession-is-capability model. Validates the name, mints a server-side UUID, and INSERTs the row.
  // Faithful outcome: 201 only after the store confirms the write; any failure → 502.
  deviceApi.post("/events", async (c) => {
    const json = await readJson(c);
    if (json instanceof Response) return json;
    const body = json.body;
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
    const event: EventRow = {
      eventId: crypto.randomUUID(),
      name,
      createdAt: new Date(now()).toISOString(),
      startsAt,
      endsAt,
      capacity: config.eventCapacity,
      lifetimeSeconds: config.eventLifetimeSeconds,
    };

    const inserted = await tryUpstream(
      c,
      `create: event insert failed for ${event.eventId}`,
      () => insertEvent(db, event),
    );
    if (inserted instanceof Response) return inserted;
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
  deviceApi.get("/events/:eventId", async (c) => {
    const eventId = eventParam(c);
    if (eventId instanceof Response) return eventId;
    const event = await gateEvent(db, c, eventId, "metadata");
    if (event instanceof Response) return event;
    // `members` feeds the ended event's waiting line (capability `sync-status`): how many of the active
    // members have settled what they share. A completed event has none left.
    return await orUpstream502(
      c,
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
    const eventId = eventParam(c);
    if (eventId instanceof Response) return eventId;
    const json = await readJson(c);
    if (json instanceof Response) return json;
    // The SAME validator the create route uses — one rule for what an event may be called.
    const name = validateEventName((json.body as { name?: unknown } | null)?.name);
    if (name === null) {
      return c.text("invalid name", 400); // missing/empty/whitespace/too long
    }

    // The same existence gate the metadata route serves from: absent → 404 (never a partial
    // rewrite of a row the sweep is about to delete); a transport failure → 502, so a
    // transient fault is never mistaken for absence.
    const current = await gateEvent(db, c, eventId, "rename");
    if (current instanceof Response) return current;
    // A closed event does not change any more, its name included (capability `event-lifetime`).
    if (current.closedAt) return closedRefusal(c);

    // ONE column. `renameEvent` is a `SET name = ?` and nothing else — see `db.ts`, where the statement
    // is spelled out in one place precisely because widening it is now a one-word edit.
    const written = await tryUpstream(
      c,
      `rename: update failed for ${eventId}`,
      () => renameEvent(db, eventId, name),
    );
    if (written instanceof Response) return written;
    // Zero rows means the event was deleted between the gate and the write. Report the absence rather
    // than a success that renamed nothing.
    if (written.rowsAffected === 0) return c.text("event not found", 404);
    return c.json(publicEvent({ ...current, name }));
  });

  // Leave an event (capability `manage-membership`). A STATE CHANGE, and non-destructive: mark the membership
  // `departed`. GATED on the event row (absent → 404; read failure → 502). The membership's assets are
  // RETAINED, so the union still serves what the device shared, and the route returns 200 REGARDLESS of
  // remaining membership — the event survives until it expires and is deleted by the nightly sweep
  // (capability `event-lifetime`), which also collects the bytes. No last-member reap, no leave-time
  // garbage collection. Idempotent: a repeated leave, or one naming a membership that never existed,
  // changes nothing and is not an error, so a retried DELETE re-runs
  // harmlessly. Any transport failure → 502.
  deviceApi.delete("/events/:eventId/devices/:deviceId", async (c) => {
    const ids = eventAndOwnDeviceParams(c, "invalid key");
    if (ids instanceof Response) return ids;
    const { eventId, deviceId } = ids;

    // The lifecycle gate (capability `event-lifetime`): an absent event 404s, which the client already
    // treats as "nothing to leave". A leave DURING grace proceeds: members may still
    // depart an over-but-not-yet-swept event.
    const event = await gateEvent(db, c, eventId, "leave");
    if (event instanceof Response) return event;

    return await orUpstream502(c, `leave: depart failed for ${eventId}/${deviceId}`, async () => {
      // ONE column. Membership is a `state`, so leaving cannot leave a half-applied pair behind and
      // cannot be double-counted. The membership's assets are RETAINED, so the union still serves what
      // this device shared.
      await departMembership(db, eventId, deviceId);
      // Always succeed: the event persists (rejoinable) regardless of how many active members remain,
      // and a leave naming a membership that never existed changes nothing rather than failing.
      return c.body(null, 200);
    });
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
  deviceApi.get("/events/:eventId/files", async (c) => {
    const eventId = eventParam(c);
    if (eventId instanceof Response) return eventId;

    // Gate on the event row (`docs/architecture.md`): absent → 404; a store failure → 502. An event past
    // its window still serves its union — the window closes nothing.
    const event = await gateEvent(db, c, eventId, "union");
    if (event instanceof Response) return event;

    return await orUpstream502(c, `union: assembly failed for event ${eventId}`, async () => {
      // ONE query, spanning the event's memberships in BOTH states: a member who has left keeps
      // contributing the photos it already shared, until the event itself is deleted. What this replaces
      // is a fan-out — one directory listing to discover members, then a manifest read AND a byte listing
      // per member — whose cost grew with the event and which no index could help.
      const rows = await unionRows(db, eventId);

      // Group by (device, asset), keeping each asset's resources together and dropping any asset that
      // names a resource the backend has not recorded as uploaded. That check IS the completeness
      // mechanism: a manifest declares roles whose bytes may not have arrived, so the declaration supplies
      // the expectation and these rows supply the reality. It is what distinguishes "this photo is coming"
      // from "this photo does not exist". The sweep still protects a referenced byte from collection.
      const byAsset = new Map<string, { row: typeof rows[number]; resources: typeof rows }>();
      for (const r of rows) {
        const id = `${r.deviceId}/${r.assetId}`;
        const slot = byAsset.get(id) ?? { row: r, resources: [] };
        slot.resources.push(r);
        byAsset.set(id, slot);
      }

      const assets: UnionAsset[] = [];
      for (const { row, resources } of byAsset.values()) {
        if (resources.length === 0 || resources.some((r) => !r.present)) continue;
        assets.push({
          deviceId: row.deviceId,
          assetId: row.assetId,
          creationDate: row.creationDate,
          resources: await Promise.all(resources.map(async (r) => ({
            role: r.role,
            contentType: r.contentType,
            key: r.key,
            filename: r.filename,
            // From `key`, never `filename`: the object is stored under its key, and a capture name that
            // differs would presign a URL that 404s at download while everything else looked right.
            url: await presignDownloadUrl(aws, config, r.deviceId, r.key),
          }))),
        });
      }

      c.header("Cache-Control", NO_CACHE); // every `url` is a time-limited presigned S3 URL
      return c.json(assets);
    });
  });

  // Write a device's config document (`docs/architecture.md`). Gated by DEVICE-ID possession alone
  // (no event) — the same capability model as the byte upload. The document is recorded against the
  // device (`docs/architecture.md`); last-write-wins, and it is not a resource, so it never appears in the
  // per-device listing or the union.
  deviceApi.put("/devices/:deviceId", async (c) => {
    const deviceId = ownDeviceParam(c, "invalid device");
    if (deviceId instanceof Response) return deviceId;
    const json = await readJson(c);
    if (json instanceof Response) return json;
    // A JSON `null` body is no document at all — `invalid body`, like one that is not JSON.
    if (json.body === null) return c.text("invalid body", 400);
    const pt = (json.body as { pushToken?: Record<string, unknown> }).pushToken;
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
      c,
      `config: device record write failed for ${deviceId}`,
      () => putDeviceRecord(db, deviceId, push, new Date(now()).toISOString()),
    );
    if (written instanceof Response) return written;
    if (written.rowsAffected === 0) {
      // The token verified — it is ours and unexpired — but we hold no attestation for this device.
      // `401` is the answer because the remedy is the same one a rejected token has, and the shipped
      // client already takes it: drop the token, attest afresh (which CREATES the row), and re-send
      // this registration when a new token arrives. A `201` here would be a silent absence — the device
      // would believe it is reachable while no push could ever reach it, and it writes its registration
      // once per OS-delivered token, so nothing would retry.
      console.info(`config: no attestation on file for ${deviceId} — refusing the registration`);
      return c.text("unattested", 401);
    }
    return c.body(null, 201);
  });
  return deviceApi;
}
