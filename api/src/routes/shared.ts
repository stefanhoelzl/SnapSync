// The SHARED device API: routes whose contract is identical under every version, mounted into each
// version's router, so there is one implementation and no possibility of the two drifting apart.

import { verifyToken } from "../attest.ts";
import { CAPACITY_PLACEHOLDER } from "../config.ts";
import {
  completeAssets,
  declaredTotal,
  downloadTarget,
  type EventRow,
  insertEvent,
  leaveStatements,
  memberCounts,
  type NameChange,
  putDeviceRecord,
  renameEvent,
  unionPosition,
  unionRows,
} from "../db.ts";
import type { Handlers } from "../gen/wire.ts";
import { CONTEXT_HEADER, contextFields } from "../delivery-context.ts";
import { envelopeShape } from "../sealed-meta.ts";
import { splitVersion } from "../version.ts";
import { refuse } from "../refusal.ts";
import { canonicalFromMs, canonicalPlusSeconds } from "../validators.ts";
import { openUntilAdmitted } from "../lifecycle.ts";
import {
  closedForOldBuilds,
  closedRefusal,
  deviceOrigin,
  downloadPath,
  gateEvent,
  NO_CACHE,
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
 * Why a client read the union, as it says in `SnapSync-Trigger`, for the union log.
 * A value outside this list is recorded as unknown, never
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
  "received-check",
] as const;

/** The response header naming the position a union answer covers (decision record D3). */
export const CURSOR_HEADER = "SnapSync-Cursor";
/** The union read's declared total (`declaredTotal`): what a member's received total counts. */
export const DECLARED_HEADER = "SnapSync-Declared";
/** The request header naming why the client reads (decision record D5). */
export const TRIGGER_HEADER = "SnapSync-Trigger";

