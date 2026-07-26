## Why

The nightly sweep deletes objects but never the directory that held them, and bunny keeps an emptied
directory. Every swept event therefore leaves `events/<eventId>/` behind holding an empty `devices/`; the
next run re-enumerates it, reads a `404` marker, classifies "no marker" as stale, and deletes it again —
every night, forever.

Measured against the live zone: 40 identical event ids were reported `deleted stale event` on five
consecutive nights, and 46 of the 50 directories now under `events/` hold no marker at all. Three of those
five nights reported 42–46 events deleted while reclaiming **zero bytes**. The summary — the sweep's only
operator-facing output, on a job that deletes real users' photos — can no longer distinguish a working
night from a broken one, and the distortion grows by one entry per event ever created.

## What Changes

- The sweep recognizes a **tombstone**: an event directory carrying **no marker AND no manifest objects**.
  A tombstone is reclaimed with one recursive `DELETE events/<eventId>/`, which clears the nested empty
  `devices/` directory in the same call.
- A tombstone is counted in **neither** `events.deleted` **nor** `events.kept`. The events tier counts
  events; a tombstone is not one. `deleted + kept` therefore no longer covers every directory enumerated,
  and the summary requirement says so explicitly.
- **Dry-run logs each prune**, so the mode still lists everything a real run would delete.
- **Real stale events are untouched.** Their manifests-then-marker-**LAST** delete order is preserved —
  that ordering is what makes an interrupted run safely retryable, and no recursive delete is applied to a
  directory that still holds objects.
- A marker-less directory that **still holds manifest objects** is explicitly *not* a tombstone. It remains
  **incomplete** and takes the existing careful path — the same `marker === null` condition now resolves to
  two distinct outcomes.
- Device byte partitions (`files/devices/<deviceId>/`) are deliberately **not** pruned. The byte upload is
  ungated by design, so a recursive directory delete could destroy an in-flight upload that the device
  ledger already records as `COMPLETED` — silent and unrecoverable. The husk costs one wasted LIST per
  night and distorts no count.
- `listDir` returns `BunnyEntry[]` rather than `BunnyEntry[] | null` (a `404` yields `[]`), removing the
  optionality from `resolveMembership` and `eventIsStale` and the eleven `?? []` guards at its call sites.
  Behavior-preserving: nothing in the backend ever distinguished `null` from `[]`.
- The measured platform facts replace an incorrect doc comment: a directory listing returns `200 []` for a
  path that is **empty** *and* for one that **never existed** — bunny `404`s neither — while a **file** GET
  does `404`. `deleteObject` gains a note that a trailing-slash key deletes **recursively**.
- New `eventDir(eventId)` key builder, so the pruned path is built by a named helper like every other
  storage key rather than concatenated at the call site.

## Capabilities

### New Capabilities

None. This extends an existing capability rather than introducing one.

### Modified Capabilities

- `scheduled-cleanup`: adds a **Tombstone reclamation** requirement (defining the tombstone, its recursive
  prune, why that prune is race-free, and why device byte partitions are excluded); amends the
  **stale-event deletion** requirement so a marker-less directory resolves to tombstone *or* incomplete
  rather than uniformly "deleted"; amends the **dry-run, best-effort, and run summary** requirement so the
  events tier counts events only, tombstones appear in neither count, and dry-run logs each prune.

## Impact

- **`api/src/sweep.ts`** — tombstone branch in the event phase; tombstones excluded from both event counts.
- **`api/src/storage.ts`** — new `eventDir`; `listDir` returns `BunnyEntry[]`; corrected `listDir` doc with
  the measurement; `deleteObject` doc notes recursive directory deletion.
- **`api/src/lifecycle.ts`** — `resolveMembership` / `eventIsStale` drop `| null` from their entries
  parameter.
- **`api/src/app.ts`** — call-site simplification only (the eleven `?? []` guards). No route behavior
  changes: `bunny-list-endpoint` requires a directory `404` to read as empty and not as a failure, and
  returning `[]` from `listDir` satisfies that exactly as `null` did. **No delta needed there.**
- **`api/test/sweep.test.ts`** — four new pins: a tombstone is pruned and counted in neither tier; a
  marker-less directory with manifests is not a tombstone; dry-run logs the prune and deletes nothing; a
  real stale event's manifests-then-marker-LAST order is unchanged.
- **No change** to `.github/workflows/nightly-cleanup.yml`, to the sweep's credentials (still the
  storage-zone `AccessKey` alone), or to any device-facing behavior.
- **Storage effect on first real run:** ~46 directories reclaimed, and `events deleted` drops from ~46 to
  the count of genuinely stale events — which is the change's own verification.
