// What every route of the device API shares (`docs/architecture.md`): the device binding, the path and body
// readers, the faithful-outcome refusals, the event gate, the streaming byte PUT and the presigned download
// URL. Each helper keeps the status, body text and log line its routes answered with before it existed — a
// route's wire answer is its contract, and a shared helper must not move it.

import type { Context } from "hono";
import type { AwsClient } from "aws4fetch";
import type { Config } from "../config.ts";
import {
  type Db,
  type EnrollOutcome,
  type EventRow,
  pushTokensForEvent,
  readEvent,
  recordAppVersion,
  unionPosition,
} from "../db.ts";
import { deleteByMs } from "../lifecycle.ts";
import { type PushSender, unsentSummary } from "../push.ts";
import { type FetchLike, storageKey } from "../storage.ts";
import { canonicalFromMs, validateUUID } from "../validators.ts";
import { APP_VERSION_HEADER, recordableVersion, splitVersion } from "../version.ts";

/** What a route factory is built over: `createApp`'s dependencies, resolved once. */
export type RouteDeps = {
  fetchImpl: FetchLike;
  revocationFetch: FetchLike;
  config: Config;
  db: Db;
  now: () => number;
  /** The S3 signer used ONLY to presign download URLs. */
  aws: AwsClient;
  /** The silent-wake sender every wake goes through. */
  pushSender: PushSender;
};

// The device the verified token was minted for, set by the token gate (capability `privacy-security`,
// "Only a genuine SnapSync app can change an event"). Typed here, once, so every route reads the same key.
declare module "hono" {
  interface ContextVariableMap {
    tokenDeviceId: string;
  }
}

/**
 * Whether this request's token was minted for `deviceId` — the binding every route naming a device in its
 * path applies after validating the id and before touching any store (capability `privacy-security`: "A
 * genuine app SHALL act only for its own device").
 *
 * WHY IT EXISTS. The token proves a genuine app instance; on its own it said nothing about WHICH device the
 * caller is. Device ids are not secret — every member's id is in the event union, which is ungated — so
 * without this any genuine install could publish, upload, leave or re-register a push token as another
 * device. The token already carries the id it was minted for (`verifyToken` returns it), so the binding
 * costs one string compare and no read — the hot-path property the gate was built around still holds.
 *
 * Compared against Hono's DECODED path parameter, per route, rather than by re-parsing the raw path in the
 * gate: the route acts on the decoded value, so that is the only value whose binding means anything (a
 * percent-encoded id would slip past a raw-path matcher and still be decoded by the router).
 *
 * FAILS CLOSED: a request that reached a route without passing the token gate has no token device, and
 * `undefined` equals no id.
 */
export function actsFor(c: Context, deviceId: string): boolean {
  return c.get("tokenDeviceId") === deviceId;
}

/**
 * The refusal of a request naming a device its token was not minted for. `403`, not `401`: the credential
 * is valid and is NOT the problem — a `401` from a gated route makes the shipped client drop its token and
 * re-attest (`withCredentialInterceptor`), which would loop forever without changing the answer.
 */
export function notThisDevice(c: Context): Response {
  return c.text("not this device", 403);
}

/**
 * The path's `deviceId` when it is a UUID the token acts for; otherwise the refusal to return — `400` with the
 * route's own `invalid` text, or `403` ({@link notThisDevice}). The id is validated BEFORE the binding, so a
 * malformed id is a `400` whoever asks.
 */
export function ownDeviceParam(c: Context, invalid: string): string | Response {
  const deviceId = c.req.param("deviceId") ?? "";
  if (!validateUUID(deviceId)) return c.text(invalid, 400);
  return actsFor(c, deviceId) ? deviceId : notThisDevice(c);
}

/**
 * The path's `eventId` and `deviceId` when both are UUIDs and the token acts for the device; otherwise the
 * refusal to return, as {@link ownDeviceParam} — one `invalid` text for either malformed id.
 */
export function eventAndOwnDeviceParams(
  c: Context,
  invalid: string,
): { eventId: string; deviceId: string } | Response {
  const eventId = c.req.param("eventId") ?? "";
  const deviceId = c.req.param("deviceId") ?? "";
  if (!validateUUID(eventId) || !validateUUID(deviceId)) return c.text(invalid, 400);
  return actsFor(c, deviceId) ? { eventId, deviceId } : notThisDevice(c);
}

/** The path's `eventId` when it is a UUID; otherwise the `400 invalid event` to return. */
export function eventParam(c: Context): string | Response {
  const eventId = c.req.param("eventId") ?? "";
  return validateUUID(eventId) ? eventId : c.text("invalid event", 400);
}

/** The request body parsed as JSON, or the `400 invalid body` to return when it is not JSON. */
export async function readJson(c: Context): Promise<{ body: unknown } | Response> {
  try {
    return { body: await c.req.json() };
  } catch {
    return c.text("invalid body", 400);
  }
}

