// APNs provider sender (capability `receiving-photos`). Token-based (provider JWT) auth: an ES256 JWT
// signed from the `.p8` Auth Key via jose (WebCrypto under the hood, no native dependency), reused within
// its lifetime. Each push is a silent (content-available) background notification sent over HTTP/2 via the
// runtime `fetch` (which ALPN-negotiates h2 — all APNs requires). Per-token failures are isolated and
// reported; the sender never throws out of a batch. No token pruning here (410/BadDeviceToken is reported,
// not acted on).

import { importPKCS8, SignJWT } from "jose";
import type { Config } from "./config.ts";
import {
  type FetchLike,
  postPush,
  type PushToken,
  type SendOutcome,
  type SilentSender,
  silentSender,
} from "./push-send.ts";

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

export type ApnsSender = SilentSender;

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
    return await postPush(fetchImpl, pt.token, `${host}/3/device/${pt.token}`, {
      method: "POST",
      headers: {
        authorization: `bearer ${jwt}`,
        "apns-topic": config.apnsTopic,
        "apns-push-type": "background",
        // 5 is not a choice: the background push type requires it. 1 deprioritises further and 10 is
        // refused for this type, so priority is no lever for improving delivery.
        "apns-priority": "5",
        // COALESCE BY EVENT. Two wakes for one event are interchangeable by construction — a wake
        // carries only its event id, and a recipient answers by reconciling that event's whole union
        // (capability `receiving-photos`) — so collapsing undelivered ones loses no information. It buys
        // real headroom: Apple throttles background notifications on total volume and documents a
        // ceiling of two or three per hour, so a burst that would spend several deliveries spends one.
        // Per-ASSET would be the mistake: it preserves a distinction no recipient reads, at a delivery
        // each.
        "apns-collapse-id": eventId,
        "content-type": "application/json",
      },
      body: silentBody(eventId),
    });
  }

  return silentSender(sendOne);
}
