// Android device attestation (capability `privacy-security`): Android Keystore KEY ATTESTATION, the
// counterpart of App Attest in `attest.ts`, in the same flow shape — attest ONCE, persist the attested
// public key, renew with a cheap local signature check. No Google API is called: the chain is verified
// against pinned roots, and the revocation list is a PUBLIC, unauthenticated fetch, cached.
//
// A hand-written verifier is where the known attacks land, so this one follows Google's own
// (github.com/android/keyattestation, `KeyAttestationCertPathValidator`) rule for rule:
//
//   * THE CHAIN, leaf first and root last: every certificate named by and signed by the next, and the root
//     one of the deployment's pinned roots — matched by PUBLIC KEY, so a root re-issued over the same key
//     (Google's RSA root has four certificates, and devices still present the older ones) needs no config.
//   * ITS SHAPE follows from how the device was provisioned, read off the certificate below the root: a
//     remotely provisioned (RKP) chain is root → "Droid CA2" → RKP server → attestation → key; a factory one
//     root → a serial-numbered intermediate → attestation → key; a software one root → attestation → key.
//     A chain any longer is refused: an attacker holding an attested key can sign one more certificate.
//   * ONE ATTESTATION EXTENSION, IN THE LEAF, and in no other certificate — the same attack, with a forged
//     extension in the appended certificate.
//   * VALIDITY: the leaf's dates are set on the device, so they are not checked. Every other certificate's
//     are — except that EXPIRY is ignored on a factory-provisioned chain, whose keys cannot be rotated
//     (Google: "Devices launched before 2021 have attestation keys with expired certificates. These keys
//     are still trustworthy unless they appear in the certificate revocation list"). An RKP chain's short
//     life is part of its threat model, so there expiry IS enforced.
//   * REVOCATION: every serial in the chain against Google's status list; `SUSPENDED` counts as revoked.
//
// Then the leaf's key description: the challenge is ours, the app is ours (package name and signing
// certificate digest), the key lives in hardware, and the device boots a verified OS behind a locked
// bootloader. How strict those last three are is the DEPLOYMENT's (`androidAttestationTrust`):
// `hardware` for anything deployed — the resolver refuses anything else there — and `any` for the local
// rig, which proves nothing, on purpose. Under `any` the root is not pinned either, because the emulator's
// cannot be: measured 2026-09-29 on the API 36 `google_apis` image, its KeyMint attests in SOFTWARE
// (security level 0, bootloader unlocked, boot unverified) under a self-signed "Droid Unregistered Device
// CA, O=Google Test LLC" root minted with the AVD, valid for ~10 weeks, its intermediate for ~2 — so every
// fresh AVD, and every CI run, brings a new one. Every other check still runs.

import * as x509 from "@peculiar/x509";
import { bytesEqual, derSignatureToRaw } from "./attest.ts";
import type { Config } from "./config.ts";

/** Where Android puts the key description. */
const KEY_ATTESTATION_OID = "1.3.6.1.4.1.11129.2.1.17";

/** Google's public attestation status list (revocations): unauthenticated, cacheable. */
export const REVOCATION_LIST_URL = "https://android.googleapis.com/attestation/status";

/** Where the Keystore keeps the key, as the key description reports it. */
export type AndroidSecurityLevel = "software" | "tee" | "strongbox";

export type VerifiedAndroidAttestation = {
  /** The attested public key, as a raw uncompressed P-256 point — the form an App Attest key is stored in. */
  publicKey: Uint8Array;
  securityLevel: AndroidSecurityLevel;
};

/** The revocation list could not be consulted — a `502` (retry), never a `401` (attest again). */
export class RevocationUnavailable extends Error {}

type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

// ── DER, as much as a key description needs ─────────────────────────────────────────────────────────

/** One DER element: its tag (class/constructed bits and number folded into one integer) and contents. */
type Der = { tag: number; value: Uint8Array };

/** Tag of a context-specific constructed element `[n]` — how an AuthorizationList names its fields. */
const ctx = (n: number): number => 0xa0_0000 + n;

