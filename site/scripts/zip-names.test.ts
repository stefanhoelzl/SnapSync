// The /join island's zip naming (src/lib/zip-names.ts), capability `event-site`. Deno:
// `deno test --allow-read scripts/zip-names.test.ts` (npm run check:unit).
import { assertEquals } from "jsr:@std/assert@^1";
import { zipEntries, type UnionAsset } from "../src/lib/zip-names.ts";

const photo = (name: string): UnionAsset => ({
  resources: [{ role: "primary", url: "u/" + name, filename: name }],
});
const live = (still: string, video: string): UnionAsset => ({
  resources: [
    { role: "primary", url: "u/" + still, filename: still },
    { role: "live", url: "u/" + video, filename: video },
  ],
});
const names = (union: UnionAsset[]) => zipEntries(union).entries.map((e) => e.name);

Deno.test("a Live Photo is its still and its video under one stem", () => {
  const { entries, photos } = zipEntries([live("IMG_4471.HEIC", "IMG_4471.MOV")]);
  assertEquals(entries, [
    { url: "u/IMG_4471.HEIC", name: "IMG_4471.HEIC" },
    { url: "u/IMG_4471.MOV", name: "IMG_4471.MOV" },
  ]);
  assertEquals(photos, 1);
});

Deno.test("the video takes the still's stem even when its own name differs", () => {
  assertEquals(names([live("IMG_1.HEIC", "FullSizeRender.mov")]), ["IMG_1.HEIC", "IMG_1.mov"]);
});

Deno.test("two same-named Live Photos are renamed as pairs", () => {
  assertEquals(names([live("IMG_1.HEIC", "IMG_1.MOV"), live("IMG_1.HEIC", "IMG_1.MOV")]), [
    "IMG_1.HEIC",
    "IMG_1.MOV",
    "IMG_1-2.HEIC",
    "IMG_1-2.MOV",
  ]);
});

Deno.test("a pair never takes a stem one of its halves would collide on", () => {
  // IMG_1.MOV is an ordinary video already, so the Live Photo cannot be IMG_1.HEIC + IMG_1.MOV.
  assertEquals(names([photo("IMG_1.MOV"), live("IMG_1.HEIC", "IMG_1.MOV")]), [
    "IMG_1.MOV",
    "IMG_1-2.HEIC",
    "IMG_1-2.MOV",
  ]);
});

Deno.test("plain photos keep the old collision rule", () => {
  assertEquals(names([photo("a.jpg"), photo("a.jpg"), photo("a.jpg"), photo("noext")]), [
    "a.jpg",
    "a-2.jpg",
    "a-3.jpg",
    "noext",
  ]);
});

Deno.test("an asset with no original, and a resource with no url, are skipped", () => {
  const { entries, photos } = zipEntries([
    { resources: [{ role: "live", url: "u/x.MOV", filename: "x.MOV" }] },
    { resources: [{ role: "primary", url: "u/y.HEIC", filename: "y.HEIC" }, { role: "live" }] },
    {},
  ]);
  assertEquals(entries, [{ url: "u/y.HEIC", name: "y.HEIC" }]);
  assertEquals(photos, 1);
});

Deno.test("a nameless resource falls back to its key", () => {
  assertEquals(names([{ resources: [{ role: "primary", url: "u/k", key: "abc-primary.heic" }] }]), [
    "abc-primary.heic",
  ]);
});
