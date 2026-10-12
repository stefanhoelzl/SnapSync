// bunny's S3-compatible MULTIPART upload, the subset the resumable byte route and the sweep use
// (`docs/architecture.md`, "Resumable uploads"). Every call is SIGNED with the zone's S3 credential and SENT
// through the injected `fetch`, so tests and the local rig answer it exactly as they answer the native
// Storage API — nothing here reaches the network on its own.
//
// Measured against the live zone (2026-10-09/12): create, UploadPart, ListParts (with sizes), re-sending a part
// (it replaces the stored one), Complete, Abort and ListMultipartUploads all work; every part but the last must
// be at least 5 MiB (`EntityTooSmall`); Complete takes ~2.3 s for 10 or 40 parts. ⚠️ A recursive directory
// DELETE on the native API does NOT remove an open multipart upload under it, and completing one afterwards
// re-creates its object — so whoever deletes a folder aborts the uploads under it first ([abortUploadsUnder]).
// ListMultipartUploads also lists uploads that have already completed; aborting one answers `404`, which is
// treated as done.

import type { AwsClient } from "aws4fetch";
import type { Config } from "./config.ts";
import { type FetchLike, storageKey } from "./storage.ts";

/** S3's floor for every part but the last. */
export const MIN_PART_SIZE = 5 * 1024 * 1024;

/** The number of parts a large upload is cut into at most — what bounds its subrequests. */
export const TARGET_PARTS = 40;

/**
 * The part size for an upload of `total` bytes: S3's 5 MiB floor, growing only so that a large file stays at
 * about [TARGET_PARTS] parts — each part is a subrequest, and each byte of a part sits in edge memory while it
 * fills. Rounded up to whole MiB. An unknown total gets the floor.
 */
export function partSizeFor(total: number | null): number {
  if (total === null || total <= MIN_PART_SIZE * TARGET_PARTS) return MIN_PART_SIZE;
  const mib = 1024 * 1024;
  return Math.ceil(total / TARGET_PARTS / mib) * mib;
}

/** One stored part, as ListParts reports it. */
export type StoredPart = { partNumber: number; size: number; etag: string };

/** An open multipart upload, as ListMultipartUploads reports it. */
export type OpenUpload = { key: string; uploadId: string };

/** The multipart calls, bound to one deployment's zone, signer and fetch. */
export type S3 = ReturnType<typeof s3Client>;

