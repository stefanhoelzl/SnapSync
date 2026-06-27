// Operator tool — wipe the bunny Storage zone, or specific events' objects.
//
//   proton-env -- deno task reset-storage                  # full reset: delete every event in the zone
//   proton-env -- deno task reset-storage <id> [<id> ...]  # delete only the given event(s)
//
// Always operates on a LIST of events. Deleting an event means removing BOTH its photo directory
// `<id>/` (a recursive DELETE — a DELETE on a directory removes its contents) AND its registry marker
// `events/<id>.json` (the object whose presence makes the event "exist"; see capability
// `event-creation`). Skipping the marker would leave a created-but-empty event behind that the list
// and upload endpoints still treat as existing.
//
// For a full reset the event ids come from the registry (the `events/` directory of markers — the
// source of truth, so created-but-empty events are included), unioned with any top-level photo
// directories (legacy / orphan objects without a marker). The `events/` directory itself is never
// treated as an event. bunny rejects a blind DELETE on the zone root, so the full reset must
// enumerate-then-delete.
//
// proton-env injects BUNNY_STORAGE_ACCESS_KEY (the storage-zone password / `AccessKey`) from
// `.proton.yaml`. Zone and host are non-secret and hardcoded. Requests hit bunny's native Storage
// API directly (the deployed Hono endpoint has no delete route). This is destructive and runs with
// no confirmation prompt.

const ZONE = "snap-sync";
const HOST = "storage.bunnycdn.com";
const BASE = `https://${HOST}/${ZONE}/`;
// Registry marker prefix — must match `MARKER_PREFIX` in backend/src/app.ts.
const MARKER_PREFIX = "events";

const accessKey = Deno.env.get("BUNNY_STORAGE_ACCESS_KEY")?.trim();
if (!accessKey) {
  console.error(
    "missing BUNNY_STORAGE_ACCESS_KEY (run via `proton-env -- deno task reset-storage`)",
  );
  Deno.exit(1);
}
const headers = { AccessKey: accessKey };

type Entry = { ObjectName: string; IsDirectory: boolean };

/** List a directory (relative to the zone root). A missing directory (404) lists as empty. */
async function listDir(path: string): Promise<Entry[]> {
  const res = await fetch(`${BASE}${path}`, { method: "GET", headers });
  if (res.status === 404) return [];
  if (!res.ok) {
    console.error(`✗ listing ${path || "the zone"} failed (${res.status})`);
    Deno.exit(1);
  }
  return await res.json() as Entry[];
}

/** Every event id in the zone: registry markers (source of truth) ∪ top-level photo directories. */
async function allEventIds(): Promise<string[]> {
  const ids = new Set<string>();
  for (const e of await listDir(`${MARKER_PREFIX}/`)) {
    if (!e.IsDirectory && e.ObjectName.endsWith(".json")) {
      ids.add(e.ObjectName.slice(0, -".json".length));
    }
  }
  for (const e of await listDir("")) {
    if (e.IsDirectory && e.ObjectName !== MARKER_PREFIX) ids.add(e.ObjectName);
  }
  return [...ids];
}

/** DELETE one path; `absent` (404) is an idempotent no-op, any other non-OK status aborts. */
async function del(path: string, label: string): Promise<"deleted" | "absent"> {
  const res = await fetch(`${BASE}${path}`, { method: "DELETE", headers });
  await res.text(); // drain so the connection can close cleanly
  if (res.ok) return "deleted";
  if (res.status === 404) return "absent";
  console.error(`✗ delete failed for ${label} (${res.status})`);
  Deno.exit(1);
}

const events = Deno.args.length > 0 ? Deno.args : await allEventIds();

if (events.length === 0) {
  console.log("no events to delete");
  Deno.exit(0);
}

console.log(`deleting ${events.length} event(s) ...`);

let deleted = 0;
for (const id of events) {
  // Photos (recursive: trailing slash) AND the registry marker, so the event truly stops existing.
  const photos = await del(`${id}/`, `${id} objects`);
  const marker = await del(`${MARKER_PREFIX}/${id}.json`, `${id} marker`);

  if (photos === "deleted" || marker === "deleted") {
    console.log(`✓ ${id} (objects: ${photos}, marker: ${marker})`);
    deleted++;
  } else {
    console.log(`· ${id} — nothing to delete`);
  }
}

console.log(`done: ${deleted} event(s) deleted`);
