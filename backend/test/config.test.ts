import { assertEquals, assertThrows } from "@std/assert";
import { readConfig } from "../src/config.ts";

const FULL = {
  BUNNY_STORAGE_ZONE: "z",
  BUNNY_STORAGE_HOST: "h",
  BUNNY_STORAGE_ACCESS_KEY: "k",
};

Deno.test("readConfig: complete env → Config", () => {
  assertEquals(readConfig(FULL), { zone: "z", host: "h", accessKey: "k" });
});

Deno.test("readConfig: missing var → throws naming it", () => {
  assertThrows(
    () => readConfig({ BUNNY_STORAGE_ZONE: "z" }),
    Error,
    "BUNNY_STORAGE_HOST",
  );
});

Deno.test("readConfig: blank/whitespace var → throws (treated as missing)", () => {
  assertThrows(() => readConfig({ ...FULL, BUNNY_STORAGE_ACCESS_KEY: "   " }), Error);
});
