# Proposal: uniform-adapter-tree

## Why

Post-migration polish, operator-decided: the adapter tree was asymmetric — `adapter/ios/` a pure
path grouping over two linkage leaves (`ext-safe`, `app-only`) while `:adapter:generic` and
`:adapter:fake` sat flat at the first level. The spec's own definition of "generic" is
platform-free, and the fakes qualify (in-memory **is** their technology, and it is platform-free),
so both belong under one platform-axis prefix. The uniform shape is
`adapter:<platform-axis>:<linkage-leaf>` across all four leaves, with both prefixes
(`adapter/ios/`, `adapter/generic/`) as pure path groupings — no build file.

## What Changes

Pure mechanical rename — `:adapter:generic` → `:adapter:generic:app`, `:adapter:fake` →
`:adapter:generic:fake` — with the verified ride-alongs in one diff (the 13a-class playbook):

- `git mv adapter/generic adapter/generic/app`; `git mv adapter/fake adapter/generic/fake`;
  the two `settings.gradle.kts` includes. No Kotlin body, package, signature, or behavior change;
  packages keep their pre-migration names (D2 of `extract-adapter-modules` still governs).
- Every `project(":adapter:generic")` / `project(":adapter:fake")` consumer declaration
  (`:adapter:ios:ext-safe`, `:app:desktop`, `:app:ios`, `:app:ios:extension`, `:test:world`,
  `:test:integration`) plus doc-comment mentions across `:domain`, `:ui:presentation`,
  `:test:world`, and `ios.yml`.
- `:test:architecture` `ModuleSetTest.targetModules` (the permanent module-set gate) and
  `FakeHonestyTest.fakeRoot` (`adapter/fake` → `adapter/generic/fake`) — coverage moves, it does
  not shrink; the plant-a-lever red-proof re-run against the new scope.
- `./gradlew architectureDiagrams` regenerated (`architecture/`).
- CLAUDE.md module rows; `app/ios/CLAUDE.md` mentions.
- Runtime identity is UNCHANGED (RuntimeIdentityTest green): framework baseNames, db filenames,
  Keychain pairs, App-Group id are all untouched by a Gradle module rename. The SQLDelight
  `packageName`s are explicitly pinned in the build script (`app.snapsync.engine.db`,
  `app.snapsync.downloadstore.db`); only the generated *implementation* sub-package derives from
  the module name (`…db.<module>`), is internal-only, and is referenced by nothing outside the
  generated code (verified post-rename).

## Impact

- Specs: `module-architecture` (the module-set list and the placed-by-linkage vocabulary),
  `architecture-guards` (the fake-honesty gate's module name). Incidental placement-prose
  mentions in `event-creation-ui`, `gallery-status`, `harness-world-model`, `ios-app-shell`,
  `ios-photokit-upload`, `ios-url-session-upload`, `join-event`, `sync-ledger` are updated as
  mechanical reference fixes (contract meaning unchanged); archived changes stay untouched, as
  always.
- No runtime behavior change; gated by the canonical build + `compileIosMainKotlinMetadata` +
  `:test:architecture:test`.
