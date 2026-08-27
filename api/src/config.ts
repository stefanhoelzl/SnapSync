// Runtime configuration, resolved from the DEPLOYMENT (capability `deployment-configuration`) and shipped
// in the same artifact as the code that reads it. Only genuine SECRETS come from the Edge Script
// environment, and the deployment names the variable — never the value.
//
// WHY NOT SOURCE CONSTANTS. Until this change the non-secret values were constants in this file. That
// bought the right property — config and code ship as ONE artifact, so drift is impossible rather than
// detected — but it bought it by making the values un-portable, and it only ever covered the backend. The
// device-facing domain lived in NINE places across four toolchains, six of them pinned by nothing;
// `TEAM_ID`/`BUNDLE_ID` were written twice in two languages with nothing checking they agreed, while
// composing the App Attest `rpIdHash` and the AASA `appIDs`. Resolving a declared deployment at build time
// keeps the one-artifact property exactly and adds portability: a different account is a different
// deployment file, not an edit to this one.
//
// WHY NOT THE ENVIRONMENT. bunny issues no scoped API key: writing an Edge Script's variables requires the
// full-access ACCOUNT key, which also owns the storage zone holding every user's photos and our DNS zone.
// CI therefore holds only the script-scoped deploy key — so CI ships code but CANNOT ship platform config.
// Config lived in a dashboard and rotted: on 2026-07-02 a change added two required env vars, set them on
// the then-active runtime only, and the bunny script fail-closed at boot for two weeks with CI green
// throughout. Worse, its `BUNNY_STORAGE_ZONE` said `snap-sync` — a zone that does not exist. THE RESOLVED
// DEPLOYMENT WINS: the environment is never consulted for a non-secret.
//
// Do NOT "fix" this by giving CI the account API key. That trades a config bug for a blast radius over
// every user's photos and our DNS. See openspec/specs/backend-deployment.
//
// The per-value rationale that used to live here now lives in the resolver's KEY INVENTORY
// (`scripts/resolve-deployment.py`), because these values are read by four toolchains and a comment in
// this file is invisible to the other three.

import {
  type BunnyDeployment,
  deployment,
  isBunnyDeployment,
  type ResolvedDeployment,
} from "./deployment.ts";

export type Config = {
  /** bunny Storage zone name (also the S3 Access Key ID + bucket). */
  zone: string;
  /** bunny native Storage host — the zone's main region. */
  host: string;
  /**
   * Storage-zone password. Sent as the native `AccessKey` header (NOT the account API key) for
   * uploads/reads/listings, and doubled as the S3 **secret access key** when presigning download URLs.
   * A SECRET: read from the environment at startup, never present in any authored file.
   */
  accessKey: string;
  /** S3 region — used only to presign download URLs. */
  s3Region: string;
  /** bunny S3-compatible endpoint host — the presigned-URL origin. DERIVED from {@link s3Region}. */
  s3Host: string;
  /**
   * URL scheme for presigned download URLs. `https` in every deployed configuration, and the only
   * value production ever carries — it exists because the local dev rig (`src/dev/`) serves plain
   * HTTP on loopback, and a device handed an `https://127.0.0.1:8080/...` URL fails on TLS rather
   * than downloading. Overridden there exactly as {@link s3Host} already is, and by nothing else.
   */
  s3Scheme: string;
  /** APNs Auth Key id — the JWT `kid`. */
  apnsKeyId: string;
  /** Apple team id — the JWT `iss`. */
  apnsTeamId: string;
  /**
   * The APNs Auth Key `.p8` private-key **PEM contents** (not a path). ES256-signs the provider JWT.
   * A SECRET: read from the environment, never in source.
   */
  apnsPrivateKey: string;
  /** APNs push topic — the `apns-topic` header. DERIVED from the bundle id. */
  apnsTopic: string;
  /**
   * Signs and verifies the device bearer token (capability `device-attestation`). A SECRET: read from the
   * environment, never in source.
   */
  attestTokenKey: string;
  /**
   * libSQL/HTTP URL of this deployment's relational store (capability `database`). A SECRET in the same
   * sense the storage `AccessKey` is: it addresses a live store holding real events, and EACH DEPLOYMENT
   * ADDRESSES ITS OWN — a dev run that wrote or deleted rows in the production store would corrupt live
   * events, and unlike the storage zone there is no per-object blast radius to fall back on.
   */
  databaseUrl: string;
  /** Access token for {@link databaseUrl}. A SECRET: read from the environment, never in source. */
  databaseToken: string;
  /** Apple's App Attest root CA (PEM) — the trust anchor for every attestation chain. */
  appAttestRootCa: string;
  /** Device-token lifetime, in seconds. */
  attestTokenTtlSeconds: number;
  /**
   * The App Attest **app id** — `<teamId>.<bundleId>` — whose SHA-256 must equal an attestation's
   * `rpIdHash`. DERIVED from the resolved team id and bundle id so the two can never drift apart.
   */
  attestAppId: string;
  /**
   * The event link's domain — the host this script serves the AASA for (capability `event-link`). The
   * SAME resolved value the app's `LINK_ORIGIN`, the `applinks:` entitlement and the compile-time upload
   * host are generated from, so agreement is constructed rather than asserted.
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
  /**
   * Whether this bundle serves a MAINTENANCE WINDOW: every route under `/api/` answers `503` while the
   * root routes keep serving (capability `backend-deployment`).
   *
   * Deployment-resolved and therefore BAKED, like every other non-secret — and here that is forced
   * rather than merely consistent. CI holds only the script-scoped deploy key and cannot write the Edge
   * Script's environment, so the only lever it has over a running deployment is which code is published.
   * A migrating deploy publishes this bundle, migrates, then publishes the ordinary one.
   *
   * Both publishes carry the SAME commit, so `sha` alone cannot tell them apart — which is why
   * `GET /health` reports this value and the deploy probe asserts it in both directions.
   */
  maintenance: boolean;
};

