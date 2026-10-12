// The resumable byte route (`docs/architecture.md`, "Resumable uploads"; `routes/resumable-upload.ts`): a body
// stored as S3 multipart parts, the tail a dropped request keeps, the status read a retry asks, and the partial
// PUT that continues. Run over the dev filesystem shim, which models bunny's native API and the S3 multipart
// subset — so these tests assert what is STORED, byte for byte, not which calls were made.

import { assert, assertEquals } from "@std/assert";
import { fsFetch } from "../src/dev/fs-storage.ts";
import { eventBytePath, type FetchLike } from "../src/storage.ts";
import { s3Client } from "../src/s3.ts";
import { AwsClient } from "aws4fetch";
import { decodeHex } from "@std/encoding/hex";
import { FORMAT_VERSION } from "../src/encrypted-file.ts";
import {
  CONFIG,
  createApp,
  D,
  E,
  EVENT,
  joinEvent,
  lineSink,
  rows,
  store,
  V2,
} from "./support/harness.ts";
import { insertEvent } from "../src/db.ts";
import { runSweep } from "../src/scripts/sweep.ts";

const MiB = 1024 * 1024;
const BYTE_PATH = `/api/v2/events/${E}/files/devices/${D}/ASSET1/primary?filename=VID_0001.MP4`;
const STATUS_PATH = `/api/v2/events/${E}/files/devices/${D}/ASSET1/primary/upload`;
const DEVLIST_PATH = `/api/v2/events/${E}/files/devices/${D}`;
const PATH = await eventBytePath(E, D, "ASSET1", "primary");

/** `n` bytes no two parts share, so a part stored at the wrong place shows. */
function file(n: number): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(n);
  for (let i = 0; i < n; i++) out[i] = (i * 31 + (i >> 16)) & 0xff;
  return out;
}

