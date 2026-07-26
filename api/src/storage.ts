// Storage primitives and on-wire shapes for the one bunny Storage zone (capability `backend-deployment`).
//
// Extracted from app.ts so BOTH the Edge Script (app.ts) AND the out-of-edge nightly sweep
// (capability `scheduled-cleanup`, which runs from GitHub Actions and cannot use the 50-subrequest-capped
// edge) import the SAME key builders, storage calls, and record types — the storage layout can then never
// drift between the two. Everything here is parameterized by `(fetch, config)`; nothing imports Hono.

import type { Config } from "./config.ts";

export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

/**
 * Which App Attest environment attested a device — persisted in {@link AttestRecord}. Defined here (with
 * the storage shapes) rather than in `attest.ts` so this module carries NO dependency on the attest
 * verifier (and its `@peculiar/x509` graph); the verifier imports this type. Keeping `storage.ts` free of
 * heavy deps is what lets storage-only tooling (the `site/` mirror-deploy) reuse its primitives.
 */
export type AttestEnvironment = "production" | "development";

// The event registry's marker prefix. Because an eventId is a UUID, the marker
// `events/<id>/metadata.json` is disjoint from any device manifest `events/<id>/devices/<deviceId>.json`
// and from the byte store `files/devices/<deviceId>/…`.
export const MARKER_PREFIX = "events";

/** Storage key of an event's marker object: `events/<eventId>/metadata.json`. */
export function markerKey(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/metadata.json`;
}

/**
 * An event's own directory to DELETE: `events/<eventId>/` (trailing slash — that is what makes bunny treat
 * it as a directory, and a directory DELETE is RECURSIVE; see {@link deleteObject}).
 *
 * Exists for exactly one caller: the nightly sweep's TOMBSTONE reclamation (capability
 * `scheduled-cleanup`). Bunny keeps a directory after its last object is removed, so every event the sweep
 * deletes leaves this husk behind — and the sweep would otherwise re-classify it stale and "delete" it
 * again every night, forever. Only ever passed a directory already established to hold no marker and no
 * manifest, so the recursion has nothing to recurse over.
 */
export function eventDir(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/`;
}

/** Storage key of a device's per-event manifest: `events/<eventId>/devices/<deviceId>.json`. */
export function deviceManifestKey(eventId: string, deviceId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/devices/${
    encodeURIComponent(deviceId)
  }.json`;
}

/** The per-event device-manifest directory to LIST: `events/<eventId>/devices/`. */
export function deviceManifestDir(eventId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/devices/`;
}

/**
 * Storage key of a device's **departed** manifest: `events/<eventId>/devices/<deviceId>.left.json`.
 * Leaving renames the active `<deviceId>.json` to this sibling (see the leave route); the union still
 * serves a departed device's photos, but notify skips it.
 */
export function deviceLeftManifestKey(eventId: string, deviceId: string): string {
  return `${MARKER_PREFIX}/${encodeURIComponent(eventId)}/devices/${
    encodeURIComponent(deviceId)
  }.left.json`;
}

/** Storage key of a stored resource byte object: `files/devices/<deviceId>/<filename>`. */
export function byteKey(deviceId: string, filename: string): string {
  return `files/devices/${encodeURIComponent(deviceId)}/${encodeURIComponent(filename)}`;
}

/** The device byte-store directory to LIST: `files/devices/<deviceId>/`. */
export function deviceDir(deviceId: string): string {
  return `files/devices/${encodeURIComponent(deviceId)}/`;
}

/** Storage key of a device's config document (holds the push token): `devices/<deviceId>.json`. */
export function deviceConfigKey(deviceId: string): string {
  return `devices/${encodeURIComponent(deviceId)}.json`;
}

/**
 * Storage key of a device's attestation record: `devices/<deviceId>.attest.json` (capability
 * `device-attestation`). Holds the attested public key, written ONCE at attestation and read ONLY when
 * renewing — never on a gated request, so no route pays a storage read to authenticate. A flat sibling of
 * `devices/<deviceId>.json`, and disjoint from every other namespace.
 */
export function deviceAttestKey(deviceId: string): string {
  return `devices/${encodeURIComponent(deviceId)}.attest.json`;
}

