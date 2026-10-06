// The ENCRYPTED FILE FORMAT of an encrypted event's stored photo — the TypeScript half, written against
// WebCrypto alone so the edge (the iOS extension's uploads, which it encrypts as they stream) and the
// event page in a browser (which decrypts the zip it builds) run the same code. The Kotlin half is
// `:domain:model`'s `EncryptedFileFormat`; both are held to `test/vectors/encrypted-file.json`, which an
// implementation independent of either produced and Google Tink decrypts.
//
// A file is a 9-byte SnapSync prefix, then Tink's `AesGcmHkdfStreaming` ciphertext byte for byte:
//
//   prefix   version (1 byte, 0x01) ‖ key id (8 bytes)
//   header   header length (1 byte, 40) ‖ salt (32 bytes) ‖ nonce prefix (7 bytes)
//   segments AES-256-GCM, each ciphertext segment 64 KiB (the first shorter by the header), the last
//            one shorter still; nonce = nonce prefix ‖ segment number (u32 big-endian) ‖ last (0|1)
//
// The segment key is HKDF-SHA256(event key, salt, associated data), the associated data naming the
// resource the file is (`fileAssociatedData`), so a file moved to another event, device, asset or role
// fails to decrypt. The key id is HKDF-SHA256(event key, no salt, "snapsync/key-id/v1") truncated to 8
// bytes: what the event row holds, telling a wrong key from a damaged file without revealing the key.

export const FORMAT_VERSION = 1;
export const KEY_ID_LENGTH = 8;
export const PREFIX_LENGTH = 1 + KEY_ID_LENGTH;
export const KEY_LENGTH = 32;
export const SALT_LENGTH = 32;
export const NONCE_PREFIX_LENGTH = 7;
export const HEADER_LENGTH = 1 + SALT_LENGTH + NONCE_PREFIX_LENGTH;
export const TAG_LENGTH = 16;
export const SEGMENT_LENGTH = 65536;

const KEY_ID_INFO = new TextEncoder().encode("snapsync/key-id/v1");

/** The opening bytes of an encrypted file: everything before its first segment. */
export type FileHead = {
  version: number;
  keyId: Uint8Array;
  salt: Uint8Array;
  noncePrefix: Uint8Array;
};

export class EncryptedFileError extends Error {}

async function hkdf(
  ikm: Uint8Array,
  salt: Uint8Array,
  info: Uint8Array,
  length: number,
): Promise<Uint8Array> {
  const base = await crypto.subtle.importKey("raw", ikm as BufferSource, "HKDF", false, [
    "deriveBits",
  ]);
  const bits = await crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: salt as BufferSource, info: info as BufferSource },
    base,
    length * 8,
  );
  return new Uint8Array(bits);
}

/** The key id an event row holds for `eventKey`. */
export function keyIdOf(eventKey: Uint8Array): Promise<Uint8Array> {
  return hkdf(eventKey, new Uint8Array(0), KEY_ID_INFO, KEY_ID_LENGTH);
}

/** What a file is bound to. The ids are validated path segments, so no `/` occurs inside one. */
export function fileAssociatedData(
  eventId: string,
  deviceId: string,
  assetId: string,
  role: string,
): Uint8Array {
  return new TextEncoder().encode(`snapsync/v1/${eventId}/${deviceId}/${assetId}/${role}`);
}

/** The key one file's segments are sealed with. */
export function fileKeyOf(
  eventKey: Uint8Array,
  salt: Uint8Array,
  associatedData: Uint8Array,
): Promise<Uint8Array> {
  return hkdf(eventKey, salt, associatedData, KEY_LENGTH);
}

/** The prefix and header, as they open the file. */
export function encodeHead(head: FileHead): Uint8Array {
  if (head.keyId.length !== KEY_ID_LENGTH) throw new EncryptedFileError("key id length");
  if (head.salt.length !== SALT_LENGTH) throw new EncryptedFileError("salt length");
  if (head.noncePrefix.length !== NONCE_PREFIX_LENGTH) {
    throw new EncryptedFileError("nonce prefix length");
  }
  const out = new Uint8Array(PREFIX_LENGTH + HEADER_LENGTH);
  out[0] = head.version;
  out.set(head.keyId, 1);
  out[PREFIX_LENGTH] = HEADER_LENGTH;
  out.set(head.salt, PREFIX_LENGTH + 1);
  out.set(head.noncePrefix, PREFIX_LENGTH + 1 + SALT_LENGTH);
  return out;
}

