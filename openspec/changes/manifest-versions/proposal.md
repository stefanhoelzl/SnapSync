## Why

Since `both-uploaders-active` (PR #284), the app and the iOS ≥26.1 upload extension each run upload cycles, and each
publishes the device manifest as a full-state replace where the last write wins. Two publishes can cross. The backend
can then keep the older snapshot while the shared skip-if-unchanged marker names the newer one. The two ways this
happens are: the server commits the writes in one order while the processes save their markers in the other, or a
committed PUT loses its response. From then on every cycle sees "unchanged" and never publishes again. The device
recovers only at the next change to the manifest, and after the event's last change there is none, so a photo left
out of the manifest is never shared. The `device-manifest` spec calls this crossed pair "bounded and self-healing".
It heals only if a later change arrives, and nothing guarantees one.

## What Changes

- **Every manifest publish carries a version that only grows, and the backend refuses a strictly older one.** The
  version is a device counter kept in the App-Group ledger database. It survives `clear()` and `resetTo()`.
- **Two things bump the counter:** every change to a ledger column the manifest shows (inserts, deletes, and updates to
  the columns it projects; never `state`), done by a SQLite trigger so that no write path can forget it; and a
  reconfigure, *after* its config save has landed.
- **A cycle reads the counter first**, before the config and before the ledger rows. Any change the projection misses
  therefore happened after the read and carries a higher number. The entry gate's port-pure translation gains this
  one read, and the number travels with the cycle to the manifest producer.
- **The skip marker includes the version** (`eventId`, version, JSON). A projection is skipped only when all three
  match. Reading the counter first gives one number to snapshots whose contents can differ, so a marker that compares
  only the content could still get stuck.
- **Backend**: an additive, nullable `memberships.manifest_version` column (`api/migrations/0003_…`). The v2 manifest
  publish compares and replaces in its one atomic batch. Missing version or `version >=` stored → store; strictly
  older → leave everything unchanged, answer 2xx, no fan-out. The client treats that 2xx as published: a newer
  snapshot is already stored.
- **The v2 join resets `manifest_version` to NULL** in its existing `ON CONFLICT` clause. A re-join starts a new
  sequence of manifests, so a device whose counter restarted (a reinstall keeps the device id in the Keychain) is
  never refused forever. This is recorded as a named exception to the database's one-writer rule.
- **Compatibility**: v2 builds that send no version are accepted without a check and set the stored version to NULL,
  so an upgraded build wins from any counter value. The v1 route is unchanged. No `426`.
- **Removed**: `device-manifest`'s "crossed pair heals at the next ledger change" acceptance, replaced by ordering.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `device-manifest`: a publish carries the manifest version; the skip marker includes it; the crossed-pair
  acceptance is replaced by backend ordering; a re-join now republishes (the ledger load bumps the counter).
- `api-endpoints`: the v2 manifest body's `version`; an older publish is refused as a 2xx that writes nothing and
  wakes nobody; the v2 join resets the stored version.
- `database`: the `memberships.manifest_version` column; the compare-and-replace inside the one publish transaction;
  the named one-writer exception for the join's reset.
- `sync-ledger`: the manifest version counter, its trigger, its survival across the reset family, and the `11.sqm`
  migration that adds them.
- `upload-lifecycle`: the entry gate's translation reads the manifest version first, before the config.
- `reconfigure-membership`: the config save bumps the manifest version after it lands.

## Impact

- **api/**: `migrations/0003_…sql` (additive column; a pending migration triggers `api-deploy`'s maintenance-bundle
  window, a short planned outage); `schema.sql` regenerated; `db.ts` (`ENROLL`, `publishStatements` for v2);
  `app.ts` (the v2 manifest route); `README.md`; `test/app.test.ts`. The v1 wire-contract tests pass unmodified.
- **App**: `LedgerStore` port (the counter read and bump), `SqlDelightLedgerStore` + `Ledger.sq` + `11.sqm` + the
  schema snapshot, `InMemoryLedgerStore`, the store contract in `:test:world`; `ManifestPublisher` /
  `HttpManifestPublisher` (body field); `DeviceManifestProducer` (marker); `UploadCore` / `UploadCycle` (read first,
  carry the number); `ReconfigureEvent` + its composition in `SnapSyncApp`.
- **Deploy order**: a merge deploys the backend (`api-deploy.yml`) while the app only reaches internal TestFlight,
  so the backend always lands first. Rollback: a reverted backend ignores the column and the field; a reverted app
  sends no version, which is accepted.
- **Not changed**: what the manifest declares, the push fan-out beyond "only when the publish won", the v1 route.
