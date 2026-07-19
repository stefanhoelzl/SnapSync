# Design — rename-extension-module

## Context

Migration step 13a of `establish-target-architecture`. The target module graph
(`module-architecture`) seats the extension composition root at `:app:ios:extension`; the live
module still carried its historical technology-name `:app:ios:photokit-extension`. The beacon's
`targetModules`, the `architecture-guards` spec's extension-safety scope, and PLAN 13a were all
written against the target name, so the rename is the last move for this pair — no consumer
declares a `project()` dependency on the module (it is a leaf composition root), which is what
keeps the diff to path/name strings.

## Goals / Non-Goals

- Goal: the module set names match the target for this module; beacon module distance burns down
  by the photokit-extension/extension pair.
- Non-goal: any Kotlin change. Packages stay `app.snapsync.ios.upload` (packages organize,
  modules withhold — the seat renames, the contents do not move).
- Non-goal: `:capability:push` removal (the remaining module-distance item; a later step).
- Non-goal: renaming the capability/spec `ios-photokit-upload` — it names the OS-driven PhotoKit
  tier, which is still exactly what it is.

## Decisions

- **D1 — the framework name does not ride along.** `SnapSyncUploadKit` is runtime identity
  (RuntimeIdentityTest pins it; the installed base's Xcode project links it). The rename stops at
  the Gradle module seat.
- **D2 — `ExtensionSafetyTest.extensionLinkedRoots` is updated in the same diff, not left to the
  non-vacuity twin.** The twin only fails when the WHOLE scan is empty; `adapter/ios/ext-safe`
  keeps it non-empty, so a stale extension root would silently drop the extension module from the
  gate's coverage. Same-diff update is the only safe sequencing.
- **D3 — pbxproj edited textually on Linux.** Three lines (one run-script, two search paths);
  the device-affecting proof is `ios-build`'s archive, per PLAN 13a "gated by ios-build".