export function s3Client(fetchImpl: FetchLike, config: Config, aws: AwsClient) {
  const base = `${config.s3Scheme}://${config.s3Host}/${config.zone}`;
  const objectUrl = (path: string, query = "") =>
    `${base}/${storageKey(path)}${query ? `?${query}` : ""}`;
  const uploadQuery = (uploadId: string) => `uploadId=${encodeURIComponent(uploadId)}`;

  async function send(
    what: string,
    url: string,
    init: { method: string; body?: BodyInit; ok?: number[] },
  ): Promise<Response> {
    const signed = await aws.sign(url, { method: init.method, body: init.body });
    const res = await fetchImpl(signed.url, {
      method: init.method,
      headers: signed.headers,
      body: init.body,
    });
    if (!res.ok && !(init.ok ?? []).includes(res.status)) {
      const detail = (await res.text().catch(() => "")).slice(0, 200).replace(/\s+/g, " ");
      throw new Error(`S3 ${what} returned ${res.status}: ${detail}`);
    }
    return res;
  }

  return {
    /** Open a multipart upload for the object at `path`; answers its upload id. */
    async create(path: string, contentType: string): Promise<string> {
      const signed = await aws.sign(objectUrl(path, "uploads"), {
        method: "POST",
        headers: { "Content-Type": contentType },
      });
      const res = await fetchImpl(signed.url, { method: "POST", headers: signed.headers });
      const xml = await res.text();
      const uploadId = res.ok ? tag(xml, "UploadId") : null;
      if (uploadId === null) {
        throw new Error(`S3 create returned ${res.status}: ${xml.slice(0, 200)}`);
      }
      return uploadId;
    },

    /** Store part `partNumber` (1-based); answers its ETag. A re-sent part replaces the stored one. */
    async uploadPart(
      path: string,
      uploadId: string,
      partNumber: number,
      bytes: Uint8Array<ArrayBuffer>,
    ): Promise<string> {
      const res = await send(
        `part ${partNumber}`,
        objectUrl(path, `partNumber=${partNumber}&${uploadQuery(uploadId)}`),
        { method: "PUT", body: bytes },
      );
      await res.body?.cancel();
      return res.headers.get("etag") ?? "";
    },

    /** The stored parts, ascending — or `null` when the upload is gone (completed or aborted). */
    async listParts(path: string, uploadId: string): Promise<StoredPart[] | null> {
      const res = await send("list parts", objectUrl(path, uploadQuery(uploadId)), {
        method: "GET",
        ok: [404],
      });
      if (res.status === 404) {
        await res.body?.cancel();
        return null;
      }
      const xml = await res.text();
      return blocks(xml, "Part").map((p) => ({
        partNumber: Number(tag(p, "PartNumber")),
        size: Number(tag(p, "Size")),
        etag: tag(p, "ETag") ?? "",
      })).sort((a, b) => a.partNumber - b.partNumber);
    },

    /** Join the parts into the object at `path`. */
    async complete(path: string, uploadId: string, parts: StoredPart[]): Promise<void> {
      const body = `<CompleteMultipartUpload>${
        parts.map((p) =>
          `<Part><PartNumber>${p.partNumber}</PartNumber><ETag>${escapeXml(p.etag)}</ETag></Part>`
        ).join("")
      }</CompleteMultipartUpload>`;
      const res = await send("complete", objectUrl(path, uploadQuery(uploadId)), {
        method: "POST",
        body,
      });
      // S3 can answer 200 with an <Error> body when a complete fails late; bunny has not been seen to, but
      // a completion read as success would record bytes that are not there.
      const xml = await res.text();
      if (xml.includes("<Error>")) throw new Error(`S3 complete failed: ${xml.slice(0, 200)}`);
    },

    /** Drop an upload and its parts. Already gone (`404`) is done. */
    async abort(path: string, uploadId: string): Promise<void> {
      const res = await send("abort", objectUrl(path, uploadQuery(uploadId)), {
        method: "DELETE",
        ok: [404],
      });
      await res.body?.cancel();
    },

    /** Every upload ListMultipartUploads reports under `prefix` (an unencoded storage path prefix). */
    async uploadsUnder(prefix: string): Promise<OpenUpload[]> {
      const res = await send(
        "list uploads",
        `${base}?uploads&prefix=${encodeURIComponent(prefix)}`,
        { method: "GET" },
      );
      const xml = await res.text();
      return blocks(xml, "Upload").flatMap((u) => {
        const key = tag(u, "Key");
        const uploadId = tag(u, "UploadId");
        return key !== null && uploadId !== null ? [{ key: unescapeXml(key), uploadId }] : [];
      });
    },
  };
}

/**
 * Abort every multipart upload under `prefix` — what a folder delete must do first, since the delete leaves
 * them behind (measured). Answers how many it aborted.
 */
export async function abortUploadsUnder(s3: S3, prefix: string): Promise<number> {
  const open = await s3.uploadsUnder(prefix);
  for (const u of open) await s3.abort(u.key, u.uploadId);
  return open.length;
}

function tag(xml: string, name: string): string | null {
  const m = xml.match(new RegExp(`<${name}>([^<]*)</${name}>`));
  return m ? unescapeXml(m[1]) : null;
}

function blocks(xml: string, name: string): string[] {
  return [...xml.matchAll(new RegExp(`<${name}>([\\s\\S]*?)</${name}>`, "g"))].map((m) => m[1]);
}

function escapeXml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(
    /"/g,
    "&quot;",
  );
}

function unescapeXml(s: string): string {
  return s.replace(/&quot;/g, '"').replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(
    /&amp;/g,
    "&",
  );
}
