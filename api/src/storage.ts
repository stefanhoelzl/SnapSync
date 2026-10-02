// Storage primitives and on-wire shapes for the one bunny Storage zone (`docs/deployment.md`).
//
// Extracted from app.ts so BOTH the Edge Script (app.ts) AND the out-of-edge nightly sweep
// (capability `event-lifetime`, which runs from GitHub Actions and cannot use the 50-subrequest-capped
// edge) import the SAME key builders and storage calls — the byte-store layout can then never drift
// between the two. Everything here is parameterized by `(fetch, config)`; nothing imports Hono.

import type { Config } from "./config.ts";

export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

/** Storage key of a stored resource byte object: `files/devices/<deviceId>/<filename>`. */
export function byteKey(deviceId: string, filename: string): string {
  return `files/devices/${encodeURIComponent(deviceId)}/${encodeURIComponent(filename)}`;
}

/** The device byte-store directory to LIST: `files/devices/<deviceId>/`. */
export function deviceDir(deviceId: string): string {
  return `files/devices/${encodeURIComponent(deviceId)}/`;
}

// A single entry from bunny's native Storage "List Files" response. We read only these fields;
// everything else (Guid, ServerId, …) is ignored. `LastChanged` is the object's server-set
// last-modified time — the upload-time floor the asset sweep compares against (capability
// `event-lifetime`); it is a wall-clock string minted by the storage zone, comparable across
// sibling objects without any client clock.
export type BunnyEntry = {
  ObjectName: string;
  Length: number;
  IsDirectory: boolean;
  LastChanged: string;
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
 * while the `404` branch here has never once fired. Do not conflate the two: the previous version of this comment claimed bunny
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
 * Can this deployment reach its storage zone at all? `true` only on a `2xx`; `false` on any other status,
 * any network error, and any abort. Used by the health route so the deploy probe witnesses the zone
 * (`docs/deployment.md`).
 *
 * IT DELIBERATELY DOES NOT REUSE {@link listDir}, and the reason is the whole point of this function.
 * `listDir` maps `404` to `[]` — tolerance, so an absent partition reads as "no bytes" — and a zone name
 * that does not exist is exactly the fault this check exists to catch. Routed through `listDir` it would
 * come back as an empty listing and the health route would report the zone reachable. That is the
 * 2026-07 outage's other half: `BUNNY_STORAGE_ZONE` named a zone that did not exist, and everything
 * booted and probed green.
 *
 * So here a `404` IS a signal. That does not reopen `listDir`'s absent-vs-empty rule, which governs the
 * DATA paths: this asks an operational question about the zone itself, not about what a directory holds.
 * The zone root of a deployed backend is never empty — it carries `site/` and `files/` — so `404` here
 * means the address is wrong, not that there is nothing to list.
 *
 * NEVER THROWS: the health route's job is to answer, and a thrown error there would be indistinguishable
 * from the script failing to serve, which is a different diagnosis.
 */
export async function storageReachable(fetchImpl: FetchLike, config: Config): Promise<boolean> {
  try {
    const res = await fetchImpl(`https://${config.host}/${config.zone}/`, {
      method: "GET",
      headers: { AccessKey: config.accessKey, Accept: "application/json" },
    });
    await res.body?.cancel();
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * PUT a storage object's body (minting a fresh last-modified time). THROWS on any non-OK status. `body` is
 * `BodyInit` so callers may PUT text (JSON documents) OR bytes (the `site/` mirror-deploy's built assets).
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
 * ONLY when its emptiness has just been established — the sweep's directory step is the one caller, and
 * it re-lists the directory immediately before — and never a truncated or computed prefix.
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
