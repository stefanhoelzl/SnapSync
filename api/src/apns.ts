// APNs provider sender (capability `apns-push-sender`). Token-based (provider JWT) auth: an ES256 JWT
// signed from the `.p8` Auth Key via jose (WebCrypto under the hood, no native dependency), reused within
// its lifetime. Each push is a silent (content-available) background notification sent over HTTP/2 via the
// runtime `fetch` (which ALPN-negotiates h2 — all APNs requires). Per-token failures are isolated and
// reported; the sender never throws out of a batch. No token pruning here (410/BadDeviceToken is reported,
// not acted on).

import { importPKCS8, SignJWT } from "jose";
import type { Config } from "./config.ts";

// Structural fetch type (kept local so app.ts ↔ apns.ts stay import-acyclic).
type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

/** A device push token as stored in `devices/<id>.json`'s `pushToken`. */
export type PushToken = { kind: string; token: string; env: string };

/**
 * Per-token outcome. `sent` = APNs returned 2xx; `skipped` = not sendable (non-`apns` kind or an
 * unrecognized `env`), no request made; `failed` = a request error or an APNs non-2xx rejection.
 */
export type SendOutcome = {
  token: string;
  status: "sent" | "skipped" | "failed";
  code?: number; // APNs HTTP status, when a request was made
  reason?: string;
};

const APNS_HOSTS: Record<string, string> = {
  production: "https://api.push.apple.com",
  sandbox: "https://api.sandbox.push.apple.com",
};

// The silent-wake payload: content-available only — no alert, sound, or badge — plus a top-level
// `eventId` sibling of `aps` naming the event this push concerns (delivered to the app as
// `userInfo["eventId"]`, capability `event-notify-endpoint`), so a receiving device knows which event
// to reconcile.
function silentBody(eventId: string): string {
  return JSON.stringify({ aps: { "content-available": 1 }, eventId });
}

// Refresh the provider JWT well within Apple's 1-hour ceiling (Apple rejects tokens older than 1h and
// throttles re-signing faster than ~20 min; 50 min sits safely between).
const JWT_TTL_MS = 50 * 60 * 1000;

export type ApnsSender = {
  sendSilent(tokens: PushToken[], eventId: string): Promise<SendOutcome[]>;
};

/**
 * Build a sender bound to the APNs credentials in {@link Config}. `fetchImpl` is the upstream fetch
 * (global `fetch` in production; a fake in tests); `now` is injectable for deterministic JWT-reuse tests.
 * The signing key and the current JWT are memoized across sends.
 */
export function createApnsSender(
  config: Config,
  fetchImpl: FetchLike,
  now: () => number = () => Date.now(),
): ApnsSender {
  // jose parses the `.p8` PEM directly (PKCS#8) and its ES256 signer emits the raw r‖s (IEEE-P1363)
  // signature APNs requires. The imported key is memoized across sends.
  let keyPromise: Promise<CryptoKey> | null = null;
  let cached: { jwt: string; at: number } | null = null;

  function signingKey(): Promise<CryptoKey> {
    if (!keyPromise) keyPromise = importPKCS8(config.apnsPrivateKey, "ES256") as Promise<CryptoKey>;
    return keyPromise;
  }

  async function providerJwt(): Promise<string> {
    const t = now();
    if (cached && t - cached.at < JWT_TTL_MS) return cached.jwt;
    const jwt = await new SignJWT({ iss: config.apnsTeamId, iat: Math.floor(t / 1000) })
      .setProtectedHeader({ alg: "ES256", kid: config.apnsKeyId })
      .sign(await signingKey());
    cached = { jwt, at: t };
    return jwt;
  }

  async function sendOne(pt: PushToken, eventId: string): Promise<SendOutcome> {
    if (pt.kind !== "apns") {
      return { token: pt.token, status: "skipped", reason: `kind ${pt.kind}` };
    }
    const host = APNS_HOSTS[pt.env];
    if (!host) return { token: pt.token, status: "skipped", reason: `env ${pt.env}` };

    let jwt: string;
    try {
      jwt = await providerJwt();
    } catch (e) {
      return { token: pt.token, status: "failed", reason: `jwt: ${e}` };
    }
    try {
      const res = await fetchImpl(`${host}/3/device/${pt.token}`, {
        method: "POST",
        headers: {
          authorization: `bearer ${jwt}`,
          "apns-topic": config.apnsTopic,
          "apns-push-type": "background",
          "apns-priority": "5",
          "content-type": "application/json",
        },
        body: silentBody(eventId),
      });
      // Drain any body so the h2 stream is released (APNs replies empty on success, JSON on error).
      await res.body?.cancel();
      return res.ok
        ? { token: pt.token, status: "sent", code: res.status }
        : { token: pt.token, status: "failed", code: res.status };
    } catch (e) {
      return { token: pt.token, status: "failed", reason: `${e}` };
    }
  }

  return {
    // Every token is attempted; one token's error/skip never aborts the others. Never throws.
    sendSilent: (tokens: PushToken[], eventId: string) =>
      Promise.all(tokens.map((pt) => sendOne(pt, eventId))),
  };
}