/** A device's attestation record: the attested public key, base64, plus which environment attested it. */
export type AttestRecord = {
  publicKey: string;
  environment: AttestEnvironment;
  attestedAt: string;
};

/**
 * The event marker's contents — the registry record written on create (capability `event-creation`).
 * `createdAt` (server wall-clock, ms) and `startsAt` (the host's canonical statement of when the event
 * began) are distinct facts.
 *
 * `endsAt`, `capacity`, and `lifetimeSeconds` are the event's LIMITS (capability `event-limits`), all
 * resolved at mint. `endsAt` bounds ONLY which captures may be uploaded — it is not a lifetime and no
 * lifecycle check reads it. `lifetimeSeconds` is a DURATION, never an absolute delete-by: stamping the
 * duration keeps the per-event value immutable against a later config change while leaving the anchor it
 * is measured from (`max(createdAt, startsAt)`) in `lifecycle.ts`, so the anchor policy can be corrected
 * without rewriting a single stored marker.
 *
 * Write-once — no route rewrites a marker; the lifecycle is recomputed from these fields on every read
 * (see `classifyEvent` / `deleteByMs`).
 */
export type EventMarker = {
  eventId: string;
  name: string;
  createdAt: string;
  startsAt: string;
  endsAt: string;
  capacity: number;
  lifetimeSeconds: number;
};

/**
 * A marker as it may sit in storage — one written before `startsAt` or the limit fields existed lacks
 * them. There is no read-time synthesis: a marker missing `startsAt`, `endsAt`, or `capacity` is `gone`
 * (see `classifyEvent`). A marker missing only `lifetimeSeconds` is still served; its delete-by falls
 * back to the configured lifetime constant (see `deleteByMs`), so there is one lifecycle path rather
 * than a second rule kept alive for legacy markers.
 */
export type StoredEventMarker =
  & Omit<EventMarker, "startsAt" | "endsAt" | "capacity" | "lifetimeSeconds">
  & {
    startsAt?: string;
    endsAt?: string;
    capacity?: number;
    lifetimeSeconds?: number;
  };

// A single entry from bunny's native Storage "List Files" response. We read only these fields;
// everything else (Guid, ServerId, …) is ignored. `LastChanged` is the object's server-set
// last-modified time — the last-write-wins tiebreak between a device's active/departed manifests
// (see `resolveMembership`) and the upload-time floor the asset sweep compares against (capability
// `scheduled-cleanup`); it is a wall-clock string minted by the storage zone, comparable across
// sibling objects without any client clock.
export type BunnyEntry = {
  ObjectName: string;
  Length: number;
  IsDirectory: boolean;
  LastChanged: string;
};

// The on-storage device manifest (`device-manifest`), after the `key`/`filename` rename. A resource's
// `key` is its storage object name (`files/devices/<deviceId>/<key>`, the fetch handle); `filename` is
// the human capture name.
export type ManifestResource = {
  role: string;
  contentType: string;
  key: string;
  filename: string;
};
export type ManifestAsset = {
  assetId: string;
  creationDate: string;
  resources: ManifestResource[];
};
export type DeviceManifest = {
  deviceId: string;
  assets: ManifestAsset[];
};

// The stored object name is `encodeURIComponent(filename)` (see the upload handler), so decode it
// back to the filename the client uploaded. Malformed escapes (never produced by our own encoder)
// fall back to the raw name rather than throw.
export function decodeObjectName(objectName: string): string {
  try {
    return decodeURIComponent(objectName);
  } catch {
    return objectName;
  }
}

/**
 * List one bunny native Storage directory (trailing slash required). Returns the parsed entries — an EMPTY
 * ARRAY for a directory with no children — and THROWS on any non-OK status other than `404`, plus network
 * errors and aborts.
 *
 * MEASURED 2026-07-26 against the live zone: a DIRECTORY listing returns `200 []` both for a path that is
 * empty and for one that NEVER EXISTED — bunny `404`s neither. A **file** GET, by contrast, DOES `404`,
 * which is why {@link readMarker}'s absent-marker branch is live and load-bearing while the `404` branch
 * here has never once fired. Do not conflate the two: the previous version of this comment claimed bunny
 * `404`s an empty directory, and that claim was wrong in both halves.
 *
 * The `404` branch is therefore kept as tolerance, not as a signal, and maps to `[]` rather than throwing —
 * `bunny-list-endpoint` REQUIRES an absent or empty directory to read as "no contributors"/"no bytes" and a
 * partition `404` to not be treated as a failure. Nothing distinguishes absent from empty anywhere in this
 * backend, and nothing may start to without re-measuring.
 *
 * EXPIRY TRIGGER: re-verify if bunny changes the Edge Storage listing contract, or if any caller ever needs
 * to tell an absent directory from an empty one.
 */