/**
 * The faithful-outcome answer to a store or upstream failure: logged as `<what>: <error>`, answered `502` —
 * never mistaken for absence, never a partial success.
 */
export function upstream502(c: Context, what: string, e: unknown): Response {
  console.error(`${what}: ${e}`);
  return c.text("upstream error", 502);
}

/** `step()`'s value, or — when it throws — the {@link upstream502} (logged under `what`) to return. */
export async function tryUpstream<T>(
  c: Context,
  what: string,
  step: () => Promise<T>,
): Promise<T | Response> {
  try {
    return await step();
  } catch (e) {
    return upstream502(c, what, e);
  }
}

/** `respond()`'s response, or — when it throws — the {@link upstream502} logged under `what`. */
export async function orUpstream502(
  c: Context,
  what: string,
  respond: () => Promise<Response>,
): Promise<Response> {
  try {
    return await respond();
  } catch (e) {
    return upstream502(c, what, e);
  }
}

// RequestInit + the streaming-body flag required when `body` is a ReadableStream.
type StreamInit = RequestInit & { duplex?: "half" };

/**
 * `body`, passed through unchanged, with a record of whether READING it failed. A storage PUT that throws
 * cannot say whose side broke: a phone whose upload dies mid-body (a weak mobile uplink — measured
 * 2026-10-05, 34 such lines in one quarter hour from two phones on one carrier) surfaces as the same
 * fetch rejection as storage being unreachable. Only the reader of the incoming stream sees which.
 */
function watchedBody(body: ReadableStream<Uint8Array> | null) {
  const watch = { clientAborted: false, body: body as ReadableStream<Uint8Array> | null };
  if (!body) return watch;
  const reader = body.getReader();
  watch.body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } catch (e) {
        watch.clientAborted = true;
        controller.error(e);
        return;
      }
      if (chunk.done) controller.close();
      else controller.enqueue(chunk.value);
    },
    cancel: (reason) => reader.cancel(reason),
  });
  return watch;
}

// 7 days — the S3 presign maximum. The device re-presigns (re-reads the union) on every foreground well
// within this window, so a queued background download that outlives one URL self-heals with a fresh one.
export const PRESIGN_EXPIRY_SECONDS = 604800;

// 1 hour — the EVENT PAGE's links (`/web/events/<id>/photos`, decision record
// `changes/separate-event-page-from-device-api` D3). The 7 days above are forced by iOS background sessions,
// which resume from the redirect target; a browser uses its links within minutes, so a short life costs it
// nothing and bounds how long a withdrawn photo stays reachable through a page loaded before the withdrawal
// (capability `privacy-security`). A zip that outlasts it re-reads the list.
export const WEB_PRESIGN_EXPIRY_SECONDS = 3600;

// The listing routes' cache header. All three directives are deliberate: the Edge Script is fronted by a
// bunny CDN pull zone, and bunny documents `no-cache` — NOT `no-store` — as the origin directive that
// suppresses its cache. `no-store` alone would rest the listings' cacheability on undocumented behavior,
// and a cached listing serves stale, expiring presigned URLs.
export const NO_CACHE = "no-store, no-cache, max-age=0";

/**
 * Stream the request body into ONE bunny native Storage PUT at `key` — pass-through, never buffered or
 * hashed. `null` once bunny confirmed the stored object; otherwise the `502` to return — `upstream error`
 * when the PUT itself errored, `upstream rejected` when bunny refused it — logged under the route's `route`.
 * A PUT that errored because the CLIENT's body broke off is logged as that, at info: it is the network
 * between the phone and the edge, which the device's retry absorbs, not a fault of ours or of storage.
 */
export async function streamPut(
  fetchImpl: FetchLike,
  config: Config,
  c: Context,
  route: string,
  key: string,
  contentType: string,
  /** What to store: the request's body, unless the route decided otherwise (`bodyToStore`). */
  body: ReadableStream<Uint8Array> | null = c.req.raw.body,
): Promise<Response | null> {
  const watch = watchedBody(body);
  let upstream: Response;
  try {
    upstream = await fetchImpl(`https://${config.host}/${config.zone}/${key}`, {
      method: "PUT",
      headers: { AccessKey: config.accessKey, "Content-Type": contentType },
      body: watch.body, // ReadableStream — streamed straight through, never buffered
      duplex: "half",
    } as StreamInit);
  } catch (e) {
    if (!watch.clientAborted) return upstream502(c, `${route}: upstream PUT errored for ${key}`, e);
    console.log(`${route}: client aborted the upload of ${key}: ${e}`);
    return c.text("upstream error", 502);
  }
  if (!upstream.ok) {
    console.error(`${route}: bunny returned ${upstream.status} for ${key}`);
    return c.text("upstream rejected", 502);
  }
  return null;
}

