import { assertEquals, assertThrows } from "@std/assert";
import { readConfig } from "../src/config.ts";

const PEM = "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n";

/** The only two values the environment supplies. Everything else is a source constant. */
const SECRETS = {
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: PEM,
};

Deno.test("readConfig: the two secrets → Config, with the non-secrets from source", () => {
  assertEquals(readConfig(SECRETS), {
    zone: "snap-sync-dev",
    host: "storage.bunnycdn.com",
    accessKey: "k",
    s3Region: "de",
    s3Host: "de-s3.storage.bunnycdn.com",
    apnsKeyId: "W34NF6UMVU",
    apnsTeamId: "E9Z8BADH58",
    apnsPrivateKey: PEM,
    apnsTopic: "app.snapsync",
  });
});

Deno.test("readConfig: nothing but the two secrets is required to boot", () => {
  // An otherwise-empty environment is enough. This is the whole point: a new non-secret config value
  // ships with the code that reads it, so a deploy can never be missing one.
  const config = readConfig(SECRETS);
  assertEquals(config.zone, "snap-sync-dev");
  assertEquals(config.apnsTopic, "app.snapsync");
});

Deno.test("readConfig: missing storage AccessKey → throws naming it (fail-closed)", () => {
  const { BUNNY_STORAGE_ACCESS_KEY: _omit, ...rest } = SECRETS;
  assertThrows(() => readConfig(rest), Error, "BUNNY_STORAGE_ACCESS_KEY");
});

Deno.test("readConfig: blank storage AccessKey → throws (treated as missing)", () => {
  assertThrows(
    () => readConfig({ ...SECRETS, BUNNY_STORAGE_ACCESS_KEY: "   " }),
    Error,
    "BUNNY_STORAGE_ACCESS_KEY",
  );
});

Deno.test("readConfig: missing APNs private key → throws naming it (fail-closed)", () => {
  const { APNS_PRIVATE_KEY: _omit, ...rest } = SECRETS;
  assertThrows(() => readConfig(rest), Error, "APNS_PRIVATE_KEY");
});

Deno.test("readConfig: blank APNs private key → throws (treated as missing)", () => {
  assertThrows(
    () => readConfig({ ...SECRETS, APNS_PRIVATE_KEY: "   \n  " }),
    Error,
    "APNS_PRIVATE_KEY",
  );
});

Deno.test("readConfig: an empty environment names BOTH missing secrets", () => {
  assertThrows(() => readConfig({}), Error, "BUNNY_STORAGE_ACCESS_KEY");
  assertThrows(() => readConfig({}), Error, "APNS_PRIVATE_KEY");
});

Deno.test("readConfig: APNs private key is preserved verbatim (trailing newline not trimmed)", () => {
  assertEquals(readConfig({ ...SECRETS, APNS_PRIVATE_KEY: PEM }).apnsPrivateKey, PEM);
});

// SOURCE WINS. The regression this pins is real: the dead Edge Script carried a stale
// BUNNY_STORAGE_ZONE=`snap-sync` — a zone that does not exist — while production ran on
// `snap-sync-dev`. Were env allowed to override a source constant, that leftover platform variable
// would silently repoint the backend at a nonexistent bucket on the first boot.
Deno.test("readConfig: a platform variable NEVER overrides a source constant", () => {
  const config = readConfig({
    ...SECRETS,
    BUNNY_STORAGE_ZONE: "snap-sync", // the stale value that was actually set on the Edge Script
    BUNNY_STORAGE_HOST: "replica.example",
    BUNNY_S3_REGION: "us",
    BUNNY_S3_HOST: "us-s3.example",
    APNS_KEY_ID: "STALEKEYID",
    APNS_TEAM_ID: "STALETEAM",
    APNS_TOPIC: "app.imposter",
    PUBLIC_BASE_URL: "https://imposter.example", // the deleted variable, for good measure
  });

  assertEquals(config.zone, "snap-sync-dev");
  assertEquals(config.host, "storage.bunnycdn.com");
  assertEquals(config.s3Region, "de");
  assertEquals(config.s3Host, "de-s3.storage.bunnycdn.com");
  assertEquals(config.apnsKeyId, "W34NF6UMVU");
  assertEquals(config.apnsTeamId, "E9Z8BADH58");
  assertEquals(config.apnsTopic, "app.snapsync");
});
