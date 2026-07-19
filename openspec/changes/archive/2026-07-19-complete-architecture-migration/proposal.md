# Complete the module-architecture migration (step 13b — the finale)

## Why

Every prior step left exactly one module (`:capability:push`), 14 pinned-or-pending shell
decisions, the 11a Keychain write-through soak window, a shell-reading flow transcriber, and a
red-by-design beacon measuring the distance. The finale drives every law to zero, makes each law a
permanent gate under `./gradlew build`, lands the one staged behavior change (reinstall = left the
event), and deletes the migration apparatus — beacon module, `verify` job, PLAN.md, RUN.md — per
the beacon's own completion contract.

## What changes

- **`:capability:push` → 0 modules of distance**: `PushRegistration` + `EventNotifier` re-home to
  `:domain` `feature/push` (pure logic over the existing `ports/` seams `PushHttpClient` /
  `PushTokenSource`); the Ktor-client tests move beside their adapter (`:adapter:generic`); the
  module is deleted and module-set equality reaches the target exactly.
- **The 14 shell decisions reach the law's bar** (each recorded in design.md): 7 drained into
  tested zones (`feature/trust`, `ports/`, `:ui:presentation`, `:adapter:ios:app-only`,
  `:adapter:ios:ext-safe`), 5 pinned with forcing proofs, 5 of the drained ones being the PhotoKit
  platform adapter's move to its lawful adapter seat. `detektAppShell` flips
  `ignoreFailures=false` and joins `check`; `KotlinShellGuardTest` pins the suppression inventory
  with a non-vacuity floor.
- **The Keychain config write-through ends** — but ONLY the write-through: `save` is file-only
  and `KeychainConfigStore` dies, while the READ keeps the 11a migration fallback through a
  minimal legacy seat (`KeychainConfigReader`: read + the leave-path delete, never a value
  write). The ship model forces this (behavior-review bounce, design D4): the branch reaches the
  installed base as ONE merge, so at update time every joined device is pre-11a — a fallback-less
  flip would silently log the whole fleet out. The true **reinstall = left** flip (fallback
  deletion) becomes a designated POST-SHIP Stage-2 change gated on production soak;
  event-rejoin-reconciliation stays staged.
- **The flow transcriber re-points at `flow/` and its generation failure arms as a hard gate**;
  the three step-8 D7 grammar debts are refactored into the grammar (membership's sealed
  `switchDecision` + `TitleNeed`, the album coordinator's `granted` fact, the null-tolerant
  name-store rule); the diagram generators drop their current-state editions.
- **The beacon's laws are promoted to permanent gates**: `ModuleSetTest`, `MixedPortImplTest`,
  `DeletionLedgerTest` (+ the already-armed zone/shell/Swift gates), each red-proofed.
- **The apparatus dies**: `:test:architecture:migration` (with PLAN.md and RUN.md), the
  `architecture` workflow's `verify` job, and CLAUDE.md's migration-era prose (the module list is
  rewritten to the target graph; every operator runbook kept).

## Impact

Specs: event-rejoin-reconciliation (the Stage-2 flip), event-link (file-only config store),
architecture-guards (permanent gates; pin inventory changes), architecture-diagrams (the realized
transcriber grammar + hard gate), push-registration & ios-app-shell (push re-homing),
upload-lifecycle / leave-event / ios-photokit-upload / device-identity (write-through hedge
sweeps + adapter/rule seats), event-album (the `granted` fact joins the leading guard),
event-creation-ui (presentation-owned transient error).

Behavior: reinstall semantics stay Stage-1 (a reinstall still resurrects while the read fallback
lasts); the flip to reinstall=left is the designated post-ship Stage-2 change. What ships now is
seat motion, gating posture, and the write-through's end (leave now also deletes the legacy item,
closing the fallback's leave-resurrection hole). Device verification: the final device pass —
headlined by the pre-11a→13b direct-update migration test (checklist in design.md).
