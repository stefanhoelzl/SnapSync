// Device attestation (capability `device-attestation`): App Attest verification, the stateless
// challenge, and the device bearer token.
//
// The shape of this module is dictated by three things measured on a real device, not assumed:
//
//   * The OS performs the byte upload, and it DOES carry the extension's custom request headers to the
//     wire (observed at the origin with `user-agent: assetsd`). So a header-borne bearer token can gate
//     the byte route at all — which is what makes this whole design possible.
//   * The bunny pull zone forwards `Authorization` to the origin unmodified.
//   * App Attest is UNAVAILABLE inside the upload extension (`DCAppAttestService.isSupported` is false
//     there, true in the app). Only the app can attest or renew.
//
// Two consequences run through everything below:
//
//   1. VERIFYING A TOKEN TOUCHES NOTHING. It is one HMAC comparison — no storage read, no Apple call —
//      because it runs on the streaming photo-upload hot path, where a round-trip per resource would be
//      paid on every photo.
//   2. RENEWAL IS AN ASSERTION, NOT A RE-ATTESTATION. Apple attests a key ONCE; re-attesting (or minting
//      a fresh key each time) is the throttled path. So the attested public key is persisted and renewal
//      verifies a cheap local assertion against it — which is what lets the app renew at EVERY wake
//      rather than in a narrow window near expiry.

import { type CBORType, decodeCBOR } from "@levischuck/tiny-cbor";
import * as x509 from "@peculiar/x509";
import { decodeBase64Url, encodeBase64 } from "@std/encoding";
import type { Config } from "./config.ts";
import type { AttestEnvironment } from "./storage.ts";

/** Apple puts the attestation's nonce in this certificate extension. */
const APPLE_NONCE_OID = "1.2.840.113635.100.8.2";

/** How long a challenge stays usable. Long enough for a slow attestation, short enough to be worthless later. */
const CHALLENGE_TTL_SECONDS = 300;

/**
 * The `aaguid` in `authData` names the attestation environment.
 *
 * BOTH are accepted, deliberately: a sideloaded dev build attests against Apple's DEVELOPMENT
 * environment, and rejecting it would leave the on-device dev loop unable to upload at all — there is no
 * local upload rig. It is safe because the attestation still binds OUR app id, so only a build signed by
 * our team can produce one.
 */
const AAGUID: Record<AttestEnvironment, Uint8Array> = {
  development: new TextEncoder().encode("appattestdevelop"),
  production: new Uint8Array([...new TextEncoder().encode("appattest"), 0, 0, 0, 0, 0, 0, 0]),
};

const enc = new TextEncoder();

const sha256 = async (b: Uint8Array): Promise<Uint8Array> =>
  new Uint8Array(await crypto.subtle.digest("SHA-256", b as BufferSource));

function concat(...parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

/** A view's own bytes as a standalone ArrayBuffer (what `@peculiar/x509` wants for DER input). */
const toArrayBuffer = (b: Uint8Array): ArrayBuffer =>
  b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength) as ArrayBuffer;

/**
 * Constant-time-ish equality. Length is not secret here; the bytes are. The one byte-compare primitive on
 * the backend — `app.ts`'s bearer-secret compare delegates to it over UTF-8 bytes.
 */
export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

export const b64ToBytes = (s: string): Uint8Array =>
  // Accept base64 as well as base64url — the device may send either. `decodeBase64Url` is strict about the
  // url alphabet, so normalize the two standard-base64 chars to their url-safe forms first; it tolerates
  // both padded and unpadded input.
  decodeBase64Url(s.replaceAll("+", "-").replaceAll("/", "_"));

export const bytesToB64 = (b: Uint8Array): string => encodeBase64(b);

async function hmacKey(secret: string): Promise<CryptoKey> {
  return await crypto.subtle.importKey(
    "raw",
    enc.encode(secret) as BufferSource,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign", "verify"],
  );
}

async function hmac(secret: string, message: string): Promise<Uint8Array> {
  const key = await hmacKey(secret);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode(message) as BufferSource));
}

// ── Challenge ─────────────────────────────────────────────────────────────────────────────────────
//
// Stateless and self-authenticating: `<expiry>.<hmac(expiry)>`. Issuing one writes NOTHING, so the
// challenge endpoint cannot be used to grow the storage bill it exists to protect.

/** Mint a time-bounded challenge. No storage write. */
export async function mintChallenge(config: Config, nowMs: number): Promise<string> {
  const expiry = Math.floor(nowMs / 1000) + CHALLENGE_TTL_SECONDS;
  const sig = await hmac(config.attestTokenKey, `challenge:${expiry}`);
  return `${expiry}.${bytesToB64(sig)}`;
}

