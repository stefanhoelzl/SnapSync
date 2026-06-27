// Runtime configuration — read exclusively from Edge Script environment variables.
// No secret ever appears in source. `readConfig` is called once at startup and THROWS on a
// missing/blank var, so a misconfigured deployment fails to boot (fail-closed at deploy time).

export type Config = {
  /** bunny Storage zone name. */
  zone: string;
  /** bunny native Storage host, e.g. `storage.bunnycdn.com` (DE/Falkenstein default). */
  host: string;
  /** Storage-zone password, sent as the `AccessKey` header (NOT the account API key). */
  accessKey: string;
  /**
   * The backend's public origin (scheme+host, no trailing slash), e.g. `https://snapsync.example.com`.
   * The list endpoint builds each entry's download URL as `<baseUrl>/event/<id>/file/<name>`. A
   * runtime env var (same category as `accessKey`), not a CI secret.
   */
  baseUrl: string;
};

export const ENV_ZONE = "BUNNY_STORAGE_ZONE";
export const ENV_HOST = "BUNNY_STORAGE_HOST";
export const ENV_ACCESS_KEY = "BUNNY_STORAGE_ACCESS_KEY";
export const ENV_PUBLIC_BASE_URL = "PUBLIC_BASE_URL";

/** Build {@link Config} from env. Throws naming every missing/blank required var. */
export function readConfig(env: Record<string, string | undefined>): Config {
  const zone = env[ENV_ZONE]?.trim();
  const host = env[ENV_HOST]?.trim();
  const accessKey = env[ENV_ACCESS_KEY]?.trim();
  const baseUrl = env[ENV_PUBLIC_BASE_URL]?.trim();

  const missing = [
    [ENV_ZONE, zone],
    [ENV_HOST, host],
    [ENV_ACCESS_KEY, accessKey],
    [ENV_PUBLIC_BASE_URL, baseUrl],
  ].filter(([, value]) => !value).map(([name]) => name);

  if (missing.length > 0) {
    throw new Error(`missing storage configuration: ${missing.join(", ")}`);
  }

  // Strip any trailing slash so `<baseUrl>/event/...` never doubles the separator.
  return { zone: zone!, host: host!, accessKey: accessKey!, baseUrl: baseUrl!.replace(/\/+$/, "") };
}
