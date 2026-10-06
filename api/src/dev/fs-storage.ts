// DEV-ONLY. A filesystem stand-in for bunny's native Storage API (capability-free dev infrastructure —
// non-gating, no spec, same posture as `:test:harness-driver`).
//
// WHY THIS EXISTS. `api/` has one deploy target and one storage zone, and `src/config.ts` names that zone
// as a source constant — so a plain local `deno run src/main.ts` writes into `snap-sync-dev`, the zone that
// holds real TestFlight/App-Store users' photos. `CLAUDE.md` is explicit that there is deliberately no
// whole-zone reset tool for exactly that reason. The consequence was that a backend change could only be
// exercised by unit tests whose bunny responses we wrote ourselves, and then deployed.
//
// WHY A FETCH SHIM AND NOT A STORAGE PORT. `createApp({ config, fetch })` already takes its upstream fetch
// as a dependency and every storage call in `storage.ts` goes through it — it is the seam every test in
// `test/` already injects. Turning `storage.ts` into an interface with bunny and fs implementations would
// rewrite shipped storage code (plus `sweep.ts` and the `site/` deploy, which share those primitives) for
// a dev-only need, and every line touched is a line that can break production. This module adds no risk to
// the deployed path because the deployed path never imports it: `deno bundle src/main.ts` roots the Edge
// Script bundle at `main.ts`, which reaches nothing under `src/dev/`.
//
// WHAT IT DOES NOT PROVE. This is a SECOND implementation of the same assumptions about bunny that the
// mocks in `test/` encode — it pins the URLs we build and the responses we believe bunny returns. It
// proves internal consistency, so a failure in the local loop means YOUR change broke rather than the rig.
// It does NOT prove either matches bunny; nothing in this repo ever has. Deploy still decides.
//
// LAYOUT. Storage keys map 1:1 onto directories and files, so `ls`/`find` on the store is the verification
// oracle a bunny dashboard used to be:
//
//   <root>/objects/<key>   the bytes         (e.g. objects/files/<eventId>/<sha256>)
//   <root>/types/<key>     its Content-Type  (a separate tree, so a LIST never observes it)
//
// The type sidecar lives in its own tree rather than beside the object precisely because `listDir` walks
// real directories: a sibling `.type` file would surface as a phantom `BunnyEntry` in every listing.

import { dirname, join, normalize, resolve, SEPARATOR } from "@std/path";
import type { Config } from "../config.ts";
import type { BunnyEntry, FetchLike } from "../storage.ts";

/**
 * Build a {@link FetchLike} that answers bunny's native Storage API from a directory tree under `root`.
 *
 * The returned fetch handles exactly the four operations `storage.ts` performs — object `PUT`, object
 * `GET`, object `DELETE`, and directory `GET` (a key with a trailing slash) — and **throws** on anything
 * else, including any URL outside `https://<config.host>/<config.zone>/`. That guard is the point: an
 * upstream call this shim does not model fails loudly instead of silently reaching the real internet (and,
 * with a real `AccessKey`, the real zone).
 */
export function fsFetch(config: Config, root: string): FetchLike {
  const objectsRoot = join(root, "objects");
  const typesRoot = join(root, "types");
  const zonePrefix = `/${config.zone}/`;
  const urlPrefix = `https://${config.host}${zonePrefix}`;

  return async (url: string, init: RequestInit): Promise<Response> => {
    if (!url.startsWith(urlPrefix)) {
      // Loud on purpose. A silent pass-through here would reach the real bunny zone.
      throw new Error(`fs-storage: refusing an upstream call outside ${urlPrefix}: ${url}`);
    }
    // `pathname` preserves percent-encoding, and the stored object name IS the encoded form (the upload
    // route builds the key with `encodeURIComponent`, and `decodeObjectName` decodes it back on listing).
    // So the raw segment is exactly the on-disk name, and the round trip stays byte-exact.
    const key = new URL(url).pathname.slice(zonePrefix.length);
    const method = (init.method ?? "GET").toUpperCase();

    if (method === "PUT") return await putObject(objectsRoot, typesRoot, key, init);
    if (method === "DELETE") return await deleteObject(objectsRoot, typesRoot, key);
    if (method === "GET" || method === "HEAD") {
      return key.endsWith("/")
        ? await listDirectory(objectsRoot, key)
        : await readObject(objectsRoot, typesRoot, key, method);
    }
    throw new Error(`fs-storage: unmodelled method ${method} for ${key}`);
  };
}

/** Store an object's bytes and remember its Content-Type. Bunny answers a confirmed store with `201`. */
async function putObject(
  objectsRoot: string,
  typesRoot: string,
  key: string,
  init: RequestInit,
): Promise<Response> {
  const path = safeJoin(objectsRoot, key);
  await Deno.mkdir(dirname(path), { recursive: true });
  // `body` may be a ReadableStream (the byte-upload route streams the request through untouched), a
  // string (JSON markers/manifests), or bytes (the site deploy). `Response` normalizes all three.
  const bytes = new Uint8Array(await new Response(init.body ?? null).arrayBuffer());
  await Deno.writeFile(path, bytes);

  const contentType = new Headers(init.headers).get("content-type");
  if (contentType) {
    const typePath = safeJoin(typesRoot, key);
    await Deno.mkdir(dirname(typePath), { recursive: true });
    await Deno.writeTextFile(typePath, contentType);
  }
  return new Response(null, { status: 201 });
}

