// What the device API accepts as input (`openapi.yaml`, validated by the generated routes through
// `src/wire-runtime.ts`), case by case — the rules the description states, exercised through the very spec the
// generated `createEvent` route runs — plus the helpers `src/validators.ts` keeps.

import { assertEquals } from "@std/assert";
import { Hono } from "hono";
import { AssetId, SPECS, Timestamp, Uuid } from "../src/gen/wire.ts";
import {
  canonicalFromMs,
  canonicalPlusSeconds,
  knownZone,
  validateUUID,
} from "../src/validators.ts";
import { validate, type WireContext } from "../src/wire-runtime.ts";

const UUID = "7a3f9c21-0000-4000-8000-000000000001";
const WINDOW_MAX = 30 * 24 * 60 * 60;
const MAX_EVENT_NAME_LENGTH = 100;

const ctx: WireContext = { policy: { eventWindowMaxSeconds: WINDOW_MAX }, checks: { knownZone } };

/** The create route's input validation, alone: the validated body, or the refusal's status and text. */
async function create(body: unknown): Promise<Record<string, unknown> | string> {
  const app = new Hono();
  app.post("/", async (c) => c.json((await validate(c, SPECS.createEvent, ctx)).body ?? {}));
  const res = await app.request("/", { method: "POST", body: JSON.stringify(body) });
  return res.ok ? await res.json() : `${res.status} ${await res.text()}`;
}

const START = "2026-07-14T18:00:00Z";
const named = (extra: Record<string, unknown>) => ({ name: "Birthday", startsAt: START, ...extra });

Deno.test("Uuid / validateUUID: a canonical UUID, nothing else", () => {
  assertEquals(validateUUID(UUID), true);
  assertEquals(Uuid.safeParse(UUID).success, true);
  for (const bad of ["not-a-uuid", "", `${UUID}x`]) {
    assertEquals(validateUUID(bad), false, bad);
    assertEquals(Uuid.safeParse(bad).success, false, bad);
  }
});

Deno.test("AssetId: one non-empty path segment, no traversal", () => {
  for (const ok of ["IMG_0001-photo.jpg", "IMG 001.jpg"]) {
    assertEquals(AssetId.safeParse(ok).success, true, ok);
  }
  for (const bad of ["", "..", "a..b", "a/b.jpg"]) {
    assertEquals(AssetId.safeParse(bad).success, false, bad);
  }
});

Deno.test("event name: trimmed, and the trimmed name is what is kept", async () => {
  assertEquals(
    (await create(named({ name: "  Birthday  " })) as { name: string }).name,
    "Birthday",
  );
});

Deno.test("event name: empty, whitespace-only and non-string are refused", async () => {
  for (const name of ["", "   ", 42, null]) {
    assertEquals(await create(named({ name })), "400 invalid name", String(name));
  }
  assertEquals(await create({ startsAt: START }), "400 invalid name");
});

Deno.test("event name: the length boundary, measured after trimming", async () => {
  const max = "a".repeat(MAX_EVENT_NAME_LENGTH);
  assertEquals((await create(named({ name: max })) as { name: string }).name, max);
  assertEquals(await create(named({ name: `${max}a` })), "400 invalid name");
  assertEquals((await create(named({ name: `  ${max}  ` })) as { name: string }).name, max);
});

Deno.test("startsAt: the canonical shape, verbatim", async () => {
  for (const startsAt of ["2026-07-14T18:00:00Z", "2001-01-01T00:00:00Z", "2099-12-31T23:59:59Z"]) {
    assertEquals((await create(named({ startsAt })) as { startsAt: string }).startsAt, startsAt);
  }
});

Deno.test("startsAt: every off-canonical shape, non-instant, empty and non-string is refused", async () => {
  const bad = [
    "2026-07-14T18:00:00.000Z",
    "2026-07-14T18:00:00+02:00",
    "2026-07-14T18:00:00",
    "2026-07-14",
    " 2026-07-14T18:00:00Z",
    "2026-07-14T18:00:00Zx",
    "yesterday",
    "2026-13-45T99:99:99Z",
    "2026-02-30T00:00:00Z",
    "2026-00-10T00:00:00Z",
    "",
    42,
    null,
  ];
  for (const startsAt of bad) {
    assertEquals(await create(named({ startsAt })), "400 invalid startsAt", String(startsAt));
  }
});

