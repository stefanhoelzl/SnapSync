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
 * began) are distinct facts. `endsAt` and `capacity` are the event's LIMITS (capability `event-limits`),
 * server-resolved at mint (`endsAt = startsAt + duration`). Write-once — no route rewrites a marker; the
 * lifecycle is recomputed from these fields on every read (see `classifyEvent`).
 */
export type EventMarker = {
  eventId: string;
  name: string;
  createdAt: string;
  startsAt: string;
  endsAt: string;
  capacity: number;
};

/**
 * A marker as it may sit in storage — one written before `startsAt` or the limit fields existed lacks
 * them. There is no read-time synthesis: a marker missing a limit field is handled by `classifyEvent`.
 */
export type StoredEventMarker = Omit<EventMarker, "startsAt" | "endsAt" | "capacity"> & {
  startsAt?: string;
  endsAt?: string;
  capacity?: number;
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
 * List one bunny native Storage directory (trailing slash required). Returns the parsed entries, or
 * `null` when the directory has nothing / does not exist (bunny `404`). Any other non-OK status, network
 * error, or abort THROWS.
 */
export async function listDir(
  fetchImpl: FetchLike,
  config: Config,
  dirPath: string,
): Promise<BunnyEntry[] | null> {
  const url = `https://${config.host}/${config.zone}/${dirPath}`;
  const res = await fetchImpl(url, {
    method: "GET",
    headers: { AccessKey: config.accessKey, Accept: "application/json" },
  });
  if (res.status === 404) return null; // empty / unknown directory
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
