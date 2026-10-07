// A resource's wire object name (src/object-names.ts) — the union's `key`, which installed clients key their
// records by, so its shape must never move. The cases mirror the client's `UploadKeysTest`.

import { assertEquals } from "@std/assert";
import { objectNameFor } from "../src/object-names.ts";

Deno.test("object name → <assetId>-<role>.<ext>, the extension lowercased", () => {
  assertEquals(objectNameFor("ASSET1", "primary", "IMG_0001.HEIC"), "ASSET1-primary.heic");
  assertEquals(objectNameFor("ASSET1", "live", "IMG_0001.MOV"), "ASSET1-live.mov");
});

Deno.test("object name → an asset id carrying dashes is kept whole", () => {
  assertEquals(
    objectNameFor("3F2A-4B1C_L0_001", "primary", "x.jpg"),
    "3F2A-4B1C_L0_001-primary.jpg",
  );
});

Deno.test("object name → no usable extension falls back to bin", () => {
  for (const name of ["IMG_0001", ".heic", "IMG_0001.", ""]) {
    assertEquals(objectNameFor("A", "primary", name), "A-primary.bin", JSON.stringify(name));
  }
});
