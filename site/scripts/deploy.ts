// Mirror-deploy the built site (dist/) to the bunny storage `site/` prefix (capability web-site).
//
// MIRROR, not sync-with-grace: after this runs, `site/` reflects exactly the current build — upload the
// new build, THEN delete any `site/` object not in it. Uploads happen BEFORE deletes, so `site/` is never
// momentarily empty (a clear-first deploy would 404 every request in the gap). Safe because the api serves
// HTML `no-cache` (no persistent stale reference can exist) and assets are immutable content hashes.
//
// It REUSES the api's own storage client + source constants (api/src/{storage,config}.ts): the bunny
// PUT/LIST/DELETE and the `zone`/`host` live in ONE place, so this deploy can never target a different
// zone than the api proxy reads. Auth is the STORAGE-ZONE PASSWORD only (BUNNY_STORAGE_ACCESS_KEY) —
// never the bunny account key, which stays out of CI (backend-deployment).
//
// Deno (not Node): `deno run --allow-read --allow-env --allow-net scripts/deploy.ts`. The site is built
// under Node (astro:assets); only this deploy step runs under Deno, matching the api's tooling.
import { storageConfig } from "../../api/src/config.ts";
import { deleteObject, listDir, putObject } from "../../api/src/storage.ts";

const accessKey = Deno.env.get("BUNNY_STORAGE_ACCESS_KEY");
if (!accessKey) {
  console.error("BUNNY_STORAGE_ACCESS_KEY is required");
  Deno.exit(1);
}
const config = storageConfig(accessKey);
const PREFIX = "site";
const DIST = new URL("../dist/", import.meta.url);

function contentType(path: string): string {
  if (path.endsWith(".html")) return "text/html; charset=utf-8";
  if (path.endsWith(".webp")) return "image/webp";
  if (path.endsWith(".css")) return "text/css; charset=utf-8";
  if (path.endsWith(".js")) return "text/javascript; charset=utf-8";
  if (path.endsWith(".svg")) return "image/svg+xml";
  if (path.endsWith(".json")) return "application/json; charset=utf-8";
  return "application/octet-stream";
}

/** Every file under dist/, as a POSIX-relative path (e.g. "index.html", "_astro/app.<hash>.js"). */
async function walkLocal(dir: URL, prefix = "", out: string[] = []): Promise<string[]> {
  for await (const entry of Deno.readDir(dir)) {
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory) await walkLocal(new URL(`${entry.name}/`, dir), rel, out);
    else out.push(rel);
  }
  return out;
}

/** Every object key under `site/` in storage, recursively (bunny LIST is one level; recurse on dirs). */
async function walkRemote(dirPath: string, out: string[] = []): Promise<string[]> {
  const entries = await listDir(fetch, config, dirPath);
  if (!entries) return out; // nothing there yet
  for (const e of entries) {
    const key = `${dirPath}${e.ObjectName}`;
    if (e.IsDirectory) await walkRemote(`${key}/`, out);
    else out.push(key);
  }
  return out;
}

const local = await walkLocal(DIST);
if (local.length === 0)
  throw new Error(`no build under ${DIST.pathname} — run \`npm run build\` first`);

// 1) Upload the whole build first (HTML at stable keys, last-write-wins; new hashed assets at new keys).
const wanted = new Set<string>();
for (const rel of local) {
  const key = `${PREFIX}/${rel}`;
  wanted.add(key);
  await putObject(fetch, config, key, await Deno.readFile(new URL(rel, DIST)), contentType(rel));
}
console.log(`uploaded ${local.length} object(s) to ${PREFIX}/`);

// 2) THEN delete anything under site/ the new build no longer contains.
const remote = await walkRemote(`${PREFIX}/`);
const stale = remote.filter((k) => !wanted.has(k));
for (const key of stale) await deleteObject(fetch, config, key);
console.log(`deleted ${stale.length} stale object(s)`);