/**
 * Delete an object. An absent object answers `404` — which is what bunny does, and what
 * `storage.ts`'s `deleteObject` deliberately treats as success so deletion cascades stay re-runnable.
 */
async function deleteObject(
  objectsRoot: string,
  typesRoot: string,
  key: string,
): Promise<Response> {
  // A key ending in `/` names a DIRECTORY, which bunny deletes RECURSIVELY — the sweep deletes an event's
  // byte folder that way (`storage.ts`'s `deleteObject`).
  const recursive = key.endsWith("/");
  try {
    await Deno.remove(safeJoin(objectsRoot, key), { recursive });
  } catch (e) {
    if (e instanceof Deno.errors.NotFound) return new Response(null, { status: 404 });
    throw e;
  }
  await Deno.remove(safeJoin(typesRoot, key), { recursive }).catch(() => {});
  return new Response(null, { status: 200 });
}

/** Read an object's bytes, or `404` when absent. */
async function readObject(
  objectsRoot: string,
  typesRoot: string,
  key: string,
  method: string,
): Promise<Response> {
  let bytes: Uint8Array<ArrayBuffer>;
  try {
    bytes = await Deno.readFile(safeJoin(objectsRoot, key));
  } catch (e) {
    // NotFound is absence; IsADirectory is a key that names a directory — neither is an object.
    if (e instanceof Deno.errors.NotFound || e instanceof Deno.errors.IsADirectory) {
      return new Response("not found", { status: 404 });
    }
    throw e;
  }
  const contentType = await Deno.readTextFile(safeJoin(typesRoot, key))
    .catch(() => "application/octet-stream");
  const headers = { "Content-Type": contentType };
  return method === "HEAD"
    ? new Response(null, { status: 200, headers })
    : new Response(bytes, { status: 200, headers });
}

/**
 * List one directory. An **absent** directory answers `404`, which `storage.ts`'s `listDir` maps to
 * `null` ("empty / unknown directory"); a directory that exists but has been emptied answers `200 []`.
 * Callers treat the two identically, and both shapes are pinned by the contract test.
 */
async function listDirectory(objectsRoot: string, key: string): Promise<Response> {
  const dir = safeJoin(objectsRoot, key);
  const entries: BunnyEntry[] = [];
  try {
    for await (const entry of Deno.readDir(dir)) {
      const stat = await Deno.stat(join(dir, entry.name));
      entries.push({
        ObjectName: entry.name,
        Length: entry.isDirectory ? 0 : stat.size,
        IsDirectory: entry.isDirectory,
        // Load-bearing, not decoration: `LastChanged` is the sweep's upload-time floor, which parses
        // it with `Date.parse`, so an ISO instant is the right shape.
        LastChanged: (stat.mtime ?? new Date(0)).toISOString(),
      });
    }
  } catch (e) {
    if (e instanceof Deno.errors.NotFound) return new Response("not found", { status: 404 });
    throw e;
  }
  return Response.json(entries);
}

/**
 * Join a storage key onto a root, refusing anything that escapes it.
 *
 * DEFENSE IN DEPTH, not the active guard — and worth being precise about, so nobody later "simplifies"
 * away the thing that IS load-bearing. A traversal cannot reach here through the fetch path by two
 * independent mechanisms: a raw `..` is normalized out by the `new URL()` parse before a key is derived
 * at all, and an encoded `%2e%2e` survives the parse but is then a literal directory name rather than a
 * parent hop. This exists for the day a caller derives a key some other way.
 */
function safeJoin(base: string, key: string): string {
  const path = normalize(join(base, key));
  const baseAbs = resolve(base);
  const pathAbs = resolve(path);
  if (pathAbs !== baseAbs && !pathAbs.startsWith(baseAbs + SEPARATOR)) {
    throw new Error(`fs-storage: key escapes the store root: ${key}`);
  }
  return path;
}

/**
 * [inner], with exactly Google's two FCM endpoints — the OAuth token exchange and this project's `messages:send` —
 * reaching the real internet, and only when the developer ran the rig WITH an FCM key (`secrets-env` injects it):
 * that is how a local run delivers a real silent wake to an emulator. Every other URL still meets [inner], which
 * refuses anything outside its storage zone; without a key nothing passes through at all.
 */
export function withFcmPassthrough(inner: FetchLike, config: Config): FetchLike {
  if (config.fcmServiceAccountKey === "" || config.fcmProjectId === "") return inner;
  const allowed = [
    "https://oauth2.googleapis.com/token",
    `https://fcm.googleapis.com/v1/projects/${config.fcmProjectId}/messages:send`,
  ];
  return (url, init) => allowed.includes(url) ? fetch(url, init) : inner(url, init);
}