const SEQUENCE = 0x30;
const SET = 0x31;
const OCTET_STRING = 0x04;
const ENUMERATED = 0x0a;
const BOOLEAN = 0x01;

/** Every element directly inside `bytes`, in order. Throws on anything malformed. */
function derElements(bytes: Uint8Array): Der[] {
  const out: Der[] = [];
  let i = 0;
  while (i < bytes.length) {
    const first = bytes[i++];
    let tag = first;
    if ((first & 0x1f) === 0x1f) {
      // High tag number: base-128, most significant first. Folded as class bits + number.
      let n = 0;
      let b;
      do {
        if (i >= bytes.length) throw new Error("truncated DER tag");
        b = bytes[i++];
        n = n * 128 + (b & 0x7f);
      } while (b & 0x80);
      tag = (first & 0xe0) === 0xa0 ? ctx(n) : (first << 16) + n;
    } else if ((first & 0xe0) === 0xa0) {
      tag = ctx(first & 0x1f);
    }
    if (i >= bytes.length) throw new Error("truncated DER length");
    let len = bytes[i++];
    if (len & 0x80) {
      const count = len & 0x7f;
      if (count === 0 || count > 4 || i + count > bytes.length) throw new Error("bad DER length");
      len = 0;
      for (let k = 0; k < count; k++) len = len * 256 + bytes[i++];
    }
    if (i + len > bytes.length) throw new Error("truncated DER value");
    out.push({ tag, value: bytes.slice(i, i + len) });
    i += len;
  }
  return out;
}

function expect(el: Der | undefined, tag: number, what: string): Uint8Array {
  if (!el || el.tag !== tag) throw new Error(`key description: ${what} is missing or mistyped`);
  return el.value;
}

/** A small non-negative INTEGER/ENUMERATED — every one a key description carries fits a number. */
function smallInt(value: Uint8Array): number {
  if (value.length === 0 || value.length > 6) {
    throw new Error("key description: integer out of range");
  }
  return value.reduce((n, b) => n * 256 + b, 0);
}

// ── The key description ─────────────────────────────────────────────────────────────────────────────

type KeyDescription = {
  attestationSecurityLevel: number;
  attestationChallenge: Uint8Array;
  softwareEnforced: Map<number, Uint8Array>;
  hardwareEnforced: Map<number, Uint8Array>;
};

/** An AuthorizationList, as its explicitly tagged fields' contents. A repeated tag is refused. */
function authorizationList(value: Uint8Array): Map<number, Uint8Array> {
  const fields = new Map<number, Uint8Array>();
  for (const el of derElements(value)) {
    if (fields.has(el.tag)) throw new Error("key description: an authorization appears twice");
    fields.set(el.tag, el.value);
  }
  return fields;
}

/** `KeyDescription ::= SEQUENCE { attestationVersion, attestationSecurityLevel, keyMintVersion, … }`. */
function keyDescription(extension: Uint8Array): KeyDescription {
  const [outer] = derElements(extension);
  const f = derElements(expect(outer, SEQUENCE, "the key description"));
  return {
    attestationSecurityLevel: smallInt(expect(f[1], ENUMERATED, "attestationSecurityLevel")),
    attestationChallenge: expect(f[4], OCTET_STRING, "attestationChallenge"),
    softwareEnforced: authorizationList(expect(f[6], SEQUENCE, "softwareEnforced")),
    hardwareEnforced: authorizationList(expect(f[7], SEQUENCE, "hardwareEnforced")),
  };
}

/** `AuthorizationList` tags this verifier reads. */
const ROOT_OF_TRUST = ctx(704);
const ATTESTATION_APPLICATION_ID = ctx(709);

const SECURITY_LEVELS: Record<number, AndroidSecurityLevel> = {
  0: "software",
  1: "tee",
  2: "strongbox",
};

/** `VerifiedBootState`: Verified (0) and SelfSigned (1) boot an OS whose image the bootloader checked. */
const BOOT_VERIFIED = 0;
const BOOT_SELF_SIGNED = 1;

