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
import { REVOCATION_LIST_URL } from "../android-attest.ts";
import type { Config } from "../config.ts";
import type { BunnyEntry, FetchLike } from "../storage.ts";

/**
 * Build a {@link FetchLike} that answers bunny's native Storage API from a directory tree under `root`.
 *
 * The returned fetch handles exactly the four operations `storage.ts` performs — object `PUT`, object
 * `GET`, object `DELETE`, and directory `GET` (a key with a trailing slash) — plus the S3 multipart subset
 * `s3.ts` performs on `<config.s3Scheme>://<config.s3Host>/<config.zone>` ({@link s3Multipart}), and **throws**
 * on anything else, including any URL outside those two. That guard is the point: an upstream call this shim
 * does not model fails loudly instead of silently reaching the real internet (and, with a real `AccessKey`,
 * the real zone).
 */
export function fsFetch(config: Config, root: string): FetchLike {
  const objectsRoot = join(root, "objects");
  const typesRoot = join(root, "types");
  const zonePrefix = `/${config.zone}/`;
  const urlPrefix = `https://${config.host}${zonePrefix}`;
  const multipart = s3Multipart(config, root);

  return async (url: string, init: RequestInit): Promise<Response> => {
    if (multipart.owns(url)) return await multipart.answer(url, init);
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
 * bunny's S3 multipart upload, the subset `s3.ts` performs, over the same object tree as the native API: an
 * open upload is a directory under `<root>/multipart/<uploadId>/` holding its parts, and Complete writes the
 * joined object where the native API reads it. Like bunny (measured), every part but the last must be at
 * least 5 MiB, a re-sent part replaces the stored one, and an object folder's recursive DELETE leaves an
 * open upload alone. Signatures are not checked.
 */
function s3Multipart(config: Config, root: string) {
  const base = `${config.s3Scheme}://${config.s3Host}/${config.zone}`;
  const uploadsRoot = join(root, "multipart");
  const xml = (body: string, status = 200) =>
    new Response(`<?xml version="1.0" encoding="UTF-8"?>${body}`, {
      status,
      headers: { "Content-Type": "application/xml" },
    });
  const noSuchUpload = () => xml("<Error><Code>NoSuchUpload</Code></Error>", 404);
  const uploadDir = (id: string) => safeJoin(uploadsRoot, id);
  const meta = async (id: string) =>
    JSON.parse(await Deno.readTextFile(join(uploadDir(id), "meta.json")).catch(() => "null")) as
      | { key: string; contentType: string }
      | null;
  const partFiles = async (id: string) => {
    const parts: { n: number; size: number; etag: string }[] = [];
    for await (const e of Deno.readDir(uploadDir(id))) {
      const m = e.name.match(/^part-(\d+)$/);
      if (!m) continue;
      const bytes = await Deno.readFile(join(uploadDir(id), e.name));
      parts.push({ n: Number(m[1]), size: bytes.byteLength, etag: await etagOf(bytes) });
    }
    return parts.sort((a, b) => a.n - b.n);
  };

  async function create(key: string, init: RequestInit) {
    const id = crypto.randomUUID();
    await Deno.mkdir(uploadDir(id), { recursive: true });
    const contentType = new Headers(init.headers).get("content-type") ?? "application/octet-stream";
    await Deno.writeTextFile(
      join(uploadDir(id), "meta.json"),
      JSON.stringify({ key, contentType }),
    );
    return xml(
      `<InitiateMultipartUploadResult><UploadId>${id}</UploadId></InitiateMultipartUploadResult>`,
    );
  }

  async function putPart(id: string, n: number, init: RequestInit) {
    if (await meta(id) === null) return noSuchUpload();
    const bytes = new Uint8Array(await new Response(init.body ?? null).arrayBuffer());
    await Deno.writeFile(join(uploadDir(id), `part-${n}`), bytes);
    return new Response(null, { status: 200, headers: { ETag: await etagOf(bytes) } });
  }

  async function listParts(id: string) {
    if (await meta(id) === null) return noSuchUpload();
    const parts = await partFiles(id);
    return xml(
      `<ListPartsResult>${
        parts.map((p) =>
          `<Part><PartNumber>${p.n}</PartNumber><Size>${p.size}</Size><ETag>${p.etag}</ETag></Part>`
        ).join("")
      }</ListPartsResult>`,
    );
  }

  async function complete(id: string, init: RequestInit) {
    const m = await meta(id);
    if (m === null) return noSuchUpload();
    const asked = [...(await new Response(init.body ?? null).text()).matchAll(
      /<PartNumber>(\d+)<\/PartNumber>/g,
    )].map((x) => Number(x[1]));
    const stored = new Map((await partFiles(id)).map((p) => [p.n, p]));
    const chunks: Uint8Array[] = [];
    for (const [i, n] of asked.entries()) {
      const part = stored.get(n);
      if (part === undefined) return xml("<Error><Code>InvalidPart</Code></Error>", 400);
      if (i < asked.length - 1 && part.size < 5 * 1024 * 1024) {
        return xml("<Error><Code>EntityTooSmall</Code></Error>", 400);
      }
      chunks.push(await Deno.readFile(join(uploadDir(id), `part-${n}`)));
    }
    const path = safeJoin(join(root, "objects"), m.key);
    await Deno.mkdir(dirname(path), { recursive: true });
    await Deno.writeFile(path, concatBytes(chunks));
    const typePath = safeJoin(join(root, "types"), m.key);
    await Deno.mkdir(dirname(typePath), { recursive: true });
    await Deno.writeTextFile(typePath, m.contentType);
    await Deno.remove(uploadDir(id), { recursive: true });
    return xml(
      `<CompleteMultipartUploadResult><Key>${m.key}</Key></CompleteMultipartUploadResult>`,
    );
  }

  async function abort(id: string) {
    if (await meta(id) === null) return noSuchUpload();
    await Deno.remove(uploadDir(id), { recursive: true });
    return new Response(null, { status: 204 });
  }

  async function listUploads(prefix: string) {
    const uploads: string[] = [];
    await Deno.mkdir(uploadsRoot, { recursive: true });
    for await (const e of Deno.readDir(uploadsRoot)) {
      const m = await meta(e.name);
      if (m !== null && decodeURIComponent(m.key).startsWith(prefix)) {
        uploads.push(
          `<Upload><Key>${decodeURIComponent(m.key)}</Key><UploadId>${e.name}</UploadId></Upload>`,
        );
      }
    }
    return xml(`<ListMultipartUploadsResult>${uploads.join("")}</ListMultipartUploadsResult>`);
  }

  return {
    owns: (url: string) => url.startsWith(`${base}/`) || url.startsWith(`${base}?`),
    async answer(url: string, init: RequestInit): Promise<Response> {
      const u = new URL(url);
      const method = (init.method ?? "GET").toUpperCase();
      const key = u.pathname.slice(`/${config.zone}/`.length);
      const id = u.searchParams.get("uploadId");
      const part = u.searchParams.get("partNumber");
      if (key === "" && u.searchParams.has("uploads") && method === "GET") {
        return await listUploads(u.searchParams.get("prefix") ?? "");
      }
      if (method === "POST" && u.searchParams.has("uploads")) return await create(key, init);
      if (method === "PUT" && id !== null && part !== null) {
        return await putPart(id, Number(part), init);
      }
      if (method === "GET" && id !== null) return await listParts(id);
      if (method === "POST" && id !== null) return await complete(id, init);
      if (method === "DELETE" && id !== null) return await abort(id);
      throw new Error(`fs-storage: unmodelled S3 call ${method} ${url}`);
    },
  };
}

async function etagOf(bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-1", bytes));
  return `"${Array.from(digest, (b) => b.toString(16).padStart(2, "0")).join("")}"`;
}

function concatBytes(chunks: Uint8Array[]): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(chunks.reduce((n, c) => n + c.byteLength, 0));
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.byteLength;
  }
  return out;
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

/**
 * The rig's attestation status list, when it trusts any attestation (`any`): an empty one, answered here. The rig
 * reaches no network but its loopback, and under that policy a chain proves nothing anyway, so nothing it holds is
 * revoked. `undefined` otherwise — the app's default, the real list — which no filesystem deployment composes today.
 */
export function localRevocations(config: Config): FetchLike | undefined {
  if (config.androidAttestationTrust !== "any") return undefined;
  return (url, init) =>
    url === REVOCATION_LIST_URL
      ? Promise.resolve(Response.json({ entries: {} }))
      : fetch(url, init);
}
