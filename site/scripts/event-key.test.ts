// The event page's half of an encrypted event (the encrypted file format, `docs/architecture.md`): the invite's key is
// read from the fragment alone and checked against the event's key id before any photo is fetched.
import { assert, assertEquals } from "jsr:@std/assert@^1";
import { keyFromFragment, keyTextFromFragment, opens, sealedName } from "../src/lib/event-key.ts";
import { sealRecord } from "../../api/src/sealed-meta.ts";

const KEY = Uint8Array.from({ length: 32 }, (_, i) => i);
// From test/vectors/encrypted-file.json: the key id of the bytes 0..31.
const KEY_ID = "de30249b854310c8";
const K = btoa(String.fromCharCode(...KEY))
  .replace(/\+/g, "-")
  .replace(/\//g, "_")
  .replace(/=+$/, "");

// @Verifies(spec = "event-site", requirement = "An encrypted event's photos open only with the whole invite", scenario = "An invite without its key")
// The page's part: a cut-off key opens nothing.
Deno.test("the invite's k is the event key, and nothing else is", () => {
  assertEquals(keyFromFragment("#k=" + K), KEY);
  assertEquals(keyFromFragment("#autoJoin=true&k=" + K), KEY);
  assertEquals(keyFromFragment(""), null);
  assertEquals(keyFromFragment("#k=" + K.slice(1)), null);
  assertEquals(keyFromFragment("#k=" + K + "A"), null);
  assertEquals(keyFromFragment("#v=3&d=abc"), null);
});

Deno.test("the invite's k is handed on exactly as the link spells it", () => {
  assertEquals(keyTextFromFragment("#autoJoin=true&k=" + K), K);
  assertEquals(keyTextFromFragment("#k=" + K + "A"), null);
  assertEquals(keyTextFromFragment(""), null);
});

// @Verifies(spec = "event-site", requirement = "An encrypted event's photos open only with the whole invite", scenario = "The whole invite downloads the photos")
// The page's part: the whole invite's key opens the event's files.
Deno.test("a key opens only the event whose key id it is", async () => {
  assert(await opens(KEY, KEY_ID));
  assert(!(await opens(new Uint8Array(32), KEY_ID)));
});

// @Verifies(spec = "event-site", requirement = "An invite link without the app opens the event's page", scenario = "The members' name replaces a stale preview name")
// The page's part: what replaces the server-rendered preview name is the name the event's record opens to.
Deno.test(
  "a sealed name opens with the invite's key, trimmed, and a record it does not open names nothing",
  async () => {
    assertEquals(
      await sealedName(KEY, await sealRecord(KEY, { name: " Ana's 30th " })),
      "Ana's 30th",
    );
    // Keys a newer build wrote are carried and ignored.
    assertEquals(await sealedName(KEY, await sealRecord(KEY, { name: "Ski", future: 1 })), "Ski");
    const other = new Uint8Array(32).fill(9);
    assertEquals(
      await sealedName(other, await sealRecord(KEY, { name: "Ana's 30th" })),
      null,
      "another key",
    );
    assertEquals(
      await sealedName(KEY, await sealRecord(KEY, { name: "   " })),
      null,
      "a blank name",
    );
    assertEquals(await sealedName(KEY, "AQ" + "A".repeat(40)), null, "altered");
    assertEquals(await sealedName(KEY, ""), null, "none");
  },
);