/** The commit this bundle was built from, served by `GET /health` so a deploy probe can identify it. */
export const BUILD_SHA: string = deployment.sha;

/**
 * The resolved deployment, exactly as it shipped in this bundle.
 *
 * `storage` is a SEALED UNION: a `filesystem` deployment has no zone to talk to and no filesystem in an
 * Edge Script, so {@link readConfig} refuses it and the script fails to boot. That is what makes "right
 * commit, wrong deployment" fail closed rather than serve against the wrong target.
 */
export const DEPLOYMENT: ResolvedDeployment = deployment.config;

/**
 * A value the deployment declares as an environment reference: the artifact carries the variable's NAME,
 * never its value. Resolving these at build time would bake a live credential into `dist/main.js` and
 * require CI to hold runtime secrets it is forbidden to hold.
 */
type EnvRef = { readonly env: string };

/** Read one declared secret, or record its variable name as missing. Blank counts as absent. */
function secret(
  ref: EnvRef,
  env: Record<string, string | undefined>,
  missing: string[],
  // NOTE: do NOT trim a PEM — its trailing newline is significant to parsers. `trimmed: false` still
  // rejects a whitespace-only value, so absence is never mistaken for a key.
  trimmed = true,
): string {
  const raw = env[ref.env];
  const value = trimmed ? raw?.trim() : raw;
  if (!value || value.trim() === "") {
    missing.push(ref.env);
    return "";
  }
  return value;
}

/** Every non-secret field, shared by {@link readConfig}, {@link readSweepConfig} and {@link storageConfig}. */
function publicFields(
  d: BunnyDeployment,
): Omit<
  Config,
  "accessKey" | "apnsPrivateKey" | "attestTokenKey" | "databaseUrl" | "databaseToken"
> {
  const storage = d.storage;
  return {
    zone: storage.zone,
    host: storage.host,
    s3Region: storage.s3Region,
    // DERIVED, never restated: `s3Region` and an `s3Host` constant previously stated one fact twice and
    // could disagree — a wrong S3 host mints presigned URLs that 403 at download while all else looks fine.
    s3Host: `${storage.s3Region}-s3.storage.bunnycdn.com`,
    // Not a deployment fact: every bunny deployment presigns over TLS. Only the filesystem dev rig
    // overrides it, and it does so by building its own Config (`src/dev/config.ts`).
    s3Scheme: "https",
    apnsKeyId: d.apnsKeyId,
    apnsTeamId: d.teamId,
    // The push topic IS the bundle id, and the attest app id is `<team>.<bundle>` — derive both so they
    // cannot drift from the identity the app is actually signed with.
    apnsTopic: d.bundleId,
    attestAppId: `${d.teamId}.${d.bundleId}`,
    appAttestRootCa: d.appAttestRootCa,
    attestTokenTtlSeconds: d.attestTokenTtlSeconds,
    linkDomain: d.domain,
    appStoreUrl: d.appStoreUrl,
    eventCapacity: d.eventCapacity,
    eventWindowMaxSeconds: d.eventWindowMaxSeconds,
    eventLifetimeSeconds: d.eventLifetimeSeconds,
    maintenance: d.maintenance,
  };
}

