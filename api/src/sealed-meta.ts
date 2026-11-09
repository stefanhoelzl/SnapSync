// An encrypted event's SEALED METADATA RECORD — the TypeScript half, written against WebCrypto alone so the
// edge (which checks only the envelope's shape) and the event page in a browser (which opens the record to
// show the members' name) run the same code. The Kotlin half is `:domain:format`'s `SealedMetaFormat` and
// data's `MetaCipher`; both are held to `test/vectors/sealed-meta.json`, which an implementation independent
// of either produced. Decision record: `changes/seal-event-name` (D1–D3).
//
//   envelope  version (1 byte, 0x01) ‖ nonce (12 bytes) ‖ AES-256-GCM ciphertext ‖ tag (16), unpadded base64url
//   key       HKDF-SHA256(event key, no salt, "snapsync/meta/v1"), 32 bytes
//   plaintext a UTF-8 JSON object, today {"name": "…"}; keys a reader does not know are ignored
//
// A photo's FILE NAME in an encrypted event is the same envelope and record under a key derived per photo:
// HKDF-SHA256(event key, no salt, "snapsync/file-name/v1/<eventId>/<deviceId>/<assetId>/<role>") (decision
// record `changes/seal-file-names`, D1). The edge checks its shape before it stores an upload's `filename`.
//
// GCM carries no associated data: the binding is the derivation's label, as the encrypted file format binds
// a file. The edge never holds the key, so it can only ever call `envelopeShape`.

export const META_VERSION = 1;
export const META_NONCE_LENGTH = 12;
export const META_TAG_LENGTH = 16;
/** The shortest envelope: an empty plaintext's. */
export const META_MIN_LENGTH = 1 + META_NONCE_LENGTH + META_TAG_LENGTH;
/** The longest envelope TEXT the service stores — what bounds a record no app's rules checked. */
export const META_MAX_TEXT_LENGTH = 1024;

const LABEL = new TextEncoder().encode("snapsync/meta/v1");

