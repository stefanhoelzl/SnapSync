// The event page's key-id reading and lapsed-link refresh (src/lib/photo-links.ts), capabilities `event-site`,
// `privacy-security`. Deno: `deno test --allow-read scripts/photo-links.test.ts` (npm run check:unit).
import { assertEquals } from "jsr:@std/assert@^1";
import { encryptionOf, lapsed, relinked } from "../src/lib/photo-links.ts";
import type { ZipEntry } from "../src/lib/zip-names.ts";

Deno.test("an empty key id is a plain event, a key id an encrypted one", () => {
  assertEquals(encryptionOf(""), { kind: "plain" });
  assertEquals(encryptionOf("0123456789abcdef"), { kind: "encrypted", keyId: "0123456789abcdef" });
});

Deno.test("anything else is unknown — never read as plain", () => {
  for (const raw of [undefined, "%%KEY_ID%%", "0123", "0123456789ABCDEF", "0123456789abcdef0"]) {
    assertEquals(encryptionOf(raw), { kind: "unknown" }, String(raw));
  }
});

Deno.test("only storage's refusal of an expired signature is a lapsed link", () => {
  assertEquals(lapsed(403), true);
  for (const status of [200, 404, 500, 502]) assertEquals(lapsed(status), false, String(status));
});

const entry = (assetId: string, role: string, url: string, name = assetId): ZipEntry => ({
  url,
  name,
  deviceId: "D",
  assetId,
  role,
});

Deno.test("a lapsed download continues with the fresh links, every file keeping its name", () => {
  const entries = [
    entry("A", "primary", "old/A", "IMG.HEIC"),
    entry("B", "primary", "old/B", "IMG-2.HEIC"),
  ];
  const fresh = [entry("B", "primary", "new/B", "IMG.HEIC"), entry("A", "primary", "new/A")];
  assertEquals(relinked(entries, 1, fresh), [
    entry("A", "primary", "old/A", "IMG.HEIC"),
    entry("B", "primary", "new/B", "IMG-2.HEIC"),
  ]);
});

Deno.test("a live video is matched by its role, not by its photo", () => {
  const entries = [entry("A", "primary", "old/p"), entry("A", "live", "old/l")];
  const fresh = [entry("A", "live", "new/l"), entry("A", "primary", "new/p")];
  assertEquals(
    relinked(entries, 0, fresh)?.map((e) => e.url),
    ["new/p", "new/l"],
  );
});

Deno.test("a file withdrawn meanwhile fails the download rather than vanishing from it", () => {
  const entries = [entry("A", "primary", "old/A"), entry("B", "primary", "old/B")];
  assertEquals(relinked(entries, 0, [entry("A", "primary", "new/A")]), null);
  assertEquals(relinked(entries, 1, [entry("A", "primary", "new/A")]), null);
  assertEquals(relinked(entries, 2, []), entries, "nothing left to fetch");
});
