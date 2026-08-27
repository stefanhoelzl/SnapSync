// The API version prefix, and the minimum app version a versioned request must declare
// (capabilities `backend-deployment`, `min-app-version`).
//
// Both middlewares need to know which version a request is for, and Hono does NOT strip a mount's prefix
// from its path accessors — so the resolution lives here, once. It used to be a regex literal inside the
// auth gate; a second copy in the version gate is exactly the kind of duplication that drifts silently,
// because nothing fails when two copies disagree about what counts as a version prefix — a request simply
// gets gated by one and not the other.

/** The API version a request is addressed to, or `null` when its path carries no version prefix. */
export type ApiVersion = 1 | 2;

const PREFIX = /^\/api\/v(\d+)(?=\/|$)/;

/**
 * Split a request path into the version it names and the path with that prefix removed.
 *
 * The un-prefixed path is what the auth gate's closed list is written in terms of, so `/api/v1` → `/` and
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
    version: n === 1 || n === 2 ? n : null,
    path: stripped === "" ? "/" : stripped,
  };
}

/**
 * Compare two `X.Y` marketing versions numerically, part by part. Returns a negative number when `a` is
 * older than `b`, zero when equal, positive when newer. A version that does not parse sorts as OLDEST, so
 * an unreadable declaration is refused exactly like a too-old one (capability `min-app-version`).
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
