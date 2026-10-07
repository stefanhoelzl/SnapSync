// The event page's server half (capabilities `event-site`, `privacy-security`): every word the route fills into
// the built page, pinned per state without a database.

import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import type { EventRow } from "../src/db.ts";
import {
  dateRange,
  escapeHtml,
  eventFilling,
  fill,
  invalidFilling,
  pendingFilling,
  phaseOf,
  TOKENS,
} from "../src/routes/event-page.ts";

const ORIGIN = "https://snapsync.example";
const ID = "3f2c0000-0000-4000-8000-00000000e91a";

// A Berlin host's "Sun 4 Oct – Mon 5 Oct 2026": local midnight Sunday to 20:00 Monday, in CEST (UTC+2).
const BERLIN: EventRow = {
  eventId: ID,
  name: "Anna's 40th",
  createdAt: "2026-10-01T10:00:00.000Z",
  startsAt: "2026-10-03T22:00:00Z",
  endsAt: "2026-10-05T18:00:00Z",
  capacity: 10,
  lifetimeSeconds: 30 * 86400,
  zone: "Europe/Berlin",
};
const DELETES_AT = "2026-11-02T22:00:00Z";
const ms = (iso: string) => Date.parse(iso);

Deno.test("escapeHtml escapes every character that could leave text or a quoted attribute", () => {
  assertEquals(
    escapeHtml(`<script>"a" & 'b'</script>`),
    "&lt;script&gt;&quot;a&quot; &amp; &#39;b&#39;&lt;/script&gt;",
  );
});

Deno.test("a name is escaped wherever it is filled, so it cannot open a tag or an attribute", () => {
  const evil = { ...BERLIN, name: `"><script>alert(1)</script>` };
  const f = eventFilling(
    ORIGIN,
    evil,
    { active: 2, final: 0 },
    ms("2026-10-04T12:00:00Z"),
    DELETES_AT,
  );
  for (const token of TOKENS) assert(!f[token].includes("<script>"), token);
  assertEquals(f.OG_TITLE, "&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;");
});

Deno.test("the dates are the host's: a Berlin event starts on Sunday, not on Saturday as in UTC", () => {
  assertEquals(dateRange(BERLIN), "Sun 4 Oct – Mon 5 Oct 2026");
});

Deno.test("an event that kept no zone shows its dates in UTC", () => {
  assertEquals(dateRange({ ...BERLIN, zone: null }), "Sat 3 Oct – Mon 5 Oct 2026");
});

Deno.test("a one-day event names its day once, and a range across a new year names both years", () => {
  assertEquals(
    dateRange({ ...BERLIN, startsAt: "2026-10-04T08:00:00Z", endsAt: "2026-10-04T18:00:00Z" }),
    "Sun 4 Oct 2026",
  );
  assertEquals(
    dateRange({ ...BERLIN, startsAt: "2026-12-30T23:00:00Z", endsAt: "2027-01-02T12:00:00Z" }),
    "Thu 31 Dec 2026 – Sat 2 Jan 2027",
  );
});

Deno.test("the phase follows the clock: upcoming, running, ended", () => {
  assertEquals(phaseOf(BERLIN, ms("2026-10-03T21:59:59Z")), "upcoming");
  assertEquals(phaseOf(BERLIN, ms("2026-10-03T22:00:00Z")), "running");
  assertEquals(phaseOf(BERLIN, ms("2026-10-05T18:00:00Z")), "ended");
});

Deno.test("an upcoming event says when it starts", () => {
  const f = eventFilling(
    ORIGIN,
    BERLIN,
    { active: 2, final: 0 },
    ms("2026-10-02T12:00:00Z"),
    DELETES_AT,
  );
  assertStringIncludes(f.PILL, "Starts Sun 4 Oct");
  assertEquals(f.DESCRIPTION, "Starts Sun 4 Oct · 2 members");
});