/**
 * `RootOfTrust ::= SEQUENCE { verifiedBootKey, deviceLocked BOOLEAN, verifiedBootState, … }` → whether
 * the device boots a verified OS behind a locked bootloader. `SelfSigned` is a locked bootloader with a key
 * the owner installed (GrapheneOS and its kin): the OS is still verified, so the app-identity claims the OS
 * makes in the attestation still hold — which is not true of an UNLOCKED bootloader, where the OS that
 * asked the TEE could claim any package and digest.
 */
function bootsVerifiedAndLocked(rootOfTrust: Uint8Array): boolean {
  const [outer] = derElements(rootOfTrust);
  const f = derElements(expect(outer, SEQUENCE, "rootOfTrust"));
  const locked = expect(f[1], BOOLEAN, "deviceLocked");
  const state = smallInt(expect(f[2], ENUMERATED, "verifiedBootState"));
  return locked.length === 1 && locked[0] !== 0 &&
    (state === BOOT_VERIFIED || state === BOOT_SELF_SIGNED);
}

/**
 * `AttestationApplicationId ::= SEQUENCE { package_infos SET OF SEQUENCE { name OCTET STRING, version
 * INTEGER }, signature_digests SET OF OCTET STRING }` — carried as an OCTET STRING holding its DER.
 */
function applicationId(field: Uint8Array): { packages: string[]; digests: Uint8Array[] } {
  const [wrapped] = derElements(field);
  const [outer] = derElements(expect(wrapped, OCTET_STRING, "attestationApplicationId"));
  const f = derElements(expect(outer, SEQUENCE, "attestationApplicationId"));
  const packages = derElements(expect(f[0], SET, "package_infos")).map((p) => {
    const [name] = derElements(expect(p, SEQUENCE, "a package info"));
    return new TextDecoder().decode(expect(name, OCTET_STRING, "a package name"));
  });
  const digests = derElements(expect(f[1], SET, "signature_digests"))
    .map((d) => expect(d, OCTET_STRING, "a signature digest"));
  return { packages, digests };
}

// ── The chain ───────────────────────────────────────────────────────────────────────────────────────

type Provisioning = "rkp" | "factory" | "software";

/** A chain's certificate count, root included, per provisioning method (Google's `Step` machine). */
const CHAIN_LENGTH: Record<Provisioning, number> = { rkp: 5, factory: 4, software: 3 };

/** How the device was provisioned, read off the certificate just below the root. */
function provisioning(chain: x509.X509Certificate[]): Provisioning {
  const below = chain.length >= 2 ? chain[chain.length - 2] : undefined;
  const name = below?.subjectName;
  if (name?.getField("CN").includes("Droid CA2") && name.getField("O").includes("Google LLC")) {
    return "rkp";
  }
  // `serialNumber` (2.5.4.5) is known to the name parser by its OID only.
  if (name && name.getField("2.5.4.5").length > 0) return "factory";
  return "software";
}

/** The pinned root `candidate` is, by public key — or `undefined`. */
function pinned(candidate: x509.X509Certificate, roots: x509.X509Certificate[]): boolean {
  const key = new Uint8Array(candidate.publicKey.rawData);
  return roots.some((r) => bytesEqual(new Uint8Array(r.publicKey.rawData), key));
}

/** Serial number as the status list keys it: lowercase hex, no leading zeros. */
const statusSerial = (cert: x509.X509Certificate): string =>
  cert.serialNumber.toLowerCase().replace(/^0+(?=.)/, "");

