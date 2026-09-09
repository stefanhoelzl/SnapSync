## 1. The port seam

- [x] 1.1 Add `freeSlots(): Int?` to `BackgroundTransfer` (`domain/ports/.../ports/BackgroundTransfer.kt`), with a KDoc stating: what it reports, that `null` means "this platform will not say", that it is advisory, and why both staleness directions are safe (design D5)
- [x] 1.2 Confirm `SelectionScopedTransfer` needs no override — it delegates by `by delegate`, and free capacity is the platform's fact under either grant; state that in a comment rather than leaving it to inference

## 2. The adapters

- [x] 2.1 `IosUrlSessionUploadPlatform.freeSlots()` returns `cap - inFlight.size` clamped at zero, read under the existing lock, wrapped in `log.invocation` like its siblings
- [x] 2.2 `IosPhotoKitUploadPlatform.freeSlots()` returns `null`, with a KDoc citing the OS's durable job queue as unreadable (design D3)
- [x] 2.3 The simulator-substituted queue (`adapter/ios/ext-safe/src/iosSimulatorArm64Main/.../UploadJobQueue.kt`) answers consistently with the host it substitutes for

## 3. The cycle

- [x] 3.1 `UploadCycle.enqueue` bounds its read: `ledger.rowsNeedingJob(platform.freeSlots() ?: enqueueBatchSize)`
- [x] 3.2 Distinguish "no free slots" from "the ledger is empty": a zero bound returns `Enqueued(created = 0, truncated = true)`, so the cycle publishes `PROCESSING` (design D2). An empty ledger keeps returning `truncated = false`
- [x] 3.3 Keep `enqueueBatchSize` and update its KDoc to state its remaining role — the bound for a platform that reports no number — rather than a compromise between two tiers

## 4. Test doubles and the world

- [x] 4.1 The fake `BackgroundTransfer` in `test/world/.../UploadFakes.kt` answers the new member; its default keeps existing tests behaving as they do today
- [x] 4.2 Give the world a lever to set free capacity, so an integration test can drive the zero-slot path; keep the lever in `:test:world`, never in `:adapter:generic:fake` (`FakeHonestyTest`)

## 5. Tests

- [x] 5.1 `UploadCycleTest`: a cycle whose platform reports capacity below the batch resolves no more rows than that capacity
- [x] 5.2 `UploadCycleTest`: a cycle whose platform reports **zero** capacity, with rows needing a job, returns `PROCESSING` and resolves nothing — the D2 stall, which nothing in the compiler catches
- [x] 5.3 `UploadCycleTest`: an **empty ledger** still returns a drained cycle, so 5.2 cannot be satisfied by reporting truncation unconditionally
- [x] 5.4 `UploadCycleTest`: a platform reporting `null` falls back to `enqueueBatchSize`, unchanged from today
- [x] 5.5 Integration test over `:test:world`: a backlog larger than capacity drains across cycles with no row resolved for a job that was refused
- [x] 5.6 ~~`iosSimulatorArm64Test`: the adapter reports zero rather than negative when live tasks exceed the cap~~ **Dropped, not deferred.** `testing-architecture` ("Device-only behaviour is measured, not mocked") names background `URLSession` **reattachment** among the behaviours that SHALL NOT be asserted by unit tests, and a session holding more live tasks than the cap *is* reattachment — a test there would assert a copy of the platform against its own fixture. The clamp is a single expression over a count the adapter already admits against; its correctness is established on device by 6.5

## 6. Verification

- [x] 6.1 `./gradlew build` green — including the zone gates, `detektAppShell`, and the complexity tiers
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green (the Linux-runnable iOS proxy)
- [x] 6.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green
- [x] 6.4 `./gradlew architectureDiagrams` produces no diff (a port member should not move the graph — if it does, understand why before committing)
- [x] 6.5 On device via `rig-channel` (iPhone12,8 / iOS 26.6, 2026-09-09, dev build with `-Psnapsync.rig=true`): a 60-asset backlog against `cap = 4`. `remainingCapacity` reported 4 / 2 / 1 / 0 slots and `resourcesFor` was called with exactly that many keys — **1, 2 or 4, never more** across 19 calls. Two cycles found zero capacity and made **no platform read at all**, publishing `PROCESSING` in 19 ms. The same log file holds the before/after: the pre-change build's calls on the same device that afternoon were 5, 6, 8, 10, 11, 12, 13, 15, 16, 16, 16
