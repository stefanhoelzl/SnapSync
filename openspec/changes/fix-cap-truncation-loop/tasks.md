## 1. The restructure (behaviour-preserving)

The evidence that this group changed nothing is that no existing `UploadCycleTest` test needed
editing. Any test outside group 6's table that has to change is an unlisted behaviour delta — stop and
add it to the inventory rather than editing the test.

- [x] 1.1 Rename `ledgerSettled` → `seedSucceeded` in `UploadCycle.kt`, freeing the word `settle`. Half of this was already done upstream: `fix-lost-upload-acks` renamed `settleTerminalJobs` to `recreateRetrySpent`, so only the `ledgerSettled` occurrences remained
- [x] 1.2 Introduce the sealed stage results — `Settled`, `Decided`, `Updated` — each with a `Short(outcome)` variant that forwards, and a sealed `CycleOutcome` over the exits (unreadable · not joined · deferred · declined · truncated · drained)
- [x] 1.3 Split `run()` into `settle()` · `decide()` · `update()` · `publish()` as member extension functions, with named locals in `run()`; make `publish()` the **only** producer of a `CycleResult`
- [x] 1.4 Move `drainTerminals`/`recreateRetrySpent` into `settle()`, above both the `contributes` and `mayUpload` branches (this is delta D5 — it changes behaviour; see 6.5)
- [x] 1.5 Write the KDoc on `decide()` stating the invariant that licenses the split — single ledger writer, single-flight pump, delegate writes only through the guarded `markTerminal` — and what breaks if either stops holding
- [x] 1.6 Confirm `./gradlew build` is green with every existing `UploadCycleTest` test unedited except those in 6.x

## 2. The DISCOVERED state

- [x] 2.1 Add `LedgerState.DISCOVERED`; extend `isDone`'s exhaustive `when` (not done) and confirm the build breaks until it is classified
- [x] 2.2 Add the `needsJob` classification in `model/` as a second exhaustive `when` with no `else`, and its bound set, mirroring `DONE_STATES`
- [x] 2.3 Add `LedgerWriter.recordDiscovered(key, assetId, eventId, detail)`, upserting only when no row exists in another state
- [x] 2.4 Add the bounded state-scoped read of rows needing a job to `LedgerStore`, binding `needsJob` as a parameter — never a literal in the query
- [x] 2.5 Implement both in `SqlDelight`'s `Ledger.sq` (no schema migration — `state` is `TEXT AS LedgerState`) and in `InMemoryLedgerStore`
- [x] 2.6 Extend `LedgerStoreContract` in `:test:world` so both backends are held to the same behaviour, including that a `DISCOVERED` row is backlog, is not in `requestedKeys()`, and is not in `completedManifestRows()`
- [x] 2.7 Teach `SyncEngine.decide()` the new state (`DISCOVERED` → `Upload`) — its `when` has no `else`, so this is a compile error until done

## 3. Resolving ledger keys to resources

- [x] 3.1 Add the port verb resolving a set of ledger keys to uploadable resources, partial-tolerant (a departed asset resolves to nothing)
- [x] 3.2 Implement it in `:adapter:ios:ext-safe` over `PHAsset.fetchAssetsWithLocalIdentifiers` → `PhotoKitCandidateSource.candidatesFrom` → `.resources()` — id-scoped, never a walk
- [x] 3.3 Wire the app tier's implementation, and make `SelectionScopedTransfer` serve it from the selection snapshot under a partial grant, with no platform read
- [x] 3.4 Implement it in the world's fake transfer, observable so a test can assert a cycle enqueued without consuming the discovery feed
- [x] 3.5 Confirm `./gradlew compileIosMainKotlinMetadata` passes — the iOS-only surface compiles on Linux

## 4. The cursor and the work source

- [x] 4.1 In `update()`, record `DISCOVERED` for every admitted resource the engine judged `Work`, before any `createJob`
- [x] 4.2 Backfill every bare row the walk covered. **No new query was needed:** once job creation left the decision loop (4.4), that loop always runs to completion, so the per-resource idempotent backfill already covers everything the walk saw. The `bareKeys` read the task assumed is not required and was not added
- [x] 4.3 Move `store.saveToken` out of the publish tail into `update()`, after 4.1/4.2 and the removal marks, and **before** the first `createJob`; state in KDoc that ordering — not atomicity — is the safety property
- [x] 4.4 Have the producer enqueue from the needs-job read (both `DISCOVERED` and `FAILED`), resolving via task 3, before falling back to the walk's own output
- [x] 4.5 **Dropped, and why.** This asked `decide()` to skip the walk when the cursor reported no change. The cursor cannot report that: `discoverResources(token)` **is** the question, and `fetchPersistentChangesSinceToken` is what returns the empty change set — there is no cheaper oracle, and the only change *observer* in the codebase (`PhotoSelectionChangeSource`) exists solely for limited mode. What delivers the intent instead is 4.3: once the cursor advances, every later walk is an incremental change-token fetch rather than a full enumeration. The `ios-url-session-upload` delta was re-worded to match — it promised "SHALL NOT perform a library walk", which would have been false as shipped
- [x] 4.6 Confirm the cap-hit path leaves the cursor advanced and the remainder in `DISCOVERED`, with no residue store anywhere

