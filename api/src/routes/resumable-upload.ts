// How a byte route STORES a body, so that a dropped upload resumes where it stopped (`docs/architecture.md`,
// "Resumable uploads"; Bugsink SNAPSYNC-54). On a slow link the pull zone ends a request after 60 s without
// progress, and each retry used to start again from byte 0.
//
//   * A body is cut into parts and each FULL part is stored as one S3 multipart part as soon as it fills (at
//     most one part in flight while the next fills). A body smaller than one part is one plain write, as
//     before. The last part lands with Complete, and only then is the resource recorded.
//   * A device that declares its file's SHA-256 (`x-snapsync-sha256`) gets a SESSION on the resource's row
//     (`db.ts`, migration 0015). When its connection drops, the bytes received past the last stored part are
//     kept as one TAIL object — the script keeps running after the client is gone (measured: ≥10 s, the
//     write takes <1 s) — so nothing that reached the edge is lost.
//   * Its retry asks where it stands (`GET …/upload`: 404 nothing · 200 `Upload-Offset` · 204 landed) and
//     sends the rest as a partial PUT (RFC 9110 §14.5, `Content-Range: bytes X-(N-1)/N`). A LOWER offset is
//     answered by discarding the overlap; a HIGHER one by `409` + `Upload-Offset` — after reading the body,
//     because the pull zone turns any reply sent before the body ends into a `502` (measured).
//   * A request without the SHA-256 (an older build, the PhotoKit extension) is stored the same way but keeps
//     nothing when it drops: it cannot resume, so its parts are aborted.
//
// Two requests for one resource: the newer CLAIMS the row and every later write of the older one is
// conditional on the claim, so the older one stops; parts both send are the same bytes of the same file.

import type { HonoRequest } from "hono";
import {
  claimUpload,
  holdsClaim,
  readUploadState,
  recordTail,
  recordUploadId,
  type ResourceKey,
  startUpload,
  type UploadState,
} from "../db.ts";
import { type Fault, refuse, rethrowRefusal } from "../refusal.ts";
import { partSizeFor, type S3, type StoredPart } from "../s3.ts";
import { deleteObject, getObject, putObject, storageKey } from "../storage.ts";
import { bodyToStore, type Upload } from "./encrypted-upload.ts";
import { type RouteDeps, streamPut, upstream502 } from "./support.ts";

/** The header a resuming device declares its file's SHA-256 in: 64 lowercase hex digits. */
export const SHA256_HEADER = "x-snapsync-sha256";
/** Where an upload stands, in bytes — the resumable-upload draft's name for it (tus uses it too). */
export const UPLOAD_OFFSET_HEADER = "Upload-Offset";

/** A partial PUT's `Content-Range: bytes <start>-<end>/<total>`. */
export type ByteRange = { start: number; end: number; total: number };

/** What a byte route writes: the resource, where its bytes go, and how the request described them. */
export type Target = ResourceKey & {
  path: string;
  contentType: string;
  filename: string;
  keyId: string | null;
};

/** How the bytes landed: a session's SHA-256 and claim, which the record is conditional on, or none. */
export type Landing = {
  session: { sha256: string; claim: string } | null;
  /** Tail objects this upload used up — deleted once the record committed. */
  usedTails: string[];
};

/** Already landed with these very bytes: nothing was written, and nothing is to be recorded. */
export const ALREADY_LANDED = Symbol("already landed");

type Deps = Pick<RouteDeps, "fetchImpl" | "config" | "db"> & { s3: S3 };

/** The request's declared SHA-256, or `null`; refused `400` when present but malformed. */
export function declaredSha256(header: string | undefined): string | null {
  if (header === undefined) return null;
  return /^[0-9a-f]{64}$/.test(header) ? header : refuse(400, "invalid sha256");
}

/** The request's `Content-Range`, or `null`; refused `400` when present but not one satisfiable range. */
export function parseContentRange(header: string | undefined): ByteRange | null {
  if (header === undefined) return null;
  const m = header.match(/^bytes (\d+)-(\d+)\/(\d+)$/);
  if (!m) refuse(400, "invalid content-range");
  const [start, end, total] = [Number(m[1]), Number(m[2]), Number(m[3])];
  if (start > end || end !== total - 1) refuse(400, "invalid content-range");
  return { start, end, total };
}

