// DEV-ONLY. The `Config` the local rig serves with. Never imported by `src/main.ts`.
//
// It is built from `storageConfig()` — the SAME source constants the Edge Script and the nightly sweep
// use — so `eventCapacity`, `eventDurationSeconds`, `eventGraceSeconds`, and `attestTokenTtlSeconds`
// behave locally EXACTLY as deployed. That is deliberate: a change to any of those constants is then
// exercised by the rig for free, and the rig cannot silently diverge from the values it exists to test.
// Only the four secrets differ, plus `s3Host`.
//
// `s3Host` is the one field the rig must move. In production the union and per-device listing embed
// presigned S3 GET URLs pointing at bunny, and the device fetches bytes DIRECTLY from there, never
// through the api. Pointing `s3Host` at whatever host the rig is reachable on makes `presignDownloadUrl`
// mint a REAL SigV4 signature in the IDENTICAL URL shape, just aimed home — so the download half is
// exercised rather than stubbed. The dev entry then serves those paths off disk and ignores the
// signature (bunny's exact acceptance semantics are not reproducible, so validating locally would pin
// our guess rather than their behavior).

import { type Config, storageConfig } from "../config.ts";

/** The storage-zone password. Meaningless to the shim, which never authenticates. */
const DEV_ACCESS_KEY = "dev-access-key";
/** Signs the device bearer token. Any stable string works — the rig mints and verifies with the same one. */
const DEV_ATTEST_TOKEN_KEY = "dev-attest-token-key";

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
 * on — `127.0.0.1:8080` for `dev:local`, the quick-tunnel hostname for `dev:tunnel`.
 */
export function devConfig(publicHost: string): Config {
  return {
    ...storageConfig(DEV_ACCESS_KEY),
    s3Host: publicHost,
    attestTokenKey: DEV_ATTEST_TOKEN_KEY,
    // Left blank, as `storageConfig` already leaves it. `createApnsSender` imports the key lazily and
    // catches a signing failure PER TOKEN, so `/events/<id>/notify` still answers 202 with every token
    // reported failed — which is exactly the route's best-effort contract, not a local fake of it.
    apnsPrivateKey: "",
  };
}