async function sha256(bytes: Uint8Array<ArrayBuffer>): Promise<string> {
  const d = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
  return Array.from(d, (b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * A body that sends `bytes` and then breaks off, as a phone whose link died. Pulled chunk by chunk: an error
 * raised while chunks are still queued would discard them, and the edge would never have received them.
 */
function breaking(bytes: Uint8Array): ReadableStream<Uint8Array> {
  let at = 0;
  return new ReadableStream({
    pull(c) {
      if (at >= bytes.byteLength) {
        c.error(new Error("error reading a body from connection"));
        return;
      }
      const next = Math.min(at + 256 * 1024, bytes.byteLength);
      c.enqueue(bytes.subarray(at, next));
      at = next;
    },
  });
}

async function world(keyId: string | null = null) {
  const root = await Deno.makeTempDir();
  const db = await store();
  await insertEvent(db, { ...EVENT, keyId });
  await joinEvent(db, E, D);
  const fetchImpl: FetchLike = fsFetch(CONFIG, root);
  const lines: string[] = [];
  const app = createApp({ config: CONFIG, db, fetch: fetchImpl, logSink: lineSink(lines) });
  const request = (path: string, init: RequestInit = {}) =>
    app.request(path, { ...init, headers: { ...V2, ...(init.headers ?? {}) } });
  const s3 = s3Client(
    fetchImpl,
    CONFIG,
    new AwsClient({ accessKeyId: CONFIG.zone, secretAccessKey: CONFIG.accessKey, service: "s3" }),
  );
  const stored = async () => {
    try {
      return await Deno.readFile(`${root}/objects/${PATH}`);
    } catch {
      return null;
    }
  };
  const close = async () => {
    db.close();
    await Deno.remove(root, { recursive: true });
  };
  return { root, db, request, lines, s3, stored, close };
}

type World = Awaited<ReturnType<typeof world>>;

/** A fresh PUT of `bytes` that declares the whole `whole` file's SHA-256 and length. */
async function putFresh(w: World, whole: Uint8Array<ArrayBuffer>, body: BodyInit) {
  return await w.request(BYTE_PATH, {
    method: "PUT",
    headers: {
      "content-type": "video/mp4",
      "content-length": `${whole.byteLength}`,
      "x-snapsync-sha256": await sha256(whole),
    },
    body,
    duplex: "half",
  } as RequestInit);
}

/** A partial PUT of `whole` from `start` (RFC 9110 §14.5). */
async function putFrom(w: World, whole: Uint8Array<ArrayBuffer>, start: number, body?: BodyInit) {
  const n = whole.byteLength;
  return await w.request(BYTE_PATH, {
    method: "PUT",
    headers: {
      "content-type": "video/mp4",
      "content-range": `bytes ${start}-${n - 1}/${n}`,
      "x-snapsync-sha256": await sha256(whole),
    },
    body: body ?? whole.slice(start),
    duplex: "half",
  } as RequestInit);
}

async function status(w: World, whole: Uint8Array<ArrayBuffer>) {
  const res = await w.request(STATUS_PATH, {
    headers: { "x-snapsync-sha256": await sha256(whole) },
  });
  await res.body?.cancel();
  return { status: res.status, offset: res.headers.get("upload-offset") };
}

const landedRow = async (w: World) =>
  (await rows(
    w.db,
    `SELECT landed_at IS NOT NULL AS landed, sha256, upload_id, tail_key, claim FROM resources`,
  ))[0];

Deno.test("resumable → a large upload is stored as parts and lands whole, byte for byte", async () => {
  const w = await world();
  const whole = file(12 * MiB + 123);
  const res = await putFresh(w, whole, whole);
  assertEquals(res.status, 201);
  assertEquals(await w.stored(), whole);
  const row = await landedRow(w);
  assertEquals(row.landed, 1);
  assertEquals(row.sha256, await sha256(whole));
  assertEquals([row.upload_id, row.tail_key, row.claim], [null, null, null]);
  assertEquals(await status(w, whole), { status: 204, offset: null });
  assertEquals(await w.s3.uploadsUnder("files/"), []);
  await w.close();
});

Deno.test("resumable → a dropped upload keeps every byte that arrived, and is in no listing", async () => {
  const w = await world();
  const whole = file(12 * MiB);
  const res = await putFresh(w, whole, breaking(whole.subarray(0, 7 * MiB + 5)));
  assertEquals(res.status, 400);
  await res.body?.cancel();
  assert(w.lines.at(-1)!.includes(" aborted=true"), w.lines.at(-1));
  // One whole 5 MiB part stored, the 2 MiB past it kept as the tail: 7 MiB + 5 bytes, exactly.
  assertEquals(await status(w, whole), { status: 200, offset: `${7 * MiB + 5}` });
  assertEquals((await landedRow(w)).landed, 0);
  assertEquals(await w.stored(), null);
  assertEquals(await (await w.request(DEVLIST_PATH)).json(), []);
  await w.close();
});

Deno.test("resumable → a retry from the offset completes the file, and leaves no tail or upload behind", async () => {
  const w = await world();
  const whole = file(12 * MiB);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 7 * MiB + 5)))).body?.cancel();
  const offset = Number((await status(w, whole)).offset);
  const res = await putFrom(w, whole, offset);
  assertEquals(res.status, 201);
  assertEquals(await w.stored(), whole);
  assertEquals((await landedRow(w)).landed, 1);
  assertEquals(await w.s3.uploadsUnder("files/"), []);
  const left = [...Deno.readDirSync(`${w.root}/objects/files/${E}`)].map((e) => e.name);
  assertEquals(left, [PATH.split("/").at(-1)]);
  await w.close();
});

Deno.test("resumable → drop after drop: each retry keeps going from where the last one stopped", async () => {
  const w = await world();
  const whole = file(16 * MiB + 77);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 3 * MiB)))).body?.cancel();
  assertEquals((await status(w, whole)).offset, `${3 * MiB}`);
  await (await putFrom(w, whole, 3 * MiB, breaking(whole.subarray(3 * MiB, 9 * MiB)))).body
    ?.cancel();
  assertEquals((await status(w, whole)).offset, `${9 * MiB}`);
  await (await putFrom(w, whole, 9 * MiB, breaking(whole.subarray(9 * MiB, 9 * MiB + 1)))).body
    ?.cancel();
  assertEquals((await status(w, whole)).offset, `${9 * MiB + 1}`);
  assertEquals((await putFrom(w, whole, 9 * MiB + 1)).status, 201);
  assertEquals(await w.stored(), whole);
  await w.close();
});

Deno.test("resumable → a LOWER offset is answered by discarding the overlap", async () => {
  const w = await world();
  const whole = file(11 * MiB);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 6 * MiB)))).body?.cancel();
  assertEquals((await putFrom(w, whole, 4 * MiB)).status, 201);
  assertEquals(await w.stored(), whole);
  await w.close();
});

Deno.test("resumable → a HIGHER offset is refused 409 with where the upload stands", async () => {
  const w = await world();
  const whole = file(11 * MiB);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 6 * MiB)))).body?.cancel();
  const res = await putFrom(w, whole, 8 * MiB);
  await res.body?.cancel();
  assertEquals(res.status, 409);
  assertEquals(res.headers.get("upload-offset"), `${6 * MiB}`);
  assertEquals((await status(w, whole)).offset, `${6 * MiB}`, "nothing changed");
  await w.close();
});

