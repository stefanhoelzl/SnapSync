// An encrypted event's key on the event page (the encrypted file format, `docs/architecture.md`): the invite's `#k=`,
// which no request ever carries, checked against the key id the event names before a single photo is fetched. The
// format itself is `api/src/encrypted-file.ts` — the edge's own module, so the page opens exactly what the edge and
// the apps seal.
import { equalBytes, keyIdOf } from "../../../api/src/encrypted-file.ts";
import { openFileName, openRecord } from "../../../api/src/sealed-meta.ts";
import type { UnionAsset } from "./zip-names.ts";

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

/**
 * The name the members gave an encrypted event, opened from the sealed metadata record the page carries
 * (`data-sealed-meta`; decision record `changes/seal-event-name`, D5) with the invite's key — trimmed, as the app's name
 * rule keeps it — or `null` when the record does not open under that key or holds no name, which the page treats as a
 * load that failed. The format is the edge's own (`api/src/sealed-meta.ts`).
 */
export async function sealedName(key: Uint8Array, envelope: string): Promise<string | null> {
  const record = await openRecord(key, envelope);
  const name = record?.name.trim() ?? "";
  return name === "" ? null : name;
}

/**
 * An encrypted event's photo list with every name opened with the invite's key (decision record
 * `changes/seal-file-names`, D7): each resource's served name is sealed for that one photo — the event, the device that
 * shared it, the asset and the role — and opens nowhere else. A photo with a name that does not open keeps none, so the
 * zip leaves the whole photo out (`zip-names.ts`), a Live Photo's still and video together.
 */
export async function openedNames(
  key: Uint8Array,
  eventId: string,
  union: UnionAsset[],
): Promise<UnionAsset[]> {
  return await Promise.all(
    union.map(async (asset) => ({
      ...asset,
      resources: await Promise.all(
        (asset.resources ?? []).map(async (r) => ({
          ...r,
          filename:
            r.filename && r.role
              ? ((await openFileName(
                  key,
                  {
                    eventId,
                    deviceId: asset.deviceId ?? "",
                    assetId: asset.assetId ?? "",
                    role: r.role,
                  },
                  r.filename,
                )) ?? undefined)
              : undefined,
        })),
      ),
    })),
  );
}
