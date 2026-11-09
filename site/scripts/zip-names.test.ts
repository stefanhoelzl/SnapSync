// The /join island's zip naming (src/lib/zip-names.ts), capability `event-site`. Deno:
// `deno test --allow-read scripts/zip-names.test.ts` (npm run check:unit).
import { assertEquals } from "jsr:@std/assert@^1";
import { type UnionAsset, zipEntries, zipFileName } from "../src/lib/zip-names.ts";

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
/** Where each entry is fetched from and saved as — what these tests are about; the identity has its own test. */
const fetched = (entries: { url: string; name: string }[]) =>
  entries.map(({ url, name }) => ({ url, name }));

// @Verifies(spec = "event-site", requirement = "The zip holds every photo the members have shared", scenario = "A Live Photo")
Deno.test("a Live Photo is its still and its video under one stem", () => {
  const { entries, photos } = zipEntries([live("IMG_4471.HEIC", "IMG_4471.MOV")]);
  assertEquals(fetched(entries), [
    { url: "u/IMG_4471.HEIC", name: "IMG_4471.HEIC" },
    { url: "u/IMG_4471.MOV", name: "IMG_4471.MOV" },
  ]);
  assertEquals(photos, 1);
});

Deno.test("the video takes the still's stem even when its own name differs", () => {
  assertEquals(names([live("IMG_1.HEIC", "FullSizeRender.mov")]), ["IMG_1.HEIC", "IMG_1.mov"]);
});

// @Verifies(spec = "event-site", requirement = "The zip holds every photo the members have shared", scenario = "Two Live Photos with the same name")
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

// @Verifies(spec = "event-site", requirement = "The zip holds every photo the members have shared", scenario = "Two photos with the same name")
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
  assertEquals(fetched(entries), [{ url: "u/y.HEIC", name: "y.HEIC" }]);
  assertEquals(photos, 1);
});

Deno.test(
  "an asset with a nameless resource is left out whole, a Live Photo's pair never split",
  () => {
    const { entries, photos } = zipEntries([
      { resources: [{ role: "primary", url: "u/k" }] },
      {
        resources: [
          { role: "primary", url: "u/s", filename: "IMG_2.HEIC" },
          { role: "live", url: "u/v" },
        ],
      },
      { resources: [{ role: "primary", url: "u/y", filename: "y.HEIC" }] },
    ]);
    assertEquals(fetched(entries), [{ url: "u/y", name: "y.HEIC" }]);
    assertEquals(photos, 1);
  },
);

Deno.test(
  "each entry names the resource it is, so an encrypted event's file opens only as that resource",
  () => {
    const union: UnionAsset[] = [
      {
        deviceId: "D1",
        assetId: "A1",
        resources: [
          { role: "primary", url: "u/1", filename: "IMG.HEIC" },
          { role: "live", url: "u/2", filename: "IMG.MOV" },
        ],
      },
    ];
    const roles = zipEntries(union).entries.map(({ deviceId, assetId, role }) => ({
      deviceId,
      assetId,
      role,
    }));
    assertEquals(roles, [
      { deviceId: "D1", assetId: "A1", role: "primary" },
      { deviceId: "D1", assetId: "A1", role: "live" },
    ]);
  },
);

// @Verifies(spec = "event-site", requirement = "The zip holds every photo the members have shared", scenario = "A full download")
Deno.test(
  "forty plain photos are forty files under their own names, in a zip named after the event",
  () => {
    const originals = Array.from(
      { length: 40 },
      (_, i) => `IMG_${1000 + i}.${i % 2 ? "MOV" : "HEIC"}`,
    );
    const { entries, photos } = zipEntries(originals.map(photo));
    assertEquals(photos, 40);
    assertEquals(
      entries.map((e) => e.name),
      originals,
    );
    assertEquals(zipFileName("Anna's 40th"), "Anna-s-40th.zip");
    assertEquals(zipFileName(""), "snapsync.zip");
  },
);
