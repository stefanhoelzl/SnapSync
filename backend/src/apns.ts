// APNs provider sender (capability `apns-push-sender`). Token-based (provider JWT) auth: an ES256 JWT
// signed from the `.p8` Auth Key with WebCrypto (no native dependency), reused within its lifetime.
// Each push is a silent (content-available) background notification sent over HTTP/2 via the runtime
// `fetch` (which ALPN-negotiates h2 — all APNs requires). Per-token failures are isolated and reported;
// the sender never throws out of a batch. No token pruning here (410/BadDeviceToken is reported, not acted on).

import type { Config } from "./config.ts";

// Structural fetch type (kept local so app.ts ↔ apns.ts stay import-acyclic).
type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

/** A device push token as stored in `devices/<id>/config.json`'s `pushToken`. */
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

function base64Url(bytes: Uint8Array): string {
  let bin = "";
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function base64UrlJson(obj: unknown): string {
  return base64Url(new TextEncoder().encode(JSON.stringify(obj)));
}

// Decode a PKCS#8 `.p8` PEM to its DER bytes (strip the header/footer + whitespace, base64-decode).
function pemToDer(pem: string): Uint8Array {
  const body = pem.split("\n").filter((l) => !l.includes("-----")).join("").replace(/\s+/g, "");
  const bin = atob(body);
  const der = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) der[i] = bin.charCodeAt(i);
  return der;
}

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
  let keyPromise: Promise<CryptoKey> | null = null;
  let cached: { jwt: string; at: number } | null = null;

  function signingKey(): Promise<CryptoKey> {
    if (!keyPromise) {
      keyPromise = crypto.subtle.importKey(
        "pkcs8",
        pemToDer(config.apnsPrivateKey) as BufferSource,
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["sign"],
      );
    }
    return keyPromise;
  }

  async function providerJwt(): Promise<string> {
    const t = now();
    if (cached && t - cached.at < JWT_TTL_MS) return cached.jwt;
    const iat = Math.floor(t / 1000);
    const header = base64UrlJson({ alg: "ES256", kid: config.apnsKeyId });
    const claims = base64UrlJson({ iss: config.apnsTeamId, iat });
    const signingInput = `${header}.${claims}`;
    // WebCrypto ECDSA yields the raw r‖s (IEEE-P1363) signature — exactly JWT ES256's encoding.
    const sig = await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      await signingKey(),
      new TextEncoder().encode(signingInput) as BufferSource,
    );
    const jwt = `${signingInput}.${base64Url(new Uint8Array(sig))}`;
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
