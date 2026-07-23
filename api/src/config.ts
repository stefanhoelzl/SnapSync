// Runtime configuration: NON-SECRET values are SOURCE CONSTANTS below; only the genuine SECRETS
// (storage AccessKey, APNs key, token-signing key) come from the Edge Script environment.
// `readConfig` is called once at startup and THROWS naming any missing/blank secret, so a misconfigured
// deployment fails to boot (fail-closed at deploy time).
//
// WHY THE NON-SECRETS ARE IN SOURCE. bunny issues no scoped API key: writing an Edge Script's
// environment variables requires the full-access ACCOUNT key, which also owns the storage zone holding
// every user's photos and our DNS zone. CI therefore holds only the script-scoped deploy key — so CI
// ships code but CANNOT ship config. Config lived in a dashboard and rotted: on 2026-07-02 a change
// added two required env vars, set them on the (then-active) Deno Deploy runtime only, and the bunny
// script fail-closed at boot for two weeks with CI green throughout. Worse, its BUNNY_STORAGE_ZONE said
// `snap-sync` — a zone that does not exist. Config in source cannot drift: a non-secret value ships in
// the same bundle as the code that reads it. SOURCE WINS — the environment is never consulted for one.
//
// Do NOT "fix" this by giving CI the account API key. That trades a config bug for a blast radius over
// every user's photos and our DNS. See openspec/specs/backend-deployment.

/** bunny Storage zone name. Doubles as the S3 **Access Key ID** and the bucket when presigning. */
const ZONE = "snap-sync-dev";

/**
 * bunny native Storage host. MUST be the zone's **main** region host (where writes land), never a
 * replica: reads from the main region are read-after-write consistent, so the nightly sweep (capability
 * `scheduled-cleanup`) sees a concurrent rejoin's fresh manifest and cannot delete an event out from
 * under an active device. A stale replica read is the one failure mode that would delete live data.
 */
const HOST = "storage.bunnycdn.com";

/** S3 region of the (S3-enabled) storage zone. Used only to presign download URLs. */
const S3_REGION = "de";

/**
 * bunny S3-compatible endpoint host. Presigned GET download URLs are `https://<s3Host>/<zone>/<key>?...`
 * (path-style). The S3 Access Key ID is {@link ZONE} and the secret is the storage `AccessKey`, so no
 * extra S3 credential exists.
 */
const S3_HOST = "de-s3.storage.bunnycdn.com";

/** APNs Auth Key id (the `.p8` Key ID) — the provider-JWT `kid`. Capability `apns-push-sender`. */
const APNS_KEY_ID = "W34NF6UMVU";

/** Apple team id — the provider-JWT `iss`. */
const APNS_TEAM_ID = "E9Z8BADH58";

/** APNs push topic — the app bundle id — sent as the `apns-topic` header. */
const APNS_TOPIC = "app.snapsync";

/**
 * The event link's domain (capability `event-link`) — the host this script serves the AASA for.
 *
 * This is the ONE place the backend knows it, and it MUST equal `snapsync.domain` in the repo's
 * `gradle.properties` (which generates the app's `LINK_ORIGIN` and feeds `Config.xcconfig`'s
 * `applinks:` entitlement). Nothing in the Gradle build can reach this file — `api/` is deployed by
 * a separate, path-scoped workflow that ships code only — so a `:test:architecture` guard asserts the
 * two agree instead. Drift is SILENT: iOS simply declines to match the link and every invite opens a
 * browser, which is indistinguishable from a recipient who never installed the app.
 */
const LINK_DOMAIN = "snapsync.stho.net";

/**
 * Where `GET /join` sends someone who opened an event link without the app (capability `event-link`).
 *
 * NOTE: until the App Store listing is published this URL 404s (`itunes.apple.com/lookup` reports no
 * such app) — a known, accepted, temporary gap, not a regression. See the migrate-to-universal-links
 * decision record.
 */
const APP_STORE_URL = "https://apps.apple.com/app/id6781692480";

