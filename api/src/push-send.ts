// What the push senders share (capability `receiving-photos`): the token and outcome shapes, the one POST
// whose answer becomes a per-token outcome, and the batch that attempts every token. `apns.ts` and `fcm.ts`
// differ only in which tokens they send and how they authenticate; everything after the credential is here.

// Structural fetch type (kept local so the senders stay import-acyclic with app.ts).
export type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

/** A device push token as the device registered it: its push service, the token, and that service's env. */
export type PushToken = { kind: string; token: string; env: string };

/**
 * Per-token outcome. `sent` = the push service returned 2xx; `skipped` = not sendable by this sender, no
 * request made; `failed` = a credential or request error, or a non-2xx rejection.
 */
export type SendOutcome = {
  token: string;
  status: "sent" | "skipped" | "failed";
  code?: number; // the push service's HTTP status, when a request was made
  reason?: string;
};

/** A sender of silent wakes for one event to a batch of tokens. Never throws. */
export type SilentSender = {
  sendSilent(tokens: PushToken[], eventId: string): Promise<SendOutcome[]>;
};

/**
 * POST one push and report it: `sent` on 2xx, `failed` with the status otherwise, `failed` with the error on a
 * request error. The body is always drained so the connection (an h2 stream, for APNs) is released — both
 * services answer empty or a small JSON object, which nothing reads.
 */
export async function postPush(
  fetchImpl: FetchLike,
  token: string,
  url: string,
  init: RequestInit,
): Promise<SendOutcome> {
  try {
    const res = await fetchImpl(url, init);
    await res.body?.cancel();
    return res.ok
      ? { token, status: "sent", code: res.status }
      : { token, status: "failed", code: res.status };
  } catch (e) {
    return { token, status: "failed", reason: `${e}` };
  }
}

/** Every token is attempted; one token's error/skip never aborts the others. Never throws. */
export function silentSender(
  sendOne: (pt: PushToken, eventId: string) => Promise<SendOutcome>,
): SilentSender {
  return {
    sendSilent: (tokens: PushToken[], eventId: string) =>
      Promise.all(tokens.map((pt) => sendOne(pt, eventId))),
  };
}