/**
 * Store the request's body for `t` and answer how it landed — or {@link ALREADY_LANDED}. Refused as the byte
 * route always was (`400 upload aborted` for a client that broke off), plus `409` + `Upload-Offset` for
 * a resume from the wrong place or one a newer request took over, and `400` for a body shorter than declared.
 */
export async function storeBody(
  deps: Deps,
  route: string,
  t: Target,
  req: HonoRequest,
  upload: Upload,
): Promise<Landing | typeof ALREADY_LANDED> {
  const sha256 = declaredSha256(req.header(SHA256_HEADER));
  const range = parseContentRange(req.header("content-range"));
  const length = req.header("content-length");
  const total = range?.total ?? (length === undefined ? null : Number(length));
  if (range !== null && sha256 === null) refuse(400, "a range needs its file's sha256");
  if (sha256 !== null && total === null) refuse(400, "a resumable upload needs its length");
  if (range !== null && range.start > 0) {
    return await resume(deps, route, t, upload, sha256!, range);
  }
  return await fresh(deps, route, t, upload, sha256 === null ? null : { sha256, total: total! });
}

/** Where an upload of `sha256` stands for a status read: landed, its offset, or nothing to resume. */
export async function uploadStatus(
  deps: Deps,
  t: Target,
  sha256: string,
): Promise<"landed" | number> {
  const state = await readUploadState(deps.db, t);
  if (state === null) return 0;
  if (state.landed && state.sha256 === sha256) return "landed";
  if (state.uploadSha256 !== sha256) return 0;
  return (await storedSoFar(deps.s3, t.path, state)).offset;
}

// ── A fresh upload ─────────────────────────────────────────────────────────────────────────────────

async function fresh(
  deps: Deps,
  route: string,
  t: Target,
  upload: Upload,
  declared: { sha256: string; total: number } | null,
): Promise<Landing> {
  // The encryption check reads the first bytes BEFORE anything is kept (`encrypted-upload.ts`), so a session
  // only ever exists for a body that passed it — which is what lets a resume, whose body starts mid-file and
  // carries no prefix, be vouched for by its session.
  const body = await bodyToStore(upload, t.keyId);
  const partSize = partSizeFor(declared?.total ?? null);
  const session = declared === null ? null : await openSession(deps, t, declared, partSize);
  return await pump(deps, route, t, body, {
    partSize,
    parts: [],
    uploadId: null,
    prefix: null,
    skip: 0,
    session,
    total: declared?.total ?? null,
    usedTails: [],
  });
}

/** A new session for `declared` on the row: whatever upload the row held before is dropped first. */
async function openSession(
  deps: Deps,
  t: Target,
  declared: { sha256: string; total: number },
  partSize: number,
): Promise<Session> {
  const state = await upstream(
    `${t.path}: upload state read failed`,
    () => readUploadState(deps.db, t),
  );
  if (state !== null) await forget(deps, t, state);
  const claim = crypto.randomUUID();
  await upstream(
    `${t.path}: upload start failed`,
    () =>
      startUpload(deps.db, t, t, {
        sha256: declared.sha256,
        size: declared.total,
        partSize,
        claim,
      }),
  );
  return { sha256: declared.sha256, claim, faults: [] };
}

/** Abort the S3 upload and delete the tail `state` names — best-effort: the sweep collects what remains. */
async function forget(deps: Deps, t: Target, state: UploadState): Promise<void> {
  if (state.uploadId !== null) await deps.s3.abort(t.path, state.uploadId).catch(() => {});
  if (state.tail !== null) {
    await deleteObject(deps.fetchImpl, deps.config, storageKey(state.tail.key)).catch(() => {});
  }
}

// ── A resume ───────────────────────────────────────────────────────────────────────────────────────

