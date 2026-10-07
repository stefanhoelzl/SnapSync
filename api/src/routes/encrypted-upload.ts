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

import type { HonoRequest } from "hono";
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
import { refuse } from "../refusal.ts";

/** The one file's key the edge seals with — 32 bytes, base64url. */
export const FILE_KEY_HEADER = "x-snapsync-file-key";
/** The file's opening bytes, prefix and header — 49 bytes, base64url. */
export const FILE_HEAD_HEADER = "x-snapsync-file-head";

/** What an upload arrived with: the two sealing headers, when sent, and its body. */
export type Upload = {
  fileKey: string | undefined;
  fileHead: string | undefined;
  body: ReadableStream<Uint8Array> | null;
};

/** The upload `req` carries: its sealing headers and its body, as {@link bodyToStore} takes them. */
export function uploadOf(req: HonoRequest): Upload {
  return {
    fileKey: req.header(FILE_KEY_HEADER),
    fileHead: req.header(FILE_HEAD_HEADER),
    body: req.raw.body,
  };
}

/**
 * The body to store for an upload into an event whose key id is `keyId` (`null` for a plain event); refused
 * otherwise. Never reads more than the 9-byte prefix before it decides.
 */
export async function bodyToStore(
  upload: Upload,
  keyId: string | null,
): Promise<ReadableStream<Uint8Array>> {
  const { fileKey, fileHead } = upload;
  const body = upload.body ?? new ReadableStream<Uint8Array>({ start: (s) => s.close() });
  if (keyId === null) {
    if (fileKey !== undefined || fileHead !== undefined) refuse(400, "event is not encrypted");
    return body;
  }
  if (fileKey !== undefined || fileHead !== undefined) {
    return sealedHere(keyId, fileKey, fileHead, body);
  }
  return await sealedOnDevice(keyId, body);
}

function sealedHere(
  keyId: string,
  fileKey: string | undefined,
  fileHead: string | undefined,
  body: ReadableStream<Uint8Array>,
): ReadableStream<Uint8Array> {
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
    refuse(400, "invalid file key");
  }
  if (encodeHex(head.keyId) !== keyId) refuse(403, "another key");
  return body.pipeThrough(encryptingStream(key, head));
}

async function sealedOnDevice(
  keyId: string,
  body: ReadableStream<Uint8Array>,
): Promise<ReadableStream<Uint8Array>> {
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
    refuse(422, "not encrypted for this event");
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
