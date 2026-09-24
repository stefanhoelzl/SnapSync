## 1. Ports and the shell's expiry forwarding

- [ ] 1.1 Add the background-time outbound port in `domain/ports` (named for the need: begin(label, onExpiry) → a handle ended exactly once; no duration, no remaining-time read), its honest fake in `:adapter:generic:fake`, and its contract in `:test:contracts` (every clause bound to a real implementation somewhere)
- [ ] 1.2 Implement it in `:adapter:ios:app-only` over `UIApplication.beginBackgroundTask`/`endBackgroundTask` (expiry handler only requests the stop and returns; invalid task id treated as immediately expired); keep it out of every extension-linked module (extension-safety gate)
- [ ] 1.3 Extend `PlatformEntries` so a BGTask's expiration reaches the core by the delivered identifier (platform-free shape, named for the need); update its KDoc and `PlatformEntriesContract` with expiry clauses
- [ ] 1.4 Swift shell: forward `task.expirationHandler` into the core instead of calling `setTaskCompleted` in it; update `SwiftShellGuardTest`/`KotlinShellGuardTest` pins if the inventory changes

## 2. The tail runner

- [ ] 2.1 Add the single-flight tail runner in the core (tested zone): units ① import staged downloads → ② upload top-up → ③ walk → manifest (full grant only), looping ③→② when ③ added rows; under a limited grant ① and ② only
- [ ] 2.2 Joining: a request arriving while the tail runs makes the running tail do exactly one more pass covering it (no request lost, no second runner, no queue); a joiner awaits the tail and applies its own re-arm policy to the result; the runner never waits on itself
- [ ] 2.3 Cooperative stop: a stop request lets the current unit (PhotoKit change block, ledger write, store transaction) complete and starts no new unit; a walk in flight is abandoned and writes nothing (never authoritative); a stopped tail reports `PROCESSING` for re-arm
- [ ] 2.4 Carry over every `BackgroundUploadPump` rule (see ios-url-session-upload delta): single-flight ledger writer, atomic decide-and-exit, failure fails all waiters and consumes the pending pass, `PROCESSING` never busy-loops, `SKIPPED` never re-arms, exhaustive re-arm decision outside the lock, per-trigger re-arm table, completions drive ② only on `Admit`
- [ ] 2.5 Unit tests over the fakes for 2.1–2.4 (commonTest, JVM + iosSimulatorArm64), including join-during-③, stop mid-walk, stop mid-import, and each re-arm row
- [ ] 2.6 Delete `BackgroundUploadPump` and its tests once every caller uses the runner

## 3. OS entry points: own work, then the tail

- [ ] 3.1 Rewrite `AppEntries` so each entry runs only its own work after the prelude (push: union read + download enqueue; download-session relaunch: staging; upload-session relaunch: record terminals; heartbeat: none; limited selection change: snapshot-fed discovery → manifest; foreground: reconcile, stored-upload settle, staged-byte reclaim, status and membership refresh), then requests the tail
- [ ] 3.2 Begin the background task no later than the OS handler is handed over (push, URLSession); release the handler right after own work (URLSession at `urlSessionDidFinishEvents`, on main); a BGTask holds `setTaskCompleted` until its tail ends or its forwarded expiry fires
- [ ] 3.3 Delete `OsReceipt` and `ReceiptDeadlines`; keep the "released on every path, exactly once, on the owner's thread" guarantees in the new holder; keep the 5 s per-request HTTP timeout
- [ ] 3.4 Keep flows free of the tail (law "A trigger flow never outlives its own run"): the inbound port's implementation hands work to the tail after the flow returns
- [ ] 3.5 Log the OS expiry line (which signal, entry point, unit running and whether it completed or was abandoned, what was left) — diagnostic-logging
- [ ] 3.6 Update `PlatformEntriesContract` clauses for the new release points and expiry behaviour; run it on JVM and the simulator bindings

## 4. Downloads

