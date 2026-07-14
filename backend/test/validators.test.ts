import { assertEquals } from "@std/assert";
import {
  MAX_EVENT_NAME_LENGTH,
  validateEventName,
  validateFilename,
  validateStartsAt,
  validateUUID,
} from "../src/validators.ts";

const UUID = "7a3f9c21-0000-4000-8000-000000000001";

Deno.test("validateUUID: accepts a canonical UUID", () => {
  assertEquals(validateUUID(UUID), true);
});

Deno.test("validateUUID: rejects non-UUID / empty / trailing junk", () => {
  assertEquals(validateUUID("not-a-uuid"), false);
  assertEquals(validateUUID(""), false);
  assertEquals(validateUUID(`${UUID}x`), false);
});

Deno.test("validateFilename: accepts a normal filename", () => {
  assertEquals(validateFilename("IMG_0001-photo.jpg"), true);
});

Deno.test("validateFilename: accepts chars that get percent-encoded later", () => {
  assertEquals(validateFilename("IMG 001.jpg"), true);
});

Deno.test("validateFilename: rejects empty", () => {
  assertEquals(validateFilename(""), false);
});

Deno.test("validateFilename: rejects traversal", () => {
  assertEquals(validateFilename(".."), false);
  assertEquals(validateFilename("a..b"), false);
});

Deno.test("validateFilename: rejects a path separator (keys stay flat)", () => {
  assertEquals(validateFilename("a/b.jpg"), false);
});

Deno.test("validateEventName: returns the trimmed name when valid", () => {
  assertEquals(validateEventName("Birthday"), "Birthday");
  assertEquals(validateEventName("  Birthday  "), "Birthday");
});

Deno.test("validateEventName: rejects empty / whitespace-only", () => {
  assertEquals(validateEventName(""), null);
  assertEquals(validateEventName("   "), null);
});

Deno.test("validateEventName: rejects non-string input", () => {
  assertEquals(validateEventName(undefined), null);
  assertEquals(validateEventName(null), null);
  assertEquals(validateEventName(42), null);
});

Deno.test("validateEventName: length boundary (≤ MAX accepted, > MAX rejected)", () => {
  assertEquals(
    validateEventName("a".repeat(MAX_EVENT_NAME_LENGTH)),
    "a".repeat(MAX_EVENT_NAME_LENGTH),
  );
  assertEquals(validateEventName("a".repeat(MAX_EVENT_NAME_LENGTH + 1)), null);
});

Deno.test("validateEventName: trims before the length check", () => {
  // 100 non-space chars padded with spaces → trims to exactly MAX → accepted
  const padded = `  ${"a".repeat(MAX_EVENT_NAME_LENGTH)}  `;
  assertEquals(validateEventName(padded), "a".repeat(MAX_EVENT_NAME_LENGTH));
});

Deno.test("validateStartsAt: accepts the canonical cutoff shape, verbatim", () => {
  assertEquals(validateStartsAt("2026-07-14T18:00:00Z"), "2026-07-14T18:00:00Z");
  assertEquals(validateStartsAt("2001-01-01T00:00:00Z"), "2001-01-01T00:00:00Z");
  assertEquals(validateStartsAt("2099-12-31T23:59:59Z"), "2099-12-31T23:59:59Z");
});

Deno.test("validateStartsAt: rejects every off-canonical shape", () => {
  // Fractional seconds: a bare NSISO8601DateFormatter (the iOS walk's parser) REJECTS these outright,
  // so an off-shape cutoff silently costs the bounded PhotoKit fetch.
  assertEquals(validateStartsAt("2026-07-14T18:00:00.000Z"), null);
  // An offset breaks the LEXICOGRAPHIC compare against `creationDate`.
  assertEquals(validateStartsAt("2026-07-14T18:00:00+02:00"), null);
  assertEquals(validateStartsAt("2026-07-14T18:00:00"), null); // no Z
  assertEquals(validateStartsAt("2026-07-14"), null); // date only
  assertEquals(validateStartsAt(" 2026-07-14T18:00:00Z"), null); // leading space
  assertEquals(validateStartsAt("2026-07-14T18:00:00Zx"), null); // trailing junk
  assertEquals(validateStartsAt("yesterday"), null);
});

Deno.test("validateStartsAt: rejects a right-shaped string that is not a real instant", () => {
  // `new Date()` silently ROLLS OVER out-of-range components (month 13 → next January), so the shape
  // regex alone is not enough — the round-trip compare is what catches these.
  assertEquals(validateStartsAt("2026-13-45T99:99:99Z"), null);
  assertEquals(validateStartsAt("2026-02-30T00:00:00Z"), null);
  assertEquals(validateStartsAt("2026-00-10T00:00:00Z"), null);
});

Deno.test("validateStartsAt: rejects empty and non-string input", () => {
  // The empty string is the dangerous one: the cutoff compare is `creationDate >= cutoff` and EVERY
  // string is `>= ""`, so an empty floor is no floor — it restores whole-library scope while presenting
  // as a present, non-null value.
  assertEquals(validateStartsAt(""), null);
  assertEquals(validateStartsAt(undefined), null);
  assertEquals(validateStartsAt(null), null);
  assertEquals(validateStartsAt(42), null);
});
