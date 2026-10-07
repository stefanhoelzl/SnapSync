// The event page's half of an encrypted event (the encrypted file format, `docs/architecture.md`): the invite's key is
// read from the fragment alone and checked against the event's key id before any photo is fetched.
import { assert, assertEquals } from "jsr:@std/assert@^1";
import { keyFromFragment, keyTextFromFragment, opens } from "../src/lib/event-key.ts";

const KEY = Uint8Array.from({ length: 32 }, (_, i) => i);
// From test/vectors/encrypted-file.json: the key id of the bytes 0..31.
const KEY_ID = "de30249b854310c8";
const K = btoa(String.fromCharCode(...KEY))
  .replace(/\+/g, "-")
  .replace(/\//g, "_")
  .replace(/=+$/, "");

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

Deno.test("a key opens only the event whose key id it is", async () => {
  assert(await opens(KEY, KEY_ID));
  assert(!(await opens(new Uint8Array(32), KEY_ID)));
});
