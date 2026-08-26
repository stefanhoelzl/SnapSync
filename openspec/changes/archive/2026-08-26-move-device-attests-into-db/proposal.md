## Why

The device attestation record `devices/<deviceId>.attest.json` is the **last non-byte object the Edge
Script reads or writes**. Every other relational fact moved to the database
(`changes/archive/2026-08-25-record-uploads-in-database`), which deliberately left this one behind as a
non-goal; its own design record named the follow-up: *"If a later change moves them, the fully-orphaned
collection rule in `scheduled-cleanup` becomes wholly relational; today it stays mixed."*

Moving it finishes that split — storage holds bytes, the database holds facts — and closes a live leak on
the way: `knownDevices()` is `device_records ∪ resources`, so a device that attested but never registered a
push token and never uploaded has no row, and its attestation object is never collected by any sweep.

## What Changes

- The attested public key, its environment, and its attestation time become **columns**, not an object.
  `POST /attest/token` writes the row; `POST /attest/renew` reads it.
- `device_records` is renamed **`devices`** — it stopped meaning "push registration" the moment
  attestation landed in it. **BREAKING** for rollback: reverting the bundle leaves old code writing a
  table that no longer exists.
- A new `attest_token_expires_at` column, written by **both** mint routes, records how long a token minted
  for this device can still verify. `POST /attest/renew` becomes read-**write** and, like
  `POST /attest/token` already does, refuses to mint when it cannot persist (502).
- **A `devices` row exists if and only if the device has attested.** The gate forces the ordering (no push
  registration without a token, no token without attestation), so attestation INSERTs and
  `PUT /devices/:deviceId` only UPDATEs.
- `PUT /devices/:deviceId` answers **401** when it affects zero rows. This widens 401 from "no valid
  token" to also mean "the backend holds no attestation for this device" — which the shipped client
  already recovers from with no change: `onRejected` → re-attest → `tokenChanged` → re-PUT.
- The sweep's device collection becomes **wholly relational and credential-gated**: a device in no
  surviving event is collected once **no token minted for it can still verify**. Collecting earlier would
  delete the attestation backing a credential the device still holds, forcing a full Apple re-attestation
  at its next call — and because the sweep runs nightly, that would recur for as long as the device stayed
  orphaned. `knownDevices()` is deleted; the phase becomes one predicate.
- **REMOVED**: the sweep's empty-store refusal (`storeIsEmpty`). It is code-only, with no requirement
  behind it. Removing it is an accepted trade recorded in the design.
- Schema evolution becomes **versioned migrations** — `SCHEMA` (created shape) plus an ordered
  `MIGRATIONS` list, bound by a test asserting the two produce identical schemas, transplanting the device
  side's `Ledger.sq` / `*.sqm` property. `api-deploy.yml` gains the **database** credentials and applies
  migrations before publish; `BUNNY_STORAGE_ACCESS_KEY` stays out, per `backend-deployment`'s standing
  requirement.
- The one-time data migration (read the objects, rebuild the table, delete the objects) runs from a
  dispatched job holding both credentials, because it is a data move, not a schema-only one.
- The push-registration **join** moves into the shared composition as `installPushRegistration(...)`,
  beside `installPermissionSubscriptions()` — the attest-first ordering and the `tokenChanged` retry, which
  are behaviour, rather than the platform objects, which the shell still builds. It is no longer assembled
  in untested shell source. It is **not yet exercised end-to-end**: doing so needs the world to attest, and
  its `AttestKey` fake refuses (see Impact).

**No client change.** Not one line of Kotlin or Swift changes behaviour; the composition move is a
relocation of existing wiring.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `device-attestation`: the attested public key persists as a database row rather than
  `devices/<deviceId>.attest.json`; renewal records the minted token's expiry and refuses to mint when it
  cannot; 401 additionally means "no attestation on file".
- `database`: `device_records` becomes `devices` and carries the attestation columns; the
  rebuildable-state requirement widens from "storage plus a manifest publish" to "reconstructible without
  operator action, by a device round-trip"; schema evolution is a versioned, verified migration list
  applied by CI.
- `scheduled-cleanup`: a fully-orphaned device's record is a row, not a row plus an object, and is
  collected only once no token minted for it can still verify.
- `api-endpoints`: `PUT /api/v1/devices/:deviceId` answers 401 when the backend holds no attestation for
  the device.
- `backend-deployment`: the deploy workflow holds database credentials and applies migrations before
  publishing; the storage access key remains excluded.
- `push-registration`: the registration write is refusable — it requires an attestation record and is
  re-sent when a fresh credential is obtained; the warm-rejoin window narrows, because a collected record
  now implies a dead credential.
- `ios-app-shell`: push registration is started by the shared composition, like the permission
  subscriptions, rather than assembled in the shell.
- `architecture-guards`: a pin that the shell still routes a rejected token into the attestation feature —
  the one wiring a construction cycle prevents moving into the composition.

## Impact

- **`api/src/`**: `storage.ts` loses `deviceAttestKey` and `AttestRecord` and becomes bytes-only;
  `db.ts` gains the `devices` table and its statements and loses `knownDevices`/`storeIsEmpty`; `app.ts`
  loses its last `putObject`/`readObjectText` call sites; `attest.ts` receives `AttestEnvironment`; a new
  `migrations.ts`; `scripts/sweep.ts` loses its device roster loop.
- **`.github/workflows/`**: `api-deploy.yml` gains database credentials and a migrate step; a new
  dispatched job runs the one-time data migration.
- **Kotlin**: `:domain` `compose/` gains `installPushRegistration`; `:app:ios` loses the corresponding
  ordering and subscription; `:test:architecture` gains one pin on the rejection hook a construction cycle
  keeps in the shell.
- **Not covered, and recorded rather than left to be discovered**: the 401 → re-attest → `tokenChanged` →
  re-register loop is exercised nowhere end-to-end. `:test:world`'s `AttestKey` reports
  `isSupported() = false` and `generateKey()` throws, and its mini-edge is unauthenticated, so
  `tokenChanged` never emits. Teaching it to attest is a `harness-world-model` change that would switch
  attestation on for every existing world test. The halves are covered separately (the trust feature by its
  own suite, the shell hook by the new pin); the note lives beside the fake in `World.kt`.
- **Deployed state**: one ordered migration against the live store, and the deletion of every
  `devices/*.attest.json` object.
- **Accepted, eyes open**: the rename forfeits revert-the-bundle rollback and costs a short window of
  502s on the config and renew routes; the empty-store refusal is removed with nothing in its place;
  devices whose attestation object is missing lose their row and re-attest once; a device woken inside
  every renewal margin while a member of nothing retains its row indefinitely.