Deno.test("a running event names itself, its dates and its members", () => {
  const f = eventFilling(
    ORIGIN,
    BERLIN,
    { active: 6, final: 0 },
    ms("2026-10-04T12:00:00Z"),
    DELETES_AT,
  );
  assertEquals(f.VIEW, "event");
  assertEquals(f.OG_TITLE, "Anna&#39;s 40th");
  assertEquals(f.TITLE, "Anna&#39;s 40th — SnapSync");
  assertEquals(f.HEADING, "Anna&#39;s 40th");
  assertEquals(f.DESCRIPTION, "Sun 4 Oct – Mon 5 Oct 2026 · 6 members");
  assertEquals(f.URL, `${ORIGIN}/join/${ID}`);
  assertStringIncludes(f.PILL, "Happening now");
  assertStringIncludes(f.FACTS, "6 members sharing");
});

Deno.test("an ended event still settling counts who is done and says until when the photos stay", () => {
  const f = eventFilling(
    ORIGIN,
    BERLIN,
    { active: 6, final: 4 },
    ms("2026-10-06T12:00:00Z"),
    DELETES_AT,
  );
  assertStringIncludes(f.PILL, "Ended — photos still arriving");
  assertEquals(f.DESCRIPTION, "Ended Mon 5 Oct · 4 of 6 members done sharing");
  assertStringIncludes(f.FACTS, "4 of 6 members done sharing");
  assertStringIncludes(f.FACTS, "Available until Mon 2 Nov at the latest"); // 22:00Z is Monday in Berlin (CET)
  assertEquals((f.FACTS.match(/class="dot done"/g) ?? []).length, 4);
  assertEquals((f.FACTS.match(/class="dot"/g) ?? []).length, 2);
});

Deno.test("a closed event, every member settled, reads as ended with all done", () => {
  const f = eventFilling(
    ORIGIN,
    BERLIN,
    { active: 3, final: 3 },
    ms("2026-10-06T12:00:00Z"),
    DELETES_AT,
  );
  assertEquals(f.PILL, `<span class="pill ended">Ended</span>`);
  assertStringIncludes(f.FACTS, "All 3 members done sharing");
});

Deno.test("the invalid and the pending page name no event", () => {
  for (const f of [invalidFilling(ORIGIN), pendingFilling(ORIGIN)]) {
    for (const token of TOKENS) assert(!f[token].includes("Anna"), token);
  }
  assertEquals(invalidFilling(ORIGIN).VIEW, "invalid");
  assertEquals(invalidFilling(ORIGIN).OG_TITLE, "SnapSync — link expired");
  assertEquals(pendingFilling(ORIGIN).VIEW, "pending");
});

Deno.test("an encrypted event's page carries its key id; a plain event's and the generic pages carry none", () => {
  const at = ms("2026-10-04T12:00:00Z");
  const members = { active: 1, final: 0 };
  assertEquals(
    eventFilling(ORIGIN, { ...BERLIN, keyId: "q1w2e3r4t5y6" }, members, at, DELETES_AT).KEY_ID,
    "q1w2e3r4t5y6",
  );
  assertEquals(
    eventFilling(ORIGIN, { ...BERLIN, keyId: '"><x' }, members, at, DELETES_AT).KEY_ID,
    "&quot;&gt;&lt;x",
  );
  assertEquals(eventFilling(ORIGIN, BERLIN, members, at, DELETES_AT).KEY_ID, "");
  assertEquals(
    eventFilling(ORIGIN, { ...BERLIN, keyId: null }, members, at, DELETES_AT).KEY_ID,
    "",
  );
  assertEquals(invalidFilling(ORIGIN).KEY_ID, "");
  assertEquals(pendingFilling(ORIGIN).KEY_ID, "");
});

Deno.test("fill replaces every occurrence of every token", () => {
  const template = TOKENS.map((t) => `%%${t}%%|%%${t}%%`).join("\n");
  const out = fill(template, invalidFilling(ORIGIN));
  assert(!out.includes("%%"));
});
