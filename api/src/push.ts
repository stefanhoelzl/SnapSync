// The silent-wake fan-out's one sender (capability `receiving-photos`): every wake — a photo that became
// available, and an event's close — goes through here, and each registered token is sent through the push
// service its `kind` names. A device's kind is what its app's push adapter stated at registration (`apns` on
// iPhone, `fcm` on Android); a kind no sender speaks is `skipped`, never an error. Never throws; outcomes keep the
// order of the tokens they answer.

import { createApnsSender } from "./apns.ts";
import type { Config } from "./config.ts";
import { createFcmSender } from "./fcm.ts";
import type { FetchLike, PushToken, SendOutcome, SilentSender } from "./push-send.ts";

export type PushSender = SilentSender;

/** One sender over APNs and FCM, each memoizing its own credential across sends. */
export function createPushSender(config: Config, fetchImpl: FetchLike): PushSender {
  const apns = createApnsSender(config, fetchImpl);
  const fcm = createFcmSender(config, fetchImpl);
  return {
    sendSilent: (tokens: PushToken[], eventId: string) =>
      Promise.all(tokens.map(async (pt) => {
        const sender = pt.kind === "fcm" ? fcm : apns;
        const [outcome] = await sender.sendSilent([pt], eventId);
        return outcome;
      })),
  };
}

/**
 * Why the tokens that were not pushed were not, as `; skipped: kind x ×2, failed: 403 ×1` — never the tokens themselves
 * (a push token addresses a device). Empty when every token was pushed.
 */
export function unsentSummary(outcomes: SendOutcome[]): string {
  const counts = new Map<string, number>();
  for (const o of outcomes) {
    if (o.status === "sent") continue;
    const why = `${o.status}: ${o.reason ?? o.code ?? "unknown"}`;
    counts.set(why, (counts.get(why) ?? 0) + 1);
  }
  return counts.size === 0 ? "" : "; " + [...counts].map(([why, n]) => `${why} ×${n}`).join(", ");
}