Deno.test("resumable → other bytes than the session's: nothing to resume — 404, and a resume is 409 at 0", async () => {
  const w = await world();
  const whole = file(11 * MiB);
  const edited = file(11 * MiB).map((b) => b ^ 1);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 6 * MiB)))).body?.cancel();
  assertEquals((await status(w, edited)).status, 404);
  const res = await putFrom(w, edited, 6 * MiB);
  await res.body?.cancel();
  assertEquals([res.status, res.headers.get("upload-offset")], [409, "0"]);
  await w.close();
});

Deno.test("resumable → a fresh upload of other bytes replaces the session and its parts", async () => {
  const w = await world();
  const whole = file(11 * MiB);
  const edited = file(11 * MiB).map((b) => b ^ 1);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 6 * MiB)))).body?.cancel();
  assertEquals((await putFresh(w, edited, edited)).status, 201);
  assertEquals(await w.stored(), edited);
  assertEquals(await w.s3.uploadsUnder("files/"), [], "the first session's upload was aborted");
  const left = [...Deno.readDirSync(`${w.root}/objects/files/${E}`)].map((e) => e.name);
  assertEquals(left.length, 1, "its tail is gone");
  await w.close();
});

Deno.test("resumable → a drop without a declared sha256 keeps nothing: it cannot resume", async () => {
  const w = await world();
  const whole = file(12 * MiB);
  const res = await w.request(BYTE_PATH, {
    method: "PUT",
    body: breaking(whole.subarray(0, 11 * MiB)),
    duplex: "half",
  } as RequestInit);
  assertEquals(res.status, 400);
  await res.body?.cancel();
  assertEquals(await w.s3.uploadsUnder("files/"), [], "its parts were aborted");
  assertEquals(await rows(w.db, `SELECT * FROM resources`), []);
  await w.close();
});

Deno.test("resumable → a large upload without a sha256 still lands whole (older builds, PhotoKit)", async () => {
  const w = await world();
  const whole = file(12 * MiB);
  const res = await w.request(BYTE_PATH, { method: "PUT", body: whole });
  assertEquals(res.status, 201);
  assertEquals(await w.stored(), whole);
  assertEquals((await landedRow(w)).landed, 1);
  await w.close();
});

Deno.test("resumable → a request a newer one took over stops: no landing, 409", async () => {
  const w = await world();
  const whole = file(11 * MiB);
  let ctl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({ start: (c) => void (ctl = c) });
  const pending = putFresh(w, whole, body);
  ctl.enqueue(whole.subarray(0, 6 * MiB));
  // A newer request for the same bytes claims the row meanwhile.
  while ((await rows(w.db, `SELECT claim FROM resources`)).length === 0) {
    await new Promise((r) => setTimeout(r, 5));
  }
  await w.db.execute(`UPDATE resources SET claim = 'newer'`);
  ctl.enqueue(whole.subarray(6 * MiB));
  ctl.close();
  const res = await pending;
  await res.body?.cancel();
  assertEquals(res.status, 409);
  assertEquals((await landedRow(w)).landed, 0);
  assertEquals(await w.stored(), null);
  await w.close();
});

Deno.test("resumable → a member who left meanwhile: the bytes it completed do not outlive its row", async () => {
  const w = await world();
  const whole = file(11 * MiB);
  let ctl!: ReadableStreamDefaultController<Uint8Array>;
  const body = new ReadableStream<Uint8Array>({ start: (c) => void (ctl = c) });
  const pending = putFresh(w, whole, body);
  ctl.enqueue(whole.subarray(0, 6 * MiB));
  while ((await rows(w.db, `SELECT claim FROM resources`)).length === 0) {
    await new Promise((r) => setTimeout(r, 5));
  }
  ctl.enqueue(whole.subarray(6 * MiB));
  ctl.close();
  // The membership's cascade takes the row between the claim check and the record — the narrowest window;
  // here the row simply goes before the request finishes.
  await w.db.execute(`DELETE FROM resources`);
  const res = await pending;
  await res.body?.cancel();
  assertEquals(res.status, 409);
  assertEquals(await w.stored(), null);
  await w.close();
});

