// LEGACY, v1 ONLY. Everything in this file is deleted when `/api/v1` is retired — it exists solely to
// let v1's routes keep their behaviour on a schema shaped for v2.
//
// WHY THIS PARSE EXISTS, AND WHY IT IS ALLOWED TO. `resources` is now keyed by IDENTITY —
// `(device_id, asset_id, role)` — but v1's byte upload URL carries only the stored object NAME
// (`/api/v1/files/devices/<deviceId>/<filename>`). v2's URL names the identity in its path and needs no
// parse at all. So v1, and only v1, has to recover identity from the name.
//
// The client owns the canonical implementation of this parse (`assetIdFromUploadKey` /
// `roleFromUploadKey` in `:domain` `model/UploadKeys.kt`), and duplicating a load-bearing parse across
// two languages is normally exactly the wrong move: two implementations drift, and the drift is invisible
// until a photo goes missing. Three things make it acceptable HERE and nowhere else:
//
//   1. It is confined to v1, which is frozen — the format it parses cannot change under it.
//   2. It has a known deletion date: this file goes when v1 does.
//   3. It was validated against the whole deployed store before being written — every one of the 972
//      resource rows there parses (`0` keys failed to match `<assetId>-<role>.<ext>`), so this is not a
//      guess about the format but a measurement of it.
//
// A NEW parse of this kind does not get the same latitude. If v2 ever needs one, that is a signal its URL
// is carrying the wrong thing.

/** A resource's identity, recovered from a v1 object name. */
export type LegacyIdentity = { assetId: string; role: string };

/**
 * Recover `(assetId, role)` from a v1 stored object name shaped `<assetId>-<role>.<ext>`, or `null` when
 * the name is not that shape.
 *
 * The split is at the **last** `-` of the stem, not the first: a PHAsset `localIdentifier` routinely
 * contains `-` (`3F2A-4B1C_L0_001`) while the role token never does. Mirrors the client's
 * `substringBeforeLast('.').substringBeforeLast('-')` exactly.
 *
 * `null` is a real answer, not a failure to try: it means this name was not produced by the client's key
 * builder. Every key in the deployed store parses, so a name that does not is either hand-crafted or
 * corrupt, and the route refuses it rather than inventing an identity to file it under. That is a
 * deliberate NARROWING of what v1 accepts — previously any safe path segment was stored — affecting
 * inputs no shipped client produces.
 *
 * The role token is NOT checked against the closed vocabulary here. v1 stored whatever name it was given,
 * and an unrecognised role stays storable and simply never matches a manifest — exactly as an orphan
 * object behaved before. Narrowing the shape is required by the schema; narrowing the vocabulary is not,
 * so it is not done.
 */
export function identityFromLegacyKey(key: string): LegacyIdentity | null {
  const dot = key.lastIndexOf(".");
  if (dot <= 0) return null; // no extension, or a name that is only an extension
  const stem = key.slice(0, dot);
  const dash = stem.lastIndexOf("-");
  if (dash <= 0) return null; // no role token, or an empty assetId
  const assetId = stem.slice(0, dash);
  const role = stem.slice(dash + 1);
  if (assetId === "" || role === "") return null;
  return { assetId, role };
}

/**
 * Compose the stored object name for a resource: `<assetId>-<role>.<ext>`, the extension taken from the
 * capture filename and lowercased, falling back to `bin` when the name carries none.
 *
 * This is v1's layout, and v2 keeps it DELIBERATELY. The extension is redundant for identity — v2's
 * primary key is `(device_id, asset_id, role)` — but the object name is an ADDRESS, and changing an
 * address strands what is already stored at the old one: a device moving from v1 to v2 would consider
 * none of its bytes uploaded and re-upload its entire library, while an event with a member on each
 * version would need two ways to name one photo. Keeping it also means the stored object still carries a
 * type-bearing suffix, so nothing downstream has to infer one.
 *
 * Mirrors the client's `uploadKey` exactly (`:domain` `model/UploadKeys.kt`). Lives beside the parse it
 * inverts, and outlives v1 only in the sense that v2 calls it — when v1 goes, this function moves out of
 * this file rather than dying with it.
 */
export function legacyKeyFor(assetId: string, role: string, originalFilename: string): string {
  const dot = originalFilename.lastIndexOf(".");
  const ext = dot > 0 && dot < originalFilename.length - 1
    ? originalFilename.slice(dot + 1).toLowerCase()
    : "bin";
  return `${assetId}-${role}.${ext}`;
}

/** The closed role vocabulary a v2 upload may name. Mirrors the client's `ResourceRole`. */
export const RESOURCE_ROLES: readonly string[] = ["primary", "live"];