## 5. Publication

- [x] 5.1 Have `DeviceManifestProducer.produce` report whether it wrote, and thread that through `publish()`
- [x] 5.2 In `publish()`, write the manifest on every outcome whose ledger seed succeeded — including truncated
- [x] 5.3 Gate the notify on the projection having changed, replacing the drained-plus-promotion counter; drop `completedThisCycle` as a concept
- [x] 5.4 Emit the `enumeration:` audit line on every cycle that walked, stating truncation and the un-enqueued remainder
- [x] 5.5 Keep the album placement and the `UPLOADED` promotion in `publish()`, and confirm the promotion still precedes the manifest write (a projection that does not yet list the assets would wake recipients to nothing)

## 6. The behaviour-delta inventory — one test each, failing before, passing after

- [x] 6.1 **D1/D2 cursor + work source**: invert `cap_during_discovery_does_not_advance_the_cursor_and_returns_processing`; add a test that a truncated cycle's remainder is enqueued on the next cycle with the discovery feed untouched
- [x] 6.2 **D3 truncated cycle publishes**: invert the manifest half of `cap_truncated_cycle_does_not_notify_even_with_a_completion`
- [x] 6.3 **D4 notify on projection-changed**: invert the notify half of the same test; add a test that a cycle which completed rows but published an unchanged projection fires no notify
- [x] 6.4 **D6 backfill past the truncation point**: add a test that bare rows after the truncation point are backfilled
- [x] 6.5 **D5 deferred reconcile settles**: invert `assertTrue(!platform.drained, …)` in `a_deferred_reconcile_creates_no_jobs_and_reports_a_clean_completed`, keeping its manifest-suppression assertions intact
- [x] 6.6 **D7 drain-level cap no longer skips the walk**: `cap_during_re_create_still_walks_publishes_and_returns_processing`. Note the cursor is **not** held — the walk's facts are durable regardless, and the un-created retry rests `FAILED`, which the ledger's work read returns next cycle. The task's parenthetical was written before the work source moved
- [x] 6.7 Add a `:test:integration` case over `:test:world`: a device with more work than the job limit publishes a manifest the union then lists, without draining

## 7. Specs and gates

- [x] 7.1 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and fix structural failures (it checks shape, not truth)
- [x] 7.2 Re-read each delta against the code as landed — especially `sync-ledger`'s corrected storage-operation list — and fix any statement the implementation contradicts
- [x] 7.3 Ran `./gradlew architectureDiagrams` — no diagram changed, because the change adds no flow and moves no module boundary. Nothing to commit under `architecture/`
- [x] 7.4 Run `./gradlew build` — the zone gates, `FakeHonestyTest`, `LawsDigestTest`, `ModuleSetTest` and `detektAppShell` all gate here
- [x] 7.5 Confirm `:app:*` is untouched: `run()` still returns `CycleResult`, so the pump, `processRawValue()` and the Swift principal class need no edit

## 8. Verification on device

- [x] 8.1 **Done on a real device instead of a simulator**, which is strictly stronger — real PhotoKit, real `URLSession`, real backend. iPhone12,8 / iOS 26.6, tier pinned to `url_session`, 1536-asset library, 20 seeded / 10 admitted. All three confirmed across cycles; trace in `design.md` under "Verified on device"
- [x] 8.2 **Measured** (iPhone12,8 / iOS 26.6, from the device's own `debug.log`, 25 walks over two days): incremental-and-nothing-changed **5–17 ms**; full enumeration **59–80 ms** at 66 candidates and **145 ms** at 1084. Recorded in `design.md` under "The walk's cost, measured" — and it **corrected** this change's own framing: a full enumeration is ~0.13 ms/candidate idle, against ~28 ms/candidate in the SNAPSYNC-16 field log, so that 6.1–7.2 s was situational (older device, 104 concurrent imports contending for `assetsd`), not a property of full enumeration. The correctness claims never rested on it; the latency claim is now stated honestly
- [x] 8.3 **Confirmed on the real device, via a sideloaded rig build rather than a TestFlight dispatch** — the same evidence, available immediately and drivable. `GET /events/<id>/files` returned all 10 admitted assets, published across cycles that returned `PROCESSING`. A `ios.yml` dispatch remains worth doing before release for the DSN-carrying build, but it is not what proves this change
- [x] 8.4 `Bugsink-Resolves: SNAPSYNC-16` trailer on commit `3862438f`, the first (and so far only) fix commit
