import { assertEquals, assertThrows } from "@std/assert";
import { DEPLOYMENT, readConfig, readSweepConfig } from "../src/config.ts";
import { isBunnyDeployment } from "../src/deployment.ts";

/** The bunny branch of the resolved deployment — every non-secret assertion below is derived from it. */
const D = isBunnyDeployment(DEPLOYMENT) ? DEPLOYMENT : (() => {
  throw new Error("these tests describe a deployed backend; resolve a bunny deployment");
})();

const PEM = "-----BEGIN PRIVATE KEY-----\nMIG...\n-----END PRIVATE KEY-----\n";

/** The only values the environment supplies. Everything else comes from the resolved deployment. */
const SECRETS = {
  BUNNY_STORAGE_ACCESS_KEY: "k",
  APNS_PRIVATE_KEY: PEM,
  ATTEST_TOKEN_KEY: "t",
  BUNNY_DATABASE_URL: "libsql://example.invalid",
  BUNNY_DATABASE_AUTH_TOKEN: "dbt",
};

Deno.test("readConfig: the secrets → Config, with every non-secret from the resolved deployment", () => {
  // Deliberately asserted against the RESOLVED DEPLOYMENT rather than against literals. Pinning
  // `zone: "snap-sync-dev"` here would test CONFIGURATION, not behaviour — it would turn this suite red
  // on any deployment change, and it would pass just as happily if readConfig ignored the deployment
  // and returned constants of its own.
  assertEquals(readConfig(SECRETS), {
    zone: D.storage.zone,
    host: D.storage.host,
    accessKey: "k",
    s3Region: D.storage.s3Region,
    // DERIVED from the region, never restated — the two used to be separate constants that could
    // disagree, and a wrong S3 host mints presigned URLs that 403 at download while all else looks fine.
    s3Host: `${D.storage.s3Region}-s3.storage.bunnycdn.com`,
    s3Scheme: "https",
    apnsKeyId: D.apnsKeyId,
    apnsTeamId: D.teamId,
    apnsPrivateKey: PEM,
    // The push topic IS the bundle id, derived rather than restated.
    apnsTopic: D.bundleId,
    attestTokenKey: "t",
    databaseUrl: "libsql://example.invalid",
    databaseToken: "dbt",
    appAttestRootCa: D.appAttestRootCa,
    attestTokenTtlSeconds: D.attestTokenTtlSeconds,
    // Derived from the team + bundle ids, so the gate's app id and the push topic cannot drift apart.
    attestAppId: `${D.teamId}.${D.bundleId}`,
    // The event link's domain (capability `event-link`). The app's entitlement and LINK_ORIGIN are
    // GENERATED from this same value now, so agreement is constructed rather than asserted.
    linkDomain: D.domain,
    appStoreUrl: D.appStoreUrl,
    // The event limits (capability `event-limits`) — the MINT-TIME source only; enforcement reads the
    // fields POST /events stamps onto each marker, so these values never reach an existing event.
    eventCapacity: D.eventCapacity,
    eventWindowMaxSeconds: D.eventWindowMaxSeconds,
    eventLifetimeSeconds: D.eventLifetimeSeconds,
  });
});

Deno.test("readConfig: the derived fields are composed, not restated", () => {
  // The three derivations are the whole reason a wrong value cannot be introduced in one place only.
  const c = readConfig(SECRETS);
  assertEquals(c.apnsTopic, D.bundleId);
  assertEquals(c.attestAppId, `${c.apnsTeamId}.${c.apnsTopic}`);
  assertEquals(c.s3Host, `${c.s3Region}-s3.storage.bunnycdn.com`);
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

Deno.test("readSweepConfig: the storage key AND the store's credentials, and nothing else", () => {
  // The nightly sweep (capability `scheduled-cleanup`) makes no request to the Edge Script, so it holds
  // no credential authorizing one. It DOES hold the relational store's: it marks from the database and
  // deletes from storage, and its deletion decision runs against the primary inside an interactive
  // transaction (capability `database`).
  const c = readSweepConfig({
    BUNNY_STORAGE_ACCESS_KEY: "k",
    BUNNY_DATABASE_URL: "libsql://example.invalid",
    BUNNY_DATABASE_AUTH_TOKEN: "dbt",
  });
  assertEquals(c.accessKey, "k");
  assertEquals(c.databaseUrl, "libsql://example.invalid");
  assertEquals(c.databaseToken, "dbt");
  assertEquals(c.apnsPrivateKey, ""); // never used by the sweep
  assertEquals(c.attestTokenKey, "");
  assertEquals(c.zone, D.storage.zone); // the resolved deployment is still present
  assertEquals(c.eventLifetimeSeconds, D.eventLifetimeSeconds);
});

Deno.test("readSweepConfig: a missing secret throws naming it", () => {
  assertThrows(() => readSweepConfig({}), Error, "BUNNY_STORAGE_ACCESS_KEY");
  // A sweep that cannot reach the store would mark nothing and collect nothing while reporting success.
  assertThrows(
    () => readSweepConfig({ BUNNY_STORAGE_ACCESS_KEY: "k" }),
    Error,
    "BUNNY_DATABASE_URL",
  );
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
  assertEquals(config.zone, D.storage.zone);
  assertEquals(config.apnsTopic, D.bundleId);
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

// THE RESOLVED DEPLOYMENT WINS. The regression this pins is real: the dead Edge Script carried a stale
// BUNNY_STORAGE_ZONE=`snap-sync` — a zone that does not exist — while production ran on the real one.
// Were env allowed to override a resolved value, that leftover platform variable would silently repoint
// the backend at a nonexistent bucket on the first boot.
Deno.test("readConfig: a platform variable NEVER overrides a resolved value", () => {
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

  assertEquals(config.zone, D.storage.zone);
  assertEquals(config.host, D.storage.host);
  assertEquals(config.s3Region, D.storage.s3Region);
  assertEquals(config.s3Host, `${D.storage.s3Region}-s3.storage.bunnycdn.com`);
  assertEquals(config.apnsKeyId, D.apnsKeyId);
  assertEquals(config.apnsTeamId, D.teamId);
  assertEquals(config.apnsTopic, D.bundleId);
  assertEquals(config.eventCapacity, D.eventCapacity);
  assertEquals(config.eventWindowMaxSeconds, 30 * 24 * 60 * 60);
  assertEquals(config.eventLifetimeSeconds, 30 * 24 * 60 * 60);
});
