## 1. The container: confirm becomes the leave

- [x] 1.1 Rename `readyOrExplain` to name the gate's loaded-phase derivation, and update its KDoc: the
      rule is unchanged (`config` absent **and** `NOT_DETERMINED`), but it now runs at two points and the
      "a switch never explains" justification is replaced by "a switch explains after its leave".
- [x] 1.2 Change `onConfirmSwitch(cutoff, until, direction)` to `onConfirmSwitch()`: take the pending
      join's loaded phase, run `commands.leave()`, then — **only if `config.value == null`** — re-derive
      the phase from the already-loaded name/startsAt/endsAt/deletesAt. No re-fetch, no commit.
- [x] 1.3 Delete `commit`'s `withLeave` parameter and its `if (withLeave) commands.leave()` line; update
      the remaining callers (`onConfirmJoin`, `onRetryJoin`). Confirm `autoConfirm` still calls
      `commands.leave()` itself, so the `autoJoin` path is unchanged.
- [x] 1.4 Update the KDoc on `onCancelSwitch` (dismisses the confirmation, stays in the current event) and
      `onCancelJoin` (discards the pending join; after a switch's leave that means landing in no event).

## 2. The screen: a shrunken dialog

- [x] 2.1 Change `SwitchDialog`'s confirm to take no picks, and rewrite the `Ready` body to name both
      events only — dropping the participation-reset sentence and the `shareCountSentence` call.
- [x] 2.2 Delete `SwitchDialog`'s `CommitFailed` branch and its `remember`-ed `cutoff`/`until` vars; drop
      the now-unused `onRetryJoin`, `shareableCount`, and `photoPermission` parameters and their call-site
      wiring in `StatusScreen`.
- [x] 2.3 Update the `ExplainAccess` branch's comment: it stays unreachable and stays required for
      exhaustiveness, but the reason is now that the explainer is derived only after the config is gone.
- [x] 2.4 Verify the `pendingSwitch` suppression of the settings gear and the rename pen is untouched —
      the dialog still precedes the leave, so both must still be suppressed while it is up.

## 3. The harness

- [x] 3.1 Remove `PanelController.showSwitchCommitFailed()` and its control-panel button, leaving the
      `Ready` / `NotFound` / `LoadFailed` switch presets.

## 4. Tests

- [x] 4.1 `StatusContainerHostTest`: confirming a switch runs the leave, commits no join, and leaves the
      state at the full-screen `JoiningEvent` for the new event once config clears.
- [x] 4.2 `StatusContainerHostTest`: with permission `NOT_DETERMINED`, the pre-leave phase is the confirm
      phase (so the dialog shows) and the post-leave phase is `ExplainAccess`.
- [x] 4.3 `StatusContainerHostTest`: with permission `GRANTED`, the post-leave re-derivation is a no-op —
      the phase stays at the confirm phase.
- [x] 4.4 `StatusContainerHostTest`: when the config clear fails, the state stays `Joined(pendingSwitch)`
      with the phase untouched, so the confirmation can be confirmed again.
- [x] 4.5 `StatusContainerHostTest`: cancelling after the leave discards the pending join and reduces to
      the create layer.
- [x] 4.6 `JoinGateIntegrationTest` (`:test:integration`): joined to A, scan B, confirm the switch, then
      join with Share **off** and the album **on** — assert the world shows A departed, B enrolled, the
      persisted membership carrying `DownloadOnly` and `saveToAlbum = true`. This is the regression the
      change exists for: the old path could only produce `Both` with album off.
- [x] 4.7 Update any `StatusScreenTest` / `JoinScreenTest` assertions on the switch dialog's body copy and
      its removed commit-failure branch.

## 5. Specs and docs

- [x] 5.1 Correct the Purpose prose in `openspec/specs/join-share-count/spec.md`: "the join, ~~switch,~~
      and reconfigure surfaces". Requirement-free prose fix, recorded here because a Purpose-only edit has
      no delta form (see the proposal's Impact section).
- [x] 5.2 Check `UiState.kt`'s KDoc on `JoiningEvent` and `PendingSwitch`: `JoiningEvent` says "Only
      entered when no event is configured (a first join); a join while already configured is a switch,
      carried as `Joined.pendingSwitch` instead" — still true of the *pre-leave* state, but it must say
      the switch lands here after its leave.

## 6. Verification

- [x] 6.1 `./gradlew build` green (compiles all targets, runs JVM tests including the architecture gates).
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` green (the Linux-runnable iOS proxy).
- [x] 6.3 Drive the forge harness headlessly (`:test:harness-driver:driveForge`): select the `Ready` switch
      preset, confirm the dialog reads with both event names and no count, and confirm no `CommitFailed`
      switch preset remains.
- [x] 6.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes (structure only — it
      proves nothing about truth).
