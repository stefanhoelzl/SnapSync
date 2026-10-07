// The attestation proofs `/attest/token` and `/attest/renew` accept, and which verifier each goes to
// (capability `privacy-security`). One place, so the two routes cannot disagree about what a proof is.
//
// WHAT TELLS THE PLATFORMS APART, and why it is two different things:
//
//   * AT ATTEST, the proof says what it is: the body carries `proof.format`. That only CHOOSES the
//     verifier — a proof claiming a format it is not fails that verifier — so the claim is never trusted,
//     only tried.
//   * AT RENEW, the request says nothing: the stored row's `attest_platform` decides, which is what the
//     attestation PROVED. The renewing client has no claim that could disagree with it.

import { b64ToBytes, verifyAssertion, verifyAttestation } from "./attest.ts";
import {
  challengeDigest,
  verifyAndroidAttestation,
  verifyAndroidSignature,
} from "./android-attest.ts";
import type { Config } from "./config.ts";
import type { AttestPlatform, DeviceAttestation } from "./db.ts";
import { validateUUID } from "./validators.ts";

/** A proof as the request carried it, still encoded: a malformed one is refused by its verifier. */
export type MintProof =
  | { format: "apple-appattest"; keyId: string; attestation: string }
  | { format: "android-key"; chain: string[] };

export type MintBody = { deviceId: string; challenge: string; proof: MintProof };

const isString = (v: unknown): v is string => typeof v === "string" && v.length > 0;

function typedProof(proof: unknown): MintProof | null {
  const p = proof as Record<string, unknown> | null;
  if (p?.format === "apple-appattest" && isString(p.keyId) && isString(p.attestation)) {
    return { format: "apple-appattest", keyId: p.keyId, attestation: p.attestation };
  }
  if (
    p?.format === "android-key" && Array.isArray(p.chain) && p.chain.length > 0 &&
    p.chain.every(isString)
  ) {
    return { format: "android-key", chain: p.chain };
  }
  return null;
}

/** The mint request, `{deviceId, challenge, proof}`, or `null` — a `400`. */
export function parseMintBody(body: unknown): MintBody | null {
  const b = body as Record<string, unknown> | null;
  if (!isString(b?.deviceId) || !validateUUID(b.deviceId) || !isString(b.challenge)) return null;
  const proof = typedProof(b.proof);
  return proof ? { deviceId: b.deviceId, challenge: b.challenge, proof } : null;
}

/** What an accepted attestation records: the key renewal will verify against, and what proved it. */
export type VerifiedProof = {
  publicKey: Uint8Array;
  platform: AttestPlatform;
  environment: string;
};

type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

/**
 * Verify [proof] over [challenge] with the verifier its format names. THROWS when refused; a
 * `RevocationUnavailable` (Android's status list could not be fetched) is "could not look", a `502`.
 */
export async function verifyMintProof(
  config: Config,
  proof: MintProof,
  challenge: string,
  at: Date,
  fetch: FetchLike,
): Promise<VerifiedProof> {
  if (proof.format === "apple-appattest") {
    const verified = await verifyAttestation(config, {
      attestation: b64ToBytes(proof.attestation),
      challenge,
      keyId: b64ToBytes(proof.keyId),
      at,
    });
    return { publicKey: verified.publicKey, platform: "ios", environment: verified.environment };
  }
  const verified = await verifyAndroidAttestation(config, {
    chain: proof.chain.map(b64ToBytes),
    expectedChallenge: await challengeDigest(challenge),
    at,
    fetch,
  });
  return {
    publicKey: verified.publicKey,
    platform: "android",
    environment: verified.securityLevel,
  };
}

/**
 * Verify a renewal's [assertion] against the device's stored attestation, by the platform that attestation
 * was PROVEN on. THROWS when refused.
 */
export async function verifyRenewal(
  config: Config,
  record: DeviceAttestation,
  assertion: string,
  challenge: string,
): Promise<void> {
  const publicKey = b64ToBytes(record.publicKey);
  if (record.platform === "ios") {
    await verifyAssertion({
      assertion: b64ToBytes(assertion),
      challenge,
      publicKey,
      appId: config.attestAppId,
    });
    return;
  }
  await verifyAndroidSignature({ signature: b64ToBytes(assertion), challenge, publicKey });
}
