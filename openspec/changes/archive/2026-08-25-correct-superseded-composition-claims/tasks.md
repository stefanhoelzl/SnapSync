## 1. The guard (first, so the cleanup has a verifier)

- [x] 1.1 Add `StackedKDocTest` to `:test:architecture`: flag two consecutive KDoc blocks separated only
      by blank lines where a declaration already appears earlier in the file, scanning every `.kt` in the
      repo (test sources included, `build/` and `openspec/changes/archive/` excluded). Fail with the file
      and the line the dropped block opens on.
- [x] 1.2 Add non-vacuity twins in the manner of `LawsDigestTest`: assert the scan visited a plausible
      number of files and that the consecutive-KDoc detector is not matching zero everywhere, so a broken
      extraction fails instead of passing empty.
- [x] 1.3 Run it and confirm it reports exactly the eleven sites in task 2 — no more, no fewer. If the
      count differs, the rule is wrong; fix the rule before touching a single site.

## 2. The eleven stacked-KDoc sites (merge, never delete)

- [x] 2.1 `domain/.../model/Direction.kt:28` — merge the dropped one-line `fromWire` summary into the
      surviving "Absence:" block; fix the block's broken indentation while there.
- [x] 2.2 `domain/.../ports/AttestSeams.kt:80` — merge the dropped "The current token, or null if none was
      ever stored. MAY be expired" summary into `token()`'s surviving block.
- [x] 2.3 `domain/.../ports/AttestSeams.kt:91` — same for `keyId()`'s dropped summary.
- [x] 2.4 `domain/.../compose/SnapSyncApp.kt:140` — merge.
- [x] 2.5 `ui/screens/.../StatusScreen.kt:349` — merge the dropped `createdAt`-default paragraph into the
      surviving `startsAt` block.
- [x] 2.6 `domain/src/commonTest/.../BackgroundUploadPumpTest.kt:65` — merge.
- [x] 2.7 `domain/src/commonTest/.../BackgroundUploadPumpTest.kt:92` — merge.
- [x] 2.8 `domain/src/commonTest/.../UploadCycleTest.kt:1071` — merge.
- [x] 2.9 `test/world/.../BackendStore.kt:244` — merge.
- [x] 2.10 `test/integration/.../JoinGateIntegrationTest.kt:44` — the dropped block is the class-level
      description; re-home it onto `class JoinGateIntegrationTest`, which currently has no doc.
- [x] 2.11 `app/ios/.../SnapSyncRoot.kt:622` — the dropped block documents `onForeground`, which lives
      elsewhere; re-home or merge, and correct its forge/live claim (task 3.4 covers the wording).
- [x] 2.12 Re-run the guard: zero findings.

## 3. Forge and composition-mode prose

- [x] 3.1 `SnapSyncRoot.kt:323` — "The tier thunks resolve through the one switch above" is false twice:
      there is no switch (`shell` is an unconditional `LiveShell()`), and `LiveShell`'s own KDoc says it
      "takes **no tier thunks any more**". State what the cast actually reads and why.
- [x] 3.2 `SnapSyncRoot.kt:609-613` — remove "through the one switch above"; the sentence already
      contradicts itself two clauses later with "There is only the live host now".
- [x] 3.3 `SnapSyncRoot.kt:616` — "live or forge, one switch" → the live query, one implementation.
- [x] 3.4 `SnapSyncRoot.kt:622-628` — drop "the forge/live decision was made once, at resolve time" and
      "the one mode switch" (paired with task 2.11).
- [x] 3.5 `SnapSyncRoot.kt:983` — the section header claims the delegate is "the target of THE one switch
      above"; there is no such switch.
- [x] 3.6 `SnapSyncRoot.kt:985` — "implemented once per composition mode" → per D2, say what `Shell` is
      now: a private seam enumerating the OS entry-point surface, single implementation.
- [x] 3.7 `SnapSyncRoot.kt:989` and `:992` — "`{ null }` in forge" / "a constant in forge" describe an
      implementation that no longer exists.
- [x] 3.8 `MainViewController.kt:22-25` — "or a forged-source host when the dev/test
      `SNAPSYNC_FORGE_STATE` launch-env variable is set" is false: forge is a separate binary that does
      not link this module, and `renderHost` is always live.
- [x] 3.9 `MainViewController.kt:50-51` — cites "`SnapSyncRoot`'s one `when (mode)` on `CompositionMode`"
      as the precedent for its own scene switch; that precedent is deleted. Point at something real or
      drop the comparison.
- [x] 3.10 `domain/.../model/SceneMode.kt:45` — `[CompositionMode]` is a KDoc link resolving to nothing.
      Same defect class as the `[DeregisterThenRun]` link already fixed.
- [x] 3.11 `domain/.../compose/SnapSyncApp.kt:105` — `AppPorts`' class doc: `[uploadProducer]` is a
      dangling link (the members are `appDrivenUpload`/`osDrivenUpload`), `resolveComposition` is deleted,
      and "only the selected tier's mechanism is ever constructed" is false on ≥26.1, where both are.
- [x] 3.12 `test/rig/.../RigHooks.kt:23` — "The resolved `UploadTier`" names a deleted type; `Boot` passes
      the mechanism this OS resolves to under a full grant. Parameter NAME left alone (a rename, not a
      comment fix).
- [x] 3.13 `app/ios/.../SnapSyncRoot.kt:973` — "there is no longer any way to select a tier the OS did
      not" is contradicted by the rig's `uploadMechanismOverride`, which this change's spec delta blesses.
      Found while fixing 3.11-3.12.

## 4. The spec

> 4.1-4.3 merge the deltas into `openspec/specs/`, which is the **sync** phase's job, not apply's.
> They were left unchecked through apply and then performed by `openspec archive`, which syncs main
> specs as it archives (`+ 1 added`, `~ 1 modified`). 4.4 was a read-only check, done during apply.

- [x] 4.1 Apply the `ios-app-shell` delta: replace the once-per-process / single-input /
      no-runtime-override / `UploadTier`-switch paragraph with the shell-owes version citing
      `upload-lifecycle`, and make the `LedgerWriter` sentence mechanism-precise (design D1b).
- [x] 4.2 Apply the `ios-app-shell` scenario changes: correct "The root assembles the real stack", and
      replace "The tier is resolved once, from the OS alone" with "The root supplies resolution's inputs
      and selects no mechanism".
- [x] 4.3 Apply the `architecture-guards` delta: add "A KDoc block is never silently dropped".
- [x] 4.4 Confirm the two specs no longer contradict each other: `ios-app-shell` must not forbid the
      runtime override `upload-lifecycle` blesses, and must not require a tier switch its own "OS entry
      points delegate upload triggers to the resolved mechanism" forbids.

## 5. Verify

- [x] 5.1 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` — structural only; it is not
      evidence the text is true.
- [x] 5.2 `./gradlew build` green, with `:test:architecture:test` executed (not `UP-TO-DATE`) so the new
      guard actually ran.
- [x] 5.3 `./gradlew architectureDiagrams` and confirm no drift — no declaration is renamed, so
      `architecture/` should regenerate unchanged; a diff here means something moved that should not have.
- [x] 5.4 Grep for the retired vocabulary (`resolveComposition`, `CompositionMode`, `UploadTier`,
      `when (tier)`, "once per process", "in forge") outside `openspec/changes/archive/` and confirm every
      remaining hit is deliberate past-tense history.
- [x] 5.5 One commit, `internal` label; leave the PR and `/ship` to the operator.
