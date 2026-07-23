import { assertEquals } from "@std/assert";
import {
  canonicalFromMs,
  canonicalPlusSeconds,
  MAX_EVENT_NAME_LENGTH,
  validateEndsAt,
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

const WINDOW_MAX = 30 * 24 * 60 * 60;

Deno.test("validateEndsAt: accepts a canonical instant strictly after startsAt, within the window cap", () => {
  const startsAt = "2026-07-14T18:00:00Z";
  assertEquals(
    validateEndsAt("2026-07-21T23:00:00Z", startsAt, WINDOW_MAX),
    "2026-07-21T23:00:00Z",
  );
  // One second after start is enough.
  assertEquals(
    validateEndsAt("2026-07-14T18:00:01Z", startsAt, WINDOW_MAX),
    "2026-07-14T18:00:01Z",
  );
});

Deno.test("validateEndsAt: the window cap is inclusive at the maximum and rejects one second past it", () => {
  // The cap is a HARD bound, not a pricing lever: a window longer than the event's storage lifetime
  // would declare captures eligible for upload into an event that no longer exists by then — a photo
  // uploading into nothing, the silent loss the selection policy exists to prevent.
  const startsAt = "2026-07-14T18:00:00Z";
  assertEquals(
    validateEndsAt("2026-08-13T18:00:00Z", startsAt, WINDOW_MAX), // exactly 30 days
    "2026-08-13T18:00:00Z",
  );
  assertEquals(validateEndsAt("2026-08-13T18:00:01Z", startsAt, WINDOW_MAX), null); // 30d + 1s
  assertEquals(validateEndsAt("2031-07-14T18:00:00Z", startsAt, WINDOW_MAX), null); // years ahead
});

Deno.test("validateEndsAt: rejects an end at or before startsAt", () => {
  const startsAt = "2026-07-14T18:00:00Z";
  assertEquals(validateEndsAt("2026-07-14T18:00:00Z", startsAt, WINDOW_MAX), null); // equal
  assertEquals(validateEndsAt("2026-07-14T17:59:59Z", startsAt, WINDOW_MAX), null); // before
  assertEquals(validateEndsAt("2001-01-01T00:00:00Z", startsAt, WINDOW_MAX), null); // long before
});

Deno.test("validateEndsAt: rejects off-canonical shapes and non-instants", () => {
  const startsAt = "2026-07-14T18:00:00Z";
  assertEquals(validateEndsAt("2026-07-21T23:00:00.000Z", startsAt, WINDOW_MAX), null); // millis
  assertEquals(validateEndsAt("2026-07-21T23:00:00", startsAt, WINDOW_MAX), null); // no Z
  assertEquals(validateEndsAt("2026-13-45T99:99:99Z", startsAt, WINDOW_MAX), null); // not real
  assertEquals(validateEndsAt("", startsAt, WINDOW_MAX), null);
  assertEquals(validateEndsAt(undefined, startsAt, WINDOW_MAX), null);
  assertEquals(validateEndsAt(null, startsAt, WINDOW_MAX), null);
  assertEquals(validateEndsAt(42, startsAt, WINDOW_MAX), null);
});

Deno.test("canonicalPlusSeconds: adds whole seconds and stays in the canonical cutoff shape", () => {
  // 30 days — the absent-endsAt fallback (capability `event-limits`): endsAt = startsAt + windowMax.
  assertEquals(
    canonicalPlusSeconds("2026-06-27T18:00:00Z", 30 * 24 * 60 * 60),
    "2026-07-27T18:00:00Z",
  );
  // No milliseconds, `Z`, second precision — the result validates as a canonical instant itself.
  assertEquals(
    validateStartsAt(canonicalPlusSeconds("2026-06-27T18:00:00Z", 1)),
    "2026-06-27T18:00:01Z",
  );
});

Deno.test("canonicalPlusSeconds: rolls over month and year boundaries correctly", () => {
  assertEquals(canonicalPlusSeconds("2026-12-31T23:59:59Z", 1), "2027-01-01T00:00:00Z");
  // Across February in a non-leap year.
  assertEquals(
    canonicalPlusSeconds("2026-02-27T12:00:00Z", 2 * 24 * 60 * 60),
    "2026-03-01T12:00:00Z",
  );
});

Deno.test("canonicalFromMs: renders the canonical cutoff shape, rounding DOWN to the second", () => {
  const at = Date.parse("2026-07-27T18:00:00Z");
  assertEquals(canonicalFromMs(at), "2026-07-27T18:00:00Z");
  // Sub-second precision is TRUNCATED, not rounded to nearest: the served deadline lands at or before
  // the real one, so a client's self-leave can only ever fire slightly early (recoverable by
  // re-scanning) and never slightly late (a phantom membership).
  assertEquals(canonicalFromMs(at + 999), "2026-07-27T18:00:00Z");
  assertEquals(canonicalFromMs(at + 1000), "2026-07-27T18:00:01Z");
  // The result validates as a canonical instant itself.
  assertEquals(validateStartsAt(canonicalFromMs(at + 1)), "2026-07-27T18:00:00Z");
});
