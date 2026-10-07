// An encrypted event's key on the event page (the encrypted file format, `docs/architecture.md`): the invite's `#k=`,
// which no request ever carries, checked against the key id the event names before a single photo is fetched. The
// format itself is `api/src/encrypted-file.ts` — the edge's own module, so the page opens exactly what the edge and
// the apps seal.
import { equalBytes, keyIdOf } from "../../../api/src/encrypted-file.ts";

export { decryptFile, fileAssociatedData } from "../../../api/src/encrypted-file.ts";

/** The `k` the invite's fragment carries, as the link spells it, or `null` when it carries none (or not a key). */
export function keyTextFromFragment(hash: string): string | null {
  const k = new URLSearchParams(hash.replace(/^#/, "")).get("k");
  return k !== null && /^[A-Za-z0-9_-]{43}$/.test(k) ? k : null;
}

/** The 32-byte key the invite's fragment carries as `k`, or `null` when it carries none (or not a key). */
export function keyFromFragment(hash: string): Uint8Array | null {
  const k = keyTextFromFragment(hash);
  if (k === null) return null;
  const binary = atob(k.replace(/-/g, "+").replace(/_/g, "/") + "=");
  const key = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return key.length === 32 ? key : null;
}

/** Whether [key] is the one an event naming [keyId] (16 lowercase hex) was encrypted under. */
export async function opens(key: Uint8Array, keyId: string): Promise<boolean> {
  const id = await keyIdOf(key);
  const hex = Array.from(id, (b) => b.toString(16).padStart(2, "0")).join("");
  return equalBytes(new TextEncoder().encode(hex), new TextEncoder().encode(keyId));
}
