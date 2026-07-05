// Operator tool — wipe the ENTIRE bunny Storage zone.
//
//   proton-env -- deno task reset-storage
//
// No flags, no selection: it deletes everything in the zone — the device namespace
// (`devices/<deviceId>/files/…` byte store + `devices/<deviceId>/config.json`), every event marker
// and device manifest (`events/<eventId>/…`), and any other top-level object. bunny rejects a blind
// DELETE on the zone root, so we enumerate the
// top-level entries and DELETE each; a DELETE on a directory (trailing slash) removes its contents
// recursively.
//
// proton-env injects BUNNY_STORAGE_ACCESS_KEY (the storage-zone password / `AccessKey`) from
// `.proton.yaml`. Zone and host are non-secret and hardcoded. Requests hit bunny's native Storage
// API directly (the deployed Hono endpoint has no delete route). This is destructive and runs with
// no confirmation prompt.

const ZONE = "snap-sync-dev";
const HOST = "storage.bunnycdn.com";
const BASE = `https://${HOST}/${ZONE}/`;

const accessKey = Deno.env.get("BUNNY_STORAGE_ACCESS_KEY")?.trim();
if (!accessKey) {
  console.error(
    "missing BUNNY_STORAGE_ACCESS_KEY (run via `proton-env -- deno task reset-storage`)",
  );
  Deno.exit(1);
}
const headers = { AccessKey: accessKey };

type Entry = { ObjectName: string; IsDirectory: boolean };

/** Every top-level entry in the zone. A missing/empty zone root (404) lists as empty. */
async function listRoot(): Promise<Entry[]> {
  const res = await fetch(BASE, { method: "GET", headers });
  if (res.status === 404) return [];
  if (!res.ok) {
    console.error(`✗ listing the zone failed (${res.status})`);
    Deno.exit(1);
  }
  return await res.json() as Entry[];
}

/** DELETE one path; `absent` (404) is an idempotent no-op, any other non-OK status aborts. */
async function del(path: string): Promise<"deleted" | "absent"> {
  const res = await fetch(`${BASE}${path}`, { method: "DELETE", headers });
  await res.text(); // drain so the connection can close cleanly
  if (res.ok) return "deleted";
  if (res.status === 404) return "absent";
  console.error(`✗ delete failed for ${path} (${res.status})`);
  Deno.exit(1);
}

const entries = await listRoot();
if (entries.length === 0) {
  console.log("zone already empty");
  Deno.exit(0);
}

console.log(`wiping ${entries.length} top-level entr${entries.length === 1 ? "y" : "ies"} ...`);

let deleted = 0;
for (const e of entries) {
  // Trailing slash on a directory makes the DELETE recursive (removes everything beneath it).
  const path = e.IsDirectory ? `${e.ObjectName}/` : e.ObjectName;
  const result = await del(path);
  console.log(`${result === "deleted" ? "✓" : "·"} ${path} (${result})`);
  if (result === "deleted") deleted++;
}

console.log(`done: ${deleted} top-level entr${deleted === 1 ? "y" : "ies"} deleted`);
