// The event page's half of an encrypted event (the encrypted file format, `docs/architecture.md`): the invite's key is
// read from the fragment alone and checked against the event's key id before any photo is fetched.
import { assert, assertEquals } from "jsr:@std/assert@^1";
import {
  keyFromFragment,
  keyTextFromFragment,
  openedNames,
  opens,
  sealedName,
} from "../src/lib/event-key.ts";
import { type UnionAsset, zipEntries } from "../src/lib/zip-names.ts";
import { sealFileName, sealRecord } from "../../api/src/sealed-meta.ts";

const KEY = Uint8Array.from({ length: 32 }, (_, i) => i);
// From test/vectors/encrypted-file.json: the key id of the bytes 0..31.
const KEY_ID = "de30249b854310c8";
const K = btoa(String.fromCharCode(...KEY))
  .replace(/\+/g, "-")
  .replace(/\//g, "_")
  .replace(/=+$/, "");

// @Verifies(spec = "event-site", requirement = "An encrypted event's photos open only with the whole invite", scenario = "An invite without its key")
// The page's part: a cut-off key opens nothing.
Deno.test("the invite's k is the event key, and nothing else is", () => {
  assertEquals(keyFromFragment("#k=" + K), KEY);
  assertEquals(keyFromFragment("#autoJoin=true&k=" + K), KEY);
  assertEquals(keyFromFragment(""), null);
  assertEquals(keyFromFragment("#k=" + K.slice(1)), null);
  assertEquals(keyFromFragment("#k=" + K + "A"), null);
  assertEquals(keyFromFragment("#v=3&d=abc"), null);
});

Deno.test("the invite's k is handed on exactly as the link spells it", () => {
  assertEquals(keyTextFromFragment("#autoJoin=true&k=" + K), K);
  assertEquals(keyTextFromFragment("#k=" + K + "A"), null);
  assertEquals(keyTextFromFragment(""), null);
});

// @Verifies(spec = "event-site", requirement = "An encrypted event's photos open only with the whole invite", scenario = "The whole invite downloads the photos")
// The page's part: the whole invite's key opens the event's files.
Deno.test("a key opens only the event whose key id it is", async () => {
  assert(await opens(KEY, KEY_ID));
  assert(!(await opens(new Uint8Array(32), KEY_ID)));
});

// @Verifies(spec = "event-site", requirement = "An invite link without the app opens the event's page", scenario = "The members' name replaces a stale preview name")
// The page's part: what replaces the server-rendered preview name is the name the event's record opens to.
Deno.test(
  "a sealed name opens with the invite's key, trimmed, and a record it does not open names nothing",
  async () => {
    assertEquals(
      await sealedName(KEY, await sealRecord(KEY, { name: " Ana's 30th " })),
      "Ana's 30th",
    );
    // Keys a newer build wrote are carried and ignored.
    assertEquals(await sealedName(KEY, await sealRecord(KEY, { name: "Ski", future: 1 })), "Ski");
    const other = new Uint8Array(32).fill(9);
    assertEquals(
      await sealedName(other, await sealRecord(KEY, { name: "Ana's 30th" })),
      null,
      "another key",
    );
    assertEquals(
      await sealedName(KEY, await sealRecord(KEY, { name: "   " })),
      null,
      "a blank name",
    );
    assertEquals(await sealedName(KEY, "AQ" + "A".repeat(40)), null, "altered");
    assertEquals(await sealedName(KEY, ""), null, "none");
  },
);

// @Verifies(spec = "event-site", requirement = "An encrypted event's photos open only with the whole invite", scenario = "A photo whose name does not open is left out")
// The page's part: every name is opened with the key for its own photo before the zip is named, and a Live Photo whose
// video's name does not open is left out whole — its still too.
Deno.test(
  "an encrypted event's photo names open with the key, and a photo whose name does not is left out whole",
  async () => {
    const E = "0b5e1c2a-7d4f-4c1e-9a3b-2f6d8e0c1a5b";
    const D = "6f1d2c3b-4a5e-4f60-8172-93a4b5c6d7e8";
    const sealed = (assetId: string, role: string, name: string, key = KEY) =>
      sealFileName(key, { eventId: E, deviceId: D, assetId, role }, name);
    const union: UnionAsset[] = [
      {
        deviceId: D,
        assetId: "1",
        resources: [
          { role: "primary", url: "u/1", filename: await sealed("1", "primary", "IMG_1.HEIC") },
        ],
      },
      {
        deviceId: D,
        assetId: "2",
        resources: [
          { role: "primary", url: "u/2s", filename: await sealed("2", "primary", "IMG_2.HEIC") },
          // Sealed for another photo: it opens nowhere here.
          { role: "live", url: "u/2v", filename: await sealed("3", "live", "IMG_2.MOV") },
        ],
      },
      {
        deviceId: D,
        assetId: "4",
        resources: [
          {
            role: "primary",
            url: "u/4",
            filename: await sealed("4", "primary", "IMG_4.JPG", new Uint8Array(32)),
          },
        ],
      },
    ];
    const { entries, photos } = zipEntries(await openedNames(KEY, E, union));
    assertEquals(
      entries.map(({ url, name }) => ({ url, name })),
      [{ url: "u/1", name: "IMG_1.HEIC" }],
    );
    assertEquals(photos, 1);
  },
);