/**
 * Apple's App Attest **root CA** — the trust anchor every attestation's certificate chain is verified
 * against (capability `device-attestation`).
 *
 * A SOURCE CONSTANT, by the same rule as everything else here: it is a **public fact** (Apple publishes
 * it at https://www.apple.com/certificateauthority/), so committing it exposes nothing — and shipping it
 * in the same bundle as the code that reads it means a verification change can never be deployed without
 * its trust anchor.
 */
const APPLE_APP_ATTEST_ROOT_CA = `-----BEGIN CERTIFICATE-----
MIICITCCAaegAwIBAgIQC/O+DvHN0uD7jG5yH2IXmDAKBggqhkjOPQQDAzBSMSYw
JAYDVQQDDB1BcHBsZSBBcHAgQXR0ZXN0YXRpb24gUm9vdCBDQTETMBEGA1UECgwK
QXBwbGUgSW5jLjETMBEGA1UECAwKQ2FsaWZvcm5pYTAeFw0yMDAzMTgxODMyNTNa
Fw00NTAzMTUwMDAwMDBaMFIxJjAkBgNVBAMMHUFwcGxlIEFwcCBBdHRlc3RhdGlv
biBSb290IENBMRMwEQYDVQQKDApBcHBsZSBJbmMuMRMwEQYDVQQIDApDYWxpZm9y
bmlhMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAERTHhmLW07ATaFQIEVwTtT4dyctdh
NbJhFs/Ii2FdCgAHGbpphY3+d8qjuDngIN3WVhQUBHAoMeQ/cLiP1sOUtgjqK9au
Yen1mMEvRq9Sk3Jm5X8U62H+xTD3FE9TgS41o0IwQDAPBgNVHRMBAf8EBTADAQH/
MB0GA1UdDgQWBBSskRBTM72+aEH/pwyp5frq5eWKoTAOBgNVHQ8BAf8EBAMCAQYw
CgYIKoZIzj0EAwMDaAAwZQIwQgFGnByvsiVbpTKwSga0kP0e8EeDS4+sQmTvb7vn
53O5+FRXgeLhpJ06ysC5PrOyAjEAp5U4xDgEgllF7En3VcE3iexZZtKeYnpqtijV
oyFraWVIyd/dganmrduC1bmTBGwD
-----END CERTIFICATE-----`;

/**
 * Device-token lifetime (capability `device-attestation`): 30 days.
 *
 * This is a **margin**, not a security knob. The extension cannot renew (App Attest is unavailable in an
 * app extension — verified on device), so only the APP process refreshes the token, at whatever wakes it
 * happens to get. Worse, the silent push that most reliably wakes it is triggered by a *successful*
 * upload — so an expired token deadlocks its own renewal until the user next opens the app. 30 days is
 * what makes falling into that rare.
 *
 * It is also, because the token's Keychain item is backup-restorable (like the device id), the ONLY bound
 * on a token lifted from a backup. Do not lengthen it.
 */
const ATTEST_TOKEN_TTL_SECONDS = 30 * 24 * 60 * 60;

/**
 * Event limits (capability `event-limits`) — SOURCE CONSTANTS, per this module's rule. They are the
 * MINT-TIME source only: `POST /events` validates against the window maximum and stamps `capacity` +
 * `lifetimeSeconds` onto the event's write-once marker, and every later check reads the marker's own
 * fields — so changing a value here affects only events minted afterwards, never a live event. Tests
 * exercise short windows by constructing a `Config` directly (the same way the attest TTL is pinned),
 * not via the environment.
 */

/** Maximum devices EVER enrolled per event (active ∪ departed — leaving frees no slot). */
const EVENT_CAPACITY = 10;

/**
 * Maximum event WINDOW: the largest `endsAt - startsAt` a create may declare, and the fallback applied
 * when a create sends no `endsAt` at all. 30 days.
 *
 * DELIBERATELY NOT the same constant as {@link EVENT_LIFETIME_SECONDS}, even though the two hold the
 * same value today. They answer different questions — "how long may photos be *taken* for?" vs "how long
 * do we *keep* them?" — and only the lifetime is stamped onto the marker. Collapsing them would make a
 * future divergence a silent behaviour change in two unrelated places.
 *
 * The cap is a hard bound, not a pricing lever: a window longer than the lifetime would declare captures
 * eligible for upload into an event that no longer exists by then, and a photo that uploads into nothing
 * is exactly the silent loss the selection policy exists to prevent. The only future paid-tier lever is
 * {@link EVENT_CAPACITY}.
 */
