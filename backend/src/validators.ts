// Validators for the upload path parts (capability `bunny-upload-endpoint`).
//
// Hono's route (`/event/:eventId/file/:filename`) owns path parsing and the not-found cases
// (unmatched path / missing filename → 404). These validate the decoded params; the handler composes
// the storage key `<eventId>/<encoded filename>` (the URL labels are not stored, the filename is
// percent-encoded per-segment so keys stay flat).

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** True when `value` is a canonical UUID (used for eventId). */
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

/** Maximum allowed length (characters) of an event name (capability `event-creation`). */
export const MAX_EVENT_NAME_LENGTH = 100;

/**
 * Validate and normalize an event name from a create request body. Returns the TRIMMED name when
 * valid (a string, non-empty after trimming, at most {@link MAX_EVENT_NAME_LENGTH} characters), or
 * `null` when the input is not a string, is empty/whitespace-only, or is too long. The trimmed value
 * is what callers store and echo back.
 */
export function validateEventName(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  const name = raw.trim();
  if (name.length === 0 || name.length > MAX_EVENT_NAME_LENGTH) return null;
  return name;
}