- [ ] 4.1 `DownloadController.reconcile` stops draining imports itself; staging and reconcile request the tail; ① drains importable assets under the cooperative stop (claim semantics unchanged: a stalled import keeps its claim)
- [ ] 4.2 A failed union fetch still gets ① (the tail runs regardless of own work's outcome)
- [ ] 4.3 Remove the download backstop: `flow/DownloadBackstop`, `scheduleBackstop` / its `BackgroundScheduler` use, the Swift BGTask registration, the identifier in `BGTaskSchedulerPermittedIdentifiers` (same build), `Background` flow's arming, `BackgroundSchedulerContract`/`PlatformEntriesContract` backstop clauses, and its tests
- [ ] 4.4 Device check (gate for 4.5): what PhotoKit does with a change request against a deleted `PHAssetCollection` (commit fails? resources consumed?) — record the result in the photo-download spec
- [ ] 4.5 If 4.4 allows: cache the event-album collection in the importer, invalidated only by the library change observer (never by a failed commit); otherwise keep the per-import fetch and note why

## 5. Uploads

- [ ] 5.1 Upload completions request ② only, and only when the app's admission is `Admit` (always recorded)
- [ ] 5.2 Upload-session relaunch's own work is recording terminals; heartbeat BGTask is the tail; re-arm per the carried-over table
- [x] 5.3 Remove the shared cycle's `deviceManifestTimeoutMs` (both tiers) and correct the stale "~3-minute cap" comment to the measured 60 s extension kill
- [ ] 5.4 Upload extension: no cooperative stop; confirm `ExtensionCore`/`UploadExtensionRoot` hold nothing across `process()` calls (32 MB limit)

## 6. Silent push and push registration

- [ ] 6.1 `flow/SilentPush`: own work is the download arm (union read + enqueue); the upload arm is no longer a receiver — the wake joins the tail only when the pushed `eventId` is the active event (no event, left event, unreadable membership or no `eventId` → no tail)
- [ ] 6.2 Install the push-registration subscription on every cold start (foreground and background), idempotently
- [ ] 6.3 Change-driven registration: persist the last-registered (token, env, deviceId) after a successful PUT; ask the OS for the token at every app entry (cold start in either state + each foreground entry — shell change, guard pins); PUT only on a difference, on join, and on a fresh credential (incl. renewals); keep the 401 re-send
- [ ] 6.4 Update the stale KDocs in `PushRegistration` and `DeviceAttestation` ("exactly once per OS-delivered token", "repeated launches are harmless")

## 7. Walk memo (app process only)

- [x] 7.1 Add the `currentChangeToken` read behind a port (read before the walk) with a contract clause bound to a real implementation
- [x] 7.2 Memo in the app's discovery binding keyed on (token, selection policy, grant); store only a completed, full-grant, readable walk; answer identical to a fresh walk; never in the extension
- [x] 7.3 Tests: unchanged token reuses; changed token/predicate/grant walks; a limited or unreadable result is never memoised
- [ ] 7.4 Device check: an external change (a Camera photo, and if possible an iCloud edit) moves the token; until recorded, the memo runs in SHADOW (walks every time, logs a would-be-stale answer at Error); flip `APP_WALK_MEMO_USE` to SERVE in the same change that records the result

## 8. Status and limited grant

- [ ] 8.1 Ledger counts refresh after tail units only while foregrounded; foreground entry re-reads
- [x] 8.2 Close the grant-flip gap: the selection lane's change path gets the baseline path's generation check; invert `SelectionSnapshotLaneTest.a_fold_stops_at_an_ended_observation_and_still_ends_it`

## 9. Guards, diagrams, docs

- [ ] 9.1 Update architecture guards per the architecture-guards delta: runtime-identity pin drops the backstop id and asserts `BGTaskSchedulerPermittedIdentifiers` equals the pinned set; the handler-holding guard follows the type replacing `OsReceipt`/`BackgroundEventsReceipts`
- [ ] 9.2 `./gradlew architectureDiagrams` (flows change: DownloadBackstop gone, SilentPush/Foreground/Background changed) and commit
- [ ] 9.3 CLAUDE.md: module list mentions (`DownloadBackstop`, pump, receipts) updated
- [ ] 9.4 Detekt tiers: fit new code to the ceilings; no ceiling raised without a stated forcing proof

## 10. Verification

- [ ] 10.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green; iOS simulator tests and contracts green on CI
- [ ] 10.2 Verify in the simulator app whether `beginBackgroundTask`'s expiry fires in the relaunch setting (open question in design)
- [ ] 10.3 SE2 benchmark after stage 2 with the uncommitted harness (re-applied), on a build that includes the download-enqueue dedupe fix; per-wake duplicate check before using any S2 number; record results beside baseline and stage 1
- [ ] 10.4 After release: watch Bugsink dumps for the OS expiry line and imports left staged (the field evidence this change rests on)

## 11. Sync and archive

- [ ] 11.1 Fix the stale Purpose texts a delta cannot edit, during sync: `ios-url-session-upload` ("pumped by `BGProcessingTask`", "The pump necessarily reimplements…" → the tail runner); `sync-status` ("off the pump's critical path"); `coverage-bounds` (names the deleted `DownloadBackstop` flow — add a note); `push-registration` ("the `BGProcessingTask` backstop" → heartbeat)
- [ ] 11.2 Run the three archive gates in `openspec/config.yaml` (placeholder Purpose, delta completeness per touched module, dead types — `OsReceipt`, `ReceiptDeadlines`, `BackgroundUploadPump`, `DownloadBackstop`)