/** True when `challenge` was minted by us and has not expired. */
export async function challengeIsValid(
  config: Config,
  challenge: string,
  nowMs: number,
): Promise<boolean> {
  const [expiryRaw, sigRaw] = challenge.split(".");
  const expiry = Number(expiryRaw);
  if (!expiryRaw || !sigRaw || !Number.isFinite(expiry)) return false;
  if (Math.floor(nowMs / 1000) > expiry) return false;
  const expected = await hmac(config.attestTokenKey, `challenge:${expiry}`);
  try {
    return bytesEqual(b64ToBytes(sigRaw), expected);
  } catch {
    return false;
  }
}

// ── The device token ──────────────────────────────────────────────────────────────────────────────
//
// `<deviceId>.<expiry>.<hmac>`. Verification is ONE HMAC comparison — no storage read, no Apple call —
// which is what keeps the streaming byte-upload path free of any added round-trip.

/** Mint a device token valid for the configured TTL. */
export async function mintToken(config: Config, deviceId: string, nowMs: number): Promise<string> {
  const expiry = Math.floor(nowMs / 1000) + config.attestTokenTtlSeconds;
  const payload = `${deviceId}.${expiry}`;
  const sig = await hmac(config.attestTokenKey, `token:${payload}`);
  return `${payload}.${bytesToB64(sig)}`;
}

/**
 * Verify a token and return the device id it was minted for, or `null`.
 *
 * NOTE the deliberate asymmetry: this proves the token is OURS and UNEXPIRED. It does NOT prove the
 * caller owns the partition named by `deviceId` — nothing binds an attestation key to a device id (see
 * the capability's stated non-goals). Ownership stays capability-based on the unguessable UUID.
 */
export async function verifyToken(
  config: Config,
  token: string,
  nowMs: number,
): Promise<string | null> {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const [deviceId, expiryRaw, sigRaw] = parts;
  const expiry = Number(expiryRaw);
  if (!Number.isFinite(expiry)) return null;
  if (Math.floor(nowMs / 1000) > expiry) return null;

  const expected = await hmac(config.attestTokenKey, `token:${deviceId}.${expiry}`);
  try {
    if (!bytesEqual(b64ToBytes(sigRaw), expected)) return null;
  } catch {
    return null;
  }
  return deviceId;
}

// ── Attestation ───────────────────────────────────────────────────────────────────────────────────

export type VerifiedAttestation = {
  /** The attested public key, as a raw uncompressed EC point. Persisted so renewal can verify assertions. */
  publicKey: Uint8Array;
  environment: AttestEnvironment;
};

/**
 * Verify an App Attest attestation object, in the order Apple specifies. THROWS on any failure — there is
 * no partial success and no "mostly valid" attestation.
 *
 * `at` is the instant the certificate chain is validated against (production passes now; tests pin it, so
 * a fixture whose leaf has since expired can still be verified).
 */