async function resume(
  deps: Deps,
  route: string,
  t: Target,
  upload: Upload,
  sha256: string,
  range: ByteRange,
): Promise<Landing | typeof ALREADY_LANDED> {
  // PhotoKit re-sends from byte 0 by itself; an edge-sealed body has no offset a device could resume from.
  if (upload.fileKey !== undefined || upload.fileHead !== undefined) {
    refuse(400, "an edge-sealed upload cannot resume");
  }
  const body = upload.body;
  const state = await upstream(
    `${t.path}: upload state read failed`,
    () => readUploadState(deps.db, t),
  );
  // These very bytes already landed — the status read said so, and the device sends its last byte to finish. An
  // upload of them still open beside (a re-upload that broke off) is moot: dropped, or every retry would meet it.
  if (state?.landed && state.sha256 === sha256) {
    if (state.uploadSha256 !== null) await forget(deps, t, state);
    await drain(body);
    return ALREADY_LANDED;
  }
  if (state === null || state.uploadSha256 !== sha256) return await conflict(body, 0);
  const stored = await upstream(
    `${t.path}: parts read failed`,
    () => storedSoFar(deps.s3, t.path, state),
  );
  const prefix = stored.tail === null ? null : await readTail(deps, stored.tail.key);
  const offset = prefix === null ? stored.partsBytes : stored.offset;
  if (range.start > offset) return await conflict(body, offset);
  const claim = crypto.randomUUID();
  if (!await upstream(`${t.path}: claim failed`, () => claimUpload(deps.db, t, sha256, claim))) {
    return await conflict(body, 0);
  }
  return await pump(deps, route, t, body, {
    partSize: state.partSize!,
    parts: stored.parts,
    uploadId: stored.uploadId,
    prefix,
    skip: offset - range.start,
    session: { sha256, claim, faults: [] },
    total: range.total,
    usedTails: state.tail === null ? [] : [state.tail.key],
  });
}

/** The stored parts that count — a contiguous run from part 1 — and a tail when it starts where they end. */
export async function storedSoFar(
  s3: S3,
  path: string,
  state: UploadState,
): Promise<{
  /** The S3 upload, while it is still open; `null` when none was created or it is gone. */
  uploadId: string | null;
  parts: StoredPart[];
  partsBytes: number;
  tail: UploadState["tail"];
  offset: number;
}> {
  const listed = state.uploadId === null ? null : await s3.listParts(path, state.uploadId);
  const uploadId = listed === null ? null : state.uploadId;
  const parts: StoredPart[] = [];
  for (const p of listed ?? []) {
    if (p.partNumber !== parts.length + 1 || p.size !== state.partSize) break;
    parts.push(p);
  }
  const partsBytes = parts.length * (state.partSize ?? 0);
  const tail = state.tail !== null && state.tail.start === partsBytes ? state.tail : null;
  return { uploadId, parts, partsBytes, tail, offset: partsBytes + (tail?.bytes ?? 0) };
}

async function readTail(deps: Deps, key: string): Promise<Uint8Array<ArrayBuffer> | null> {
  return await getObject(deps.fetchImpl, deps.config, storageKey(key)).catch(() => null);
}

/** Read the body to its end — the only way to answer before it, through the pull zone — then `409`. */
async function conflict(body: ReadableStream<Uint8Array> | null, offset: number): Promise<never> {
  await drain(body);
  refuse(409, "wrong offset", {
    fields: { offset },
    headers: { [UPLOAD_OFFSET_HEADER]: `${offset}` },
  });
}

async function drain(body: ReadableStream<Uint8Array> | null): Promise<void> {
  if (body === null) return;
  try {
    for await (const _ of body) { /* discarded */ }
  } catch { /* a client that broke off while being refused changes nothing */ }
}

// ── The pump: body → parts ─────────────────────────────────────────────────────────────────────────

type Session = { sha256: string; claim: string; faults: Fault[] };

type PumpPlan = {
  partSize: number;
  /** The parts already stored, contiguous from 1. */
  parts: StoredPart[];
  uploadId: string | null;
  /** A tail to send before the body — what an earlier request received past these parts. */
  prefix: Uint8Array<ArrayBuffer> | null;
  /** How many leading body bytes are already stored — a resume from a lower offset. */
  skip: number;
  session: Session | null;
  /** The declared size of the whole file, or `null` when the request named none. */
  total: number | null;
  usedTails: string[];
};

class Superseded extends Error {}

