// The token ISSUERS (capability `privacy-security`): the stateless challenge, the mint and the renewal — the
// only device-API routes the token gate admits without a token, because they are what issue it.

import { Hono } from "hono";
import { RevocationUnavailable } from "../android-attest.ts";
import {
  bytesToB64,
  challengeIsValid,
  mintChallenge,
  mintToken,
  refusalDetail,
  refusalReason,
  tokenExpiryIso,
} from "../attest.ts";
import { parseMintBody, verifyMintProof, verifyRenewal } from "../attest-proofs.ts";
import { putAttestation, readAttestation, touchTokenExpiry } from "../db.ts";
import { validateUUID } from "../validators.ts";
import {
  declaredAppVersion,
  NO_CACHE,
  readJson,
  type RouteDeps,
  tryUpstream,
  upstream502,
} from "./support.ts";

// The token ISSUERS (capability `privacy-security`). A stale challenge is `409 stale challenge`, never `401`:
// `401` means "your credential is rejected" and a stale challenge rejects no credential — it is what let a
// client read a renewal's expired challenge as a revoked token. Decision record: harden-seam-bug-classes.
//
// The MINT BODY carries a typed `proof` whose `format` names its verifier, which is how an Android key
// attestation reaches its own (`attest-proofs.ts`). (v1's flat App Attest body and its `401` went with v1,
// `changes/separate-event-page-from-device-api`.)
export function attestRoutes({ config, db, now, revocationFetch }: RouteDeps): Hono {
  const issuers = new Hono();

  // Issue a challenge. Stateless and self-authenticating (an HMAC over its own expiry), so this writes
  // NOTHING — the one route a stranger can call cannot grow the bill this gate exists to protect.
  issuers.get("/attest/challenge", async (c) => {
    c.header("Cache-Control", NO_CACHE);
    return c.json({ challenge: await mintChallenge(config, now()) });
  });

  // Attest: verify the attestation, persist the attested public key, mint a token.
  issuers.post("/attest/token", async (c) => {
    const json = await readJson(c);
    if (json instanceof Response) return json;
    const body = parseMintBody(json.body);
    if (!body) return c.text("invalid body", 400);
    const { deviceId, challenge, proof } = body;
    if (!await challengeIsValid(config, challenge, now())) {
      return c.text("stale challenge", 409);
    }

    let verified;
    try {
      verified = await verifyMintProof(
        config,
        proof,
        challenge,
        new Date(now()),
        revocationFetch,
      );
    } catch (e) {
      // Android's revocation list could not be fetched: "could not look", which the client retries —
      // never the 401 that would send it down a fresh attestation for a verdict nobody reached.
      if (e instanceof RevocationUnavailable) {
        return upstream502(c, `attest: ${deviceId}`, e.message);
      }
      const reason = refusalReason(e);
      const detail = refusalDetail(e);
      // The detail is diagnostics only: the app logs and reports it, and never tells the user.
      const named = detail ? `${reason} (${detail})` : reason;
      console.error(
        `attest: ${proof.format} attestation rejected for ${deviceId} (${named}): ${e}`,
      );
      // The refusal names the REASON, so the app can tell its user why (capability `privacy-security`, "A refused
      // phone is told why"). Every shipped client reads any `401` body it does not know as a refusal, so the suffix
      // changes nothing for them. The precise check stays in the log line above.
      return c.text(`attestation rejected: ${named}`, 401);
    }

    // Persist the attested key so RENEWAL can verify a cheap local assertion against it instead of
    // forcing a fresh attestation — which is the throttled path, and which would make renewal too
    // expensive to attempt at every wake. This INSERT is also the device's enrolment: a `devices` row
    // exists if and only if the device has attested, and this is the only route that creates one.
    //
    // PERSIST BEFORE MINTING. A token handed out against a record we failed to write is a credential
    // nothing knows about; the client retries at its next wake, so refusing costs nothing.
    const persisted = await tryUpstream(
      c,
      `attest: could not persist the attestation record for ${deviceId}`,
      () =>
        putAttestation(
          db,
          deviceId,
          {
            publicKey: bytesToB64(verified.publicKey),
            platform: verified.platform,
            environment: verified.environment,
          },
          new Date(now()).toISOString(),
          tokenExpiryIso(config, now()),
          declaredAppVersion(c),
        ),
    );
    if (persisted instanceof Response) return persisted;

    console.info(`attest: ${deviceId} attested (${verified.platform}, ${verified.environment})`);
    return c.json({ token: await mintToken(config, deviceId, now()) }, 201);
  });

  // Renew: verify an assertion against the stored key, mint a fresh token. No Apple round-trip, so this
  // is cheap enough for the app to attempt at EVERY wake rather than in a narrow window near expiry.
  issuers.post("/attest/renew", async (c) => {
    const json = await readJson(c);
    if (json instanceof Response) return json;
    const { deviceId, assertion, challenge } = json.body as {
      deviceId?: string;
      assertion?: string;
      challenge?: string;
    };
    if (!deviceId || !validateUUID(deviceId) || !assertion || !challenge) {
      return c.text("invalid body", 400);
    }
    if (!await challengeIsValid(config, challenge, now())) {
      return c.text("stale challenge", 409);
    }

    // Absence and "could not ask" are DIFFERENT answers here and must not collapse: absence sends the
    // device down a full Apple attestation, which is the throttled path, so a database blink must read
    // as retry-me and not as attest-again.
    const record = await tryUpstream(
      c,
      `renew: could not read the attestation record for ${deviceId}`,
      () => readAttestation(db, deviceId),
    );
    if (record instanceof Response) return record;
    if (record === null) {
      // Two causes, one answer, and the log keeps them apart: a device the backend has never seen, or one
      // whose row the nightly sweep collected. Both mean "attest afresh", which is what the client does.
      console.info(
        `renew: no attestation on file for ${deviceId} — never attested, or collected`,
      );
      return c.text("not attested", 401);
    }

    try {
      await verifyRenewal(config, record, assertion, challenge);
    } catch (e) {
      console.error(`renew: ${record.platform} assertion rejected for ${deviceId}: ${e}`);
      return c.text("assertion rejected", 401);
    }

    // RECORD THE NEW EXPIRY BEFORE MINTING, exactly as `/attest/token` persists before minting. The sweep
    // decides whether this device may still hold a working credential from this value; minting first and
    // writing after would leave the store understating the token's life, and the sweep would then collect
    // a device that is still using it — costing it a full re-attestation.
    const touched = await tryUpstream(
      c,
      `renew: could not record the token expiry for ${deviceId}`,
      () => touchTokenExpiry(db, deviceId, tokenExpiryIso(config, now())),
    );
    if (touched instanceof Response) return touched;
    if (touched.rowsAffected === 0) {
      // The row went away between the read and this write. Nothing to renew against.
      console.info(`renew: the attestation record for ${deviceId} vanished mid-renewal`);
      return c.text("not attested", 401);
    }

    return c.json({ token: await mintToken(config, deviceId, now()) }, 201);
  });
  return issuers;
}
