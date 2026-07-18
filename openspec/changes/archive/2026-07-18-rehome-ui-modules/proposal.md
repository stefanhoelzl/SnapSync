# Proposal: rehome-ui-modules

## Why

Migration step 9 of the `module-architecture` plan (`test/architecture/migration/PLAN.md`, "`:ui`
re-homing"). The target module set names `:ui:presentation`, `:ui:screens`, and `:ui:components`;
the code still lived under `:domain:presentation` / `:domain:ui` / `:domain:ui:components`, and the
presentation-imports zone gate — created pending at step 0, pinned to the scope
`ui/presentation/src` — had nothing to scan. Presentation also still violated the laws the gate
exists to enforce: `StatusContainerHost` named five `ports/` types and the `flow/` bundle
directly, its permission taps called a port instead of crossing the one door, and
`CutoffFormatter` read the system clock and timezone through in-presentation defaults rather than
through ports. Finally, the `Arrow`/`ArrowLevel` duplicate enum (deletion-ledger row, deferred
from step 1 to this step) still existed on both sides of the presentation/components boundary.

## What Changes

- **Module re-homing** (pure `git mv`): `:domain:presentation` → `:ui:presentation`, `:domain:ui`
  → `:ui:screens`, `:domain:ui:components` → `:ui:components`. Packages deliberately keep their
  names (`app.snapsync.presentation` / `app.snapsync.ui` / `app.snapsync.ui.components`) — the
  gate scope is a directory, no spec names target packages, and the pure-move rule (step-4 D2)
  applies. Diagram scan roots and the beacon's deletion-ledger scan roots gain `ui` (loud-stale
  lists, updated in-PR per the plan's rule).
- **`StatusContainerHost` split** — presentation's inputs become exactly (read-models · the
  bundle · a query · a pure formatter): the `ConfigSource`/`PhotoAccessStatusSource` params become
  bare `StateFlow`s, the dead `ConfigStore` param is deleted, the `PhotoAccessRequester` param
  dissolves into two new bundle commands (`requestAccess` / `openSettings`, bound to the port in
  `compose/`'s `AppCore.userCommands`; `AppPorts` gains `photoAccessRequester`), and
  `EventDetails.toJoinLoad()` re-seats in `feature/membership` with the `JoinLoad` vocabulary in
  `model/`. The **presentation-imports gate arms** (deliberate-red proven) over the whole of
  `ui/presentation/src`, tests included — which also forced the two presentation tests that
  assembled the real create use-case over a stubbed `EventCreation` port to become bundle-level
  choreography (their feature half is `CreateEventTest`'s and `:test:integration`'s).
- **`UserCommands` re-seats `flow/` → `model/`**: the armed gate forbids presentation naming
  `flow/`, and the bundle is pure vocabulary both `compose/` and presentation must name; live
  instances are still built only in `compose/`. The inert `UserCommands()` default in
  presentation stays — a model-typed null object, not command wiring (the C3-review A1 item,
  resolved by the gate's letter).
- **`CutoffFormatter` becomes pure**: the interface + `SystemCutoffFormatter` collapse into one
  presentation class taking `now: () -> Instant` and `zone: TimeZone` with **no system-reading
  defaults** anywhere (host and screen params are now required); new need-named ports `Clock` and
  `TimeZoneSource` in `ports/`, implemented by `:adapter:generic`'s `SystemClock` /
  `SystemTimeZone`, bound once in `SnapSyncRoot` into the shared formatter handed to the host,
  the screen, and the forge factory (root-owned so the forge composition reaches it with no route
  to the live graph).
- **Arrow/ArrowLevel unify** (deletion ledger −1): the one `Arrow` enum re-seats in `model/`;
  components' `ArrowLevel` and the screens-side `toLevel()` mapping die; `:ui:components` gains an
  api dependency on `:domain`.

Beacon (fresh `detektAppShell`, `--rerun-tasks`): **36 → 29** — module set 16 → 10 (the three old
includes removed, the three target modules created), deletion ledger 2 → 1 (the Enrollment ×2 row
remains, dying at step 10), shells 18 unchanged, no law increased.

## Impact

- Affected specs: `ios-app-shell`, `sync-status`, `gallery-status`, `harness-world-model`,
  `architecture-guards`, `design-system`. (`desktop-test-harness` / `full-stack-harness` carry no
  old module-path references — verified by grep — and `module-architecture` already names the
  `:ui:*` targets, so neither needs a delta. `sync-status-screen` names no module or enum type;
  its arrow-derivation contract is unchanged.)
- Affected code: the three moved modules; `domain/src` `model/` (+`UserCommands`, `JoinLoad`,
  `Arrow`), `ports/` (+`Time.kt`), `feature/membership` (+`JoinDetails.kt`), `compose/`
  (`AppPorts`/`AppCore`); `:adapter:generic` (+`SystemTime.kt`); the shells (`SnapSyncRoot`,
  `MainViewController`); both desktop harness roots + `StatusPane`; `:test:integration`;
  `tools/diagrams` scan lists; the beacon's ledger scan roots; `settings.gradle.kts` + dependent
  build files; CLAUDE.md module rows and runbook paths.
- Behavior-preserving: production wiring passes the real system clock/zone; every merge still
  ships to TestFlight. Verification is one `screenshots.yml` dispatch (the sole exerciser of
  `forgeStatusHost`), per the plan's step-9 note.
