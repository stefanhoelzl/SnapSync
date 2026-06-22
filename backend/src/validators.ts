// Validators for the upload path parts (capability `bunny-upload-endpoint`).
//
// Hono's route (`/event/:eventId/device/:deviceId/file/:filename`) owns path parsing and the
// not-found cases (unmatched path / missing filename → 404). These validate the decoded params; the
// handler composes the storage key `<eventId>/<deviceId>/<encoded filename>` (the URL labels are not
// stored, the filename is percent-encoded per-segment so keys stay flat).

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** True when `value` is a canonical UUID (used for eventId and deviceId). */
export function validateUUID(value: string): boolean {
  return UUID_RE.test(value);
}

/**
 * True when `filename` is a single, non-empty path segment with no separator and no traversal.
 * `filename` is the DECODED route param, so these checks run against the real characters.
 */
export function validateFilename(filename: string): boolean {
  return filename.length > 0 && !filename.includes("/") && !filename.includes("..");
}
