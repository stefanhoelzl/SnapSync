// The silent-wake fan-out's one sender (capability `receiving-photos`): every wake — a photo that became
// available, and an event's close — goes through here, and each registered token is sent through the push
// service its `kind` names. A device's kind is what its app's push adapter stated at registration (`apns` on
// iPhone, `fcm` on Android); a kind no sender speaks is `skipped`, never an error. Never throws; outcomes keep the
// order of the tokens they answer.

import { createApnsSender, type PushToken, type SendOutcome } from "./apns.ts";
import type { Config } from "./config.ts";
import { createFcmSender } from "./fcm.ts";

type FetchLike = (url: string, init: RequestInit) => Promise<Response>;

export type PushSender = {
  sendSilent(tokens: PushToken[], eventId: string): Promise<SendOutcome[]>;
};

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
