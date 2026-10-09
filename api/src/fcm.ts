// FCM HTTP v1 sender (capability `delivery`) — the Android counterpart of `apns.ts`. Each push is a
// high-priority DATA message carrying only the event id, so a receiving device reconciles that event in the
// background; there is no notification block, so nothing is shown. Auth is Google's service-account flow: an
// RS256 JWT signed with jose from the service account's private key, exchanged at the OAuth token endpoint for an
// access token, reused within its lifetime. Per-token failures are isolated and reported; the sender never throws
// out of a batch. No token pruning here (an UNREGISTERED answer is reported, not acted on).

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

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
const ASSERTION_LIFETIME_S = 3600;
// Google's access tokens live an hour; refresh well inside it, as the APNs provider JWT is.
const ACCESS_TOKEN_TTL_MS = 50 * 60 * 1000;

export type FcmSender = SilentSender;

/** The two fields of a service-account key file the flow needs. */
type ServiceAccount = { clientEmail: string; privateKey: string };

/** The service account from its JSON key file, or `null` when absent or not a key file. */
function serviceAccount(raw: string): ServiceAccount | null {
  if (raw.trim() === "") return null;
  try {
    const json = JSON.parse(raw) as { client_email?: unknown; private_key?: unknown };
    if (typeof json.client_email !== "string" || typeof json.private_key !== "string") return null;
    return { clientEmail: json.client_email, privateKey: json.private_key };
  } catch {
    return null;
  }
}

/**
 * The FCM message for [eventId]: data only (the app handles it itself, nothing is displayed), HIGH priority so it
 * can wake a dozing phone, and collapsed per event — two wakes for one event are interchangeable, exactly as the APNs
 * collapse id treats them.
 */
export function fcmBody(token: string, eventId: string, seq?: number): string {
  // A wake for a photo names the union position it announces (decision record `changes/incremental-union`,
  // D6). FCM data values are strings, so it rides as one; the close wake carries none.
  const data = seq === undefined ? { eventId } : { eventId, seq: String(seq) };
  return JSON.stringify({
    message: { token, data, android: { priority: "HIGH", collapse_key: eventId } },
  });
}

/**
 * Build a sender bound to the FCM fields of {@link Config}. A token is sent only when its `kind` is `fcm` and its
 * `env` names this deployment's Firebase project (`fcmProjectId`) — an FCM token is bound to the project that issued
 * it. Without a service-account key every FCM token is `skipped`: the backend still boots and serves, and Android
 * members simply get no wake (their photos arrive on the next opening).
 */
export function createFcmSender(
  config: Pick<Config, "fcmProjectId" | "fcmServiceAccountKey">,
  fetchImpl: FetchLike,
  now: () => number = () => Date.now(),
): FcmSender {
  const account = serviceAccount(config.fcmServiceAccountKey);
  let keyPromise: Promise<CryptoKey> | null = null;
  // The exchange in flight or done, and when it started: concurrent sends share one exchange, and a failed one is
  // forgotten so the next send tries again.
  let cached: { token: Promise<string>; at: number } | null = null;

  function accessToken(acct: ServiceAccount): Promise<string> {
    const t = now();
    if (cached && t - cached.at < ACCESS_TOKEN_TTL_MS) return cached.token;
    const token = exchange(acct, t);
    const entry = { token, at: t };
    cached = entry;
    token.catch(() => {
      if (cached === entry) cached = null;
    });
    return token;
  }

  async function exchange(acct: ServiceAccount, t: number): Promise<string> {
    if (!keyPromise) keyPromise = importPKCS8(acct.privateKey, "RS256") as Promise<CryptoKey>;
    const iat = Math.floor(t / 1000);
    const assertion = await new SignJWT({ scope: SCOPE })
      .setProtectedHeader({ alg: "RS256", typ: "JWT" })
      .setIssuer(acct.clientEmail)
      .setAudience(TOKEN_URL)
      .setIssuedAt(iat)
      .setExpirationTime(iat + ASSERTION_LIFETIME_S)
      .sign(await keyPromise);
    const res = await fetchImpl(TOKEN_URL, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
        assertion,
      }).toString(),
    });
    if (!res.ok) {
      await res.body?.cancel();
      throw new Error(`token exchange answered ${res.status}`);
    }
    const json = await res.json() as { access_token?: unknown };
    if (typeof json.access_token !== "string") {
      throw new Error("token exchange answered no access_token");
    }
    return json.access_token;
  }

  async function sendOne(pt: PushToken, eventId: string, seq?: number): Promise<SendOutcome> {
    if (pt.kind !== "fcm") return { token: pt.token, status: "skipped", reason: `kind ${pt.kind}` };
    if (!account) {
      return { token: pt.token, status: "skipped", reason: "no FCM service account configured" };
    }
    if (config.fcmProjectId === "" || pt.env !== config.fcmProjectId) {
      return { token: pt.token, status: "skipped", reason: `env ${pt.env}` };
    }

    let bearer: string;
    try {
      bearer = await accessToken(account);
    } catch (e) {
      return { token: pt.token, status: "failed", reason: `auth: ${e}` };
    }
    return await postPush(
      fetchImpl,
      pt.token,
      `https://fcm.googleapis.com/v1/projects/${config.fcmProjectId}/messages:send`,
      {
        method: "POST",
        headers: { authorization: `Bearer ${bearer}`, "content-type": "application/json" },
        body: fcmBody(pt.token, eventId, seq),
      },
    );
  }

  return silentSender(sendOne);
}
