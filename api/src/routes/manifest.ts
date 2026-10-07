// The device manifest's wire format (capability `photo-sharing`): the body the publish route parses before
// recording anything.

import type { ManifestAssetEntry } from "../db.ts";

/**
 * Validate a device manifest body into the entries the publish records (wire format: capability
 * `photo-sharing`). Returns `null` when the body is not a manifest — a `400`, never a partial publish.
 *
 * Unknown fields are IGNORED rather than rejected: the manifest is written by a shipped app, and a
 * backend that refused a field a future client adds would break every device the moment that client
 * shipped. What is checked is what this backend records.
 */
export function parseManifestAssets(body: { assets?: unknown }): ManifestAssetEntry[] | null {
  if (!Array.isArray(body.assets)) return null;
  const out: ManifestAssetEntry[] = [];
  for (const raw of body.assets) {
    if (typeof raw !== "object" || raw === null) return null;
    const a = raw as Record<string, unknown>;
    if (typeof a.assetId !== "string" || a.assetId === "") return null;
    if (typeof a.creationDate !== "string") return null;
    if (!Array.isArray(a.resources) || a.resources.length === 0) return null;
    const resources = [];
    for (const rawResource of a.resources) {
      if (typeof rawResource !== "object" || rawResource === null) return null;
      const r = rawResource as Record<string, unknown>;
      if (
        typeof r.role !== "string" || typeof r.contentType !== "string" ||
        typeof r.key !== "string" || r.key === "" || typeof r.filename !== "string"
      ) return null;
      resources.push({
        role: r.role,
        contentType: r.contentType,
        key: r.key,
        filename: r.filename,
      });
    }
    out.push({ assetId: a.assetId, creationDate: a.creationDate, resources });
  }
  return out;
}

/**
 * Read a v2 manifest body's optional `version` (`docs/architecture.md`, "The v2 manifest publish is
 * ordered by its version"): the number, `null` when the field is absent (a build that predates it), or
 * `undefined` when it is present but not a non-negative safe integer — a `400`. Safe-integer rather than
 * any number because the comparison is exact: a value past 2^53 would already have been rounded by the
 * JSON parse, and two distinct device versions could compare equal.
 */
/**
 * The manifest's `final` declaration (capability `photo-sharing`): absent → `false` (every build that
 * predates it); anything but a boolean → `undefined`, which the route answers `400`.
 */
export function parseManifestFinal(body: { final?: unknown }): boolean | undefined {
  if (body.final === undefined || body.final === null) return false;
  return typeof body.final === "boolean" ? body.final : undefined;
}

/**
 * The whole v2 manifest body, or the `400` text naming what is wrong with it. One place, so the route
 * reads as its gates rather than its parsing.
 */
export function parseManifestBody(
  body: unknown,
):
  | { assets: ManifestAssetEntry[]; version: number | null; final: boolean }
  | { invalid: string } {
  const b = (body ?? {}) as { version?: unknown; final?: unknown };
  const assets = parseManifestAssets(b as Parameters<typeof parseManifestAssets>[0]);
  if (assets === null) return { invalid: "invalid manifest" };
  const version = parseManifestVersion(b);
  if (version === undefined) return { invalid: "invalid version" };
  const final = parseManifestFinal(b);
  if (final === undefined) return { invalid: "invalid final" };
  return { assets, version, final };
}

export function parseManifestVersion(body: { version?: unknown }): number | null | undefined {
  if (body.version === undefined || body.version === null) return null;
  const v = body.version;
  return typeof v === "number" && Number.isSafeInteger(v) && v >= 0 ? v : undefined;
}