/** Whole days from one instant to a later one, rounded — the delivery record's measure of an event's dates. */
function daysBetween(from: string, to: string): number {
  return Math.round((Date.parse(to) - Date.parse(from)) / 86_400_000);
}

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
    // Create an event (`docs/architecture.md`). GATED by the device token (`app.ts`)
    // (an ungated create let a stranger mint unbounded events). Beyond that gate it stays a possession-is-capability
    // model. The body arrives validated (`EventCreate`): a trimmed name, a canonical `startsAt`, and an `endsAt`
    // strictly after it and within the window maximum — each refused with its own text before this runs. Mints a
    // server-side UUID and INSERTs the row. Faithful outcome: 201 only after the store confirms the write; any
    // failure → 502.
    //
    // `endsAt` is CREATOR-SUPPLIED and bounds ONLY which captures may be uploaded — it is not a lifetime. ABSENT, it
    // falls back to `startsAt + windowMax`, so old clients that send only `startsAt` keep working. `lifetimeSeconds`
    // stays server-resolved, and `capacity` is the placeholder (`CAPACITY_PLACEHOLDER`) whatever a client sends. An ENCRYPTED event names its key's id (the encrypted file format,
    // `docs/architecture.md`); the key itself never reaches this backend. The host's `zone` is only the event
    // page's: one this runtime cannot use arrives absent and is stored null.
    //
    // `openUntil` is until when the event stays open — the date every lifecycle rule counts from (decision record
    // `changes/back-dated-events`, D1). ABSENT it is `endsAt`, which is what every build that predates it gets. Its
    // bound reads the clock, which the declared validation cannot, so it is checked here: at least `endsAt`, and
    // at most the window maximum after the later of `endsAt` and now — room for a host who creates an event that
    // is already over to keep it open for the guests still to scan its invite, and no more.
    //
    // An ENCRYPTED event's name arrives SEALED (`sealedMeta`, decision record `changes/seal-event-name`, D3/D4) and a
    // plain event's readable (`name`) — never both, never the other way round, each mismatch `invalid name`. So no
    // event is ever created in the legacy shape (a key id with a readable name), which only rows from before that
    // change hold. The record's shape is all this can check of it: the key that opens it never reaches here.
    async createEvent(c, { body }) {
      const { startsAt } = body;
      const named = createdName(body);
      const endsAt = body.endsAt ?? canonicalPlusSeconds(startsAt, config.eventWindowMaxSeconds);
      const openUntil = body.openUntil ?? endsAt;
      if (!openUntilAdmitted(endsAt, openUntil, now(), config.eventWindowMaxSeconds)) {
        refuse(400, "invalid openUntil");
      }
      const event: EventRow = {
        eventId: crypto.randomUUID(),
        name: "name" in named ? named.name : null,
        sealedMeta: "sealedMeta" in named ? named.sealedMeta : null,
        createdAt: canonicalFromMs(now()),
        startsAt,
        endsAt,
        openUntil,
        capacity: CAPACITY_PLACEHOLDER,
        lifetimeSeconds: config.eventLifetimeSeconds,
        zone: body.zone ?? null,
        keyId: body.keyId ?? null,
      };

      await tryUpstream(
        `create: event insert failed for ${event.eventId}`,
        () => insertEvent(db, event),
      );
      // The row exists — only now is the event created. The delivery record's facts of a creation: which event,
      // whether it is encrypted, how many days its date range spans, and whether it was created after its end.
      c.var.log.field("event", event.eventId);
      c.var.log.field("encrypted", event.keyId !== null);
      c.var.log.field("window_days", daysBetween(event.startsAt, event.endsAt));
      c.var.log.field("open_days", daysBetween(event.endsAt, event.openUntil));
      c.var.log.field("backdated", Date.parse(event.endsAt) < Date.parse(event.createdAt));
      return c.json(publicEvent(event), 201);
    },

    // Event metadata / existence (`docs/architecture.md`). Returns the event — always carrying
    // `startsAt`, `endsAt`, the `capacity` placeholder, and the derived `deletesAt`, because those are `NOT NULL` columns
    // — or 404 when the event was never created or the sweep deleted it; a read failure → 502. This is the
    // canonical existence check the device-manifest write gate relies on. There is no third answer: the
    // INCOMPLETE case the object era had to carry is unstateable now (see `gateEvent`).
    //
    // An event past its WINDOW (`endsAt`) serves normally — the window closes nothing. An event past its
    // derived `deletesAt` ALSO serves normally until the nightly sweep removes it: no route deletes on
    // touch. The 404 a client acts on is therefore always a real deletion, which is what makes it safe as
    // one of the two witnesses the client's self-leave requires.
    //
    // A bearer token is OPTIONAL, as on the union: present, it must verify (`401` otherwise, so the app re-attests);
    // absent, the read is served as before. The app sends one on every read of an event; builds that predate it
    // send none, and are served until a minimum app version retires them
    // (decision record `changes/separate-event-page-from-device-api`, D7).
    async getEvent(c, { params: { eventId } }) {
      await optionalReader(c.req.header("authorization"));
      const event = await gateEvent(db, eventId, "metadata");
      // `members` feeds the joined screen: how many are in the event, and how many of
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

    // Change an event — today, rename it. The ONLY route that writes an existing
    // event row, and it writes exactly ONE column. `name` is the single exception to the row's write-once rule
    // because it touches neither threat that rule names: a name cannot
    // retroactively widen a joiner's capture scope and cannot extend an event's limits. It is cosmetic to
    // the upload gate, cosmetic to the extension, and load-bearing for display alone.
    //
    // GATED by the device token like `POST /events`, and BEYOND that gate there is no ownership check —
    // there is no owner field, and possession of the event id already authorizes uploading into the event
    // and listing every photo in it, so a rename is strictly weaker than what a holder already has.
    //
    // ⚠️ Every other field is written back VERBATIM — never restamped, never recomputed. That is what
    // makes a race with the nightly sweep self-defusing: a rename that
    // re-creates a row the sweep has just deleted re-creates it carrying its ORIGINAL `createdAt`,
    // `startsAt`, and `lifetimeSeconds`, so its derived delete-by is still in the past and the next sweep
    // reaps it again. Restamping any of those would resurrect the event for a fresh lifetime.
    //
    // Concurrent renames are last-write-wins: bunny has no compare-and-set (the same constraint the
    // device-manifest capacity gate reads and writes under). No ordering guarantee is available or claimed.
    // The body arrives validated (`EventChange`): the same `EventName` the create takes — one rule for what an event
    // may be called — or a sealed record, and at least one field to change. Which of the two the row takes follows
    // its shape (`renamedTo`): a plain event takes a name, a sealed one a record; a LEGACY encrypted event takes
    // either — a build that predates sealed names still renames it, and a record moves it to the sealed shape.
    async changeEvent(c, { params: { eventId }, body }) {
      // The same existence gate the metadata route serves from: absent → 404 (never a partial
      // rewrite of a row the sweep is about to delete); a transport failure → 502, so a
      // transient fault is never mistaken for absence.
      const current = await gateEvent(db, eventId, "rename");
      // A completed event does not change any more, its name included.
      if (current.completedAt) closedRefusal();
      const change = renamedTo(current, body);

      // ONE column. `renameEvent` writes the name and nothing else — see `db.ts`, where each statement
      // is spelled out in one place precisely because widening it is a one-word edit.
      const written = await tryUpstream(
        `rename: update failed for ${eventId}`,
        () => renameEvent(db, eventId, change),
      );
      // Zero rows means the event was deleted between the gate and the write. Report the absence rather
      // than a success that renamed nothing.
      if (written.rowsAffected === 0) return c.text("event not found", 404);
      return c.json(
        publicEvent(
          "sealedMeta" in change
            ? { ...current, name: null, sealedMeta: change.sealedMeta }
            : { ...current, name: change.name },
        ),
      );
    },

    // Leave an event. A STATE CHANGE, and non-destructive: the membership becomes
    // `done` or `left` (see `leaveStatements`) — `done` only when it had settled, every role it declared has landed,
    // and the device says `?received=true` (it holds every photo of the others; anything but `true` is "no"). GATED
    // on the event row (absent → 404; read failure → 502). The membership's assets are RETAINED, so the union still
    // serves what the device shared, and the route returns 200 REGARDLESS of remaining membership — the event
    // survives until the nightly sweep completes or deletes it, which also collects
    // the bytes. Idempotent: a repeated leave, or one naming a membership that never existed, changes nothing and
    // is not an error, so a retried DELETE re-runs harmlessly. Any transport failure → 502.
    //
    // A leave closes nothing and wakes nobody: events no longer close (decision record `changes/back-dated-events`,
    // D2). Whether a departure leaves the event finished is computed by the sweep and the event read.
    async leaveEvent(c, { params }) {
      const { eventId } = params;
      const deviceId = ownDevice(c.get("tokenDeviceId"), params.deviceId);
      const received = params.received === true;

      // The lifecycle gate: an absent event 404s, which the client already
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
    // (the ungated closed list in `app.ts`) — but still gated on event EXISTENCE: absent → 404,
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
    //     is kept: a web visitor leaves no trace in the event.
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
        // The event's declared total, the same on a full and a delta read: it describes the event, not the page.
        const declared = await declaredTotal(db, eventId);

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

        // The delivery record of this read (decision record `changes/delivery-record`):
        // which device read — verified by its token, `-` for any other reader, of whom nothing else is kept — why,
        // from which position to which, how much it was given, and for a read a push caused, when the push arrived
        // and which position it announced.
        c.var.log.field("reader", reader ?? "-");
        c.var.log.field("served", assets.length);
        c.var.log.field("trigger", trigger ?? "-");
        if (after !== undefined) c.var.log.field("from", after);
        c.var.log.field("to", position);
        const notified = params["x-snapsync-notified"];
        if (notified !== undefined) c.var.log.field("notified", notified);
        const announced = params["x-snapsync-announced"];
        if (announced !== undefined) c.var.log.field("announced", Number(announced));

        c.header("Cache-Control", NO_CACHE); // a `url` may be a time-limited presign; a cursor is a moment
        c.header(CURSOR_HEADER, String(position));
        c.header(DECLARED_HEADER, String(declared));
        return c.json(assets);
      });
    },

    // DOWNLOAD one resource of an event's union (decision record `changes/incremental-union`, D1–D2): `302`
    // to a freshly presigned S3 GET, so no union read signs anything and a signature is minted per download
    // started. UNGATED like the union — the event id is the read capability — and exempt from the version
    // gate (`app.ts`): the OS download transports that fetch it send no app header. `404` when the event no
    // longer declares the asset with this role or its bytes are not recorded (a withdrawal stops the link).
    // The redirect is never cached: bunny's pull zone fronts this script,
    // and a cached 302 would hand out a stale signature.
    //
    // The signature keeps S3's 7-day maximum on purpose (D2): an iOS background session resumes from the
    // redirect TARGET and never revisits this route (measured), so a shorter one turns a resume after an
    // outage into a restart.
    //
    // The redirect is where a receiver's download STARTS, so it carries the delivery record's facts of that start
    // (decision record `changes/delivery-record`, D2/D7): when the phone queued it and why, and whether the photo
    // landed before this receiver joined. The receiver is the context's `device`, unverified; a browser names none.
    async downloadResource(c, { params }) {
      const { eventId, deviceId, assetId, role } = params;
      const receiver = contextFields(c.req.header(CONTEXT_HEADER)).device;
      return await orUpstream502(`download: lookup failed for ${eventId}`, async () => {
        const target = await downloadTarget(db, {
          eventId,
          deviceId,
          assetId,
          role,
          receiver: typeof receiver === "string" ? receiver : null,
        });
        c.header("Cache-Control", NO_CACHE);
        if (target === null) return c.text("not found", 404);
        const queued = params["x-snapsync-queued"];
        if (queued !== undefined) c.var.log.field("queued", queued);
        const queuedBy = params["x-snapsync-queued-by"];
        if (queuedBy !== undefined) c.var.log.field("queued_by", queuedBy);
        if (target.backlog !== null) c.var.log.field("backlog", target.backlog);
        return c.redirect(await presignDownloadUrl(aws, config, target.path), 302);
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
      // route cannot attest on its behalf.
      const written = await tryUpstream(
        `config: device record write failed for ${deviceId}`,
        () => putDeviceRecord(db, deviceId, push),
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

/** A sealed record whose envelope is this format, or `invalid sealedMeta` — all the edge can check of one. */
function shapedRecord(sealedMeta: string): string {
  if (!envelopeShape(sealedMeta).ok) refuse(400, "invalid sealedMeta");
  return sealedMeta;
}

/**
 * The name a create carries, by the event's encryption (decision record `changes/seal-event-name`, D4): an encrypted
 * event's sealed, a plain event's readable — each mismatch `invalid name`, so no new row takes the legacy shape.
 */
function createdName(body: { name?: string; sealedMeta?: string; keyId?: string }): NameChange {
  if (body.keyId !== undefined) {
    if (body.name !== undefined || body.sealedMeta === undefined) refuse(400, "invalid name");
    return { sealedMeta: shapedRecord(body.sealedMeta) };
  }
  if (body.sealedMeta !== undefined || body.name === undefined) refuse(400, "invalid name");
  return { name: body.name };
}

/**
 * What a rename of [current] writes, by the row's shape: a plain event takes a name, a sealed one a record, and a
 * LEGACY encrypted one either — a build from before sealed names still renames it, and a record seals it. A sealed
 * event is never handed a readable name: its row may not hold one.
 */
function renamedTo(current: EventRow, body: { name?: string; sealedMeta?: string }): NameChange {
  if (body.name !== undefined && body.sealedMeta !== undefined) refuse(400, "invalid name");
  if (body.sealedMeta !== undefined) {
    if (!current.keyId) refuse(400, "invalid name");
    return { sealedMeta: shapedRecord(body.sealedMeta) };
  }
  if (body.name === undefined || current.sealedMeta) refuse(400, "invalid name");
  return { name: body.name };
}
