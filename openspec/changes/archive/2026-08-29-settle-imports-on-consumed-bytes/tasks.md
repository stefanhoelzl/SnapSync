## 1. The port read

- [x] 1.1 Add the presence read to `ports/StagedBytes` (`:domain`): answers whether a set of staged paths is
      still on disk, reporting the filesystem fact only. Document that any missing member answers
      not-all-present, and that an empty set answers all-present carrying no evidence.
- [x] 1.2 Implement it in `IosStagedBytes` (`:adapter:ios:app-only`) against `NSFileManager`, no dispatcher
      hop (a `stat` is not the thread-blocking library call the sweep already keeps off the lock).
- [x] 1.3 Implement it in the in-memory `StagedBytes` (`:adapter:generic:fake`), keeping the fake honest —
      its surface stays the port contract plus initial state; the operator lever belongs in `:test:world`.
- [x] 1.4 Update `StagedBytes.None` so the read answers consistently with "releases nothing", and state
      which answer it gives and why.

## 2. The adjudication branch

- [x] 2.1 In `DownloadController.adjudicateUnconfirmed()`, read the row's staged resources and ask the port
      whether their paths are still present, before the *absent* branch acts.
- [x] 2.2 Route *absent with bytes gone* to the same handling as *present*: `confirmCreatedLocalId(ref,
      marker)`, then release staged bytes and the claim only past the guard, exactly as that branch does.
- [x] 2.3 Leave *absent with bytes present* on today's path (clear the marker, then import).
- [x] 2.4 Treat an unconfirmed row with no staged resource rows as *unknown* — clear nothing, settle
      nothing.
- [x] 2.5 Give the two settle paths distinguishable log lines, at `Info`, so a dump says which evidence
      settled the row.

## 3. Fixtures that can observe the harm

- [x] 3.1 Name the `:test:world` lever for bytes the photo library has consumed — `World.consumeStagedBytes`.
      The mechanism already existed (`World.stagedFiles` IS the disk); what was missing was a name, so a
      reader can tell "the library took these" from "the release pass ran".
- [x] 3.2 Assert on `suppressedLocalIds()` and on how many assets the world importer created — never on a
      marker's value alone.

## 4. Tests

- [x] 4.1 Invert `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`: the handle
      survives, the row settles against it, and no second asset is created. Rename it so it no longer
      describes an accepted residual.
- [x] 4.2 Cover *absent with bytes present* — the marker is cleared and the asset is imported, unchanged.
- [x] 4.3 Cover the no-staged-resources row — nothing cleared, nothing settled.
- [x] 4.4 Cover a multi-resource asset with one resource consumed — settled, not cleared.
- [x] 4.5 ~~Extend `DownloadStoreContract`~~ — **corrected during apply**: the read is on `StagedBytes`, which
      the SQLDelight store does not implement, so that contract is the wrong home. Covered instead by
      `IosStagedBytesTest` against a real filesystem (all-present, one consumed, all consumed, empty) and
      through the fake in `DownloadControllerTest`.

## 5. Revert-proofing

- [x] 5.1 In an isolated git worktree, remove the byte check and confirm the suite goes **red** naming a
      failing test — not a compile error, not a hang.
- [x] 5.2 Mutate the branch the other way (settle on *absent* regardless of bytes) and confirm the
      bytes-intact test fails.
- [x] 5.3 Record the kills in the PR body. Three mutations, each killed by a named test with an assertion
      failure (no compile error, no hang):
      - byte check removed → `a_surviving_commit_still_in_flight_at_relaunch_settles_rather_than_clearing`
        and `one_consumed_resource_of_several_settles_the_row`: *"no second asset was created expected:<0>
        but was:<1>"* — the harm counted in assets, not read off a marker.
      - settle regardless of bytes → `an_absent_verdict_clears_the_marker_once_the_import_is_no_longer_claimed`.
      - empty-evidence guard removed → `absent_with_no_staged_resources_changes_nothing`.

## 6. Acceptance

- [x] 6.1 `./gradlew build` green, including the architecture guards and the complexity tiers.
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green (the Linux-runnable iOS proxy).
- [x] 6.3 On-device: import a foreign asset large enough to commit for seconds, kill the app mid-commit,
      relaunch, and confirm from `debug.log` that the sweep settled the row on consumed bytes rather than
      clearing it. DONE 2026-08-29 (SE2, iOS 26.6): 18 imports killed mid-burst, relaunch 1.3 s later,
      4 rows took the new branch and all 4 assets existed afterwards with handles intact. It also found
      a window this change does NOT close (submitted-but-not-yet-ingested → 2 orphans); see
      PROBE-FINDINGS.md. The upload-cycle check was blocked by an unrelated pre-existing
      `Database version 8 newer than config version 7` on the device.
- [x] 6.4 Launch-to-adjudication measured: sweep entered 75 ms after process start, first verdict at
      1.58 s — far below the ~5 s floor the prior design assumed, so the exposure is wider than believed.

## 7. Documentation

- [x] 7.1 Confirm no CLAUDE.md change is needed (no runbook, module, or law moves).
- [ ] 7.2 PR carries exactly one changelog label — `bug`.
