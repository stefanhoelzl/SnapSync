// Operator tool — wipe the bunny Storage zone, or specific events' objects.
//
//   proton-env -- deno task reset-storage                  # full reset: delete every event in the zone
//   proton-env -- deno task reset-storage <id> [<id> ...]  # delete only the given event(s)
//
// Always operates on a LIST of events: the ids come either from the args, or — when none are
// given — from listing the zone root (each top-level directory is one event). Each event is then
// removed with a recursive DELETE (a DELETE on a directory removes its contents). bunny rejects a
// blind DELETE on the zone root, so the full reset must enumerate-then-delete.
//
// proton-env injects BUNNY_STORAGE_ACCESS_KEY (the storage-zone password / `AccessKey`) from
// `.proton.yaml`. Zone and host are non-secret and hardcoded. Requests hit bunny's native Storage
// API directly (the deployed Hono endpoint has no delete route). This is destructive and runs with
// no confirmation prompt.

const ZONE = "snap-sync";
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

/** List the zone root; each top-level directory is one event. */
async function listEvents(): Promise<string[]> {
  const res = await fetch(BASE, { method: "GET", headers });
  if (!res.ok) {
    console.error(`✗ listing the zone failed (${res.status})`);
    Deno.exit(1);
  }
  const entries = await res.json() as Array<{ ObjectName: string; IsDirectory: boolean }>;
  return entries.filter((e) => e.IsDirectory).map((e) => e.ObjectName);
}

const events = Deno.args.length > 0 ? Deno.args : await listEvents();

if (events.length === 0) {
  console.log("no events to delete");
  Deno.exit(0);
}

console.log(`deleting ${events.length} event(s) ...`);

let deleted = 0;
for (const id of events) {
  // Trailing slash denotes a directory so bunny deletes recursively.
  const res = await fetch(`${BASE}${id}/`, { method: "DELETE", headers });
  await res.text(); // drain so the connection can close cleanly

  if (res.ok) {
    console.log(`✓ ${id} (${res.status})`);
    deleted++;
  } else if (res.status === 404) {
    // Idempotent: nothing there to delete is a successful no-op.
    console.log(`· ${id} — nothing to delete (404)`);
  } else {
    console.error(`✗ delete failed for ${id} (${res.status})`);
    Deno.exit(1);
  }
}

console.log(`done: ${deleted} event(s) deleted`);