export async function verifyAttestation(
  config: Config,
  opts: {
    attestation: Uint8Array;
    challenge: string;
    keyId: Uint8Array;
    at: Date;
  },
): Promise<VerifiedAttestation> {
  const obj = decodeCBOR(opts.attestation) as Map<string, CBORType>;
  if (obj.get("fmt") !== "apple-appattest") throw new Error(`unexpected fmt: ${obj.get("fmt")}`);

  const attStmt = obj.get("attStmt") as Map<string, CBORType>;
  const authData = obj.get("authData") as Uint8Array;
  const x5c = attStmt?.get("x5c") as Uint8Array[] | undefined;
  if (!authData || !x5c || x5c.length < 2) throw new Error("malformed attestation object");

  // 1. The chain: leaf → intermediate → Apple's root. Every signature, and validity at `at`.
  const leaf = new x509.X509Certificate(toArrayBuffer(x5c[0]));
  const intermediate = new x509.X509Certificate(toArrayBuffer(x5c[1]));
  const root = new x509.X509Certificate(config.appAttestRootCa);

  const built = await new x509.X509ChainBuilder({ certificates: [intermediate, root] }).build(leaf);
  if (built.length !== 3) throw new Error("certificate chain does not reach the root");
  if (!bytesEqual(new Uint8Array(built[2].rawData), new Uint8Array(root.rawData))) {
    throw new Error("chain does not terminate at Apple's App Attest root");
  }
  for (let i = 0; i < built.length - 1; i++) {
    if (!await built[i].verify({ date: opts.at, publicKey: built[i + 1].publicKey })) {
      throw new Error("certificate chain failed verification");
    }
  }

  // 2. The nonce binds this attestation to OUR challenge: SHA256(authData || SHA256(challenge)).
  const clientDataHash = await sha256(enc.encode(opts.challenge));
  const expectedNonce = await sha256(concat(authData, clientDataHash));

  const ext = leaf.getExtension(APPLE_NONCE_OID);
  if (!ext) throw new Error("no Apple nonce extension on the leaf certificate");
  const extBytes = new Uint8Array(ext.value);
  const actualNonce = extBytes.slice(extBytes.length - 32); // the nonce is the tail of a small DER envelope
  if (!bytesEqual(actualNonce, expectedNonce)) throw new Error("nonce mismatch");

  // 3. The keyId must be SHA256 of the attested public key.
  const spki = new Uint8Array(leaf.publicKey.rawData);
  const publicKey = spki.slice(spki.length - 65); // trailing uncompressed EC point
  if (publicKey[0] !== 0x04) throw new Error("attested key is not an uncompressed EC point");
  if (!bytesEqual(await sha256(publicKey), opts.keyId)) {
    throw new Error("keyId does not match the attested key");
  }

  // 4. authData: rpIdHash(32) | flags(1) | counter(4) | aaguid(16) | credIdLen(2) | credId
  const rpIdHash = authData.slice(0, 32);
  const counter = authData.slice(33, 37);
  const aaguid = authData.slice(37, 53);
  const credIdLen = (authData[53] << 8) | authData[54];
  const credId = authData.slice(55, 55 + credIdLen);

  if (!bytesEqual(rpIdHash, await sha256(enc.encode(config.attestAppId)))) {
    throw new Error("rpIdHash is not this app");
  }
  if (!counter.every((b) => b === 0)) throw new Error("attestation counter is not zero");
  if (!bytesEqual(credId, opts.keyId)) throw new Error("credentialId is not the keyId");

  const environment: AttestEnvironment | undefined = bytesEqual(aaguid, AAGUID.production)
    ? "production"
    : bytesEqual(aaguid, AAGUID.development)
    ? "development"
    : undefined;
  if (!environment) throw new Error("unrecognized aaguid");

  return { publicKey, environment };
}

// ── Assertion (renewal) ───────────────────────────────────────────────────────────────────────────

/**
 * Verify an App Attest assertion against a device's previously-attested public key. THROWS on failure.
 *
 * Deliberately NO counter check. Apple's assertion carries a monotonic counter, and the textbook flow
 * persists it — but maintaining it means a read-modify-write per assertion, which a last-write-wins object
 * store cannot do atomically. It buys nothing here anyway: replaying an assertion merely re-mints the same
 * device's token, granting the replayer nothing it did not already hold.
 */
export async function verifyAssertion(opts: {
  assertion: Uint8Array;
  challenge: string;
  publicKey: Uint8Array;
  appId: string;
}): Promise<void> {
  const obj = decodeCBOR(opts.assertion) as Map<string, CBORType>;
  const signature = obj.get("signature") as Uint8Array;
  const authenticatorData = obj.get("authenticatorData") as Uint8Array;
  if (!signature || !authenticatorData) throw new Error("malformed assertion");

  // The device signs SHA256(authenticatorData || SHA256(challenge)) with the attested key.
  const clientDataHash = await sha256(enc.encode(opts.challenge));
  const signedData = await sha256(concat(authenticatorData, clientDataHash));

  const rpIdHash = authenticatorData.slice(0, 32);
  if (!bytesEqual(rpIdHash, await sha256(enc.encode(opts.appId)))) {
    throw new Error("assertion rpIdHash is not this app");
  }

  const key = await crypto.subtle.importKey(
    "raw",
    opts.publicKey as BufferSource,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  // App Attest signs with ECDSA/DER; WebCrypto wants the raw r||s pair.
  const raw = derSignatureToRaw(signature);
  const ok = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    raw as BufferSource,
    signedData as BufferSource,
  );
  if (!ok) throw new Error("assertion signature does not verify against the attested key");
}

/** DER `SEQUENCE { INTEGER r, INTEGER s }` → the raw 64-byte `r||s` WebCrypto expects. */
function derSignatureToRaw(der: Uint8Array): Uint8Array {
  if (der[0] !== 0x30) throw new Error("assertion signature is not DER");
  let i = 2;
  if (der[1] & 0x80) i = 2 + (der[1] & 0x7f); // long-form length
  const readInt = (): Uint8Array => {
    if (der[i] !== 0x02) throw new Error("assertion signature is not DER");
    const len = der[i + 1];
    let v = der.slice(i + 2, i + 2 + len);
    i += 2 + len;
    while (v.length > 32 && v[0] === 0x00) v = v.slice(1); // strip the sign byte
    const padded = new Uint8Array(32);
    padded.set(v, 32 - v.length);
    return padded;
  };
  const r = readInt();
  const s = readInt();
  return concat(r, s);
}
