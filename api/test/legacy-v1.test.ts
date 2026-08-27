// Pins the v1 key parse against the SAME examples the client's own round-trip test uses
// (`domain/src/commonTest/.../UploadKeysTest.kt`). Two implementations of one parse only stay in step if
// they are pinned to the same cases — so when that test gains a case, this one should too, and when v1 is
// retired both this file and `src/legacy-v1.ts` are deleted together.

import { assertEquals } from "@std/assert";
import { identityFromLegacyKey } from "../src/legacy-v1.ts";

Deno.test("legacy key → the client's canonical examples round-trip", () => {
  assertEquals(identityFromLegacyKey("ASSET1-primary.heic"), {
    assetId: "ASSET1",
    role: "primary",
  });
  assertEquals(identityFromLegacyKey("ASSET1-live.mov"), { assetId: "ASSET1", role: "live" });
});

Deno.test("legacy key → an assetId containing '-' splits at the LAST dash, not the first", () => {
  // A PHAsset localIdentifier routinely contains '-'; the role token never does. Splitting at the first
  // dash would file every such resource under a truncated asset and silently orphan it.
  assertEquals(identityFromLegacyKey("X-9-live.mov"), { assetId: "X-9", role: "live" });
  assertEquals(identityFromLegacyKey("a-b-c-live.mov"), { assetId: "a-b-c", role: "live" });
  assertEquals(
    identityFromLegacyKey("3F2A-4B1C_L0_001-primary.heic"),
    { assetId: "3F2A-4B1C_L0_001", role: "primary" },
  );
});

Deno.test("legacy key → the 'bin' extension fallback still parses", () => {
  // `uploadKey` falls back to `bin` when the capture filename carries no extension, so this shape is
  // real rather than hypothetical.
  assertEquals(identityFromLegacyKey("ASSET1-primary.bin"), { assetId: "ASSET1", role: "primary" });
});

Deno.test("legacy key → an unrecognised role token still parses", () => {
  // v1 stored whatever name it was given. The schema forces the SHAPE; it does not force the vocabulary,
  // and narrowing further would refuse an orphan object v1 used to accept.
  assertEquals(identityFromLegacyKey("ASSET1-weird.heic"), { assetId: "ASSET1", role: "weird" });
});

Deno.test("legacy key → a name that is not the shape is refused, not guessed", () => {
  for (
    const bad of [
      "noextension", // no '.'
      "no-dash-at-all", // no '.'
      "nodash.heic", // no '-' in the stem
      "-primary.heic", // empty assetId
      "ASSET1-.heic", // empty role
      ".heic", // only an extension
      "", // empty
    ]
  ) {
    assertEquals(identityFromLegacyKey(bad), null, `expected ${JSON.stringify(bad)} to be refused`);
  }
});
