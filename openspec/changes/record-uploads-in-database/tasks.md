## 1. Provision and connect

- [x] 1.1 Create the production database; record its URL and token as the `BUNNY_DATABASE_URL` /
      `BUNNY_DATABASE_AUTH_TOKEN` Edge Script environment variables **before** any code reading them
      merges to `main`
- [x] 1.2 Declare both as environment references in the deployment components; confirm `local` resolves to
      its own database and cannot address the production one
- [x] 1.3 Add `@libsql/client@^0.15/web` and a single client module; validate both credentials at startup
      with the existing secrets, so a missing one fails the boot
- [x] 1.4 Verify `PRAGMA foreign_keys` reports enforcement on from an Edge Script, not only from a
      workstation; extend the post-publish boot probe to assert it and to fail immediately (not retry) when
      it is off
- [x] 1.5 Store-capability probe RUN against the deployed store (`PROBE-FINDINGS.md` §5): foreign keys ON,
      dangling reference rejected, two-level cascade, batch rollback, and `STRICT` both accepted AND
      enforcing — so `STRICT` is adopted
- [x] 1.6 Give the nightly sweep its store credentials — it marks from the database now, so without them
      its first scheduled run would fail at startup

## 2. Schema and migration

- [x] 2.1 Write the idempotent, re-runnable migration creating `events`, `memberships`, `event_assets`,
      `resources`, `device_records` (all `STRICT`) and the `resources_by_asset` index (design.md D1)
- [x] 2.2 Give every text primary key an explicit `NOT NULL`; add a test that a null key is rejected
- [x] 2.3 Add a test that the two-level cascade removes memberships and their assets when an event row is
      deleted, and that a `resources` row survives it

## 3. Writes

- [x] 3.1 Manifest route: replace the storage `PUT` with one atomic transaction — membership upsert,
      full-state `event_assets` replace, `resources` upserts with `uploaded ?? true`
- [x] 3.2 Chunk the replace within one transaction against the 32 766 bound-parameter limit; test that a
      chunked publish is never observable half-applied
- [x] 3.3 Byte route: record `uploaded = 1` best-effort after a successful store; test that a database
      failure still returns the storage success
- [x] 3.4 Enrollment: implement capacity as one conditional `INSERT … SELECT`; test all four cases (new at
      capacity refused, known passes, departed rejoin reuses its slot, active re-enrol idempotent) and the
      concurrent race
- [x] 3.5 Distinguish the two `rowsAffected = 0` causes with a follow-up existence read — `409` at
      capacity, `404` absent; test both (design.md D6)
- [x] 3.6 Create: write the `events` row; keep name validation, minted id, and the cited window rules
- [x] 3.7 Rename: update `name` only; add a test asserting no other column changes
- [x] 3.8 Leave: set `state = 'departed'`, idempotent, assets retained
- [x] 3.9 Device config: write the `device_records` row, last-write-wins

## 4. Reads

- [x] 4.1 Per-device listing: serve `{filename, url}` from `resources` where `uploaded = 1`; drop `size`
- [x] 4.2 Event union: one query spanning `active` **and** `departed` memberships, excluding any asset with
      an unrecorded resource; drop `size`; keep the closed entry shapes
- [x] 4.3 Keep one presigned-URL authority shared by both read routes
- [x] 4.4 Existence gates read the `events` row; a non-absence failure is `502`, never `404`
- [x] 4.5 Notify: enumerate `active` memberships joined to `device_records`; keep the best-effort `202`
- [x] 4.6 Assert no read route enumerates storage any more

## 5. Sweep

- [x] 5.1 Event phase: delete stale event rows (past deadline, or has memberships and none active) with the
      decision taken inside an interactive transaction (design.md D9)
- [x] 5.2 Delete the *incomplete* staleness class and tombstone reclamation; add a test that an event with
      no memberships survives to its deadline
- [x] 5.3 Asset phase: compute the referenced-byte set and per-device floors by query over surviving events
- [x] 5.4 Collect a byte only after deleting its `resources` row; add a test that a crash between the two
      leaves a byte the next run collects (design.md D8)
- [x] 5.5 Fully-orphaned device: collect its `device_records` row and its attest object
- [x] 5.6 Keep the run summary and dry-run; confirm the sweep still touches no `site/` prefix

## 6. Cutover

- [x] 6.1 Write the backfill (throwaway, scratchpad — see design.md D13a): markers → `events`; active/departed manifests → `memberships` with the state
      the last-write-wins rule resolves, plus their `event_assets` and `resources` rows with `uploaded = 1`;
      device config objects → `device_records`
- [x] 6.2 Write the verifier (throwaway, scratchpad): for each surviving event, compare the database-served union against what the
      previous implementation would have served, as sets of `(deviceId, assetId, key)`
- [ ] 6.3 (OPERATOR — needs the deployed store) From the scratchpad: `./run.sh probe`, `./run.sh backfill
      --dry-run`, `./run.sh backfill`, `./run.sh verify` — and see verify GREEN **before** the next
      nightly sweep window (03:17 UTC). Record what the probe measured in `PROBE-FINDINGS.md`
- [x] 6.4 Confirm no code path deletes a marker, manifest, or config object — they stay in place as the
      rollback path (design.md D13)

## 7. Harness and tests

- [x] 7.1 Update `:test:world`'s backend model to the relational state: membership as a state on one
      record, resources with an `uploaded` flag, no active/departed sibling objects
- [x] 7.2 Update the world's per-device listing to `{filename, url}` and its union to span departed
      memberships
- [x] 7.3 Run `:test:integration` and the api test suite; fix fallout in both

## 8. Specs and docs

- [ ] 8.1 (SYNC PHASE — not apply) Sweep stale citations in specs whose requirements do not change — `event-rename`,
      `web-event-download`, `push-registration`, `device-identity`, `leave-event`,
      `edge-upload-provider` — each naming a removed capability or a retired storage key in prose
- [x] 8.2 Update `api/README.md` and the `local-backend` skill for the local database the dev rig needs
- [x] 8.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 8.4 `./gradlew build` and `./gradlew architectureDiagrams`; commit any diagram change
