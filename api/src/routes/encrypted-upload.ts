// What a byte route stores for an upload, given the event's key id (the encrypted file format,
// `docs/architecture.md`). One decision, made before a single byte is stored, for every byte route:
//
//   plain event      the body as it came — and a file-key header is refused, since a device that sent one
//                    believes the event is encrypted and must not be told its bytes were taken as plaintext;
//   encrypted event  with the iOS extension's two file-key headers, the body ENCRYPTED HERE as it streams —
//                    PhotoKit uploads the library's own bytes, so this is the one place they can be sealed.
//                    The headers carry that ONE file's key and opening bytes, never the event key, so what
//                    passes through here opens that file and nothing else;
//                    without them, the body must already be an encrypted file of THIS event's key: its first
//                    9 bytes (format version, key id) are read and the rest passes untouched.
//
// Either way plaintext never lands in an encrypted event. The file-key headers are secrets: no line here,
// nor any caller's, ever logs a header value.

import type { Context } from "hono";
import { decodeBase64Url } from "@std/encoding/base64url";
import { encodeHex } from "@std/encoding/hex";
import {
  decodeHead,
  EncryptedFileError,
  encryptingStream,
  FORMAT_VERSION,
  HEADER_LENGTH,
  KEY_LENGTH,
  PREFIX_LENGTH,
} from "../encrypted-file.ts";

/** The one file's key the edge seals with — 32 bytes, base64url. */
export const FILE_KEY_HEADER = "x-snapsync-file-key";
/** The file's opening bytes, prefix and header — 49 bytes, base64url. */
export const FILE_HEAD_HEADER = "x-snapsync-file-head";

/**
 * The body to store for an upload into an event whose key id is `keyId` (`null` for a plain event), or the
 * refusal to answer. Never reads more than the 9-byte prefix before it decides.
 */
export async function bodyToStore(
  c: Context,
  keyId: string | null,
): Promise<ReadableStream<Uint8Array> | Response> {
  const fileKey = c.req.header(FILE_KEY_HEADER);
  const fileHead = c.req.header(FILE_HEAD_HEADER);
  const body = c.req.raw.body ?? new ReadableStream<Uint8Array>({ start: (s) => s.close() });
  if (keyId === null) {
    if (fileKey !== undefined || fileHead !== undefined) {
      return c.text("event is not encrypted", 400);
    }
    return body;
  }
  if (fileKey !== undefined || fileHead !== undefined) {
    return sealedHere(c, keyId, fileKey, fileHead, body);
  }
  return await sealedOnDevice(c, keyId, body);
}

function sealedHere(
  c: Context,
  keyId: string,
  fileKey: string | undefined,
  fileHead: string | undefined,
  body: ReadableStream<Uint8Array>,
): ReadableStream<Uint8Array> | Response {
  let key: Uint8Array;
  let head;
  try {
    if (fileKey === undefined || fileHead === undefined) {
      throw new EncryptedFileError("one header of two");
    }
    key = decodeBase64Url(fileKey);
    const headBytes = decodeBase64Url(fileHead);
    if (key.length !== KEY_LENGTH || headBytes.length !== PREFIX_LENGTH + HEADER_LENGTH) {
      throw new EncryptedFileError("header length");
    }
    head = decodeHead(headBytes);
  } catch {
    return c.text("invalid file key", 400);
  }
  if (encodeHex(head.keyId) !== keyId) return c.text("another key", 403);
  return body.pipeThrough(encryptingStream(key, head));
}

async function sealedOnDevice(
  c: Context,
  keyId: string,
  body: ReadableStream<Uint8Array>,
): Promise<ReadableStream<Uint8Array> | Response> {
  const reader = body.getReader();
  const seen: Uint8Array[] = [];
  let length = 0;
  while (length < PREFIX_LENGTH) {
    const { done, value } = await reader.read();
    if (done) break;
    seen.push(value);
    length += value.length;
  }
  const prefix = concat(seen, length);
  if (
    prefix.length < PREFIX_LENGTH || prefix[0] !== FORMAT_VERSION ||
    encodeHex(prefix.subarray(1, PREFIX_LENGTH)) !== keyId
  ) {
    await reader.cancel().catch(() => {});
    return c.text("not encrypted for this event", 422);
  }
  // The bytes already read go first, then the rest of the body untouched.
  return new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(prefix);
    },
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) controller.close();
      else controller.enqueue(value);
    },
    cancel(reason) {
      return reader.cancel(reason);
    },
  });
}

function concat(parts: Uint8Array[], length: number): Uint8Array {
  const out = new Uint8Array(length);
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}
