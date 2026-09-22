## 1. Backend: the stored version and the ordered publish

- [x] 1.1 Add `api/migrations/0003_membership_manifest_version.sql` (`ALTER TABLE memberships ADD COLUMN manifest_version INTEGER`) and regenerate `api/schema.sql` with `deno task schema`
- [x] 1.2 `db.ts`: `ENROLL`'s `ON CONFLICT … DO UPDATE SET state = 'active', manifest_version = NULL`, with its comment naming the one-writer exception (design D7)
- [x] 1.3 `db.ts`: `publishStatements` for v2 takes an optional version. With a version: a leading guarded `UPDATE memberships SET manifest_version = ?` (`IS NULL OR <= ?`), and every following statement gated on `EXISTS (… manifest_version = ?)`. Without one: a leading `SET manifest_version = NULL`, ungated. The legacy (v1) path emits no version statement.
- [x] 1.4 `app.ts`: parse and validate the optional body `version` (non-negative safe integer, else `400`) on the v2 manifest route only; read statement 1's `rowsAffected` to learn whether the publish won; run the fan-out only when it won; respond `200` either way
- [x] 1.5 `api/test/v2.test.ts`: newer / equal / older (asset set, stored version and fan-out all unchanged) / versionless (clears) / invalid version (`400`) / a re-join clears the version and a restarted counter is accepted / an older publish large enough to chunk changes nothing / v1 ignores `version`. The existing v1 wire-contract tests pass unmodified.
- [x] 1.6 `api/README.md`: document the body field, the ordering, the `200` on refusal, and the join reset
- [x] 1.7 `cd api && deno task` checks (fmt, lint including `lint/complexity.ts`, check, test) green; the schema assertion matches the replayed migrations

## 2. Ledger: the manifest version counter

- [x] 2.1 `Ledger.sq`: the `manifestVersion` table (no seed; an absent row reads 0); the `AFTER INSERT` / `AFTER DELETE` / `AFTER UPDATE` (advancing only when a projected column changed) triggers on `ledgerRow`; the `selectManifestVersion` and `bumpManifestVersion` queries. Add a comment that a future column drop must recreate the triggers.
- [x] 2.2 `11.sqm` (v11 → v12): the same table, seed and triggers, touching no row; the SQLDelight migration verify passes against the committed snapshot
- [x] 2.3 `LedgerStore` port: `manifestVersion(): Long` and `bumpManifestVersion()`; implement in `SqlDelightLedgerStore`
- [x] 2.4 `InMemoryLedgerStore` (`:adapter:generic:fake`): the same advance rules, without triggers (insert, delete, projected-field change, reset family; not `state`/`destinationPath`; a declined record does not advance)
- [x] 2.5 `LedgerStoreContract` (`:test:world`): the sync-ledger version scenarios, which run against the SQLDelight (JVM + native) and in-memory stores
- [x] 2.6 `SqlDelightLedgerStoreTest`: `11.sqm` preserves `COMPLETED` rows and seeds the version at `0`

## 3. App: carry the version from the gate to the publish

- [x] 3.1 `uploadCore`'s entry-gate translation reads `ledger.manifestVersion()` first, before `ConfigReader.read()`; an unreadable version produces Skip; the version rides on the admitted cycle's state to `onDiscovery`
- [x] 3.2 `DeviceManifestProducer.produce` takes the version and stamps it on the document (`DeviceManifest.version`, `withVersion`). The skip marker (`"$eventId $json"`) therefore holds the version.
- [x] 3.3 The body carries `version` as a field of the manifest document; the `ManifestPublisher` port and `HttpManifestPublisher` are unchanged (they send the document verbatim, which `HttpJoinSeamsTest` already asserts)
- [x] 3.4 `ReconfigureEvent`: an injected `bumpManifestVersion: suspend () -> Unit`, called inside the `save config` step after `store.save`. Wire it in `SnapSyncApp` over the ledger store, and in the world and harness compositions.
- [x] 3.5 Unit tests (commonTest): the producer skips only on an equal (event, version, snapshot); a version change with equal content republishes; the reconfigure bumps after a save and not after a failed one; the gate reads the version before the config

## 4. Integration: the race, end to end

- [x] 4.1 `:test:world` mini-edge: model the backend ordering (stored version per membership, refusal as `200`, join reset) so the world's backend matches the real route
- [x] 4.2 `:test:integration`: two cycles that cross (older publish delivered last) leave the backend holding the newer snapshot; a same-version cross is republished by the next cycle; a reconfigure landing at the gate's version read is published by that same cycle (pins version-before-config); a changing cycle is followed by exactly one republish

## 5. Gates and records

- [x] 5.1 `./gradlew build` green (including the detekt tiers and `:test:architecture`); `./gradlew compileIosMainKotlinMetadata` green
- [x] 5.2 `./gradlew architectureDiagrams` regenerated and committed if it changed
- [x] 5.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green
- [ ] 5.4 At sync: update `device-manifest`'s Purpose, where "last write wins" becomes "ordered by the manifest version", and add a `changes/archive/<date>-manifest-versions` citation to `sync-ledger`'s Purpose
