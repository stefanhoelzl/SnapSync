// An encrypted event's name, SEALED (decision record `changes/seal-event-name`): the create's and the rename's
// pairing rules (D3/D4), the bodies every shape of row serves, and the event page's preview name (D5) — through
// the real routes.

import { assert, assertEquals } from "@std/assert";
import { encodeHex } from "@std/encoding/hex";
import { createApp as createSiteApp, type FetchLike } from "../src/app.ts";
import { readConfig } from "../src/config.ts";
import { type Db, insertEvent } from "../src/db.ts";
import { keyIdOf } from "../src/encrypted-file.ts";
import { sealRecord } from "../src/sealed-meta.ts";
import { emptyStore } from "./support/db.ts";
import { CONFIG, createApp, E, EVENT, recorder, rows, store, V2 } from "./support/harness.ts";

const EVENT_KEY = Uint8Array.from({ length: 32 }, (_, i) => i * 3);
const KEY_ID = encodeHex(await keyIdOf(EVENT_KEY));
const SEALED = await sealRecord(EVENT_KEY, { name: "Ana's 30th" });
const RESEALED = await sealRecord(EVENT_KEY, { name: "Ana's 31st" });

const json = { ...V2, "content-type": "application/json" };

function api(db: Db) {
  const app = createApp({ config: CONFIG, db, fetch: recorder().fetchImpl });
  return {
    create: (body: unknown) =>
      app.request("/api/v2/events", { method: "POST", headers: json, body: JSON.stringify(body) }),
    rename: (id: string, body: unknown) =>
      app.request(`/api/v2/events/${id}`, {
        method: "PATCH",
        headers: json,
        body: JSON.stringify(body),
      }),
    read: (id: string) => app.request(`/api/v2/events/${id}`, { headers: V2 }),
  };
}

Deno.test("create: an encrypted event takes a sealed record and no name; a plain one the reverse", async () => {
  const db = await store();
  const { create, read } = api(db);
  const startsAt = EVENT.startsAt;

  const sealed = await create({ sealedMeta: SEALED, startsAt, keyId: KEY_ID });
  assertEquals(sealed.status, 201);
  const body = await sealed.json();
  assertEquals(body.sealedMeta, SEALED);
  assert(!("name" in body), "a sealed event's body carries no readable name");
  const stored =
    (await rows(db, `SELECT name, sealed_meta FROM events WHERE id = ?`, [body.eventId]))[0];
  assertEquals([stored.name, stored.sealed_meta], [null, SEALED], "the row holds no readable name");
  const served = await (await read(body.eventId)).json();
  assertEquals([served.sealedMeta, "name" in served], [SEALED, false]);

  const plain = await (await create({ name: "Picnic", startsAt })).json();
  assertEquals(plain.name, "Picnic");
  assert(!("sealedMeta" in plain), "a plain event's body is what every installed build parses");

  for (
    const [why, bad] of [
      ["encrypted with a readable name", { name: "Secret", startsAt, keyId: KEY_ID }],
      ["encrypted with both", { name: "Secret", sealedMeta: SEALED, startsAt, keyId: KEY_ID }],
      ["encrypted with neither", { startsAt, keyId: KEY_ID }],
      ["plain with a record", { sealedMeta: SEALED, startsAt }],
      ["plain with neither", { startsAt }],
      ["plain with a null name", { name: null, startsAt }],
    ] as const
  ) {
    const res = await create(bad);
    assertEquals([res.status, await res.text()], [400, "invalid name"], why);
  }
  for (
    const bad of [
      SEALED + "==",
      "A".repeat(38),
      "A".repeat(1025),
      // A well-formed envelope of a version this backend does not know.
      "Ag" + SEALED.slice(2),
    ]
  ) {
    const res = await create({ sealedMeta: bad, startsAt, keyId: KEY_ID });
    assertEquals([res.status, await res.text()], [400, "invalid sealedMeta"], bad.slice(0, 12));
  }
  db.close();
});

Deno.test("rename: each shape of row takes what it may hold, and a sealed rename seals a legacy row", async () => {
  const db = await store();
  const { rename } = api(db);
  const ids = {
    plain: crypto.randomUUID(),
    sealed: crypto.randomUUID(),
    legacy: crypto.randomUUID(),
  };
  await insertEvent(db, { ...EVENT, eventId: ids.plain });
  await insertEvent(db, {
    ...EVENT,
    eventId: ids.sealed,
    name: null,
    sealedMeta: SEALED,
    keyId: KEY_ID,
  });
  await insertEvent(db, { ...EVENT, eventId: ids.legacy, name: "Legacy", keyId: KEY_ID });

  const refused = async (id: string, body: unknown, why: string) => {
    const res = await rename(id, body);
    assertEquals([res.status, await res.text()], [400, "invalid name"], why);
  };
  await refused(ids.plain, { sealedMeta: RESEALED }, "a plain event takes no record");
  await refused(ids.sealed, { name: "Readable" }, "a sealed event takes no readable name");
  await refused(ids.legacy, { name: "x", sealedMeta: RESEALED }, "never both");

  assertEquals((await (await rename(ids.plain, { name: "Picnic 2" })).json()).name, "Picnic 2");

  const resealed = await (await rename(ids.sealed, { sealedMeta: RESEALED })).json();
  assertEquals([resealed.sealedMeta, "name" in resealed], [RESEALED, false]);

  // A build from before sealed names still renames a legacy event readably…
  assertEquals((await (await rename(ids.legacy, { name: "Legacy 2" })).json()).name, "Legacy 2");
  // …and the first sealed rename moves it to the sealed shape, its readable name leaving the row.
  const moved = await (await rename(ids.legacy, { sealedMeta: RESEALED })).json();
  assertEquals([moved.sealedMeta, "name" in moved], [RESEALED, false]);
  const row =
    (await rows(db, `SELECT name, sealed_meta FROM events WHERE id = ?`, [ids.legacy]))[0];
  assertEquals([row.name, row.sealed_meta], [null, RESEALED]);
  db.close();
});