const EVENT_WINDOW_MAX_SECONDS = 30 * 24 * 60 * 60;

/**
 * Event LIFETIME: how long an event's data is kept, measured from `max(createdAt, startsAt)`. 30 days.
 *
 * Stamped onto the marker as a DURATION, never as an absolute delete-by instant. Stamping the duration
 * keeps the per-event value immutable against a later change to this constant, while leaving the anchor
 * it is measured from in shared code (`lifecycle.ts`) — so the anchor policy can be corrected without
 * rewriting a single stored marker. Past that derived instant the event is stale and the nightly sweep
 * (capability `scheduled-cleanup`) deletes it.
 */
const EVENT_LIFETIME_SECONDS = 30 * 24 * 60 * 60;

export type Config = {
  /** bunny Storage zone name (also the S3 Access Key ID + bucket). */
  zone: string;
  /** bunny native Storage host — the zone's main region. */
  host: string;
  /**
   * Storage-zone password. Sent as the native `AccessKey` header (NOT the account API key) for
   * uploads/reads/listings, and doubled as the S3 **secret access key** when presigning download URLs.
   * A SECRET: read from the environment, never in source.
   */
  accessKey: string;
  /** S3 region — used only to presign download URLs. */
  s3Region: string;
  /** bunny S3-compatible endpoint host — the presigned-URL origin. */
  s3Host: string;
  /** APNs Auth Key id — the JWT `kid`. */
  apnsKeyId: string;
  /** Apple team id — the JWT `iss`. */
  apnsTeamId: string;
  /**
   * The APNs Auth Key `.p8` private-key **PEM contents** (not a path). ES256-signs the provider JWT.
   * A SECRET: read from the environment, never in source.
   */
  apnsPrivateKey: string;
  /** APNs push topic — the `apns-topic` header. */
  apnsTopic: string;
  /**
   * Signs and verifies the device bearer token (capability `device-attestation`). A SECRET: read from the
   * environment, never in source.
   */
  attestTokenKey: string;
  /** Apple's App Attest root CA (PEM) — the trust anchor for every attestation chain. */
  appAttestRootCa: string;
  /** Device-token lifetime, in seconds. */
  attestTokenTtlSeconds: number;
  /**
   * The App Attest **app id** — `<teamId>.<bundleId>` — whose SHA-256 must equal an attestation's
   * `rpIdHash`. Derived from the existing team/bundle constants so the two can never drift apart.
   */
  attestAppId: string;
  /**
   * The event link's domain — the host this script serves the AASA for (capability `event-link`).
   * Must match the app's `LINK_ORIGIN` and `applinks:` entitlement; a `:test:architecture` guard
   * asserts it, because nothing in the Gradle build can reach this file.
   */
  linkDomain: string;
  /** Where `GET /join` redirects someone who opened an event link without the app. */
  appStoreUrl: string;
  /** Maximum devices ever enrolled per event (capability `event-limits`). */
  eventCapacity: number;
  /** Largest permitted `endsAt - startsAt`, in seconds; also the absent-`endsAt` fallback. */
  eventWindowMaxSeconds: number;
  /** Event lifetime in seconds — stamped onto the marker, measured from `max(createdAt, startsAt)`. */
  eventLifetimeSeconds: number;
};

/** The storage-zone password (the native `AccessKey`; also the S3 secret). The zone's password. */
export const ENV_ACCESS_KEY = "BUNNY_STORAGE_ACCESS_KEY";
/** The APNs Auth Key `.p8` PEM contents. */
export const ENV_APNS_PRIVATE_KEY = "APNS_PRIVATE_KEY";
/**
 * The HMAC key signing the device bearer token (capability `device-attestation`).
 *
 * MUST be set on the Edge Script **before** the code reading it is merged: `readConfig` throws when it is
 * missing, and CI ships code but cannot ship config — so merging first takes the backend down until it is
 * set by hand. That is not hypothetical; it is how this backend stayed dead for two weeks.
 */
export const ENV_ATTEST_TOKEN_KEY = "ATTEST_TOKEN_KEY";