async function verifyChain(
  chain: x509.X509Certificate[],
  config: Config,
  at: Date,
): Promise<Provisioning> {
  const method = provisioning(chain);
  if (chain.length !== CHAIN_LENGTH[method]) {
    throw new Error(
      `a ${method} chain has ${CHAIN_LENGTH[method]} certificates, this one ${chain.length}`,
    );
  }
  const root = chain[chain.length - 1];
  if (config.androidAttestationTrust === "hardware") {
    if (!pinned(root, config.androidAttestationRoots.map((pem) => new x509.X509Certificate(pem)))) {
      throw new Error("the chain does not end at a pinned attestation root");
    }
  } else if (root.issuer !== root.subject || !await root.verify({ signatureOnly: true })) {
    // Under `any` the anchor is not compared — the emulator's is a per-AVD "Google Test LLC" root that
    // lives for weeks (measured 2026-09-29) — but the chain must still end at a root that signs itself.
    throw new Error("the chain does not end at a self-signed root");
  }
  for (let i = 0; i < chain.length - 1; i++) {
    const cert = chain[i];
    const issuer = chain[i + 1];
    if (cert.issuer !== issuer.subject) {
      throw new Error(`certificate ${i} is not named by its issuer`);
    }
    if (!await cert.verify({ publicKey: issuer.publicKey, signatureOnly: true })) {
      throw new Error(`certificate ${i} is not signed by its issuer`);
    }
    if (i > 0 && cert.getExtension(KEY_ATTESTATION_OID)) {
      throw new Error(`certificate ${i} carries an attestation extension: only the leaf may`);
    }
  }
  // The leaf's dates are the device's; every other certificate's are checked, expiry excepted on a
  // factory chain.
  for (const cert of chain.slice(1)) {
    if (at < cert.notBefore) throw new Error("a certificate in the chain is not yet valid");
    if (at > cert.notAfter && method !== "factory") {
      throw new Error("a certificate in the chain has expired");
    }
  }
  return method;
}

// ── Revocation ──────────────────────────────────────────────────────────────────────────────────────

/** This isolate's copy of the status list, until the response's `max-age` runs out. */
let revocations: { revoked: Set<string>; until: number } | undefined;

/** How long a status list without a `max-age` is kept. */
const DEFAULT_REVOCATION_TTL_MS = 60 * 60 * 1000;

/** Forget the cached status list — for tests, which each bring their own. */
export function forgetRevocationList(): void {
  revocations = undefined;
}