async function pump(
  deps: Deps,
  route: string,
  t: Target,
  body: ReadableStream<Uint8Array> | null,
  plan: PumpPlan,
): Promise<Landing> {
  const sink = new PartSink(deps.s3, t, plan, async (uploadId) => {
    if (plan.session && !await recordUploadId(deps.db, t, plan.session.claim, uploadId)) {
      throw new Superseded();
    }
  });
  try {
    if (plan.prefix !== null) await sink.write(plan.prefix);
    const received = await feed(sink, body, plan.skip);
    if (received === "dropped") await dropped(deps, t, sink, plan);
    if (plan.session !== null && plan.total !== null && sink.offset() !== plan.total) {
      await keepTail(deps, t, sink, plan);
      refuse(400, "body shorter than declared", {
        fields: { offset: sink.offset() },
        headers: { [UPLOAD_OFFSET_HEADER]: `${sink.offset()}` },
      });
    }
    await finish(deps, route, t, sink, plan);
  } catch (e) {
    rethrowRefusal(e);
    if (e instanceof Superseded) refuse(409, "superseded");
    // Storing failed — ours or storage's, never the client's. What arrived is still good: a session keeps
    // it for the retry; anything else drops its parts.
    if (plan.session !== null) await keepTail(deps, t, sink, plan);
    else if (sink.uploadId !== null) await deps.s3.abort(t.path, sink.uploadId).catch(() => {});
    upstream502(`${route}: storing ${t.path} failed`, e);
  }
  return { session: plan.session, usedTails: plan.usedTails };
}

/**
 * Feed the body into `sink`, skipping its first `skip` bytes. Answers `"dropped"` when READING the body
 * failed — the client's side broke — and throws when STORING failed, which is ours or storage's.
 */
async function feed(
  sink: PartSink,
  body: ReadableStream<Uint8Array> | null,
  skip: number,
): Promise<"complete" | "dropped"> {
  if (body === null) return "complete";
  const reader = body.getReader();
  let toSkip = skip;
  for (;;) {
    let chunk: ReadableStreamReadResult<Uint8Array>;
    try {
      chunk = await reader.read();
    } catch {
      return "dropped";
    }
    if (chunk.done) return "complete";
    let value = chunk.value;
    if (toSkip > 0) {
      const cut = Math.min(toSkip, value.byteLength);
      toSkip -= cut;
      value = value.subarray(cut);
    }
    if (value.byteLength > 0) await sink.write(value);
  }
}

/** The client broke off: keep what arrived when it can resume, otherwise drop the parts; then `502`. */
async function dropped(deps: Deps, t: Target, sink: PartSink, plan: PumpPlan): Promise<never> {
  if (plan.session !== null) {
    await keepTail(deps, t, sink, plan);
  } else if (sink.uploadId !== null) {
    await deps.s3.abort(t.path, sink.uploadId).catch(() => {});
  }
  refuse(400, "upload aborted", {
    fields: { aborted: true, ...(plan.session ? { offset: sink.offset() } : {}) },
    fault: plan.session?.faults[0],
  });
}

/**
 * Keep what this request received past its last stored part as the session's tail — written, then recorded
 * under the claim; a request that lost the claim deletes what it wrote. The tail it replaces is deleted.
 * Best-effort: a tail that cannot be kept only means a smaller offset for the retry.
 */
async function keepTail(deps: Deps, t: Target, sink: PartSink, plan: PumpPlan): Promise<void> {
  const session = plan.session!;
  try {
    const { start, bytes } = await sink.salvage();
    const tail = bytes.byteLength === 0
      ? null
      : { key: `${t.path}.tail-${session.claim}`, start, bytes: bytes.byteLength };
    if (tail !== null) {
      await putObject(
        deps.fetchImpl,
        deps.config,
        storageKey(tail.key),
        bytes,
        "application/octet-stream",
      );
    }
    const kept = await recordTail(deps.db, t, session.claim, tail);
    const stale = kept ? plan.usedTails : tail === null ? [] : [tail.key];
    for (const key of stale) await deleteObject(deps.fetchImpl, deps.config, storageKey(key));
    if (kept) plan.usedTails = [];
  } catch (e) {
    session.faults.push({ tag: "tail-save", detail: `${t.path}: ${e}` });
  }
}

/** The body is complete: store the rest and join the parts — unless a newer request took over. */
async function finish(
  deps: Deps,
  route: string,
  t: Target,
  sink: PartSink,
  plan: PumpPlan,
): Promise<void> {
  const rest = await sink.end();
  const stillOurs = async () =>
    plan.session === null ||
    await upstream(`${t.path}: claim read failed`, () =>
      holdsClaim(deps.db, t, plan.session!.claim));
  if (sink.uploadId === null && sink.parts.length === 0) {
    if (!await stillOurs()) refuse(409, "superseded");
    await streamPut(
      deps.fetchImpl,
      deps.config,
      route,
      storageKey(t.path),
      t.contentType,
      once(rest),
    );
    return;
  }
  if (rest.byteLength > 0) await sink.shipLast(rest);
  if (!await stillOurs()) refuse(409, "superseded");
  await upstream(
    `${route}: completing ${t.path} failed`,
    () => deps.s3.complete(t.path, sink.uploadId!, sink.parts),
  );
}

