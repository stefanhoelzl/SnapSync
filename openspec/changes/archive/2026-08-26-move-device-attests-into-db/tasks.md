## 1. The migration mechanism (design.md D6)

- [x] 1.1 Add `api/src/migrations.ts`: an ordered `MIGRATIONS` list and a `schema_migrations` record table;
      `migrate(db)` applies only unapplied entries, in order, and is a no-op on an already-migrated store
- [x] 1.2 Make migration **v1** the current five `CREATE TABLE IF NOT EXISTS` statements verbatim, so
      applying it to the live store records a version and changes nothing
- [x] 1.3 Add `api/test/migrations.test.ts`: build one store from `SCHEMA`, another by replaying every
      migration, assert the two schemas are identical (tables, columns, types, NOT NULL, indexes)
- [x] 1.4 Point `dev/serve.ts` and the test helpers at the new `migrate`; confirm a fresh dev store still
      comes up on the created shape

## 2. The `devices` table (D1, D2)

- [x] 2.1 Rewrite `SCHEMA`'s `device_records` as `devices` with `created_at`, the four `NOT NULL`
      attestation columns (`attest_key`, `attest_env`, `attested_at`, `attest_token_expires_at`) and the
      four nullable push columns (`push_kind`, `push_token`, `push_env`, `push_updated_at`), `STRICT`
- [x] 2.2 Write migration **v2**: rename the table, add the columns, seed `created_at` and
      `push_updated_at` from the old `updated_at` — `push_updated_at` exactly, `created_at` as an upper
      bound, stated as such in the migration's comment
- [x] 2.3 Split `putDeviceRecord` into a push-only upsert that names **only** the push columns and never
      `created_at`, and returns `rowsAffected` so its caller can disambiguate a zero-row write
- [x] 2.4 Add the attestation upsert: inserts the row with `created_at`, replaces the `attest_*` group on
      conflict, and never touches the push columns
- [x] 2.5 Add the renewal write (advance `attest_token_expires_at`) and the read the renew route needs
      (`attest_key` by `deviceId`), with absence and transport failure kept apart — null only for "no row"
- [x] 2.6 Move `AttestEnvironment` from `storage.ts` to `attest.ts`; `db.ts` imports the type only

## 3. The attest routes (D3, D5)

- [x] 3.1 `POST /attest/token`: persist key + environment + attested-at + the minted token's expiry as a
      row; keep the existing `502`-and-mint-nothing behaviour on a failed persist
- [x] 3.2 `POST /attest/renew`: read the key from the row; on a verified assertion, **write the new expiry
      before minting**, and answer `502` minting nothing if that write fails
- [x] 3.3 `POST /attest/renew`: answer `401` when no row exists, with two distinguishable log lines — a
      device never seen, versus one whose record the sweep collected
- [x] 3.4 Delete `putObject` / `readObjectText` / `deviceAttestKey` / `AttestRecord` from `app.ts`'s
      imports and from `storage.ts`; confirm `app.ts` imports only `byteKey` and `FetchLike` from it

## 4. The device-config route (D3)

- [x] 4.1 `PUT /devices/:deviceId`: UPDATE only, never INSERT; answer `401` on a zero-row write, and log
      it as "no attestation on file" rather than as a token failure
- [x] 4.2 Confirm the check sits in the route, not the gate — the gate still performs no read

## 5. The sweep (D4, D9)

- [x] 5.1 Replace the device-record phase's roster walk with one predicate: no membership in any surviving
      event **and** `attest_token_expires_at` in the past; `SELECT` for dry-run, `DELETE` for a real run
- [x] 5.2 Delete `knownDevices` and the object delete beside the row delete
- [x] 5.3 Delete `storeIsEmpty`, its call site and comment block, and its tests (D9 — recorded in the
      design as a deliberate reduction in safety)
- [x] 5.4 Add a test that an orphaned device whose recorded expiry is still in the future is **retained**,
      and one that it is collected once the expiry has passed
- [x] 5.5 Keep the summary's three tiers as `{deleted, kept}`; drop the now-obsolete
      "regardless of how many of its global config/attestation records exist" parenthetical

## 6. Deployment (D7)

