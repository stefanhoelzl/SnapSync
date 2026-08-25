## Why

`openspec/specs/ios-app-shell/spec.md` — the contract of record — contradicts itself and contradicts
`upload-lifecycle`. Its "iOS live composition root" requirement still says the root SHALL resolve its
composition **once per process** through `resolveComposition`, that resolution's **only** input SHALL be
the OS capability fact, that there SHALL be **no runtime override**, and that the root SHALL switch on
the resolved `UploadTier` in exactly one place. `resolveComposition` and `UploadTier` were deleted by
`2026-08-25-collapse-upload-tier-seam`; resolution now takes three inputs and is re-read per transition;
the override exists and `upload-lifecycle` explicitly blesses it ("A mechanism override is a runtime
input a shipped build cannot carry"). That change shipped an `ios-app-shell` delta, but it was
`## ADDED Requirements` only — it added "no entry point SHALL re-check a tier" ~420 lines below a
requirement saying the root SHALL switch on a tier, and never modified the older one.
`openspec validate --specs --strict` passes on this, because it checks structure and not truth.

The same supersession left ten false statements in production prose, and an *earlier* one — the forge
target's extraction — left more: `SnapSyncRoot` still describes a forge/live "mode switch" whose
`ForgeShell` was deleted and whose `SNAPSYNC_FORGE_STATE` moved to a separate binary. Two of these are
KDoc links to deleted types, which resolve to nothing.

Six of the drift sites survived because of a defect the compiler hides: **two KDoc blocks in a row bind
only the last, and Kotlin drops the first silently**. Eleven sites in the repo have stacked blocks, and
the dropped halves carry real content — at `AttestSeams.kt` the one-line summaries of what `token()` and
`keyId()` return; at `UploadArm.kt` (already corrected) the only statement of why the upload lifecycle
lives in tested `:domain` rather than the untested iOS root. This class is mechanically detectable and
nothing detects it.

## What Changes

- **`ios-app-shell`'s "iOS live composition root" requirement is corrected.** The duplicated
  mechanism-resolution paragraph is removed rather than re-synchronised: the requirement states what the
  **shell** owes — it supplies facts, constructs adapters, calls `snapSyncApp`, and delegates every OS
  entry point to a single live shell delegate re-checking nothing — and cites `upload-lifecycle` for
  resolution, which already owns it normatively. The duplicate is the copy that rotted; removing it is
  what stops it rotting again. The requirement's other content (permission-grant subscriptions installed
  only from host assembly, the Kotlin-side lifecycle observers, `MainViewController`'s rendering
  contract) is unaffected and stays.
- **A new `:test:architecture` guard forbids stacked KDoc**, and the eleven existing sites are fixed by
  **merging** — recovering what the surviving block lost, not deleting the dropped one.
- **Ten forge/composition-mode prose sites are corrected** across `SnapSyncRoot.kt`,
  `MainViewController.kt` and `SceneMode.kt`, including two KDoc links to deleted types.
- **`SnapSyncRoot`'s `Shell` seam is kept and re-documented**, not collapsed. It has one implementation
  and its stated reason ("implemented once per composition mode") is gone, but it is `private`, costs
  nothing externally, and enumerates the OS entry-point surface in one place. Deleting it is a ~100-line
  refactor of wiring that project rule leaves untested; the honest fix is to say what it is now.

Not in scope, deliberately: a dead-symbol guard over spec prose. It would have caught this drift, but it
cannot separate a stale normative claim from a correct historical one — `architecture-guards` names
`UploadTier` and `app/ios/CLAUDE.md` names `CompositionMode.Forge`, both correctly, in the past tense.
It would need an exception list, which is the thing that goes stale.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-app-shell`: the "iOS live composition root" requirement stops restating mechanism resolution and
  cites `upload-lifecycle` instead; its stale once-per-process / single-input / no-runtime-override /
  `UploadTier`-switch claims are removed, resolving a contradiction both with `upload-lifecycle` and
  with this spec's own "OS entry points delegate upload triggers to the resolved mechanism".
- `architecture-guards`: a new requirement for the stacked-KDoc guard — two consecutive KDoc blocks are
  forbidden where a declaration already appears earlier in the file, so the deliberate file-header
  convention is exempt by construction.

## Impact

- **Specs**: `openspec/specs/ios-app-shell/spec.md`, `openspec/specs/architecture-guards/spec.md`.
- **New test**: one guard in `:test:architecture` (JVM, gating under `./gradlew build`), scanning every
  `.kt` in the repo including test sources, with non-vacuity assertions so a broken extractor fails
  loudly rather than passing empty.
- **Comments only, no behavior**: `app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt`,
  `.../MainViewController.kt`, `domain/src/commonMain/kotlin/app/snapsync/model/SceneMode.kt`, plus the
  eleven stacked-KDoc sites in `:domain`, `:ui:screens`, `:test:world`, `:test:integration` and
  `:app:ios`.
- **One dead local is removed** (`SnapSyncRoot`'s `val live = shell as LiveShell`), because correcting
  its comment surfaced that nothing reads it — see design D7. No Kotlin *declaration* is added, removed,
  or renamed, no code path changes, and the guard is the only new executable code.
