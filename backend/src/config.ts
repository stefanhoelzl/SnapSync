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
};

export const ENV_ZONE = "BUNNY_STORAGE_ZONE";
export const ENV_HOST = "BUNNY_STORAGE_HOST";
export const ENV_ACCESS_KEY = "BUNNY_STORAGE_ACCESS_KEY";

/** Build {@link Config} from env. Throws naming every missing/blank required var. */
export function readConfig(env: Record<string, string | undefined>): Config {
  const zone = env[ENV_ZONE]?.trim();
  const host = env[ENV_HOST]?.trim();
  const accessKey = env[ENV_ACCESS_KEY]?.trim();

  const missing = [
    [ENV_ZONE, zone],
    [ENV_HOST, host],
    [ENV_ACCESS_KEY, accessKey],
  ].filter(([, value]) => !value).map(([name]) => name);

  if (missing.length > 0) {
    throw new Error(`missing storage configuration: ${missing.join(", ")}`);
  }

  return { zone: zone!, host: host!, accessKey: accessKey! };
}
