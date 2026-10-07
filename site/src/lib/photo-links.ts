// What the event page knows before fetching a photo, and what it does when a photo's link has lapsed (capabilities
// `event-site`, `privacy-security`; decision record `changes/separate-event-page-from-device-api`, D3 + D5). Pure, so
// it is unit-tested (`site/scripts/photo-links.test.ts`) apart from the page that fetches and zips.

import type { ZipEntry } from "./zip-names.ts";

/** A key id as the event row holds it: 8 bytes, lowercase hex. */
const KEY_ID = /^[0-9a-f]{16}$/;

/** Whether the event's photos are encrypted, as the server rendered it into the page. */
export type Encryption =
  | { kind: "plain" }
  | { kind: "encrypted"; keyId: string }
  /** The page carries no value the server could have written — never read as plain, never decrypted. */
  | { kind: "unknown" };

/**
 * [raw] — the page's `data-key-id` — read: empty is a plain event, a key id an encrypted one, anything else
 * (the literal token an older server left unfilled, a missing attribute) unknown. Unknown must never become
 * "plain": the page would zip ciphertext as photos.
 */
export function encryptionOf(raw: string | undefined): Encryption {
  if (raw === "") return { kind: "plain" };
  if (raw !== undefined && KEY_ID.test(raw)) return { kind: "encrypted", keyId: raw };
  return { kind: "unknown" };
}

/** Whether a photo GET's status says its 1-hour link has lapsed (storage refuses an expired signature `403`). */
export function lapsed(status: number): boolean {
  return status === 403;
}

/**
 * [entries] with the links of everything from [from] on taken from [fresh] — the entries of a list read again
 * after a link lapsed — matched by the resource each is (device, asset, role), so every file keeps the name it was
 * given. `null` when a remaining entry is no longer listed (its photo was withdrawn meanwhile): the download then
 * fails as any other does, rather than leaving a file out unannounced.
 */
export function relinked(entries: ZipEntry[], from: number, fresh: ZipEntry[]): ZipEntry[] | null {
  const id = (e: ZipEntry) => `${e.deviceId}/${e.assetId}/${e.role}`;
  const urls = new Map(fresh.map((e) => [id(e), e.url]));
  const out = entries.slice();
  for (let i = from; i < out.length; i++) {
    const url = urls.get(id(out[i]));
    if (url === undefined) return null;
    out[i] = { ...out[i], url };
  }
  return out;
}
