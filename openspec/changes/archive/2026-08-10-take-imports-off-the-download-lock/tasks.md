## 1. Store: guarded writes and the loud no-op

- [x] 1.1 Guard `clearCreatedLocalId` in `DownloadStore.sq` on `state != 'IMPORTED' AND createdLocalId = :expected`, mirroring `confirmCreatedLocalId`; take the expected marker and report whether it applied
- [x] 1.2 Make `recordCreatedLocalId` report whether its update matched a row (it stays unguarded — D9)
- [x] 1.3 Make `confirmCreatedLocalId` report whether it applied, so a verdict's caller can gate the byte release on it
- [x] 1.4 Update the `DownloadStore` port for 1.1–1.3, restating in each KDoc why the guard lives in the write and not in a caller's `if`
- [x] 1.5 Mirror all three in `InMemoryDownloadStore`, exactly (`FakeHonestyTest` is armed here)
- [x] 1.6 Delete `isUnconfirmedWith` from the port, the `.sq`, the fake and `DownloadStoreContract`
- [x] 1.7 Extend `DownloadStoreContract` with: a stale clear changes nothing on a settled row; a stale clear changes nothing on a row carrying a different marker; a stale confirm changes nothing; a marker write onto a deleted row reports it — all deterministic, no concurrency

## 2. Store: the prune protects and returns

- [x] 2.1 Reshape `pruneNonTerminal` to `pruneNonTerminal(protecting: Set<AssetRef>): List<String>` in ONE transaction, sparing protected rows and returning the staged paths it stranded
- [x] 2.2 Delete `stagedPathsOfPrunableAssets` from the port, the `.sq`, the fake and the contract (absorbed by 2.1)
- [x] 2.3 Mirror in `InMemoryDownloadStore`; no `protecting` default, per D11
- [x] 2.4 Extend `DownloadStoreContract`: a protected markerless row survives a prune and its paths are not returned; an unprotected markerless row is dropped and its paths ARE returned; a marker-carrying row survives regardless of `protecting`

## 3. Controller: the lock, the claim, the drain

- [x] 3.1 Add the private claim set to `DownloadController`, mutated only under the mutex, with the KDoc naming its three readers and where the excluded fourth question is answered
- [x] 3.2 Move `importer.import` outside the mutex; keep selection, the claim, `stagedResources` and every store write inside it
- [x] 3.3 Replace `importReadyLocked` with a serial drain: claim one ref under the lock, import outside, repeat, with a per-drain attempted-set
- [x] 3.4 Release the claim after the post-import store writes in the same acquisition; retain it on `CancellationException`
- [x] 3.5 Rewrite adjudication's two branches as single guarded writes — `confirm` for *present* (releasing the claim, gating the byte release on the write applying), guarded `clear` for *absent* — and delete both `isUnconfirmedWith` re-checks
- [x] 3.6 Read the *absent* gate (claimed?) UNDER the lock, and pin that placement in a comment naming the device run that found it
- [x] 3.7 Wrap the per-asset import in `Logger.invocation` so a stuck import is visible with no exit line
- [x] 3.8 Pass the claim set to `pruneNonTerminal` from `onLeaveOrSwitch`, and add the reset's lock-holding entry point

## 4. Deleting the deadline and its guard

