## 1. Reconciler — flip the empty-listing behavior

- [x] 1.1 In `capability/rejoin/.../Reconciler.kt`, remove the `filenames.isEmpty() && ledger.aggregates().completed > 0` defer guard so a confirmed-successful empty (or partial) listing proceeds to `resetTo` + `clearDiscoveryCursor` + `marker.set` like any other successful listing.
- [x] 1.2 Keep the fetch-failure and timeout defers (`withTimeoutOrNull` null → return false; `getOrElse` failure → return false) exactly as-is — the ledger must still only reset on an authoritative listing.
- [x] 1.3 Replace the stale "same-session-switch transient / read-your-writes lag" comment with the real rationale: a successful listing is authoritative (upload confirms bytes before 2xx; storage LIST is read-after-write consistent; the list endpoint returns 502 — not an empty array — on failure), so an empty listing means a real reset.

## 2. Tests

- [x] 2.1 Rewrite the `ReconcilerTest` case "an empty listing while the ledger holds COMPLETED rows defers instead of wiping" into "…re-baselines to empty and re-uploads": assert the ledger is `resetTo` empty, the cursor is cleared, the marker is set, and `reconcile` returns true (uploads may proceed).
- [x] 2.2 Add a `ReconcilerTest` case for **partial** deletion: listing returns a strict subset of the ledger's COMPLETED files → ledger seeded to exactly that subset (missing keys absent), cursor cleared, marker set.
- [x] 2.3 Confirm the untouched cases still pass: fresh-device zero-row settle, fetch-failure defer, listing-timeout defer, stored-resource seeded, stale-row drop.
- [x] 2.4 (Optional, if the world fakes support a storage wipe) add a `:test:world` scenario: back up N assets → wipe the backend store → join a new event → the producer re-uploads all N and the union/manifest recover.

## 3. Verify

- [x] 3.1 `./gradlew :capability:rejoin:jvmTest` green; then `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green.
- [x] 3.2 On-device (per the earlier notify verification loop): reset storage → provision a fresh event → confirm the device re-uploads (byte partition refills, union/manifest return, status returns to In sync) instead of hanging in Syncing.
- [x] 3.3 `npx --yes @fission-ai/openspec@1.4.1 validate rebaseline-ledger-on-storage-reset --strict` passes; archive after merge.