/**
 * Mint an AWS SigV4 **presigned S3 GET URL** for a stored object (the download-URL authority for
 * `docs/architecture.md`): `<s3Scheme>://<s3Host>/<zone>/<key>?X-Amz-…&X-Amz-Signature=…` — `https` in
 * every deployed configuration; only the local dev rig moves it, so it can serve loopback HTTP that a
 * device can actually fetch. Path-style, each
 * key segment percent-encoded (deviceId is a UUID → identity), `X-Amz-Expires` 7 days unless
 * [expirySeconds] says otherwise. The zone name is
 * the S3 Access Key ID and `accessKey` the secret. The device fetches this URL DIRECTLY from bunny's S3
 * endpoint with no credential — the query signature is the sole authorization. A fresh URL is minted on
 * every listing response, so each read yields one valid for a further 7 days. Both list routes use this
 * single builder, so per-device list and union agree by construction.
 */
export async function presignDownloadUrl(
  aws: AwsClient,
  config: Config,
  /** The stored `resources.path` — wherever the bytes were written, before or after migration 0010. */
  path: string,
  /** How long the link lives: the devices' 7 days unless a caller (the event page's read) asks for less. */
  expirySeconds: number = PRESIGN_EXPIRY_SECONDS,
): Promise<string> {
  const url = `${config.s3Scheme}://${config.s3Host}/${config.zone}/${storageKey(path)}` +
    `?X-Amz-Expires=${expirySeconds}`;
  const signed = await aws.sign(url, { method: "GET", aws: { signQuery: true } });
  return signed.url;
}

/**
 * The device-facing origin this backend is reached at (decision record `changes/incremental-union`, D1),
 * derived from the deployment's domain exactly as every client derives its upload base
 * (`scripts/resolve-deployment.py`'s `upload_scheme`): `http` for a loopback literal, `https` otherwise.
 * From configuration, never from the request: behind the pull zone the request's own host is not a
 * promise, and a redirect URL is handed to builds that keep it for days.
 */
export function deviceOrigin(config: Config): string {
  const host = config.linkDomain.split(":")[0];
  const loopback = host === "localhost" || host === "::1" || /^127\.\d+\.\d+\.\d+$/.test(host);
  return `${loopback ? "http" : "https"}://${config.linkDomain}`;
}

/**
 * The stable address of one resource of an event's union (decision record `changes/incremental-union`,
 * D1): the upload path behind the event, each identity segment percent-encoded. It answers `302` to a
 * freshly presigned URL. Clients build the same path themselves; this one is what an old build's `url`
 * field carries.
 */
export function downloadPath(
  eventId: string,
  deviceId: string,
  assetId: string,
  role: string,
): string {
  const seg = encodeURIComponent;
  return `/events/${seg(eventId)}/files/devices/${seg(deviceId)}/${seg(assetId)}/${seg(role)}`;
}

// ── THE EVENT-LIMITS GATE (capability `event-lifetime`) ───────────────────────────────────────────
//
// Every event-scoped route resolves its event through `gateEvent` below: one row read. The lifecycle
// is BINARY — an event exists, or the sweep has deleted it. `endsAt` is NOT
// consulted: it bounds only which captures may be UPLOADED, and closes nothing. In particular JOINING
// IS NEVER CLOSED BY TIME, because a guest who scans days late still holds in-window captures that
// belong in the event. There is no on-touch reap: deleting is the nightly sweep's alone
// (capability `event-lifetime`), including for an event already past its derived delete-by.

/**
 * Resolve an event for a route: ONE row read (`docs/architecture.md`). An event exists exactly when its
 * row does, so `absent` now means precisely "never created, or the sweep deleted it" — the INCOMPLETE
 * case the marker era had to carry is unstateable, because `startsAt`, `endsAt`, `capacity` and
 * `lifetimeSeconds` are `NOT NULL` columns.
 *
 * Returns the row, or the response the route answers with: `404 event not found` when absent, and on a
 * store failure `502` (logged as `<route>: event read failed for <id>`), so the route never mistakes a
 * transient fault for absence. That distinction is load-bearing beyond this file: a `404` here is a
 * SEALED deletion, and `manage-membership`'s two-witness teardown acts on it.
 */
export async function gateEvent(
  db: Db,
  c: Context,
  eventId: string,
  route: string,
): Promise<EventRow | Response> {
  try {
    return await readEvent(db, eventId) ?? c.text("event not found", 404);
  } catch (e) {
    return upstream502(c, `${route}: event read failed for ${eventId}`, e);
  }
}