Deno.test("endsAt: strictly after startsAt, within the window maximum (inclusive)", async () => {
  for (const endsAt of ["2026-07-21T23:00:00Z", "2026-07-14T18:00:01Z", "2026-08-13T18:00:00Z"]) {
    assertEquals((await create(named({ endsAt })) as { endsAt: string }).endsAt, endsAt);
  }
  const bad = [
    "2026-08-13T18:00:01Z", // 30 days + 1 s
    "2031-07-14T18:00:00Z",
    "2026-07-14T18:00:00Z", // equal
    "2026-07-14T17:59:59Z",
    "2026-07-21T23:00:00.000Z",
    "2026-13-45T99:99:99Z",
    "",
    42,
  ];
  for (const endsAt of bad) {
    assertEquals(await create(named({ endsAt })), "400 invalid endsAt", String(endsAt));
  }
});

Deno.test("endsAt: null or absent is no endsAt — the route falls back", async () => {
  assertEquals("endsAt" in (await create(named({ endsAt: null })) as object), false);
  assertEquals("endsAt" in (await create(named({})) as object), false);
});

Deno.test("zone: an IANA name this runtime knows is kept; anything else is dropped, never refused", async () => {
  for (
    const zone of ["Europe/Berlin", "America/New_York", "UTC", "America/Argentina/Buenos_Aires"]
  ) {
    assertEquals((await create(named({ zone })) as { zone: string }).zone, zone, zone);
  }
  for (const zone of ["+02:00", "Mars/Base", "", "Europe/Berlin; DROP", "a".repeat(65), null, 3]) {
    assertEquals("zone" in (await create(named({ zone })) as object), false, String(zone));
  }
});

Deno.test("keyId: 16 lowercase hex characters, refused otherwise", async () => {
  assertEquals(
    (await create(named({ keyId: "0123456789abcdef" })) as { keyId: string }).keyId,
    "0123456789abcdef",
  );
  assertEquals(await create(named({ keyId: "0123456789ABCDEF" })), "400 invalid keyId");
});

Deno.test("a body that is not JSON, or not an object, is refused", async () => {
  const app = new Hono();
  app.post("/", async (c) => c.json((await validate(c, SPECS.createEvent, ctx)).body ?? {}));
  const notJson = await app.request("/", { method: "POST", body: "{" });
  assertEquals(`${notJson.status} ${await notJson.text()}`, "400 invalid body");
  assertEquals(await create([1]), "400 invalid body");
});

Deno.test("canonicalPlusSeconds: adds whole seconds and stays in the canonical shape", () => {
  assertEquals(canonicalPlusSeconds("2026-06-27T18:00:00Z", WINDOW_MAX), "2026-07-27T18:00:00Z");
  assertEquals(Timestamp.safeParse(canonicalPlusSeconds("2026-06-27T18:00:00Z", 1)).success, true);
  assertEquals(canonicalPlusSeconds("2026-12-31T23:59:59Z", 1), "2027-01-01T00:00:00Z");
  assertEquals(
    canonicalPlusSeconds("2026-02-27T12:00:00Z", 2 * 24 * 60 * 60),
    "2026-03-01T12:00:00Z",
  );
});

Deno.test("canonicalFromMs: renders the canonical shape, rounding DOWN to the second", () => {
  const at = Date.parse("2026-07-27T18:00:00Z");
  assertEquals(canonicalFromMs(at), "2026-07-27T18:00:00Z");
  // Truncated, not rounded: a served deadline lands at or before the real one, so a self-leave can only fire
  // slightly early (recoverable by re-scanning) and never slightly late (a phantom membership).
  assertEquals(canonicalFromMs(at + 999), "2026-07-27T18:00:00Z");
  assertEquals(canonicalFromMs(at + 1000), "2026-07-27T18:00:01Z");
  assertEquals(Timestamp.safeParse(canonicalFromMs(at + 1)).success, true);
});
