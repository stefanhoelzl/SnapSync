import { assertEquals } from "@std/assert";
import {
  MAX_EVENT_NAME_LENGTH,
  validateEventName,
  validateFilename,
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