/** The bytes of unpadded base64url `text`, or `null` when it is not exactly that. */
function fromBase64Url(text: string): Uint8Array | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return null;
  const padded = text.replace(/-/g, "+").replace(/_/g, "/") +
    "=".repeat((4 - (text.length % 4)) % 4);
  try {
    return Uint8Array.from(atob(padded), (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** The envelope's nonce and sealed bytes, or why it is not this format. */
export type EnvelopeRead =
  | { ok: true; nonce: Uint8Array; sealed: Uint8Array }
  | { ok: false; reason: string };

/** Whether `envelope` is this format — all the edge can check of a record it cannot open. */
export function envelopeShape(envelope: string): EnvelopeRead {
  if (envelope.length > META_MAX_TEXT_LENGTH) return { ok: false, reason: "too long" };
  const bytes = fromBase64Url(envelope);
  if (bytes === null) return { ok: false, reason: "not unpadded base64url" };
  if (bytes.length < META_MIN_LENGTH) return { ok: false, reason: "truncated" };
  if (bytes[0] !== META_VERSION) return { ok: false, reason: `unknown version ${bytes[0]}` };
  return {
    ok: true,
    nonce: bytes.slice(1, 1 + META_NONCE_LENGTH),
    sealed: bytes.slice(1 + META_NONCE_LENGTH),
  };
}

async function metaKey(
  eventKey: Uint8Array,
  usage: KeyUsage,
  label: Uint8Array,
): Promise<CryptoKey> {
  const base = await crypto.subtle.importKey("raw", eventKey as BufferSource, "HKDF", false, [
    "deriveKey",
  ]);
  return crypto.subtle.deriveKey(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: label as BufferSource },
    base,
    { name: "AES-GCM", length: 256 },
    false,
    [usage],
  );
}

/** The raw 32 bytes of a record's key — for the vectors test only; nothing else needs the key as bytes. */
export async function metaKeyBytes(
  eventKey: Uint8Array,
  label: Uint8Array = LABEL,
): Promise<Uint8Array> {
  const base = await crypto.subtle.importKey("raw", eventKey as BufferSource, "HKDF", false, [
    "deriveBits",
  ]);
  const bits = await crypto.subtle.deriveBits(
    { name: "HKDF", hash: "SHA-256", salt: new Uint8Array(0), info: label as BufferSource },
    base,
    256,
  );
  return new Uint8Array(bits);
}

/** An opened record: its name as written, and every key carried untouched. */
export type MetaRecord = { name: string; [key: string]: unknown };

/** The record `envelope` holds, opened with `eventKey`, or `null` when it does not open or holds no name. */
export function openRecord(eventKey: Uint8Array, envelope: string): Promise<MetaRecord | null> {
  return openUnder(eventKey, envelope, LABEL);
}

/** `record` sealed under `eventKey`, with `nonce` (12 bytes; a fresh random one unless a test pins it). */
export function sealRecord(
  eventKey: Uint8Array,
  record: MetaRecord,
  nonce: Uint8Array = crypto.getRandomValues(new Uint8Array(META_NONCE_LENGTH)),
): Promise<string> {
  return sealUnder(eventKey, record, nonce, LABEL);
}

// ── A photo's file name (decision record `changes/seal-file-names`, D1) ───────────────────────────
//
// The same envelope and record, under a key derived PER PHOTO: the label names the event, the device, the
// asset and the role, so a record moved onto another photo opens under no key there.

/** The photo a file name is sealed for — the ids as the byte route's path names them. */
export type FileNameBinding = { eventId: string; deviceId: string; assetId: string; role: string };

/** The derivation label binding a file name to its photo. */
export function fileNameLabel(b: FileNameBinding): Uint8Array {
  return new TextEncoder().encode(
    `snapsync/file-name/v1/${b.eventId}/${b.deviceId}/${b.assetId}/${b.role}`,
  );
}

/**
 * The file name `envelope` holds for the photo `binding` names, opened with the event's key — or `null` when it
 * does not open there, or names no file (every upload names one).
 */
export async function openFileName(
  eventKey: Uint8Array,
  binding: FileNameBinding,
  envelope: string,
): Promise<string | null> {
  const record = await openUnder(eventKey, envelope, fileNameLabel(binding));
  return record === null || record.name === "" ? null : record.name;
}

/** `name`, sealed for the photo `binding` names (`nonce`: a fresh random one unless a test pins it). */
export function sealFileName(
  eventKey: Uint8Array,
  binding: FileNameBinding,
  name: string,
  nonce: Uint8Array = crypto.getRandomValues(new Uint8Array(META_NONCE_LENGTH)),
): Promise<string> {
  return sealUnder(eventKey, { name }, nonce, fileNameLabel(binding));
}

async function openUnder(
  eventKey: Uint8Array,
  envelope: string,
  label: Uint8Array,
): Promise<MetaRecord | null> {
  const read = envelopeShape(envelope);
  if (!read.ok) return null;
  let plaintext: ArrayBuffer;
  try {
    plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: read.nonce as BufferSource },
      await metaKey(eventKey, "decrypt", label),
      read.sealed as BufferSource,
    );
  } catch {
    return null;
  }
  try {
    const json = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
    if (json === null || typeof json !== "object" || Array.isArray(json)) return null;
    return typeof json.name === "string" ? json as MetaRecord : null;
  } catch {
    return null;
  }
}

async function sealUnder(
  eventKey: Uint8Array,
  record: MetaRecord,
  nonce: Uint8Array,
  label: Uint8Array,
): Promise<string> {
  const sealed = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce as BufferSource },
      await metaKey(eventKey, "encrypt", label),
      new TextEncoder().encode(JSON.stringify(record)) as BufferSource,
    ),
  );
  const out = new Uint8Array(1 + nonce.length + sealed.length);
  out[0] = META_VERSION;
  out.set(nonce, 1);
  out.set(sealed, 1 + nonce.length);
  return toBase64Url(out);
}
