## 1. Storage helpers and measured facts

- [x] 1.1 Add `eventDir(eventId): string` to `api/src/storage.ts` beside `deviceDir`, returning
      `events/<encodeURIComponent(eventId)>/` — the trailing slash is what makes it a directory path.
- [x] 1.2 Correct `listDir`'s doc comment to the measured behavior: a directory listing returns `200 []` for
      an **empty** path and for one that **never existed** — bunny `404`s neither — while a **file** GET does
      `404` (which is why `readMarker`'s null branch is live and this one is not). Cite the 2026-07-26
      measurement and its expiry trigger.
- [x] 1.3 Change `listDir`'s return type to `BunnyEntry[]`, mapping a `404` to `[]` rather than `null`. It
      must NOT throw on `404`: `bunny-list-endpoint` requires an absent/empty directory to read as empty and
      a partition `404` to not be treated as a failure.
- [x] 1.4 Extend `deleteObject`'s doc to state that a key ending in `/` deletes the directory **recursively**,
      citing <https://docs.bunny.net/api-reference/storage/manage-files/delete-file>.

## 2. Drop the `| null` from the listing seam

- [x] 2.1 Narrow `resolveMembership(entries: BunnyEntry[])` and `eventIsStale(..., entries: BunnyEntry[], ...)`
      in `api/src/lifecycle.ts`, removing the internal `entries ?? []`.
- [x] 2.2 Remove the now-redundant `?? []` guards at every `listDir` call site in `api/src/sweep.ts` and
      `api/src/app.ts`, and drop `app.ts:979`'s stale `// 404 → null → no bytes present` comment.
- [x] 2.3 Confirm no behavior changed: `deno task check` (or the repo's type/lint task) passes and
      `api/test/app.test.ts` is green with no edits — the union's empty-partition scenarios are the proof.

## 3. Tombstone reclamation in the sweep

- [x] 3.1 In `api/src/sweep.ts`'s event phase, add the tombstone branch: when `marker === null` **and** the
      `devices/` listing holds no manifest object, reclaim it with one `deleteObject(f, config, eventDir(id))`
      and `continue` — before the existing staleness classification.
- [x] 3.2 Count a tombstone in **neither** `events.deleted` nor `events.kept`, and do not push it onto
      `surviving`.
- [x] 3.3 Leave the stale-event path untouched: manifests object-by-object first, marker last. Verify the
      recursive delete is reachable only from the tombstone branch.
- [x] 3.4 Under `dryRun`, log `[dry-run] would prune empty directory events/<id>/` and delete nothing,
      matching the existing dry-run line style.
- [x] 3.5 Keep the prune inside the existing per-event `try`, so a failure logs through the current catch and
      increments `errors` without aborting the run.
- [x] 3.6 Confirm no `files/devices/<deviceId>/` directory delete was added anywhere — the byte partition is
      deliberately never reclaimed (design D3).

## 4. Tests (`api/test/sweep.test.ts`)

- [x] 4.1 A tombstone (marker absent, no manifest objects) is pruned via a single delete of
      `events/<id>/`, and appears in neither `events.deleted` nor `events.kept`.
- [x] 4.2 A marker-less directory that still holds a manifest object is NOT a tombstone: its manifests are
      deleted individually, its marker delete is attempted last, no directory delete is issued, and it counts
      as a deleted event.
- [x] 4.3 Dry-run over a zone holding tombstones logs each one and performs zero deletes.
- [x] 4.4 A real stale event with manifests still deletes manifests before the marker (assert call order),
      and no recursive directory delete is issued for it.
- [x] 4.5 A fully-orphaned device with every byte collected has its byte objects deleted and its
      `files/devices/<id>/` directory left in place (no directory delete recorded).

## 5. Verify and ship

- [x] 5.1 `cd api && deno task test` green; run the repo's canonical check for anything else the diff touched.
- [x] 5.2 `npx --yes @fission-ai/openspec@1.5.0 validate prune-swept-event-directories --strict` passes.
- [ ] 5.3 Branch → PR → `/ship`.
- [ ] 5.4 After merge: `gh workflow run nightly-cleanup.yml -f dry_run=true`; confirm the prune lines name the
      known husk ids and that no live event id appears among them.
- [ ] 5.5 Let the 03:17 UTC schedule run for real, then confirm `events deleted` dropped from ~46 to the count
      of genuinely stale events and that `GET events/` returns only live event directories.
