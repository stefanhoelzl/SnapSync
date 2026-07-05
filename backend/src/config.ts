// Runtime configuration — read exclusively from Edge Script environment variables.
// No secret ever appears in source. `readConfig` is called once at startup and THROWS on a
// missing/blank var, so a misconfigured deployment fails to boot (fail-closed at deploy time).

export type Config = {
  /** bunny Storage zone name. */
  zone: string;
  /** bunny native Storage host, e.g. `storage.bunnycdn.com` (DE/Falkenstein default). */
  host: string;
  /**
   * Storage-zone password. Sent as the native `AccessKey` header (NOT the account API key) for
   * uploads/reads/listings, and doubled as the S3 **secret access key** when presigning download URLs.
   */
  accessKey: string;
  /**
   * The backend's public origin (scheme+host, no trailing slash), e.g. `https://snapsync.example.com`.
   * The origin clients reach the backend at for upload/event/list requests. A runtime env var (same
   * category as `accessKey`), not a CI secret. It does NOT appear in any download URL — downloads are
   * presigned S3 URLs at the S3 endpoint (see `s3Host`).
   */
  baseUrl: string;
  /** S3 region of the (S3-enabled) storage zone, e.g. `de`. Used only to presign download URLs. */
  s3Region: string;
  /**
   * bunny S3-compatible endpoint host, e.g. `de-s3.storage.bunnycdn.com`. Presigned GET download URLs
   * are `https://<s3Host>/<zone>/<key>?...` (path-style). The S3 Access Key ID is the zone name and the
   * secret is `accessKey`, so no extra S3 credential is configured.
   */
  s3Host: string;
  /** APNs Auth Key id (the `.p8` key's Key ID) — the JWT `kid`. Capability `apns-push-sender`. */
  apnsKeyId: string;
  /** Apple team id — the JWT `iss`. */
  apnsTeamId: string;
  /**
   * The APNs Auth Key `.p8` private-key **PEM contents** (not a path). Used to ES256-sign the provider
   * JWT. A runtime env var (same category as `accessKey`), never a CI/deploy secret, never in source.
   */
  apnsPrivateKey: string;
  /** APNs push topic — the app bundle id `app.snapsync` — sent as the `apns-topic` header. */
  apnsTopic: string;
};

export const ENV_ZONE = "BUNNY_STORAGE_ZONE";
export const ENV_HOST = "BUNNY_STORAGE_HOST";
export const ENV_ACCESS_KEY = "BUNNY_STORAGE_ACCESS_KEY";
export const ENV_PUBLIC_BASE_URL = "PUBLIC_BASE_URL";
export const ENV_S3_REGION = "BUNNY_S3_REGION";
export const ENV_S3_HOST = "BUNNY_S3_HOST";
export const ENV_APNS_KEY_ID = "APNS_KEY_ID";
export const ENV_APNS_TEAM_ID = "APNS_TEAM_ID";
export const ENV_APNS_PRIVATE_KEY = "APNS_PRIVATE_KEY";
export const ENV_APNS_TOPIC = "APNS_TOPIC";

/** Build {@link Config} from env. Throws naming every missing/blank required var. */
export function readConfig(env: Record<string, string | undefined>): Config {
  const zone = env[ENV_ZONE]?.trim();
  const host = env[ENV_HOST]?.trim();
  const accessKey = env[ENV_ACCESS_KEY]?.trim();
  const baseUrl = env[ENV_PUBLIC_BASE_URL]?.trim();
  const s3Region = env[ENV_S3_REGION]?.trim();
  const s3Host = env[ENV_S3_HOST]?.trim();
  const apnsKeyId = env[ENV_APNS_KEY_ID]?.trim();
  const apnsTeamId = env[ENV_APNS_TEAM_ID]?.trim();
  // NOTE: do NOT trim the private key — a PEM's trailing newline is significant to parsers. Only
  // reject it when absent or whitespace-only.
  const apnsPrivateKey = env[ENV_APNS_PRIVATE_KEY];
  const apnsTopic = env[ENV_APNS_TOPIC]?.trim();

  const missing = [
    [ENV_ZONE, zone],
    [ENV_HOST, host],
    [ENV_ACCESS_KEY, accessKey],
    [ENV_PUBLIC_BASE_URL, baseUrl],
    [ENV_S3_REGION, s3Region],
    [ENV_S3_HOST, s3Host],
    [ENV_APNS_KEY_ID, apnsKeyId],
    [ENV_APNS_TEAM_ID, apnsTeamId],
    [ENV_APNS_PRIVATE_KEY, apnsPrivateKey?.trim()],
    [ENV_APNS_TOPIC, apnsTopic],
  ].filter(([, value]) => !value).map(([name]) => name);

  if (missing.length > 0) {
    throw new Error(`missing configuration: ${missing.join(", ")}`);
  }

  // Strip any trailing slash so `<baseUrl>/...` never doubles the separator.
  return {
    zone: zone!,
    host: host!,
    accessKey: accessKey!,
    baseUrl: baseUrl!.replace(/\/+$/, ""),
    s3Region: s3Region!,
    s3Host: s3Host!,
    apnsKeyId: apnsKeyId!,
    apnsTeamId: apnsTeamId!,
    apnsPrivateKey: apnsPrivateKey!,
    apnsTopic: apnsTopic!,
  };
}
