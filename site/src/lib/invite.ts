// The invite in either of its forms — /join#v=3&d=… and /join/<eventId> — and the Google Play link that carries it
// (capabilities event-site, join-event).
// Pure — no DOM — so the island imports it and `scripts/invite.test.ts` runs it under Deno.

/** The invite link's version, as capability `join-event` fixes it: `#v=3&d=<base64url(json)>`. */
export const CONFIG_VERSION = "3";

const UUID = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** A decoded invite: the event it names, and its payload exactly as the link carried it. */
export interface Invite {
  eventId: string;
  d: string;
}

/** Decodes `location.hash` — the same wire contract the app decodes — or null for anything that is not an invite. */
export function decodeInvite(hash: string): Invite | null {
  const frag = hash.startsWith("#") ? hash.slice(1) : hash;
  if (!frag) return null;
  const params: Record<string, string> = {};
  for (const part of frag.split("&")) {
    const eq = part.indexOf("=");
    if (eq < 0) continue;
    params[part.slice(0, eq)] = part.slice(eq + 1);
  }
  if (params.v !== CONFIG_VERSION || !params.d) return null;
  try {
    let b64 = params.d.replace(/-/g, "+").replace(/_/g, "/");
    while (b64.length % 4) b64 += "=";
    const bytes = Uint8Array.from(atob(b64), (ch) => ch.charCodeAt(0));
    const payload = JSON.parse(new TextDecoder().decode(bytes));
    if (!payload || typeof payload.eventId !== "string" || !UUID.test(payload.eventId)) return null;
    return { eventId: payload.eventId, d: params.d };
  } catch (_e) {
    return null;
  }
}

/**
 * The event a path-form page names (`/join/<eventId>`, an optional trailing slash), or null for any other path. The
 * server has already refused a malformed one (it renders the invalid view), so this only reads it back.
 */
export function eventIdFromPath(pathname: string): string | null {
  const m = /^\/join\/([^/]+)\/?$/.exec(pathname);
  return m && UUID.test(m[1]) ? m[1] : null;
}

/**
 * The invite for [eventId] exactly as the app encodes it — `d` is the unpadded base64url of `{"eventId":"…"}` — so a
 * path-form page hands Google Play the same referrer the fragment form's page did, and the app's referrer decoder is
 * untouched.
 */
export function inviteFor(eventId: string): Invite {
  const bytes = new TextEncoder().encode(JSON.stringify({ eventId }));
  const b64 = btoa(String.fromCharCode(...bytes));
  return { eventId, d: b64.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "") };
}

/**
 * The Play listing link carrying [invite] as the install referrer, which the installed app opens once on its first
 * launch. The referrer is rebuilt from `v` and `d` alone — never the raw fragment — so nothing else a hand-edited link
 * carries reaches Google, and it is URL-encoded because it sits inside a query parameter (Play decodes it once).
 */
export function playHrefFor(playStoreUrl: string, invite: Invite): string {
  return `${playStoreUrl}&referrer=${encodeURIComponent(`v=${CONFIG_VERSION}&d=${invite.d}`)}`;
}
