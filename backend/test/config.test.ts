import { assertEquals, assertThrows } from "@std/assert";
import { readConfig } from "../src/config.ts";

const FULL = {
  BUNNY_STORAGE_ZONE: "z",
  BUNNY_STORAGE_HOST: "h",
  BUNNY_STORAGE_ACCESS_KEY: "k",
  PUBLIC_BASE_URL: "https://dl.example",
  BUNNY_S3_REGION: "de",
  BUNNY_S3_HOST: "de-s3.storage.bunnycdn.com",
  APNS_KEY_ID: "ABC123KEYID",
  APNS_TEAM_ID: "E9Z8BADH58",
  APNS_PRIVATE_KEY: "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n",
  APNS_TOPIC: "app.snapsync",
};

Deno.test("readConfig: complete env → Config", () => {
  assertEquals(readConfig(FULL), {
    zone: "z",
    host: "h",
    accessKey: "k",
    baseUrl: "https://dl.example",
    s3Region: "de",
    s3Host: "de-s3.storage.bunnycdn.com",
    apnsKeyId: "ABC123KEYID",
    apnsTeamId: "E9Z8BADH58",
    apnsPrivateKey: "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n",
    apnsTopic: "app.snapsync",
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

Deno.test("readConfig: missing each APNs var → throws naming it (fail-closed)", () => {
  for (const name of ["APNS_KEY_ID", "APNS_TEAM_ID", "APNS_PRIVATE_KEY", "APNS_TOPIC"]) {
    const { [name]: _omit, ...rest } = FULL as Record<string, string>;
    assertThrows(() => readConfig(rest), Error, name);
  }
});

Deno.test("readConfig: blank APNs private key → throws (treated as missing)", () => {
  assertThrows(
    () => readConfig({ ...FULL, APNS_PRIVATE_KEY: "   \n  " }),
    Error,
    "APNS_PRIVATE_KEY",
  );
});

Deno.test("readConfig: APNs private key is preserved verbatim (trailing newline not trimmed)", () => {
  const pem = "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n";
  assertEquals(readConfig({ ...FULL, APNS_PRIVATE_KEY: pem }).apnsPrivateKey, pem);
});