/**
 * Narrow the resolved deployment to the one storage kind a deployed backend can serve, or throw.
 *
 * A `filesystem` deployment reaching the Edge Script is a deploy of the wrong artifact; there is no
 * filesystem there, so failing at startup is the honest outcome and the boot probe reports it.
 */
function deployed(): BunnyDeployment {
  if (!isBunnyDeployment(DEPLOYMENT)) {
    throw new Error(
      `this bundle was built for a '${DEPLOYMENT.storage.kind}' deployment, which cannot serve as the ` +
        `deployed backend — deploy a bunny-storage deployment`,
    );
  }
  return DEPLOYMENT;
}

/**
 * Build {@link Config}: the resolved deployment, plus the secrets it declares, read from `env`.
 *
 * Throws naming EVERY missing/blank variable at once. The required set is DERIVED from the deployment's
 * own declarations — there is no hand-written list here that could drift from what the code needs.
 */
export function readConfig(env: Record<string, string | undefined>): Config {
  const d = deployed();
  const missing: string[] = [];

  const accessKey = secret(d.storage.accessKey, env, missing);
  const apnsPrivateKey = secret(d.apnsPrivateKey, env, missing, false);
  const attestTokenKey = secret(d.attestTokenKey, env, missing);
  // Validated with every other secret, so a deployment that cannot reach its store fails to BOOT rather
  // than serving requests whose relational writes silently go nowhere (capability `backend-deployment`).
  const databaseUrl = secret(d.databaseUrl, env, missing);
  const databaseToken = secret(d.databaseToken, env, missing);

  if (missing.length > 0) {
    throw new Error(`missing configuration: ${missing.join(", ")}`);
  }

  return {
    ...publicFields(d),
    accessKey,
    apnsPrivateKey,
    attestTokenKey,
    databaseUrl,
    databaseToken,
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
  const d = deployed();
  const missing: string[] = [];
  const accessKey = secret(d.storage.accessKey, env, missing);
  const databaseUrl = secret(d.databaseUrl, env, missing);
  const databaseToken = secret(d.databaseToken, env, missing);
  if (missing.length > 0) {
    throw new Error(`missing configuration: ${missing.join(", ")}`);
  }
  return {
    ...publicFields(d),
    accessKey,
    apnsPrivateKey: "", // unused by the sweep (the edge holds the real APNs key)
    attestTokenKey: "", // unused by the sweep (the edge holds the real token-signing key)
    // The sweep DOES hold these: it marks from the database and deletes from storage, and its deletion
    // decision runs against the primary inside an interactive transaction (capability `database`).
    databaseUrl,
    databaseToken,
  };
}

/**
 * Build a Config for DATABASE-ONLY tooling — the schema migration `api-deploy.yml` runs before it
 * publishes (capability `database`).
 *
 * It exists because `readSweepConfig` demands EVERY secret, storage access key included, and the deploy
 * workflow deliberately holds none: `backend-deployment` requires that key to be an Edge Script
 * environment value and not a CI secret, because bunny issues no scoped keys and that one also owns the
 * zone holding every user's photos. Resolving the sweep's config there therefore fails at startup on a
 * credential the step must never have — which is exactly what it did, blocking a deploy on a key it was
 * right not to hold. Every field the migration does not touch is blank.
 */
export function migrateConfig(env: Record<string, string | undefined>): Config {
  const d = deployed();
  const missing: string[] = [];
  const databaseUrl = secret(d.databaseUrl, env, missing);
  const databaseToken = secret(d.databaseToken, env, missing);
  if (missing.length > 0) {
    throw new Error(`missing configuration: ${missing.join(", ")}`);
  }
  return {
    ...publicFields(d),
    accessKey: "",
    apnsPrivateKey: "",
    attestTokenKey: "",
    databaseUrl,
    databaseToken,
  };
}

/**
 * Build a Config for storage-ONLY tooling that runs outside the Edge Script and needs only the storage
 * `AccessKey` — currently the `site/` mirror-deploy (capability `web-site`, `site/scripts/deploy.ts`).
 * It reuses the SAME resolved deployment the edge and the sweep use, so the deploy can never target a
 * different zone than the api proxy reads. Every credential the storage helpers do not touch is blank.
 */
export function storageConfig(accessKey: string): Config {
  return {
    ...publicFields(deployed()),
    accessKey,
    apnsPrivateKey: "",
    attestTokenKey: "",
    databaseUrl: "",
    databaseToken: "",
  };
}