/**
 * The WIRE shape of an event (capabilities `docs/architecture.md`, `event-lifetime`): the row's public
 * fields with the stamped `lifetimeSeconds` replaced by the DERIVED `deletesAt`, in the canonical
 * cutoff shape.
 *
 * Serving the derived instant — rather than the duration and the anchor for a client to combine —
 * keeps the anchor policy in ONE place and means no client ever holds a copy of the lifetime constant.
 * A duplicated constant would let a join gate confidently promise a date the backend will not honour,
 * and the drift would be silent.
 */
export function publicEvent(event: EventRow) {
  // `zone` stays off the wire too: only the event page reads it (capability `event-site`), and no app needs it.
  // `keyId` is on the wire only for an ENCRYPTED event, so a plain event's body is byte-identical to what
  // every installed build has always parsed.
  const { lifetimeSeconds: _stamped, lastLandedAt: _landed, zone: _zone, keyId, ...wire } = event;
  return {
    ...wire,
    ...(keyId ? { keyId } : {}),
    closedAt: event.closedAt ?? null,
    completedAt: event.completedAt ?? null,
    deletesAt: canonicalFromMs(deleteByMs(event)),
  };
}

/** The refusal every write to a closed (or completed) event answers (capability `event-lifetime`). */
export function closedRefusal(c: Context) {
  return c.json({ error: "closed" }, 410);
}

/**
 * What an enrollment that did not admit the device answers — or `null` when it did. The zero-row outcome
 * has TWO causes and they are told apart rather than collapsed (capability `database`): at capacity is a
 * `409` the user can act on, absent is a `404` that means something else entirely. A failed enrollment
 * stays the `502` it already is.
 */
export function enrollRefusal(c: Context, outcome: EnrollOutcome | Response): Response | null {
  if (outcome instanceof Response) return outcome;
  if (outcome === "no-such-event") return c.text("event not found", 404);
  if (outcome === "closed") return closedRefusal(c);
  if (outcome === "full") return c.text("event full", 409);
  return null;
}

/**
 * The app version this request declared, as a device row keeps it — or `null` off v2 (only v2 runs the
 * version gate that parses it) or when it is not {@link recordableVersion}.
 */
export function declaredAppVersion(c: Context): string | null {
  if (splitVersion(new URL(c.req.url).pathname).version !== 2) return null;
  return recordableVersion(c.req.header(APP_VERSION_HEADER));
}

/**
 * Keep the app version this request declared on `deviceId`'s row (capability `app-update-required`).
 *
 * Best-effort: the record of a version never costs the request it rode in on. Called by the join, the
 * manifest publish and a union read that carries a verified token — never by every gated route, because
 * each statement is an Edge subrequest and the byte route's fan-out already spends most of the 50
 * (`docs/deployment.md`).
 */
export async function noteAppVersion(
  c: Context,
  db: Db,
  deviceId: string,
  what: string,
): Promise<void> {
  const declared = declaredAppVersion(c);
  if (declared === null) return;
  try {
    await recordAppVersion(db, deviceId, declared);
  } catch (e) {
    console.error(
      `${what}: could not record app version ${declared} for ${deviceId} (best-effort): ${e}`,
    );
  }
}

// The fan-out's own bound. Generous next to the work (one push per other member, at most the event's
// capacity, sent in parallel over one HTTP/2 connection) but well inside the device's 12-second budget for
// the request that carries it — so a stalled APNs socket costs a notification, never the write. Each push
// is also a subrequest, and Edge allows 50 per request: the capacity is bounded by that, not by this
// (`docs/deployment.md`, "Edge Scripting limits worth knowing").
const FANOUT_TIMEOUT_MS = 4000;

/**
 * The fan-out: wake the event's other PRESENT members (`sharing` or `settled`) so they read the union — after a
 * publish or a byte that made something fetchable (`gain`), and after the publish or leave that closed the event
 * (`close`, which carries no union position).
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
export async function notifyMembers(
  { db, pushSender }: Pick<RouteDeps, "db" | "pushSender">,
  eventId: string,
  publisherId: string,
  announce: "gain" | "close",
): Promise<void> {
  try {
    const tokens = await pushTokensForEvent(db, eventId, publisherId);
    if (tokens.length === 0) return;
    // A wake for a gain names the union position it announces (decision record
    // `changes/incremental-union`, D6), read AFTER the commit: the event's last gain, at or past the one
    // this caller logged. A failed read sends the wake without one, which the device reads as before.
    let seq: number | undefined;
    if (announce === "gain") {
      try {
        seq = await unionPosition(db, eventId);
      } catch (e) {
        console.error(`v2 notify: position read failed for ${eventId}, waking without one: ${e}`);
      }
    }
    const outcomes = await Promise.race([
      pushSender.sendSilent(tokens, eventId, seq),
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
    console.error(`v2 notify: fan-out failed for ${eventId} (best-effort, the write stands): ${e}`);
  }
}
