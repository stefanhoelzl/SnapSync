// What validation the description (`openapi.yaml`) cannot carry, and the canonical time shape the backend renders.
//
// The device API's inputs are validated by the GENERATED routes against `openapi.yaml` (`src/wire-runtime.ts`): an
// event's name, its dates, a zone's shape, a key id, every path segment. What stays here is what a schema cannot
// state — `knownZone`, which only this runtime can answer — the UUID check the ROOT routes use (the event page, the
// join page, the sweep), which the description does not cover, and the renderers of the one time shape.

const UUID_RE = /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

/** True when `value` is a canonical UUID — for the routes outside the device API, which no description covers. */
export function validateUUID(value: string): boolean {
  return UUID_RE.test(value);
}

/**
 * Add a whole number of seconds to a canonical-cutoff-shaped instant, returning the canonical shape
 * (capability `event-lifetime`: `endsAt` is stored in the SAME shape as `startsAt` so comparisons stay
 * plain string/epoch comparisons and no consumer ever normalizes). The input is expected to be
 * already-validated (`openapi.yaml`, `Timestamp`); whole seconds in, whole seconds out, so the `.000Z`
 * strip is exact, never a truncation.
 */
export function canonicalPlusSeconds(canonical: string, seconds: number): string {
  return new Date(Date.parse(canonical) + seconds * 1000).toISOString().replace(".000Z", "Z");
}

/**
 * Render an epoch-ms instant in the canonical shape, rounding DOWN to the second — the ONE time shape on the
 * wire (`openapi.yaml`, `Timestamp`): every instant the backend mints and serves (`createdAt`, `closedAt`,
 * `completedAt`, `deletesAt`) is rendered here. Rounding down rather than to nearest keeps a served deadline at
 * or before the real one — an early self-leave is recoverable by re-scanning, a late one is a phantom.
 */
export function canonicalFromMs(ms: number): string {
  return new Date(Math.floor(ms / 1000) * 1000).toISOString().replace(".000Z", "Z");
}

/**
 * The `knownZone` check (`openapi.yaml`, `Zone`): whether this runtime can format dates in the IANA zone `name`
 * (capability `event-site`). The description checks the zone's SHAPE — which is what keeps a bare offset such as
 * `+02:00`, which `Intl` also accepts, out: a fixed offset is wrong across a daylight-saving change inside the
 * window. An unusable zone is DROPPED, never refused: a phone whose zone name this runtime does not know must
 * still be able to create its event — a wrong weekday on the page is a smaller failure than no event at all.
 */
export function knownZone(name: string): boolean {
  try {
    new Intl.DateTimeFormat("en-GB", { timeZone: name });
    return true;
  } catch (_e) {
    return false;
  }
}
