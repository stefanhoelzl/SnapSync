# Design — uniform-adapter-tree

## Context

The architecture migration completed at the 13b finale: the beacon is dead and every law gates
permanently. The module set it left behind carried one asymmetry: the ios adapters live as
`adapter:ios:<leaf>` (prefix = platform axis, a pure path grouping with no build file) while the
platform-free adapters sat flat (`:adapter:generic`, `:adapter:fake`). The operator decided to
make the tree uniformly two-level.

## Goals / Non-Goals

- Goal: `adapter:<platform-axis>:<linkage-leaf>` uniformly across all four adapter leaves;
  both prefixes are pure path groupings (`adapter/generic/` gets NO build file — same as
  `adapter/ios/`).
- Non-goal: any Kotlin change. Packages stay (`app.snapsync.fake` et al.) — packages organize,
  modules withhold; only the module seats rename.
- Non-goal: touching `:adapter:ios:ext-safe` / `:adapter:ios:app-only` — already on the target
  shape.
- Non-goal: renaming any capability/spec id — none is named after the module seats.

## Decisions

- **D1 — the fakes are `generic`.** The spec's own definition of "generic" is platform-free, and
  the fakes qualify: in-memory is their *technology*, and it is platform-free. "Fake" is not a
  platform axis; it is what the leaf records (below).
- **D2 — axis-dependent leaf vocabulary, deliberate (operator-chosen).** On the ios axis the leaf
  names encode PROCESS linkage (`ext-safe` = safe to link into the extension process, `app-only`
  = must not be). On the generic axis the leaf names encode SHIPPABILITY (`app` = links into the
  shipped app AND extension binaries, `fake` = never ships). Note the asymmetry this makes
  explicit: `:adapter:generic:app` links into BOTH processes, unlike `:adapter:ios:app-only` —
  a single leaf vocabulary would either lie about that (calling the generic leaf `app-only`) or
  say nothing (calling it `shared`). Each axis's discriminating question is different, so each
  axis names its own answer.
- **D3 — the prefix is a path grouping, not a module.** `adapter/generic/` gets no
  `build.gradle.kts` and no `include(":adapter:generic")` — exactly like `adapter/ios/`. A prefix
  module would withhold nothing (the module-set law's own test) and would re-create the flat seat
  under a new name.
- **D4 — gate scopes move in the same diff, not left to the non-vacuity twins.**
  `FakeHonestyTest.fakeRoot` moves to `adapter/generic/fake` with the red-proof re-run (plant a
  public `var` in a fake → red → restore → green); `ModuleSetTest.targetModules` renames the two
  entries. Same-diff update is the only safe sequencing — a stale `fakeRoot` would report the
  gate "PENDING" forever (the directory no longer exists), which is precisely the fail-open the
  self-arming clause warns about.
- **D5 — generated SQLDelight impl packages may shift; verified inert.** The database
  `packageName`s are pinned in the build script and unchanged. SQLDelight derives the generated
  *implementation* sub-package from the module name; that package is internal to the generated
  code (nothing in the tree references `…db.generic` or `…db.app`), and
  `verifyCommonMainLedgerDatabaseMigration` still passes. Runtime identity is the pinned db
  *filenames*, untouched.