/** The prefix and header of `bytes`, which must hold at least `PREFIX_LENGTH + HEADER_LENGTH` bytes. */
export function decodeHead(bytes: Uint8Array): FileHead {
  if (bytes.length < PREFIX_LENGTH + HEADER_LENGTH) throw new EncryptedFileError("truncated head");
  if (bytes[0] !== FORMAT_VERSION) {
    throw new EncryptedFileError(`unknown format version ${bytes[0]}`);
  }
  if (bytes[PREFIX_LENGTH] !== HEADER_LENGTH) throw new EncryptedFileError("header length");
  return {
    version: bytes[0],
    keyId: bytes.slice(1, PREFIX_LENGTH),
    salt: bytes.slice(PREFIX_LENGTH + 1, PREFIX_LENGTH + 1 + SALT_LENGTH),
    noncePrefix: bytes.slice(PREFIX_LENGTH + 1 + SALT_LENGTH, PREFIX_LENGTH + HEADER_LENGTH),
  };
}

function segmentNonce(noncePrefix: Uint8Array, segment: number, last: boolean): Uint8Array {
  const nonce = new Uint8Array(12);
  nonce.set(noncePrefix, 0);
  new DataView(nonce.buffer).setUint32(NONCE_PREFIX_LENGTH, segment, false);
  nonce[11] = last ? 1 : 0;
  return nonce;
}

/** How many plaintext bytes segment `segment` carries, unless it is the last. */
function plaintextSegmentLength(segment: number): number {
  return SEGMENT_LENGTH - TAG_LENGTH - (segment === 0 ? HEADER_LENGTH : 0);
}

function importAesKey(fileKey: Uint8Array, usage: "encrypt" | "decrypt"): Promise<CryptoKey> {
  if (fileKey.length !== KEY_LENGTH) throw new EncryptedFileError("file key length");
  return crypto.subtle.importKey("raw", fileKey as BufferSource, "AES-GCM", false, [usage]);
}

function concat(a: Uint8Array, b: Uint8Array): Uint8Array {
  if (a.length === 0) return b;
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

/**
 * Encrypts a plaintext stream into a whole file: `head` first, then the segments. A segment is sealed
 * only once a byte BEYOND it has arrived, so the stream's end alone decides which one is last — the
 * reason this holds at most one segment, ~64 KiB, however long the photo.
 */
export function encryptingStream(
  fileKey: Uint8Array,
  head: FileHead,
): TransformStream<Uint8Array, Uint8Array> {
  const key = importAesKey(fileKey, "encrypt");
  let pending: Uint8Array = new Uint8Array(0);
  let segment = 0;
  const seal = async (plain: Uint8Array, last: boolean): Promise<Uint8Array> => {
    const nonce = segmentNonce(head.noncePrefix, segment++, last);
    return new Uint8Array(
      await crypto.subtle.encrypt(
        { name: "AES-GCM", iv: nonce as BufferSource },
        await key,
        plain as BufferSource,
      ),
    );
  };
  return new TransformStream({
    async start(controller) {
      await key;
      controller.enqueue(encodeHead(head));
    },
    async transform(chunk, controller) {
      pending = concat(pending, chunk);
      while (pending.length > plaintextSegmentLength(segment)) {
        const size = plaintextSegmentLength(segment);
        controller.enqueue(await seal(pending.subarray(0, size), false));
        pending = pending.slice(size);
      }
    },
    async flush(controller) {
      controller.enqueue(await seal(pending, true));
    },
  });
}

/**
 * Decrypts a whole file with the key of the event it belongs to. Throws [EncryptedFileError] when the
 * file is not this format, names another key, or any segment fails to authenticate — a truncated,
 * reordered, moved or altered file never yields bytes.
 */
export async function decryptFile(
  file: Uint8Array,
  eventKey: Uint8Array,
  associatedData: Uint8Array,
): Promise<Uint8Array> {
  const head = decodeHead(file);
  const expected = await keyIdOf(eventKey);
  if (!equalBytes(head.keyId, expected)) {
    throw new EncryptedFileError("encrypted under another key");
  }
  const key = await importAesKey(await fileKeyOf(eventKey, head.salt, associatedData), "decrypt");
  const parts: Uint8Array[] = [];
  let at = PREFIX_LENGTH + HEADER_LENGTH;
  let segment = 0;
  let total = 0;
  for (;;) {
    const size = plaintextSegmentLength(segment) + TAG_LENGTH;
    const last = file.length - at <= size;
    const sealed = file.subarray(at, last ? file.length : at + size);
    if (sealed.length < TAG_LENGTH) throw new EncryptedFileError("truncated segment");
    let plain: ArrayBuffer;
    try {
      plain = await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: segmentNonce(head.noncePrefix, segment, last) as BufferSource },
        key,
        sealed as BufferSource,
      );
    } catch {
      throw new EncryptedFileError(`segment ${segment} failed to authenticate`);
    }
    parts.push(new Uint8Array(plain));
    total += plain.byteLength;
    if (last) break;
    at += size;
    segment++;
  }
  const out = new Uint8Array(total);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

export function equalBytes(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}
