// The zip's file names (capability `event-site`, "The zip holds every photo the members have shared"). Pure, so it is
// unit-tested (`site/scripts/zip-names.test.ts`) apart from the page that fetches and zips.
//
// Every photo is saved under its original name, and a name already taken gets "-2", "-3", … before its extension.
// A Live Photo is TWO files — its still and its motion video — saved under ONE stem ("IMG_4471.HEIC" +
// "IMG_4471.MOV"), which is Apple's own export form and re-imports as a Live Photo. So a pair's stem is allocated as
// a unit: renaming only one half would split the pair.

/** One resource of a photo, as the event page's read lists it (`/web/events/<id>/photos`). */
export interface UnionResource {
  role?: string;
  url?: string;
  filename?: string;
  key?: string;
}

/** One photo of the event, as the event page's read lists it. */
export interface UnionAsset {
  deviceId?: string;
  assetId?: string;
  resources?: UnionResource[];
}

/**
 * One file of the zip: where to fetch it, the name it is saved under, and the resource it is — what an encrypted
 * event's file is bound to, so it opens only as that resource (the encrypted file format, `docs/architecture.md`).
 */
export interface ZipEntry {
  url: string;
  name: string;
  deviceId: string;
  assetId: string;
  role: string;
}

function splitName(name: string): [string, string] {
  const dot = name.lastIndexOf(".");
  return dot > 0 ? [name.slice(0, dot), name.slice(dot)] : [name, ""];
}

function nameOf(r: UnionResource, fallback: string): string {
  return r.filename || r.key || fallback;
}

/**
 * The zip's entries for [union], and how many photos and videos they are (a Live Photo counts once). Every asset's
 * original is included; a Live Photo's paired video is included beside it, under the still's stem and the video's own
 * extension.
 */
export function zipEntries(union: UnionAsset[]): { entries: ZipEntry[]; photos: number } {
  const used = new Set<string>();
  const entries: ZipEntry[] = [];
  let photos = 0;
  for (const asset of union) {
    const resources = asset.resources || [];
    const primary = resources.find((r) => r.role === "primary" && r.url);
    if (!primary) continue;
    const live = resources.find((r) => r.role === "live" && r.url);
    const [stem, ext] = splitName(nameOf(primary, "photo"));
    const liveExt = live ? splitName(nameOf(live, "video.MOV"))[1] || ".MOV" : null;
    let candidate = stem;
    for (let i = 2; taken(used, candidate, ext, liveExt); i++) candidate = stem + "-" + i;
    used.add(candidate + ext);
    const identity = { deviceId: asset.deviceId || "", assetId: asset.assetId || "" };
    entries.push({ url: primary.url!, name: candidate + ext, ...identity, role: "primary" });
    if (live && liveExt !== null) {
      used.add(candidate + liveExt);
      entries.push({ url: live.url!, name: candidate + liveExt, ...identity, role: "live" });
    }
    photos++;
  }
  return { entries, photos };
}

function taken(used: Set<string>, stem: string, ext: string, liveExt: string | null): boolean {
  return used.has(stem + ext) || (liveExt !== null && used.has(stem + liveExt));
}
