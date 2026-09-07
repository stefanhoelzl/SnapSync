## 1. The download projection's counts become one read

- [x] 1.1 Add a combined counts read to the `DownloadStore` port (`:domain` `ports/`) returning the imported
      count, the still-arriving count (excluding `UNIMPORTABLE`) and the in-flight count together; document it as
      one round-trip, citing the group's internal-consistency requirement (`sync-status`) as the reason.
- [x] 1.2 Remove `importedCount()`, `assetCount()` and `inFlightCount()` from the port once 1.4–1.6 land, so the
      separate reads cannot be reintroduced by a caller that did not need them.
- [x] 1.3 Add the SQLDelight query behind it in `:adapter:generic:app` (`SqlDelightDownloadStore` + its `.sq`),
      as a single statement rather than three calls wrapped in one method.
- [x] 1.4 Implement it in `:adapter:generic:fake`'s `InMemoryDownloadStore`, keeping the fake's public surface
      exactly its port contract (`FakeHonestyTest`).
- [x] 1.5 Extend the `DownloadStore` contract in `:test:world` with the *"The projection's counts come from one
      read"* scenario, so both driver implementations are held to it from their own test source sets.
- [x] 1.6 Update `StoreDownloadStatusSource` to build `DownloadProgress` from the single read, and sweep for any
      remaining caller of the three removed reads.

## 2. The cheap local status reads become a named group

- [x] 2.1 Give `StatusRefresh` a narrower entry point that performs exactly the group — the ledger aggregate then
      the download line — so the group has ONE definition rather than an ordering convention repeated per caller.
- [x] 2.2 Reduce `StatusRefresh.run()` to that entry point followed by the policy derivation and the library
      enumeration, leaving its existing failure-isolation and no-membership behaviour unchanged.
- [x] 2.3 Cover the group in `commonTest`: the two members are read before the enumeration, and one member's
      failure neither cancels the other nor the enumeration.

## 3. The poll ticks the group

- [x] 3.1 Rename `LedgerCountsPoller` to match what it now ticks, and change its input from `LedgerCountsSource`
      to the group entry point from 2.1.
- [x] 3.2 Update the composition in `:domain` `compose/` to build it from `StatusRefresh`, and the `Foreground` /
      `Background` flows' parameter types.
- [x] 3.3 Update `LedgerCountsPollerTest` and `ForegroundOrderingTest` to the new type and input, keeping the
      existing assertions (idempotent `start()`, first tick waits one cadence, a throwing tick does not kill the
      loop, `stop()` lands mid-tick).
- [x] 3.4 Add a `commonTest` assertion that a tick re-reads **both** members, so a future edit cannot quietly
      narrow the poll back to one arm.

## 4. Prove the defect is gone

- [x] 4.1 Add an integration test in `:test:integration` over `:test:world` on a `TestScope`: with a membership
      joined and the screen settled, plan foreign assets **without** calling `refreshStatus`, advance virtual
      time past one cadence, and assert `UiState` leaves `InSync`.
- [x] 4.2 Add the entry-race case to the same test: read the projection before discovery plans the burst (the
      order `Foreground` actually produces), then assert a later tick corrects it — the scenario *"The entry read
      is repaired by the poll, not by re-ordering"*.
- [x] 4.3 Assert the download-arm case explicitly — an asset imported mid-foreground reaches the projection
      within the bound — so the requirement's new scenario has a test behind it.

## 5. Gates and housekeeping

- [x] 5.1 Run `./gradlew architectureDiagrams` and commit; the flows' parameter types changed in 3.2 and stale
      `architecture/` blocks the PR.
- [x] 5.2 Run `./gradlew build` — JVM tests, the `:test:architecture` guards and the eight `detekt*Tier` budgets;
      raise no tier ceiling to make this fit.
- [x] 5.3 Run `./gradlew compileIosMainKotlinMetadata` to catch iOS-only breakage from the port change without a
      Mac.
- [x] 5.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and the change's own validation.
- [x] 5.5 Confirm no iOS shell or harness file changed, per the design's Non-Goals.
