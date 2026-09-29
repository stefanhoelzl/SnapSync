// THE REHEARSAL COPY'S PROMISES (`docs/deployment.md`, "Gates"): what a migration may rely on survives the
// pseudonymisation, and what is secret does not. No network — the store-to-store copy is exercised by the
// `migration-rehearsal` job itself; this pins the rewrite, the allowlist and the two safety guards.

import { assert, assertEquals, assertMatch, assertNotEquals } from "@std/assert";
import {
  isLoopbackTarget,
  isPseudonymised,
  Pseudonymiser,
  safeMessage,
  VERBATIM_TABLES,
} from "../../src/scripts/rehearsal-copy.ts";

const fixed = () => Pseudonymiser.create(new Uint8Array(32).fill(7));

Deno.test("a value keeps its shape: token lengths, character classes and every separator", async () => {
  const p = await fixed();
  const uuid = "53293B29-DBE2-4675-94D4-868D40066C6D";
  const out = await p.value(`${uuid}_L0_001`);
  assertMatch(
    out,
    /^[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}_[A-Z][0-9]_[0-9]{3}$/,
  );
  assertNotEquals(out.slice(0, 36), uuid);
  assertMatch(await p.value("f3d78aab-6664-4108"), /^[0-9a-f]{8}-[0-9]{4}-[0-9]{4}$/);
  assertMatch(await p.value("Wedding Anna 2026"), /^[A-Z][a-z]{6} [A-Z][a-z]{3} [0-9]{4}$/);
});

Deno.test("equal tokens map equally ACROSS values — an id embedded in a key still joins", async () => {
  const p = await fixed();
  const asset = await p.value("C8525771-6097-45B2-8EA4-263EA8174C24_L0_001");
  const key = await p.value("C8525771-6097-45B2-8EA4-263EA8174C24_L0_001-primary.heic");
  assert(
    key.startsWith(asset),
    "the asset id inside the object key maps to the asset id's pseudonym",
  );
});

Deno.test("distinct tokens never share a pseudonym — even in a space small enough to collide", async () => {
  const p = await fixed();
  // 100 distinct two-digit tokens into a 100-value space: without collision resolution this collides.
  const seen = new Set<string>();
  for (let i = 0; i < 100; i++) seen.add(await p.value(String(i).padStart(2, "0")));
  assertEquals(seen.size, 100);
});

Deno.test("the map is per run: two random keys give two unrelated pseudonyms", async () => {
  const [a, b] = [await Pseudonymiser.create(), await Pseudonymiser.create()];
  const id = "DD92FAC9-5629-4A55-A755-34FBE1487CCE";
  assertNotEquals(await a.value(id), await b.value(id));
});

Deno.test("secrets are rewritten; structural columns and the runner's history are not", () => {
  for (
    const [table, column] of [
      ["devices", "device_id"],
      ["devices", "attest_key"],
      ["devices", "push_token"],
      ["events", "id"],
      ["events", "name"],
      ["memberships", "event_id"],
      ["resources", "filename"],
      ["resources", "key"],
    ]
  ) assert(isPseudonymised(table, column), `${table}.${column} must be pseudonymised`);
  for (
    const [table, column] of [
      ["memberships", "state"],
      ["resources", "role"],
      ["event_assets", "roles"],
      ["events", "ends_at"],
    ]
  ) assert(!isPseudonymised(table, column), `${table}.${column} is structural`);
  assert(VERBATIM_TABLES.has("__bunny_migrations"));
  assert(!isPseudonymised("__bunny_migrations", "checksum"));
});

Deno.test("the allowlist is closed: an unknown column or table is pseudonymised", () => {
  assert(isPseudonymised("devices", "some_future_column"));
  assert(isPseudonymised("some_future_table", "created_at"));
});

Deno.test("an error message keeps its first line and loses every quoted literal", () => {
  const leaked = new Error(
    `near "Type": syntax error in INSERT INTO "devices" VALUES ('1671B734-829E', 'BOdZ7+4k');\nINSERT …`,
  );
  const safe = safeMessage(leaked);
  assert(!safe.includes("1671B734"), safe);
  assert(!safe.includes("BOdZ7"), safe);
  assert(!safe.includes("\n"));
});

Deno.test("the copy writes only to a loopback http server", () => {
  assert(isLoopbackTarget("http://127.0.0.1:8080"));
  assert(isLoopbackTarget("http://localhost:8080"));
  assert(!isLoopbackTarget("libsql://01M0JP1YWS6JTXQ1EERHKC610C-snap-sync.lite.bunnydb.net"));
  assert(!isLoopbackTarget("https://127.0.0.1:8443"));
  assert(!isLoopbackTarget("http://10.0.0.1:8080"));
  assert(!isLoopbackTarget("not a url"));
});
