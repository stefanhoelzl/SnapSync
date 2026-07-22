// Mirror-deploy the built site (dist/) to the bunny storage `site/` prefix (capability web-site).
//
// MIRROR, not sync-with-grace: after this runs, `site/` reflects exactly the current build — upload the
// new build, THEN delete any `site/` object not in it. Uploads happen BEFORE deletes, so `site/` is never
// momentarily empty (a clear-first deploy would 404 every request in the gap). Safe because the api serves
// HTML `no-cache` (no persistent stale reference can exist) and assets are immutable content hashes.
//
// Auth is the STORAGE-ZONE PASSWORD only (BUNNY_STORAGE_ACCESS_KEY) — never the bunny account key, which
// stays out of CI (backend-deployment). This is the same secret the nightly sweep already holds.
//
// ZONE/HOST mirror the api's source constants (backend/src/config.ts). They are public and change ~never;
// they MUST match what the api proxy reads, or the proxy would serve a different zone than this writes.
import { readdir, readFile, stat } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import process from "node:process";

const ZONE = "snap-sync-dev";
const HOST = "storage.bunnycdn.com";
const PREFIX = "site";
const DIST = new URL("../dist/", import.meta.url).pathname;

const ACCESS_KEY = process.env.BUNNY_STORAGE_ACCESS_KEY;
if (!ACCESS_KEY) {
  console.error("BUNNY_STORAGE_ACCESS_KEY is required");
  process.exit(1);
}

const base = `https://${HOST}/${ZONE}`;

/** Every file under dist/, as a POSIX-relative path (e.g. "index.html", "_astro/app.<hash>.js"). */
async function walkLocal(dir, out = []) {
  for (const name of await readdir(dir)) {
    const full = join(dir, name);
    if ((await stat(full)).isDirectory()) await walkLocal(full, out);
    else out.push(relative(DIST, full).split(sep).join("/"));
  }
  return out;
}

/** Every object key under `site/` in storage, recursively (bunny LIST is one level; recurse on dirs). */
async function walkRemote(dirPath, out = []) {
  const res = await fetch(`${base}/${dirPath}`, {
    headers: { AccessKey: ACCESS_KEY, Accept: "application/json" },
  });
  if (res.status === 404) return out; // nothing there yet
  if (!res.ok) throw new Error(`LIST ${dirPath} → ${res.status}`);
  for (const e of await res.json()) {
    const key = `${dirPath}${e.ObjectName}`;
    if (e.IsDirectory) await walkRemote(`${key}/`, out);
    else out.push(key);
  }
  return out;
}

async function put(key, body) {
  const res = await fetch(`${base}/${key}`, {
    method: "PUT",
    headers: { AccessKey: ACCESS_KEY },
    body,
  });
  if (!res.ok) throw new Error(`PUT ${key} → ${res.status}`);
}

async function del(key) {
  const res = await fetch(`${base}/${key}`, { method: "DELETE", headers: { AccessKey: ACCESS_KEY } });
  if (!res.ok && res.status !== 404) throw new Error(`DELETE ${key} → ${res.status}`);
}

const local = await walkLocal(DIST);
if (local.length === 0) throw new Error(`no build found under ${DIST} — run \`npm run build\` first`);

// 1) Upload the whole build first (new hashed assets + HTML at stable keys, last-write-wins).
const wanted = new Set();
for (const rel of local) {
  const key = `${PREFIX}/${rel}`;
  wanted.add(key);
  await put(key, await readFile(join(DIST, rel)));
}
console.log(`uploaded ${local.length} object(s) to ${PREFIX}/`);

// 2) THEN delete anything under site/ that the new build no longer contains.
const remote = await walkRemote(`${PREFIX}/`);
const stale = remote.filter((k) => !wanted.has(k));
for (const key of stale) await del(key);
console.log(`deleted ${stale.length} stale object(s)`);
