import { assertEquals, assertThrows } from "@std/assert";
import { readConfig } from "../src/config.ts";

const FULL = {
  BUNNY_STORAGE_ZONE: "z",
  BUNNY_STORAGE_HOST: "h",
  BUNNY_STORAGE_ACCESS_KEY: "k",
  PUBLIC_BASE_URL: "https://dl.example",
  BUNNY_S3_REGION: "de",
  BUNNY_S3_HOST: "de-s3.storage.bunnycdn.com",
};

Deno.test("readConfig: complete env → Config", () => {
  assertEquals(readConfig(FULL), {
    zone: "z",
    host: "h",
    accessKey: "k",
    baseUrl: "https://dl.example",
    s3Region: "de",
    s3Host: "de-s3.storage.bunnycdn.com",
  });
});

Deno.test("readConfig: missing var → throws naming it", () => {
  assertThrows(
    () => readConfig({ BUNNY_STORAGE_ZONE: "z" }),
    Error,
    "BUNNY_STORAGE_HOST",
  );
});

Deno.test("readConfig: missing S3 config → throws naming it (fail-closed)", () => {
  const { BUNNY_S3_HOST: _omit, ...rest } = FULL;
  assertThrows(() => readConfig(rest), Error, "BUNNY_S3_HOST");
});

Deno.test("readConfig: blank/whitespace var → throws (treated as missing)", () => {
  assertThrows(() => readConfig({ ...FULL, BUNNY_STORAGE_ACCESS_KEY: "   " }), Error);
});

Deno.test("readConfig: missing PUBLIC_BASE_URL → throws naming it (fail-closed)", () => {
  const { PUBLIC_BASE_URL: _omit, ...rest } = FULL;
  assertThrows(() => readConfig(rest), Error, "PUBLIC_BASE_URL");
});

Deno.test("readConfig: blank PUBLIC_BASE_URL → throws (treated as missing)", () => {
  assertThrows(() => readConfig({ ...FULL, PUBLIC_BASE_URL: "   " }), Error, "PUBLIC_BASE_URL");
});

Deno.test("readConfig: trailing slash on PUBLIC_BASE_URL is stripped", () => {
  assertEquals(
    readConfig({ ...FULL, PUBLIC_BASE_URL: "https://dl.example/" }).baseUrl,
    "https://dl.example",
  );
  assertEquals(
    readConfig({ ...FULL, PUBLIC_BASE_URL: "https://dl.example///" }).baseUrl,
    "https://dl.example",
  );
});