- [x] 4.1 Delete `IMPORT_DEADLINE` and the `withTimeoutOrNull` wrapper from `IosPhotoLibraryImporter`
- [x] 4.2 Delete `ImportResult.TimedOut` from `DownloadSeams` and every branch on it
- [x] 4.3 Delete `UnreportedImports`, `UnreportedImportsTest` and `UnreportedImportIntegrationTest`
- [x] 4.4 Delete the importer's `forgetUnreported` constructor lambda and its call sites in the completion callback
- [x] 4.5 Unwire `UnreportedImports` from `SnapSyncApp`, `World` and `SnapSyncRoot`
- [x] 4.6 Log at `Error` from the change block when the marker write matched no row (1.2's report), so the prune's protection fails loudly

## 5. Reset path

- [x] 5.1 Replace `ResetDeviceState`'s `stagedPathsOfPrunableAssets` + `pruneNonTerminal` steps with an injected `resetDownloads: suspend () -> Unit`
- [x] 5.2 Add the controller entry point it binds to, holding the mutex across the release-and-prune
- [x] 5.3 Bind it in `compose/SnapSyncApp`, keeping the membership feature blind to the download feature
- [x] 5.4 Update `ResetDeviceStateTest` for the new collaborator

## 6. Fixtures

- [x] 6.1 Replace `abandonNextImport` / `abandonNextImportBeforeCommit` with a lever that suspends after writing its marker and resumes with a test-chosen outcome
- [x] 6.2 Make resuming drive the real completion path — land + settle on success, clear the marker on failure
- [x] 6.3 Add the attempt cap that raises, so a live-locked drain fails by assertion rather than hanging
- [x] 6.4 Remove the `recordCreatedLocalId` default from `FakePhotoLibraryImporter` and fix every construction site
- [x] 6.5 Confirm distinct created identifiers per attempt still hold on every path (already true; assert it in `ImporterFixtureTest`)

## 7. Tests

- [x] 7.1 Flagship: with an import suspended, a concurrent reconcile, staged-resource callback, leave and switch each complete — written to FAIL on a bounded wait, never to hang
- [x] 7.2 Two concurrent triggers over one importable asset create exactly ONE asset (asserted on gallery asset count, not on a marker)
- [x] 7.3 One suspended import strands no other ref: refs B and C import while A is held
- [x] 7.4 A permanently failing asset is attempted once per drain
- [x] 7.5 An *absent* verdict about a claimed ref is not acted on; the same ref is adjudicated once the claim is released
- [x] 7.6 A *present* verdict releases the claim and settles the row, recovering a never-delivered completion
- [x] 7.7 A leave during a claimed import spares the row, and the import settles afterwards (D13)
- [x] 7.8 Integration: a live transaction held open across a full trigger cycle leaves exactly one asset in the world gallery and one suppression handle

## 8. Revert-proofing (isolated worktrees; each kill must NAME a failing test)

- [x] 8.1 Move the platform call back under the lock → 7.1 red
- [x] 8.2 Delete the claim → 7.2 red
- [x] 8.3 Claim the whole batch up front → 7.3 red
- [~] 8.4 Read the *absent* gate outside the lock → **SURVIVES; not revert-proofable, and that is a result** (D14): the claim's membership begins at the claim, before the marker exists, so a stale read is conservative. Retained for data-race safety only.
- [x] 8.5 Drop the attempted-set → 7.4 red via the attempt cap (an assertion, not a hang)
- [x] 8.6 Drop `clearCreatedLocalId`'s guard → 1.7 red
- [x] 8.7 Drop `protecting` → 2.4 red
- [x] 8.8 Record the kill for each in the change's notes; a mutation that fails to compile or hangs is NOT a proof

## 9. Specs, diagrams, review

- [x] 9.1 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`
- [x] 9.2 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green
- [x] 9.3 `./gradlew architectureDiagrams` and commit (stale blocks the PR)
- [x] 9.4 Reviewer A: the diff, the specs, and the parked branch's history — WITHOUT this design's rationale
- [x] 9.5 Reviewer B: full context including `design.md`, checking fidelity to it
- [x] 9.6 Resolve both reviews before the device run

## 10. Device acceptance

- [x] 10.1 Build and install via `ssh-mac-build`; load `ios-device` for the lease before anything touches the phone
- [x] 10.2 Throwaway probe (never committed) making one import's completion never arrive
- [x] 10.3 With the probe armed, drive a concurrent reconcile and a leave via `SNAPSYNC_*` triggers; confirm from `debug.log` that neither waits on the stalled import
- [x] 10.4 Confirm the stuck import shows an entry line with no exit line, and that no marker is cleared for it
- [x] 10.5 Remove the probe; run an unprobed large-import burst to exercise concurrent imports of distinct refs against real PhotoKit
- [x] 10.6 Confirm one asset per foreign photo in the library, every row terminal, and no re-upload of a downloaded photo

## 11. Device acceptance — observed (SE2, iOS 26.5.2, local-backend rig)

- [x] 11.1 Six foreign assets downloaded; the probe swallowed exactly ONE completion
- [x] 11.2 All six imports ENTERED (21:53:39.128–.172) before any EXITED (.251–.419) — concurrent against real PhotoKit
- [x] 11.3 Five imports completed while one stayed parked; five `onResourceStaged` callbacks returned in 121–260 ms
- [x] 11.4 A `reconcile` 80 s after the stall completed in 325 ms — nothing queued behind the stalled import
- [x] 11.5 `adjudicated …: absent, but its import is in flight — left unconfirmed` — the SNAPSYNC-9 gate fired on device, in the exact field shape
- [x] 11.6 No `marker cleared` line; the stalled ref's marker survived
- [x] 11.7 Exactly one `→ import` with no `← import` — a stuck import is identifiable from the log
- [x] 11.8 Unprobed relaunch: `12 union asset(s), 0 foreign planned` — no re-import, no duplicate
- [x] 11.9 Echo check: 0 objects uploaded for any of the five created local ids
- [x] 11.10 Reset on the way back to production: `6 imported row(s) kept` — the injected resetDownloads effect and the prune's protecting set, on device
- [x] 11.11 MEASURED (SIGKILL +200 ms after the change block, 48 MB asset): **the commit SURVIVES process death** — the prior change's D2 premise is false. The guard held anyway: the relaunch adjudicated *present* and settled the row, 0 duplicates. `design.md` restates the safety argument on the *present* branch rather than on the false premise.
- [x] 11.12 REPRODUCED off-device and PINNED: a relaunch adjudicating while a surviving commit is still in flight clears the marker, re-imports, and leaves the first copy unsuppressed — SNAPSYNC-9's harm. Accepted (no sound fix at this layer: waiting is a clock D1 forbids; never acting loses the photo). Test: `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`.
- [~] 11.13 ATTEMPTED, BLOCKED BY THE RIG — not by the design. A 199 MB asset (matching the measured 197 MB → 5.2 s import) was built, planted and planned by the device (`1 union asset(s), 1 foreign planned`), but never downloaded: the dev rig serves objects **whole** (no Range support — measured 199,447,662 bytes in 0.37 s on 127.0.0.1) and a cloudflared quick tunnel cannot carry that to the phone (>60 s, no completion). A smaller asset defeats the purpose: the window needs a commit outlasting the ~5 s relaunch-to-adjudication floor, which needs ~200 MB. Exposure therefore remains un-quantified; the BEHAVIOUR is pinned off-device by 11.12.