/**
 * Build {@link Config}: the source constants above, plus the four secrets from `env`. Throws naming
 * every missing/blank secret. The environment is NOT consulted for any non-secret value — a stale
 * platform variable cannot override a source constant.
 */
export function readConfig(env: Record<string, string | undefined>): Config {
  const accessKey = env[ENV_ACCESS_KEY]?.trim();
  // NOTE: do NOT trim the private key — a PEM's trailing newline is significant to parsers. Only
  // reject it when absent or whitespace-only.
  const apnsPrivateKey = env[ENV_APNS_PRIVATE_KEY];

  const attestTokenKey = env[ENV_ATTEST_TOKEN_KEY]?.trim();

  const missing = [
    [ENV_ACCESS_KEY, accessKey],
    [ENV_APNS_PRIVATE_KEY, apnsPrivateKey?.trim()],
    [ENV_ATTEST_TOKEN_KEY, attestTokenKey],
  ].filter(([, value]) => !value).map(([name]) => name);

  if (missing.length > 0) {
    throw new Error(`missing configuration: ${missing.join(", ")}`);
  }

  return {
    ...sourceConstants(),
    accessKey: accessKey!,
    apnsPrivateKey: apnsPrivateKey!,
    attestTokenKey: attestTokenKey!,
  };
}

/** Every NON-secret Config field — the source constants, shared by `readConfig` and `readSweepConfig`. */
function sourceConstants(): Omit<
  Config,
  "accessKey" | "apnsPrivateKey" | "attestTokenKey"
> {
  return {
    zone: ZONE,
    host: HOST,
    s3Region: S3_REGION,
    s3Host: S3_HOST,
    apnsKeyId: APNS_KEY_ID,
    apnsTeamId: APNS_TEAM_ID,
    apnsTopic: APNS_TOPIC,
    appAttestRootCa: APPLE_APP_ATTEST_ROOT_CA,
    attestTokenTtlSeconds: ATTEST_TOKEN_TTL_SECONDS,
    // The gate's app id and the push topic are the SAME bundle id, and the attest chain's team is the
    // SAME team that signs the APNs JWT — derive, never restate, so they cannot drift.
    attestAppId: `${APNS_TEAM_ID}.${APNS_TOPIC}`,
    linkDomain: LINK_DOMAIN,
    appStoreUrl: APP_STORE_URL,
    eventCapacity: EVENT_CAPACITY,
    eventWindowMaxSeconds: EVENT_WINDOW_MAX_SECONDS,
    eventLifetimeSeconds: EVENT_LIFETIME_SECONDS,
  };
}

/**
 * Build a Config for the nightly sweep (capability `scheduled-cleanup`), which runs OUTSIDE the Edge
 * Script and holds exactly ONE secret — the storage `AccessKey`, to read and delete storage.
 *
 * The sweep makes NO request to the Edge Script, so it needs no credential authorizing one: it no longer
 * announces a deletion (the announcement could not say what it meant, and arrived after the deletes it
 * described). The edge-only secrets it never uses are left blank. Throws naming any missing/blank secret.
 */
export function readSweepConfig(env: Record<string, string | undefined>): Config {
  const accessKey = env[ENV_ACCESS_KEY]?.trim();
  if (!accessKey) {
    throw new Error(`missing configuration: ${ENV_ACCESS_KEY}`);
  }
  return {
    ...sourceConstants(),
    accessKey,
    apnsPrivateKey: "", // unused by the sweep (the edge holds the real APNs key)
    attestTokenKey: "", // unused by the sweep (the edge holds the real token-signing key)
  };
}

/**
 * Build a Config for storage-ONLY tooling that runs outside the Edge Script and needs only the storage
 * `AccessKey` — currently the `site/` mirror-deploy (capability `web-site`, `site/scripts/deploy.ts`).
 * It reuses the SAME source constants (`zone`/`host`) the edge and the sweep use, so the deploy can never
 * target a different zone than the api proxy reads. Every credential the storage helpers do not touch is
 * left blank.
 */
export function storageConfig(accessKey: string): Config {
  return {
    ...sourceConstants(),
    accessKey,
    apnsPrivateKey: "",
    attestTokenKey: "",
  };
}
