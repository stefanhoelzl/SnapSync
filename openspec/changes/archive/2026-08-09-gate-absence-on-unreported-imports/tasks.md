## 1. The unreported-imports record

- [x] 1.1 Add `UnreportedImports` to `:domain` `feature/download`: `record(ref)`, `forget(ref)`,
      `holds(ref)`, backed by an in-memory `MutableStateFlow<Set<AssetRef>>` (mutated from the completion
      callback's thread as well as the composition lane, so CAS rather than a plain set).
- [x] 1.2 State in its KDoc that it has exactly ONE reader — adjudication — and why: selection is already
      protected by the row's marker, and a second reader is what made the superseded design wrong.
- [x] 1.3 Unit-test it in `commonTest`: record/forget/holds, forgetting an unrecorded ref is harmless,
      and an empty record answers `false` rather than throwing.

## 2. Store: the confirming write and the marker-scoped read

- [x] 2.1 Add `confirmCreatedLocalId(ref, createdLocalId)` to the `DownloadStore` port — non-suspending,
      like its two siblings — guarded so it settles the row only when the row still carries that marker.
- [x] 2.2 Add `isUnconfirmedWith(ref, createdLocalId): Boolean` to the port: true only when the row is
      non-terminal AND carries exactly that marker.
- [x] 2.3 Implement both in `SqlDelightDownloadStore` (+ `.sq` queries carrying the marker guard in the
      `WHERE` clause, not in Kotlin) and in `InMemoryDownloadStore`.
- [x] 2.4 Extend `DownloadStoreContract` in `:test:world` with the scenarios from the `download-store`
      delta, including a late completion whose marker moved on, so both implementations are held to them.

## 3. Adjudication

- [x] 3.1 In `DownloadController.adjudicateUnconfirmed`, gate the `ABSENT` branch on
      `!unreported.holds(row.ref)`; when it is held, log that the outcome is unreported and leave the row.
- [x] 3.2 In the `PRESENT` branch, re-check `store.isUnconfirmedWith(row.ref, row.createdLocalId)` under
      the lock before writing, and discard the verdict if it went stale.
- [x] 3.3 Leave the `UNKNOWN` branch untouched, and add the tests it has never had — including that a
      verdict map with no entry for a row is read as UNKNOWN, not as absence.
- [x] 3.4 In the `TimedOut` branch of the drain, `unreported.record(ref)` before stopping the wake.

## 4. The importer adapter

- [x] 4.1 Raise `IMPORT_DEADLINE` from 5 s to 30 s, and rewrite its KDoc against the measured numbers
      (1.0 s at 49 MB, 5.2 s at 197 MB, field device ~2× slower) rather than the current 250–600 ms ones.
- [x] 4.2 State in that KDoc that the bound is on the LOCK, and that 30 s is therefore how long a stalled
      library can block reconcile, leave and switch.
- [x] 4.3 On a successful completion, call the injected `confirmCreatedLocalId` before resuming the
      continuation, so a completion whose requester is gone still settles the row.
- [x] 4.4 On both completion paths (success and reported failure), call an injected `forgetUnreported(ref)`
      so the ref stops being distrusted once the library has spoken.
- [x] 4.5 Wire all three lambdas in `SnapSyncRoot`, alongside the existing `recordCreatedLocalId`.

## 5. Tests that can actually observe the defect

- [x] 5.1 Make `FakePhotoLibraryImporter` mint a **distinct** created identifier for every asset it
      creates, on every path — including after a failed attempt. The superseded branch's flagship test
      passed while the duplicate was being created, because a failure short-circuited before the attempt
      counter and the re-import reused the first identifier.
- [x] 5.2 Assert on the **number of assets created** for a ref, not only on the marker's value: creating
      the second asset IS the harm, and a marker assertion alone cannot see it.
- [x] 5.3 Add the controller tests for the new behaviour: an absent verdict about an unreported ref keeps
      the marker; the same ref is adjudicated normally once forgotten; a stale present verdict is
      discarded; an abandoned wait records the ref.
- [x] 5.4 Add an integration test over the real core: an abandoned wait, then an absent verdict, then the
      completion arriving — and assert exactly one asset exists and no upload job was created for it.
- [x] 5.5 Confirm 5.4 FAILS against the pre-change core, so it is a regression test rather than a
      restatement, and record how that was confirmed. **Confirmed by reverting the gate itself** — the
      `ABSENT` branch restored to its unconditional `mutex.withLock { clearCreatedLocalId(...) }` — and
      running `:test:integration:jvmTest :adapter:generic:fake:jvmTest`. All three
      `UnreportedImportIntegrationTest` cases FAILED, including
      `the_downloaded_photo_is_never_uploaded_back_into_the_event`, which is the one that observes the
      reported harm (an upload job for the downloaded photo) rather than the bookkeeping. Restored, and
      both modules green again.

## 6. Verification

- [x] 6.1 `./gradlew build` green, and `./gradlew compileIosMainKotlinMetadata` green for the iOS sources.
- [x] 6.2 Mutation-check each behavioural change by reverting it in an isolated worktree and requiring the
      suite to go red; a mutation that fails to compile, or that hangs, is not a proof and must be
      re-authored. **19 mutations, 18 killed, each by a NAMED failing test** (the 19th cannot compile by construction and is superseded by `M18b`, which reproduces the same defect faithfully). Two findings from the run
      itself: (a) the harness first scored a `.sq` mutation as a kill when it had actually broken
      SQLDelight *codegen* — the classifier now requires a named failing test rather than merely a red
      build, and the mutation was re-authored to compile; (b) `M14` survived, exposing that nothing
      pinned the ref being forgotten once a later attempt reports. That is not cosmetic — a leftover
      entry gates the adjudication of the ref's NEXT import — so the controller now forgets on a reported
      result and `a_ref_stops_being_distrusted_once_a_later_attempt_reports` pins it.
- [x] 6.3 Independent review of the diff by a reviewer that did not write it, scoped to behaviour and to
      comments that are false about the code. **Six findings, all fixed.** The HIGH one was a re-run of
      the very defect this change fixes: the `ABSENT` branch had no under-lock re-check, so a completion
      landing between the `holds` gate and the write left `holds` false and an unguarded clear stripped
      the marker off an already-IMPORTED row — terminal, therefore never adjudicated again, therefore
      permanently unsuppressed. The parked branch HAD that re-check on `ABSENT`; narrowing the change
      moved it to `PRESENT` and dropped it from the branch that does the damage. Both branches now
      re-check, the spec requires it of *every* verdict, and `M16` pins it. Also fixed: the two-instance
      rationale was stated backwards (the real failure is over-caution, not re-upload); the 30 s cost
      omitted `onResourceStaged` and the fact that 30 s now exceeds the 20 s receipt budgets; the adapter
      wrote `forget` before the store write, which is the unsafe order — it now settles the row first, so
      the under-lock re-check is a second line rather than the only one; a pre-existing comment claiming
      inter-phase staleness is "harmless" was falsified by this change; and the world importer's
      collision-free identifier scheme rested on comments alone, now pinned by `ImporterFixtureTest`.
- [x] 6.4 On device: a throwaway, never-committed probe that swallows the first N photo-library
      completions, producing a genuine abandoned wait. Confirm from `debug.log` that no `marker cleared`
      line appears while the ref is unreported, and that no upload key matches a downloaded asset's
      created identifier. **First run FAILED, and found a real defect no test or reviewer had caught.**
      SE2 / iOS 26.6, against the local rig with a synthetic foreign device (four 3.15 MP photos dated
      +1 h so the imported copies are genuinely upload-eligible). Measured sequence: the change block for
      `PROBEB` wrote marker `D9159A43…` at 18:16:25.266; its completion was swallowed; three other
      resources staged at 18:16:25.384-.388 and each adjudicated `PROBEB`, read the unreported gate as
      **false** — the 30 s deadline had not fired, so nothing was recorded — and then queued on the
      controller's mutex, which the hung import held. At 18:16:55.274 the import timed out, recorded the
      ref and released the lock; at **18:16:55.284 the first queued adjudication woke with a 30 s-stale
      gate answer and cleared the marker**, and at 18:16:55.296 the photo was re-imported as
      `CFA01CCB…` while `D9159A43…` remained in the library unsuppressed. Two assets for one photo:
      SNAPSYNC-9, through the guard built to prevent it. Root cause: `unreported.holds()` was read
      OUTSIDE the lock. The `isUnconfirmedWith` re-check could not catch it, because at that instant the
      row genuinely is still unconfirmed with that marker. Fixed by reading the gate under the lock;
      pinned by `an_absent_verdict_rechecks_the_gate_after_waiting_for_the_lock`, which contends for the
      lock exactly as the device did and fails against the pre-fix shape. **Re-run pending.**
      ⚠️ Two notes on what this run does and does not prove. `SNAPSYNC_LOSE_COMPLETIONS` is faithful — it
      produces a real `TimedOut` on real hardware. `SNAPSYNC_FORCE_ABSENT` is NOT: `performChanges`
      commits regardless of whether anyone is listening, so ~100 ms later the library genuinely answers
      *present*, and a 30 s deadline always expires after that. The field defect needed the process to be
      SUSPENDED between the change block and its completion (measured 116 s and 254 s), which cannot be
      induced on demand — so the knob substitutes the library's answer to model a window the hardware
      passes through too fast to catch. Proven on device: the deadline, the `TimedOut` path, the
      unreported record, the lock contention, the adjudication branch and every store write. NOT proven:
      that PhotoKit answers *absent* mid-transaction; that rests on the SNAPSYNC-9 field data.
      **RE-RUN PASSED, on the identical interleaving.** Fixed build, four fresh foreign assets
      (`PROBEI`-`PROBEL`): `4 union asset(s), 4 foreign planned`, one swallowed completion, one real 30 s
      timeout. `PROBEL`'s change block ran at 18:39:30.492 and its completion was swallowed; the other
      three staged and queued on the lock; at 18:40:00.499 the import timed out and released it; at
      18:40:00.507/.510/.512 **all three queued adjudications woke and left the marker alone** —
      `absent, but its outcome is unreported — left unconfirmed` ×3, where the pre-fix build had cleared
      it on the first one. **Zero `marker cleared` lines**, and `PROBEL` was never re-imported, so no
      second asset. Non-vacuity was checked FIRST: an earlier attempt reported zero of everything because
      a still-running process had already imported the seed assets 29 s before the launch, which made
      "no marker cleared" true and meaningless.
      ⚠️ The echo half of this task is **NOT** verified. Nothing was uploaded by this device, but the only
      gallery enumeration ran at 18:39:29 — before the imports — reporting `N=0`, and the upload tier here
      is the OS-scheduled PhotoKit extension, which never ran in the window. So "no upload key matches a
      downloaded asset's created identifier" holds only because no upload was attempted. Suppression is
      verified structurally (every marker intact) and by `the_downloaded_photo_is_never_uploaded_back_into_the_event`
      over the real core, not by a device upload cycle declining to offer the photo.
- [x] 6.5 Delete the probe and confirm it is absent from the diff before opening the PR. Both adapter
      hooks and `Snapsync9Probe.kt` removed; a repo-wide grep for the type, both env-var names and the
      log line returns nothing outside this change's own notes; `./gradlew build` and
      `compileIosMainKotlinMetadata` green afterwards.
- [x] 6.6 `npx --yes @fission-ai/openspec@1.5.0 validate gate-absence-on-unreported-imports --strict`,
      remembering that it checks structure and never truth.