function once(bytes: Uint8Array): ReadableStream<Uint8Array> {
  return new ReadableStream({
    start(c) {
      if (bytes.byteLength > 0) c.enqueue(bytes);
      c.close();
    },
  });
}

async function upstream<T>(what: string, step: () => Promise<T>): Promise<T> {
  try {
    return await step();
  } catch (e) {
    rethrowRefusal(e);
    if (e instanceof Superseded) refuse(409, "superseded");
    upstream502(what, e);
  }
}

/**
 * Collects a body into parts of `partSize` and stores each FULL one as soon as it fills, with at most one part
 * in flight while the next fills. Creates the multipart upload with its first part, and keeps the bytes of the
 * part in flight until it lands, so a drop can still keep them.
 */
class PartSink {
  readonly parts: StoredPart[];
  uploadId: string | null;
  private buf: Uint8Array<ArrayBuffer>;
  private fill = 0;
  private inflight: Promise<void> = Promise.resolve();
  private pending: Uint8Array<ArrayBuffer> | null = null;
  private failure: unknown = null;

  constructor(
    private readonly s3: S3,
    private readonly t: Target,
    private readonly plan: PumpPlan,
    private readonly onCreated: (uploadId: string) => Promise<void>,
  ) {
    this.parts = [...plan.parts];
    this.uploadId = plan.uploadId;
    this.buf = new Uint8Array(plan.partSize);
  }

  /** Bytes stored as parts plus bytes held — where the next body byte goes. */
  offset(): number {
    return this.storedBytes() + (this.pending?.byteLength ?? 0) + this.fill;
  }

  async write(chunk: Uint8Array): Promise<void> {
    let off = 0;
    while (off < chunk.byteLength) {
      const take = Math.min(this.plan.partSize - this.fill, chunk.byteLength - off);
      this.buf.set(chunk.subarray(off, off + take), this.fill);
      this.fill += take;
      off += take;
      if (this.fill === this.plan.partSize) await this.ship(this.take());
    }
  }

  /** The body ended: answers the bytes past the last full part, once every part in flight landed. */
  async end(): Promise<Uint8Array<ArrayBuffer>> {
    await this.settle();
    return this.take();
  }

  /** Send `rest` as the last part (it may be under 5 MiB) and wait for it. */
  async shipLast(rest: Uint8Array<ArrayBuffer>): Promise<void> {
    await this.ship(rest);
    await this.settle();
  }

  /** After a drop: what arrived past the last STORED part — a part in flight that failed included. */
  async salvage(): Promise<{ start: number; bytes: Uint8Array<ArrayBuffer> }> {
    await this.inflight;
    const held = this.buf.subarray(0, this.fill);
    const pending = this.pending ?? new Uint8Array(0);
    const bytes = new Uint8Array(pending.byteLength + held.byteLength);
    bytes.set(pending, 0);
    bytes.set(held, pending.byteLength);
    return { start: this.storedBytes(), bytes };
  }

  private storedBytes(): number {
    return this.parts.reduce((n, p) => n + p.size, 0);
  }

  private take(): Uint8Array<ArrayBuffer> {
    const bytes = this.buf.subarray(0, this.fill);
    this.buf = new Uint8Array(this.plan.partSize);
    this.fill = 0;
    return bytes;
  }

  private async settle(): Promise<void> {
    await this.inflight;
    if (this.failure !== null) throw this.failure;
  }

  private async ship(bytes: Uint8Array<ArrayBuffer>): Promise<void> {
    await this.settle();
    const partNumber = this.parts.length + 1;
    this.pending = bytes;
    this.inflight = (async () => {
      try {
        if (this.uploadId === null) {
          this.uploadId = await this.s3.create(this.t.path, this.t.contentType);
          await this.onCreated(this.uploadId);
        }
        const etag = await this.s3.uploadPart(this.t.path, this.uploadId, partNumber, bytes);
        this.parts.push({ partNumber, size: bytes.byteLength, etag });
        this.pending = null;
      } catch (e) {
        this.failure = e;
      }
    })();
  }
}