Deno.test("resumable → status read: gated like the upload, and it needs the sha256", async () => {
  const w = await world();
  const whole = file(1024);
  assertEquals((await status(w, whole)).status, 404, "nothing stored");
  const none = await w.request(STATUS_PATH);
  await none.body?.cancel();
  assertEquals(none.status, 400);
  await w.db.execute(`UPDATE memberships SET state = 'left'`);
  assertEquals((await status(w, whole)).status, 403);
  await w.close();
});

Deno.test("resumable → a small file with a sha256 is one write, and the status read then answers 204", async () => {
  const w = await world();
  const whole = file(4096);
  assertEquals((await putFresh(w, whole, whole)).status, 201);
  assertEquals(await w.stored(), whole);
  assertEquals(await w.s3.uploadsUnder("files/"), []);
  assertEquals(await status(w, whole), { status: 204, offset: null });
  await w.close();
});

Deno.test("resumable → a small file dropped mid-way resumes from its tail", async () => {
  const w = await world();
  const whole = file(3 * MiB);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 2 * MiB)))).body?.cancel();
  assertEquals((await status(w, whole)).offset, `${2 * MiB}`);
  assertEquals((await putFrom(w, whole, 2 * MiB)).status, 201);
  assertEquals(await w.stored(), whole);
  await w.close();
});

Deno.test("resumable → a resume after the photo already landed with these bytes changes nothing", async () => {
  const w = await world();
  const whole = file(4096);
  assertEquals((await putFresh(w, whole, whole)).status, 201);
  const res = await putFrom(w, whole, 1000);
  assertEquals(res.status, 201);
  assertEquals(await w.stored(), whole);
  await w.close();
});

Deno.test("resumable → malformed resume headers are refused 400", async () => {
  const w = await world();
  for (
    const headers of <Record<string, string>[]> [
      { "content-range": "bytes 5-9/10" }, // a range needs its file's sha256
      { "content-range": "bytes 5-8/10", "x-snapsync-sha256": "a".repeat(64) }, // not to the end
      { "x-snapsync-sha256": "not-hex" },
    ]
  ) {
    const res = await w.request(BYTE_PATH, { method: "PUT", headers, body: "x" });
    await res.body?.cancel();
    assertEquals(res.status, 400, JSON.stringify(headers));
  }
  await w.close();
});

Deno.test("resumable → an encrypted event checks the prefix at byte 0; the resume is vouched for by its session", async () => {
  const keyId = "0123456789abcdef";
  const w = await world(keyId);
  // A sealed file opens with its format version and the event's key id (`encrypted-file.ts`).
  const whole = file(9 * MiB);
  whole.set([FORMAT_VERSION, ...decodeHex(keyId)], 0);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 6 * MiB)))).body?.cancel();
  assertEquals((await status(w, whole)).offset, `${6 * MiB}`);
  assertEquals((await putFrom(w, whole, 6 * MiB)).status, 201, "no prefix in a resume's body");
  assertEquals(await w.stored(), whole);
  // A plaintext body is still refused where the prefix is read.
  const plain = file(9 * MiB);
  const refused = await putFresh(w, plain, plain);
  await refused.body?.cancel();
  assertEquals(refused.status, 422);
  await w.close();
});

Deno.test("resumable → the sweep aborts an event's open upload, which its folder delete would leave behind", async () => {
  const w = await world();
  const whole = file(12 * MiB);
  await (await putFresh(w, whole, breaking(whole.subarray(0, 7 * MiB)))).body?.cancel();
  assertEquals((await w.s3.uploadsUnder("files/")).length, 1);
  await w.db.execute(`DELETE FROM events`); // the event no longer lives
  await runSweep({
    fetch: fsFetch(CONFIG, w.root),
    config: CONFIG,
    db: w.db,
    now: () => Date.parse("2026-08-01T00:00:00Z"),
    dryRun: false,
    log: () => {},
  });
  assertEquals(await w.s3.uploadsUnder("files/"), []);
  await w.close();
});

Deno.test("resumable → landed bytes win over an open upload of the same bytes: the last byte finishes it", async () => {
  const w = await world();
  const whole = file(9 * MiB);
  assertEquals((await putFresh(w, whole, whole)).status, 201);
  // The same bytes again, broken off: a session beside the landed resource.
  await (await putFresh(w, whole, breaking(whole.subarray(0, 6 * MiB)))).body?.cancel();
  assertEquals((await status(w, whole)).status, 204, "the bytes are there");
  assertEquals(
    (await putFrom(w, whole, whole.byteLength - 1)).status,
    201,
    "and the last byte lands the retry",
  );
  assertEquals(await w.s3.uploadsUnder("files/"), [], "the moot upload went");
  assertEquals(await w.stored(), whole);
  await w.close();
});