Deno.test("a legacy encrypted event's body still carries its readable name", async () => {
  const db = await store();
  await insertEvent(db, { ...EVENT, keyId: KEY_ID });
  const body = await (await api(db).read(E)).json();
  assertEquals([body.name, body.keyId, "sealedMeta" in body], [EVENT.name, KEY_ID, false]);
  db.close();
});

// ── The event page's preview name ──────────────────────────────────────────────────────────────────

const SITE_CONFIG = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
  BUNNY_DATABASE_URL: "libsql://example.invalid",
  BUNNY_DATABASE_AUTH_TOKEN: "dbt",
  ADMIN_NOTIFY_KEY: "a",
});
const TEMPLATE =
  `<title>%%TITLE%%</title><meta property="og:title" content="%%OG_TITLE%%"><h1>%%HEADING%%</h1>`;
const template: FetchLike = () => Promise.resolve(new Response(TEMPLATE, { status: 200 }));
const PAGE_ID = "3f2c0000-0000-4000-8000-00000000e91b";
const PAGE_NOW = Date.parse("2026-10-04T12:00:00Z");

async function page(
  row: { name: string | null; sealedMeta?: string; keyId?: string; completedAt?: string },
) {
  const db = await emptyStore();
  await insertEvent(db, {
    eventId: PAGE_ID,
    createdAt: "2026-10-01T10:00:00.000Z",
    startsAt: "2026-10-03T22:00:00Z",
    endsAt: "2026-10-05T18:00:00Z",
    openUntil: "2026-10-05T18:00:00Z",
    capacity: 10,
    lifetimeSeconds: 30 * 86400,
    zone: "Europe/Berlin",
    ...row,
  });
  if (row.completedAt) await db.execute(`UPDATE events SET completed_at = ?`, [row.completedAt]);
  const app = createSiteApp({ config: SITE_CONFIG, db, fetch: template, now: () => PAGE_NOW });
  return async (query = "") => {
    const res = await app.request(`/join/${PAGE_ID}${query}`);
    return { status: res.status, html: await res.text() };
  };
}

const sealedRow = { name: null, sealedMeta: SEALED, keyId: KEY_ID };

// @Verifies(spec = "event-site", requirement = "A shared invite previews its event", scenario = "Sharing an encrypted event's invite in a chat")
// The preview names the event from the invite's own preview name, which the service cannot read from its row.
Deno.test("event page: a sealed event is named by the invite's preview name, escaped and cut to 100", async () => {
  const open = await page(sealedRow);
  const named = await open(`?n=${encodeURIComponent("Ana's 30th")}`);
  assertEquals(named.status, 200);
  assert(named.html.includes("<title>Ana&#39;s 30th — SnapSync</title>"), named.html);
  assert(named.html.includes(`content="Ana&#39;s 30th"`), named.html);
  assert(named.html.includes("<h1>Ana&#39;s 30th</h1>"), named.html);

  const hostile = await open(`?n=${encodeURIComponent(`<script>alert(1)</script>`)}`);
  assert(!hostile.html.includes("<script>"), "the preview name is HTML-escaped");

  const long = await open(`?n=${encodeURIComponent("🎉".repeat(150))}`);
  assert(
    long.html.includes(`<h1>${"🎉".repeat(100)}</h1>`),
    "cut to the name rule's 100 characters",
  );
});

// @Verifies(spec = "event-site", requirement = "An invite link without the app opens the event's page", scenario = "An encrypted event without scripts and without a preview name")
// Without a preview name the server has no name to render: the page names no event.
Deno.test("event page: a sealed event without a preview name names no event", async () => {
  const open = await page(sealedRow);
  for (const query of ["", "?n=", "?n=%20%20"]) {
    const { status, html } = await open(query);
    assertEquals(status, 200);
    assert(html.includes("<title>SnapSync — event photos</title>"), `${query}: ${html}`);
    assert(html.includes("<h1>Event photos</h1>"), `${query}: ${html}`);
    assert(!html.includes("Ana"), "no name the row holds leaks in");
  }
});

Deno.test("event page: a row's own name wins over any preview name the invite carries", async () => {
  for (const row of [{ name: "Picnic" }, { name: "Legacy", keyId: KEY_ID }]) {
    const { html } = await (await page(row))(`?n=${encodeURIComponent("Forged")}`);
    assert(html.includes(`<h1>${row.name}</h1>`) && !html.includes("Forged"), html);
  }
});

// @Verifies(spec = "event-site", requirement = "A shared invite previews its event", scenario = "An expired encrypted invite's preview names nothing")
// A deleted event's page is the invalid view, whatever the invite says it is called.
Deno.test("event page: a deleted sealed event names nothing, whatever the invite carries", async () => {
  const { status, html } =
    await (await page({ ...sealedRow, completedAt: "2026-10-04T00:00:00Z" }))(
      `?n=${encodeURIComponent("Ana's 30th")}`,
    );
  assertEquals(status, 410);
  assert(!html.includes("Ana"), html);
});
