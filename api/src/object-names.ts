// A resource's OBJECT NAME and the role vocabulary (capability `photo-sharing`).

/**
 * Compose a resource's OBJECT NAME: `<assetId>-<role>.<ext>`, the extension taken from the capture filename
 * and lowercased, falling back to `bin` when the name carries none.
 *
 * It names nowhere anything is stored — since migration 0010 a resource's `path` does (change
 * `per-event-storage-layout`). It is the WIRE's name for a resource: the union's `key`, which installed clients
 * key their ledger and download records by, is derived with it from `asset_id`, `role` and `filename`, so the
 * string they see never changes.
 *
 * Mirrors the client's `uploadKey` exactly (`:domain` `model/UploadKeys.kt`). It lived beside
 * the v1 parse it inverts, and moved here when v1 was retired (`changes/separate-event-page-from-device-api`).
 */
export function objectNameFor(assetId: string, role: string, originalFilename: string): string {
  const dot = originalFilename.lastIndexOf(".");
  const ext = dot > 0 && dot < originalFilename.length - 1
    ? originalFilename.slice(dot + 1).toLowerCase()
    : "bin";
  return `${assetId}-${role}.${ext}`;
}

/** The closed role vocabulary an upload may name. Mirrors the client's `ResourceRole`. */
export const RESOURCE_ROLES: readonly string[] = ["primary", "live"];
