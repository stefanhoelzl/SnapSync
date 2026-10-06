// Validators for the upload path parts (capability `bunny-upload-endpoint`).
//
// Hono's route (`/files/devices/:deviceId/:filename`) owns path parsing and the not-found cases
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

/**
 * The canonical capture-date cutoff shape (capability `photo-sharing`): UTC `Z`, SECOND precision,
 * no offset, no fractional seconds. Anchored, so no prefix/suffix slips through.
 */
const CUTOFF_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;

/**
 * Validate an event's `startsAt` from a create request body. Returns the value when it is EXACTLY the
 * canonical cutoff shape `yyyy-MM-ddTHH:mm:ssZ` and names a real instant, else `null`.
 *
 * The canonical form is demanded AT THE BOUNDARY rather than accepted loosely and normalized, because
 * `startsAt` is consumed directly as a capture-date cutoff: it is compared LEXICOGRAPHICALLY against
 * PhotoKit `creationDate` and parsed by a bare `NSISO8601DateFormatter` (whose default options reject a
 * fractional second). A marker holding the canonical form is usable as a cutoff with NO client-side
 * normalization — unlike `createdAt`, which we mint with `toISOString()` and which therefore always
 * carries milliseconds.
 *
 * The empty string is rejected like any other non-match, and that matters more than it looks: the cutoff
 * compare is `creationDate >= cutoff` and EVERY string is `>= ""`, so an empty floor is no floor at all —
 * it would silently restore whole-library scope while presenting as a present, non-null value.
 *
 * NOT bounded: an event may start arbitrarily far in the past (bringing existing photos into scope) or
 * in the future (created ahead of time). Bounding it is not the backend's concern — the floor only ever
 * NARROWS a member's scope.
 */
export function validateStartsAt(raw: unknown): string | null {
  if (typeof raw !== "string") return null;
  if (!CUTOFF_RE.test(raw)) return null;
  // The shape is right; make sure it is a real instant (rejects e.g. 2026-13-45T99:99:99Z) and that it
  // round-trips — `Date` silently rolls over out-of-range components, so compare rather than trust.
  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) return null;
  if (parsed.toISOString().replace(".000Z", "Z") !== raw) return null;
  return raw;
}

/**
 * Add a whole number of seconds to a canonical-cutoff-shaped instant, returning the canonical shape
 * (capability `event-lifetime`: `endsAt` is stored in the SAME shape as `startsAt` so comparisons stay
 * plain string/epoch comparisons and no consumer ever normalizes). The input is expected to be
 * already-validated (see {@link validateStartsAt}); whole seconds in, whole seconds out, so the `.000Z`
 * strip is exact, never a truncation.
 */
export function canonicalPlusSeconds(canonical: string, seconds: number): string {
  return new Date(Date.parse(canonical) + seconds * 1000).toISOString().replace(".000Z", "Z");
}

/**
 * Render an epoch-ms instant in the canonical cutoff shape, rounding DOWN to the second (capability
 * `event-lifetime`: the derived `deletesAt` is served in the same shape as `startsAt`/`endsAt`, so a client
 * compares all three the same way). Rounding down rather than to nearest keeps the served deadline at or
 * before the real one — an early self-leave is recoverable by re-scanning, a late one is a phantom.
 */
export function canonicalFromMs(ms: number): string {
  return new Date(Math.floor(ms / 1000) * 1000).toISOString().replace(".000Z", "Z");
}

/**
 * Validate a client-supplied event `endsAt` (capability `event-lifetime`). Same canonical-instant
 * discipline as {@link validateStartsAt} — right shape, a real instant that round-trips — PLUS it must
 * fall strictly after the (already-validated) `startsAt` and no more than [windowMaxSeconds] after it.
 *
 * The upper cap is a HARD BOUND, not a pricing lever. `endsAt` says which captures may be uploaded, and
 * the event's storage lifetime is bounded independently; a window longer than that lifetime would declare
 * captures eligible for upload into an event that no longer exists by then — a photo uploading into
 * nothing, which is exactly the silent loss the selection policy exists to prevent. The only future
 * paid-tier lever is `capacity`.
 *
 * Returns the value when valid, else null (the caller maps null to a 400). An ABSENT `endsAt` is handled
 * by the caller (it falls back to `startsAt + windowMax`), not here — this only judges a supplied value.
 */
export function validateEndsAt(
  raw: unknown,
  startsAt: string,
  windowMaxSeconds: number,
): string | null {
  if (typeof raw !== "string") return null;
  if (!CUTOFF_RE.test(raw)) return null;
  const parsed = new Date(raw);
  if (Number.isNaN(parsed.getTime())) return null;
  if (parsed.toISOString().replace(".000Z", "Z") !== raw) return null;
  // Both are fixed-width canonical UTC, so a plain string compare is chronological.
  if (raw <= startsAt) return null; // must be strictly after the start
  if (parsed.getTime() - Date.parse(startsAt) > windowMaxSeconds * 1000) return null; // window cap
  return raw;
}

/** The longest zone name kept; the IANA database's longest is under 40. */
export const MAX_ZONE_LENGTH = 64;

/**
 * Judge a client-supplied `zone` (capability `event-site`; migration 0009): the host's IANA time zone, which the
 * event page reads `startsAt`/`endsAt` in. Returns the name when this runtime can format dates in it, else null.
 *
 * Unlike every other create field, an unusable zone is NOT a refusal: the caller stores null and the page renders
 * the dates in UTC. A zone only labels dates on the web, so a phone whose zone name this runtime does not know must
 * still be able to create its event — a wrong weekday on the page is a smaller failure than no event at all.
 *
 * The shape is checked before `Intl` is asked, because `Intl` also accepts what is not a zone NAME — a bare offset
 * such as `+02:00` — and a fixed offset is exactly what the column exists to avoid (it is wrong across a
 * daylight-saving change inside the window).
 */
export function validateZone(raw: unknown): string | null {
  if (typeof raw !== "string" || raw.length === 0 || raw.length > MAX_ZONE_LENGTH) return null;
  if (!/^[A-Za-z][A-Za-z0-9_+\-]*(\/[A-Za-z0-9_+\-]+)*$/.test(raw)) return null;
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone: raw });
    return raw;
  } catch (_e) {
    return null;
  }
}

/**
 * An encrypted event's key id as `POST /events` may carry it (the encrypted file format, `docs/architecture.md`):
 * exactly 16 lowercase hex characters, or `null` when the value is anything else. Unlike `zone`, a present
 * but invalid one is REFUSED by the route rather than dropped: dropping it would create a PLAIN event for a
 * host who meant an encrypted one.
 */
export function validateKeyId(raw: unknown): string | null {
  return typeof raw === "string" && /^[0-9a-f]{16}$/.test(raw) ? raw : null;
}
