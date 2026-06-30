# Tasks — status in-flight from a read-only ledger peek

## 1. InFlightSource seam (`:domain:status`, `sync-status`)

- [ ] 1.1 Define `InFlightSource` in `commonMain`: `val inFlight: StateFlow<Int>` + `suspend fun
      refresh()`. Value = the device's asset-counted in-flight (jobs created, not yet `COMPLETED`).
- [ ] 1.2 Provide a settable fake (`FakeInFlightSource`) for tests and the desktop harness.
- [ ] 1.3 `commonTest`: refresh updates the value; a failed read keeps `0` (or last good) without throwing.

## 2. iOS implementation (`:app:ios` iosMain — wiring only)

- [ ] 2.1 `LedgerInFlightSource(backend: LedgerBackend)` (or a thin reader): `refresh()` reads
      `backend.aggregates().pending` and publishes it; **read-only** — never `put`/`clear`/`resetTo`.
- [ ] 2.2 Construct it from `iosLedgerBackend()` in `SnapSyncRoot`. Do **not** construct a
      `LedgerWriter` (single-writer invariant). On any read failure, publish `0`.

## 3. Wire into the status pipeline (`sync-status`)

- [ ] 3.1 `ListingSyncStatusSource` takes an `InFlightSource`; add it as a 4th `combine` input.
- [ ] 3.2 Mint `pending = min(inFlight, max(0, total − completed))` (clamped to remaining). Keep
      `completed`/`total`/`active`/`failed`/`estimatedRemaining` as today. Classification (`state`)
      stays `synced` vs `total` — `pending` remains display-only.
- [ ] 3.3 Update the `SyncProgress.pending` KDoc: it is the ledger-reported in-flight asset count
      clamped to remaining, display-only.

## 4. Liveness (foreground refresh)

- [ ] 4.1 Refresh `InFlightSource` on **foreground entry**, alongside the existing `CompletedAssetsSource`
      refresh in the iOS composition root.

## 5. Harness + docs

- [ ] 5.1 Desktop control panel: a knob to forge the in-flight number (drive the fake), so every
      "{n} in progress" value is reviewable without a device.
- [ ] 5.2 `docs/design.md §2.4`: classification + synced count stay storage-truth; the in-flight
      caption is a read-only ledger peek (display-only). Note the narrow reversal of `ledger-free-status`.

## 6. Verify

- [ ] 6.1 `./gradlew build` green (JVM + Compose tests; `compileIosMainKotlinMetadata`).
- [ ] 6.2 On device: with photos mid-upload, the caption shows the ledger in-flight count (≤ remaining),
      distinct from `total − synced`; it refreshes on foregrounding and hides at 0.
