// DEV-ONLY. The `Config` the local rig serves with. Never imported by `src/main.ts`.
//
// WHY THIS SYNTHESISES RATHER THAN RESOLVES. The rig's storage is a FETCH SHIM: `serve.ts` builds bunny
// URLs (`https://<host>/<zone>/<key>`) and `fs-storage.ts` answers them from disk. So the app-facing
// `Config` must stay bunny-SHAPED even when the deployment's storage kind is `filesystem` — turning
// `storage.ts` into an interface with two implementations was considered and rejected in `fs-storage.ts`'s
// own header (it would rewrite shipped storage code, plus `sweep.ts` and the site deploy, for a dev-only
// need). The sealed union therefore lives on the RESOLVED DEPLOYMENT: `readConfig` narrows to `bunny` or
// throws, and this file narrows to `filesystem` and builds what the shim needs.
//
// It inherits every non-storage value — `eventCapacity`, the window maximum, the lifetime, the attest TTL,
// the Apple identity — from the SAME resolved deployment the Edge Script uses, so a change to any of them
// is exercised by the rig for free and the rig cannot silently diverge from the values it exists to test.
// What it supplies itself is exactly what a filesystem deployment does not declare: the four secrets (a
// `filesystem` kind requires none) and the zone/host pair the shim intercepts.
//
// `s3Host` is the one field the rig must move at RUN TIME. In production the union and per-device listing
// embed presigned S3 GET URLs pointing at bunny, and the device fetches bytes DIRECTLY from there, never
// through the api. Pointing `s3Host` at whatever host the rig is reachable on makes `presignDownloadUrl`
// mint a REAL SigV4 signature in the IDENTICAL URL shape, just aimed home — so the download half is
// exercised rather than stubbed. The dev entry then serves those paths off disk and ignores the
// signature (bunny's exact acceptance semantics are not reproducible, so validating locally would pin
// our guess rather than their behavior).
//
// The host cannot come from the deployment at all: `dev:tunnel`'s hostname is minted by cloudflared INSIDE
// the running process (`serve.ts`), after every static import has been evaluated, and is random per
// session. No schema can source a value that does not exist yet — which is why it stays a parameter.

import { DEPLOYMENT, MIN_APP_VERSION } from "../config.ts";
import type { Config } from "../config.ts";
import { isFilesystemDeployment } from "../deployment.ts";

/** The storage-zone password. Meaningless to the shim, which never authenticates. */
const DEV_ACCESS_KEY = "dev-access-key";
/** Signs the device bearer token. Any stable string works — the rig mints and verifies with the same one. */
const DEV_ATTEST_TOKEN_KEY = "dev-attest-token-key";

/**
 * The zone and native host the shim intercepts. Deliberately NOT the production pair: the rig used to
 * borrow `snap-sync-dev` / `storage.bunnycdn.com` from the source constants, so a mistyped shim fell
 * through to the zone holding real users' photos. These name nothing that exists.
 */
const DEV_ZONE = "dev-zone";
const DEV_HOST = "storage.invalid";

/**
 * The device id the rig's fallback bearer token is minted for.
 *
 * It does not gate anything: `verifyToken` proves a token is ours and unexpired and deliberately does
 * NOT bind it to the route's device id ("ownership stays capability-based on the unguessable UUID"), so
 * one token authorizes a `curl` against any device's partition. It only has to be a valid UUID.
 */
export const DEV_TOKEN_DEVICE_ID = "00000000-0000-4000-8000-000000000000";

/**
 * Build the rig's `Config`. `publicHost` is the host (no scheme, no trailing slash) the rig is reachable
 * on — `127.0.0.1:8080` for `dev:local`, the quick-tunnel hostname for `dev:tunnel` — and `s3Scheme` is
 * that origin's scheme.
 *
 * The scheme has to travel with the host, because a presigned download URL is fetched by the DEVICE, not
 * by the rig: minting `https://127.0.0.1:8080/...` for a plain-HTTP server hands every simulator a URL
 * that fails on TLS, which reads as "downloads are inert on this host" rather than as a wrong scheme.
 *
 * Throws when the resolved deployment is not a `filesystem` one: running the rig against a bunny
 * deployment would write into a real storage zone, which is the accident `fs-storage.ts` exists to make
 * impossible.
 */
export function devConfig(publicHost: string, s3Scheme: string): Config {
  if (!isFilesystemDeployment(DEPLOYMENT)) {
    throw new Error(
      `the local rig requires a filesystem deployment; this bundle resolved ` +
        `'${DEPLOYMENT.storage.kind}'. ` +
        `Run the resolver with a filesystem deployment (e.g. \`local\`) first.`,
    );
  }
  const d = DEPLOYMENT;
  return {
    zone: DEV_ZONE,
    host: DEV_HOST,
    accessKey: DEV_ACCESS_KEY,
    s3Region: "dev",
    s3Host: publicHost,
    s3Scheme,
    apnsKeyId: d.apnsKeyId,
    apnsTeamId: d.teamId,
    // Left blank deliberately. `createApnsSender` imports the key lazily and catches a signing failure
    // PER TOKEN, so `/events/<id>/notify` still answers 202 with every token reported failed — which is
    // exactly the route's best-effort contract, not a local fake of it.
    apnsPrivateKey: "",
    apnsTopic: d.bundleId,
    attestTokenKey: DEV_ATTEST_TOKEN_KEY,
    // Blank, and unreachable by construction: the rig builds its `Db` from `node:sqlite` against a local
    // file (`src/dev/serve.ts`), never from these. A filesystem deployment declares no database
    // credentials at all — which is what makes it impossible for a dev run to address the production
    // store, the one mistake that would corrupt live events with no per-object blast radius to fall
    // back on.
    databaseUrl: "",
    databaseToken: "",
    appAttestRootCa: d.appAttestRootCa,
    attestTokenTtlSeconds: d.attestTokenTtlSeconds,
    attestAppId: `${d.teamId}.${d.bundleId}`,
    linkDomain: d.domain,
    appStoreUrl: d.appStoreUrl,
    eventCapacity: d.eventCapacity,
    eventWindowMaxSeconds: d.eventWindowMaxSeconds,
    eventLifetimeSeconds: d.eventLifetimeSeconds,
    // Never on, and not read from the deployment. The maintenance window exists to bound the interval
    // between a migration landing and the bundle written against it serving (capability
    // `backend-deployment`) — and the rig has no publish, no probe and no such interval. Reading `d`
    // here would let a `local` deployment that set the flag produce a rig that refuses every device
    // request, with no pipeline able to lift it.
    maintenance: false,
    // The local rig serves the same gate as production, so it needs a minimum. It takes the SHIPPED one
    // rather than a permissive local value: a rig that admitted builds production refuses would hide exactly
    // the failure the gate exists to surface, and only on device.
    minAppVersion: MIN_APP_VERSION,
  };
}

/** The directory the shim reads and writes, as the resolved deployment declares it. */
export function devStoreRoot(fallback: string): string {
  return isFilesystemDeployment(DEPLOYMENT) ? DEPLOYMENT.storage.root : fallback;
}
