// The API version prefix, and the minimum app version a versioned request must declare
// (capabilities `docs/deployment.md`, `app-update-required`).
//
// Both middlewares need to know which version a request is for, and Hono does NOT strip a mount's prefix
// from its path accessors — so the resolution lives here, once. It used to be a regex literal inside the
// auth gate; a second copy in the version gate is exactly the kind of duplication that drifts silently,
// because nothing fails when two copies disagree about what counts as a version prefix — a request simply
// gets gated by one and not the other.

/** The header every v2 request declares the app's marketing version in (capability `app-update-required`). */
export const APP_VERSION_HEADER = "x-snapsync-app-version";

/** The API version a request is addressed to, or `null` when its path carries no served version's prefix. */
export type ApiVersion = 2;

const PREFIX = /^\/api\/v(\d+)(?=\/|$)/;

/**
 * The versions RETIRED from service: their paths are answered `426` and routed nowhere (`app.ts`). v1 went
 * with decision record `changes/separate-event-page-from-device-api` (D6).
 */
const RETIRED: readonly number[] = [1];

/** Whether [pathname] is addressed to a retired API version. */
export function isRetiredVersion(pathname: string): boolean {
  const m = PREFIX.exec(pathname);
  return m !== null && RETIRED.includes(Number(m[1]));
}

/**
 * Split a request path into the version it names and the path with that prefix removed.
 *
 * The un-prefixed path is what the auth gate's closed list is written in terms of, so `/api/v2` → `/` and
 * `/api/v2/attest/x` → `/attest/x`. A path carrying no version prefix (the marketing page, `/join`, the
 * AASA, `/health`) comes back with `version: null` and its path untouched — those are served at the root
 * and belong to no version.
 *
 * Deliberately version-AGNOSTIC in its matching (`v\d+`), so mounting a further version needs no change
 * here. An unrecognised version number still resolves as a prefix and is normalized away — routing then
 * answers `404` because no such mount exists, which is the honest answer, rather than the gate treating
 * `/api/v9/attest/token` as an unprefixed path and reaching a different conclusion from the router.
 */
export function splitVersion(pathname: string): { version: ApiVersion | null; path: string } {
  const m = PREFIX.exec(pathname);
  if (!m) return { version: null, path: pathname };
  const stripped = pathname.slice(m[0].length);
  const n = Number(m[1]);
  return {
    version: n === 2 ? n : null,
    path: stripped === "" ? "/" : stripped,
  };
}

/**
 * Compare two `X.Y` marketing versions numerically, part by part. Returns a negative number when `a` is
 * older than `b`, zero when equal, positive when newer. A version that does not parse sorts as OLDEST, so
 * an unreadable declaration is refused exactly like a too-old one (capability `app-update-required`).
 *
 * NOT a string comparison, and the difference is not academic: `"0.10" < "0.9"` lexicographically, so a
 * string compare admits builds the gate exists to refuse and refuses builds it exists to admit — silently,
 * and only from the tenth release onward. This codebase already carries one bug of that family, recorded
 * in `db.ts`: `…+00:00` sorts before `…Z` for the same instant.
 */
export function compareVersions(a: string, b: string): number {
  const parts = (v: string): number[] | null => {
    const trimmed = v.trim();
    if (!/^\d+(\.\d+)*$/.test(trimmed)) return null;
    return trimmed.split(".").map(Number);
  };
  const pa = parts(a);
  const pb = parts(b);
  if (!pa) return pb ? -1 : 0; // unparseable is oldest; two unparseables are indistinguishable
  if (!pb) return 1;
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d !== 0) return d;
  }
  return 0;
}

/**
 * The declared version as the device row stores it, or `null` when it is not worth storing: absent, not
 * an `X.Y…` version, or longer than any real one. The bound is what makes storing a caller's header safe —
 * the gate admits any parseable version at or above the minimum, and a parseable string has no length
 * limit of its own.
 */
export function recordableVersion(declared: string | undefined): string | null {
  const trimmed = declared?.trim();
  if (!trimmed || trimmed.length > 32 || !/^\d+(\.\d+)*$/.test(trimmed)) return null;
  return trimmed;
}