- [x] 6.1 Add `BUNNY_DATABASE_URL` / `BUNNY_DATABASE_AUTH_TOKEN` to `api-deploy.yml` and a migrate step
      ordered **before** the publish step, failing the run without publishing if it fails
- [x] 6.2 Confirm `BUNNY_STORAGE_ACCESS_KEY` is absent from `api-deploy.yml`
- [x] 6.3 Add the dispatched one-time job (with a `dry_run` input, as `nightly-cleanup.yml` has) carrying
      both credential sets

## 7. The one-time data migration (D8)

- [x] 7.1 Write the program: list `devices/`, read each `*.attest.json`, rebuild `devices` with the
      attestation columns `NOT NULL`, insert rows for devices holding an object but no `device_records`
      row (seeding `created_at` from the object's `attestedAt`), drop rows with no attestation data and
      log the count
- [x] 7.2 Seed `attest_token_expires_at` for carried rows — no minted-token expiry is recoverable from the
      object, so choose and record the seeding rule in the program's comment (the conservative choice is
      `now + tokenTtl`, which retains every carried device for one full token lifetime)
- [x] 7.3 Delete the objects: **exact `.attest.json` suffix only**, never a prefix, never a key ending in
      `/`, after the read, and re-runnable
- [x] 7.4 Record migration v2 as applied so the CI runner skips it thereafter

## 8. The composition move and its guard (D10)

- [x] 8.1 Add `AppCore.installPushRegistration(pushTokenSource)` in `:domain` `compose/`, owning both the
      construction and the token-changed re-send subscription, beside `installPermissionSubscriptions()`
- [x] 8.2 Remove the corresponding construction and subscription from `SnapSyncRoot`; the root invokes the
      installer from its host-assembly path only
- [ ] 8.3 **BLOCKED — needs a decision.** A test that a refused registration is re-sent once a new token
      is obtained requires the world to ATTEST: `World.kt`'s `AttestKey` reports `isSupported() = false`
      and `generateKey()` throws, and the mini-edge is unauthenticated, so `tokenChanged` never emits and
      the join has nothing to observe. Teaching it to attest is a `harness-world-model` change that would
      switch attestation on for every existing world test. The token-delivered arm alone tests nothing
      new (it is `PushRegistration.run`'s own contract). Recorded in `World.kt` beside the fake; the
      shell half is pinned by `CredentialRejectionWiringTest` instead
- [x] 8.4 Add the `:test:architecture` pin that the root still passes the rejection hook into the shared
      client and that it reaches the trust feature — failing closed if the scanned source is absent

## 9. The dev rig (D11)

- [x] 9.1 Make the fallback bearer enrol the device on `PUT /api/v1/devices/<id>`, extract the matcher to
      `src/dev/fallback.ts`, and pin it — without this a simulator (no App Attest, no recovery) loses push
      registration permanently

## 10. Tests, harness and docs

- [x] 10.1 Update `api/test/attest.test.ts`, `app.test.ts` and `scripts/sweep.test.ts` for the renamed table
      and the new columns (including the raw `INSERT INTO device_records …` fixtures)
- [x] 10.2 Add route tests: config write `401` on no row; renew `401` on no row; renew `502` on an
      unreadable store. The two "verified, then the write fails" `502` branches are NOT reachable: both
      need a valid App Attest attestation for THIS deployment's app id, and the committed fixture is a
      real device's attestation for a different app, so the verifier refuses it first. Covered instead at
      the statement layer (the two writers, the vanished-row bump), with the gap stated in the test file
- [x] 10.3 Update `:test:world`'s mini-edge if it is taught to model the config route's `401`; otherwise
      record in `harness-world-model` that it does not
- [x] 10.4 Update `api/README.md` and the `local-backend` skill for the migration step and the local rig's
      first-run behaviour (a dev device's first config write `401`s until it has attested against the rig)

## 11. Close-out

- [x] 11.1 `deno task check` and the api test suite green
- [x] 11.2 `./gradlew build` and `./gradlew architectureDiagrams`; commit any diagram change
- [x] 11.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 11.4 Sweep specs for stale citations of `devices/<deviceId>.attest.json`, `device_records`,
      `knownDevices` and the empty-store refusal