async function revokedSerials(fetch: FetchLike, nowMs: number): Promise<Set<string>> {
  if (revocations && nowMs < revocations.until) return revocations.revoked;
  let response: Response;
  let body: { entries?: Record<string, { status?: string }> };
  try {
    response = await fetch(REVOCATION_LIST_URL, { method: "GET" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    body = await response.json();
  } catch (e) {
    throw new RevocationUnavailable(`the attestation status list could not be fetched: ${e}`);
  }
  const revoked = new Set(
    Object.entries(body.entries ?? {})
      .filter(([, entry]) => entry?.status === "REVOKED" || entry?.status === "SUSPENDED")
      .map(([serial]) => serial.toLowerCase()),
  );
  const maxAge = /max-age=(\d+)/.exec(response.headers.get("cache-control") ?? "")?.[1];
  revocations = {
    revoked,
    until: nowMs + (maxAge ? Number(maxAge) * 1000 : DEFAULT_REVOCATION_TTL_MS),
  };
  return revoked;
}

// ── The attestation ─────────────────────────────────────────────────────────────────────────────────

const sha256 = async (b: Uint8Array): Promise<Uint8Array> =>
  new Uint8Array(await crypto.subtle.digest("SHA-256", b as BufferSource));

/** `AA:BB:…` (the assetlinks form the deployment declares) → bytes. */
export const digestBytes = (hex: string): Uint8Array =>
  new Uint8Array(hex.split(":").map((b) => parseInt(b, 16)));

/**
 * What the Android adapter puts in a key's attestation challenge for the server's `challenge`: its SHA-256
 * over the UTF-8 bytes — the adapter hashes it itself, as the `DeviceIntegrity` port's contract says.
 */
export const challengeDigest = async (challenge: string): Promise<Uint8Array> =>
  await sha256(new TextEncoder().encode(challenge));

/**
 * Verify an Android key attestation — `chain` as the device's Keystore returned it, leaf first — against
 * `expectedChallenge` (the {@link challengeDigest} of ours; a parameter so a test can hand a recorded
 * chain's own). THROWS on any failure; a {@link RevocationUnavailable} means "could not look", every
 * other error "refused".
 *
 * `at` is the instant the chain's validity is judged at (production passes now; tests pin it, so a
 * recorded chain whose RKP certificates have since expired can still be verified).
 */
export async function verifyAndroidAttestation(
  config: Config,
  opts: { chain: Uint8Array[]; expectedChallenge: Uint8Array; at: Date; fetch: FetchLike },
): Promise<VerifiedAndroidAttestation> {
  const chain = opts.chain.map((der) => new x509.X509Certificate(der as BufferSource));
  await verifyChain(chain, config, opts.at);
  const revoked = await revokedSerials(opts.fetch, opts.at.getTime());
  if (chain.some((c) => revoked.has(statusSerial(c)))) {
    throw new Error("a certificate in the chain is revoked");
  }

  const leaf = chain[0];
  const extension = leaf.getExtension(KEY_ATTESTATION_OID);
  if (!extension) throw new Error("the leaf carries no key attestation");
  const description = keyDescription(new Uint8Array(extension.value));

  // 1. The challenge is ours.
  if (!bytesEqual(description.attestationChallenge, opts.expectedChallenge)) {
    throw new Error("the attestation challenge is not ours");
  }

  // 2. Where the key lives.
  const securityLevel = SECURITY_LEVELS[description.attestationSecurityLevel];
  if (!securityLevel) throw new Error("unknown attestation security level");
  const hardware = config.androidAttestationTrust === "hardware";
  if (hardware && securityLevel === "software") {
    throw new Error("a software attestation proves nothing");
  }

  // 3. The device: a verified OS behind a locked bootloader, as the TEE saw it at boot.
  if (hardware) {
    const rootOfTrust = description.hardwareEnforced.get(ROOT_OF_TRUST);
    if (!rootOfTrust || !bootsVerifiedAndLocked(rootOfTrust)) {
      throw new Error("the device does not boot a verified OS behind a locked bootloader");
    }
  }

  // 4. The app: our package, signed with a certificate this deployment accepts.
  const appField = description.softwareEnforced.get(ATTESTATION_APPLICATION_ID);
  if (!appField) throw new Error("the attestation names no application");
  const app = applicationId(appField);
  if (!app.packages.includes(config.androidPackageName)) {
    throw new Error("the attestation is not this app's");
  }
  if (hardware) {
    const accepted = config.androidSigningCertDigests.map(digestBytes);
    if (!app.digests.some((d) => accepted.some((a) => bytesEqual(a, d)))) {
      throw new Error("the app is not signed with a certificate this deployment accepts");
    }
  }

  // The attested key: P-256, stored as the raw point, exactly as an App Attest key is.
  const spki = new Uint8Array(leaf.publicKey.rawData);
  const publicKey = spki.slice(spki.length - 65);
  if (publicKey[0] !== 0x04 || spki.length !== 91) {
    throw new Error("the attested key is not a P-256 key");
  }
  return { publicKey, securityLevel };
}

/**
 * Verify a renewal: an ECDSA P-256/SHA-256 signature, DER as the Keystore produces it, over the challenge's
 * UTF-8 bytes, by the attested key. THROWS on failure. No counter, for `verifyAssertion`'s reason: a
 * replay re-mints the same device's token and grants nothing.
 */
export async function verifyAndroidSignature(opts: {
  signature: Uint8Array;
  challenge: string;
  publicKey: Uint8Array;
}): Promise<void> {
  const key = await crypto.subtle.importKey(
    "raw",
    opts.publicKey as BufferSource,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  const ok = await crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    derSignatureToRaw(opts.signature) as BufferSource,
    new TextEncoder().encode(opts.challenge) as BufferSource,
  );
  if (!ok) throw new Error("the renewal signature does not verify against the attested key");
}