export async function listDir(
  fetchImpl: FetchLike,
  config: Config,
  dirPath: string,
): Promise<BunnyEntry[]> {
  const url = `https://${config.host}/${config.zone}/${dirPath}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (res.status === 404) return []; // tolerated, never observed — see above
  if (!res.ok) throw new Error(`bunny LIST returned ${res.status} for ${dirPath}`);
  return await res.json() as BunnyEntry[];
}

/**
 * Read an event's marker RAW. Returns the stored marker when present (`200`), `null` when absent (`404`),
 * and THROWS on any other status, network error, or abort — so the caller never mistakes a transient
 * read failure for "event absent". Bunny's Edge Storage API has no `HEAD`, so existence is a small `GET`.
 */
export async function readMarker(
  fetchImpl: FetchLike,
  config: Config,
  eventId: string,
): Promise<StoredEventMarker | null> {
  const url = `https://${config.host}/${config.zone}/${markerKey(eventId)}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (res.status === 404) return null; // event was never created
  if (!res.ok) throw new Error(`bunny marker GET returned ${res.status} for ${eventId}`);
  return await res.json() as StoredEventMarker;
}

/**
 * Read one device-manifest object by its full key (the LWW-winning `<id>.json` or `<id>.left.json`).
 * THROWS on any non-OK status, network error, abort, OR a JSON parse failure.
 */
export async function readManifestObject(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
): Promise<DeviceManifest> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (!res.ok) {
    throw new Error(`bunny device-manifest GET returned ${res.status} for ${key}`);
  }
  return await res.json() as DeviceManifest;
}

/**
 * Read a storage object's raw body text, or `null` when absent (`404`). THROWS on any other non-OK
 * status. Used to copy an active manifest into its departed sibling.
 */
export async function readObjectText(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
): Promise<string | null> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey },
  });
  if (res.status === 404) return null;
  if (!res.ok) throw new Error(`bunny GET returned ${res.status} for ${key}`);
  return await res.text();
}

/**
 * PUT a storage object's body (minting a fresh last-modified time). THROWS on any non-OK status. `body` is
 * `BodyInit` so callers may PUT text (JSON manifests) OR bytes (the `site/` mirror-deploy's built assets).
 */
export async function putObject(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
  body: BodyInit,
  contentType: string,
): Promise<void> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "PUT",
    headers: { AccessKey: config.accessKey, "Content-Type": contentType },
    body,
  });
  if (!res.ok) throw new Error(`bunny PUT returned ${res.status} for ${key}`);
  await res.body?.cancel();
}

/**
 * DELETE a storage object, idempotently: a `404` (already gone) is success. THROWS on any other non-OK
 * status. Deleting an absent object is a no-op, which keeps deletion cascades safe to re-run.
 *
 * ⚠️ A key ending in `/` names a DIRECTORY, and bunny deletes a directory **RECURSIVELY** — "in case the
 * object is a directory all the data in it will be recursively deleted as well"
 * (<https://docs.bunny.net/api-reference/storage/manage-files/delete-file>). One call can therefore destroy
 * an arbitrary subtree, and this function cannot tell that from deleting one object. Pass a directory key
 * ONLY when its emptiness has already been established — {@link eventDir} is the one such caller — and
 * never a truncated or computed prefix.
 */
export async function deleteObject(
  fetchImpl: FetchLike,
  config: Config,
  key: string,
): Promise<void> {
  const url = `https://${config.host}/${config.zone}/${key}`;
  const res = await fetchImpl(url, {
    method: "DELETE",
    headers: { AccessKey: config.accessKey },
  });
  if (!res.ok && res.status !== 404) {
    throw new Error(`bunny DELETE returned ${res.status} for ${key}`);
  }
  await res.body?.cancel();
}
