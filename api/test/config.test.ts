import { assertEquals, assertThrows } from "@std/assert";
import { readConfig, readSweepConfig } from "../src/config.ts";

/** Apple's App Attest root — a source constant, asserted here only so the shape stays honest. */
const APPLE_ROOT_CA_PEM = readConfig({
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: "p",
  ATTEST_TOKEN_KEY: "t",
}).appAttestRootCa;

const PEM = "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n";

/** The only values the environment supplies. Everything else is a source constant. */
const SECRETS = {
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: PEM,
  ATTEST_TOKEN_KEY: "t",
};

Deno.test("readConfig: the secrets → Config, with the non-secrets from source", () => {
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
    attestTokenKey: "t",
    appAttestRootCa: APPLE_ROOT_CA_PEM,
    attestTokenTtlSeconds: 30 * 24 * 60 * 60,
    // Derived from the team + bundle constants, never restated — so the gate's app id and the push topic
    // cannot drift apart.
    attestAppId: "E9Z8BADH58.app.snapsync",
    // The event link's domain (capability `event-link`). MUST equal `snapsync.domain` in
    // gradle.properties; a :test:architecture guard holds that seam, since Gradle cannot reach here.
    linkDomain: "snapsync.stho.net",
    appStoreUrl: "https://apps.apple.com/app/id6781692480",
    // The event limits (capability `event-limits`) — the MINT-TIME source only; enforcement reads the
    // fields POST /events stamps onto each marker, so these values never reach an existing event.
    eventCapacity: 10,
    eventWindowMaxSeconds: 30 * 24 * 60 * 60,
    eventLifetimeSeconds: 30 * 24 * 60 * 60,
  });
});

Deno.test("readConfig: missing token signing key → throws naming it (the gate can never be silently absent)", () => {
  const { ATTEST_TOKEN_KEY: _omit, ...rest } = SECRETS;
  assertThrows(() => readConfig(rest), Error, "ATTEST_TOKEN_KEY");
});

Deno.test("readConfig: a retired admin key in the environment is simply unread", () => {
  // Removing a required secret is safe in either deploy order — a value no longer read cannot fail
  // validation — so an Edge Script still carrying ADMIN_NOTIFY_KEY boots and serves, authorizing nothing
  // with it (capability `backend-deployment`).
  const c = readConfig({ ...SECRETS, ADMIN_NOTIFY_KEY: "left-over" });
  assertEquals(Object.hasOwn(c, "adminKey"), false);
});

Deno.test("readSweepConfig: needs ONLY the storage AccessKey (edge-only secrets blank)", () => {
  // The nightly sweep (capability `scheduled-cleanup`) makes no request to the Edge Script, so it holds
  // no credential authorizing one — just the storage key it reads and deletes with.
  const c = readSweepConfig({ BUNNY_STORAGE_ACCESS_KEY: "k" });
  assertEquals(c.accessKey, "k");
  assertEquals(c.apnsPrivateKey, ""); // never used by the sweep
  assertEquals(c.attestTokenKey, "");
  assertEquals(c.zone, "snap-sync-dev"); // source constants still present
  assertEquals(c.eventLifetimeSeconds, 30 * 24 * 60 * 60);
});

Deno.test("readSweepConfig: a missing storage AccessKey throws naming it", () => {
  assertThrows(() => readSweepConfig({}), Error, "BUNNY_STORAGE_ACCESS_KEY");
});

Deno.test("readConfig: blank token signing key → throws (treated as missing)", () => {
  assertThrows(
    () => readConfig({ ...SECRETS, ATTEST_TOKEN_KEY: "   " }),
    Error,
    "ATTEST_TOKEN_KEY",
  );
});

Deno.test("readConfig: nothing but the secrets is required to boot", () => {
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

Deno.test("readConfig: an empty environment names EVERY missing secret", () => {
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
    EVENT_CAPACITY: "9999", // the event limits are source constants like every other non-secret
    EVENT_DURATION_SECONDS: "1",
    EVENT_GRACE_SECONDS: "0",
  });

  assertEquals(config.zone, "snap-sync-dev");
  assertEquals(config.host, "storage.bunnycdn.com");
  assertEquals(config.s3Region, "de");
  assertEquals(config.s3Host, "de-s3.storage.bunnycdn.com");
  assertEquals(config.apnsKeyId, "W34NF6UMVU");
  assertEquals(config.apnsTeamId, "E9Z8BADH58");
  assertEquals(config.apnsTopic, "app.snapsync");
  assertEquals(config.eventCapacity, 10);
  assertEquals(config.eventWindowMaxSeconds, 30 * 24 * 60 * 60);
  assertEquals(config.eventLifetimeSeconds, 30 * 24 * 60 * 60);
});
