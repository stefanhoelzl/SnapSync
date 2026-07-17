# Establish the target architecture

## Why

The codebase's most-churned, most-bug-fixed region is exactly its untested one: `:app:ios` holds
~2,400 production lines with 0 tests, reaches nine external systems directly past every seam the
codebase owns, and `fix(ios)` is the largest fix category — while every rule that had an
executable guard held, and the one honor-system rule ("shells are wiring-only") is the one that
eroded. The module names encode no law (`:domain:` means "shared", `:capability:` labels both
use-cases and vocabulary), so nothing contradicts a wrong edge: the documented spine is inverted
in two places and one declared edge was never imported. This change writes down the target
architecture the codebase should converge on — derived from the codebase's own evidence, checked
against the literature and KMP practice, hardened by four independent adversarial reviews and a
40-claim necessity audit — together with the gates that enforce it and the derived diagrams that
keep it reviewable. Migration sequencing is deliberately out of scope (follow-up changes).

## What Changes

- **A new contract of record for the module graph and its laws** (`module-architecture`): one
  platform-free `:domain` module (packages `model/` · `ports/` · `feature/` · `flow/` ·
  `compose/` with derived, text-gated boundaries), `:ui:*`, `:adapter:*` split on the
  extension-safety linkage line, wiring-only `:app:*` shells (Kotlin zero-conditional,
  Swift-as-transcriber), one shared composition (`snapSyncApp` / `uploadCore`), ports named for
  the need with interfaces owned by the core, and the state/coordination laws (authority behind
  ports; features mutually blind, coordinating via one-writer shared state; rules in features,
  order in flows; commands cross `flow/`, reads are feature-owned projections).
- **The forcing-proof convention**: necessity claims cite an API contract, an on-device
  measurement, or a vendor document — never the current code's shape — and name their expiry
  trigger. Pinned-exception gates carry the proof in their failure message.
- **New executable guards + a migration beacon** (delta to `architecture-guards`): text-pattern
  zone gates with derived scopes and non-vacuity twins, per-zone library allowlists, an
  extension-safety text gate, a detekt complexity gate on `:app:*` Kotlin, a Swift
  decision-keyword pin guard, a fake-honesty gate on `:adapter:fake`, and a **non-required
  `verify` beacon job, red by design until the migration completes** (D8 revised), whose
  burn-down reports migration distance (accepted risk, on record: during migration nothing GATES
  new violations — review is measured absent and the beacon is visible, not blocking).
- **A derived-diagrams system** (`architecture-diagrams`): module graph, zone graph, per-trigger
  flow sequence diagrams, port × adapter matrix, feature cards, DI wiring + binary × port matrix
  — generated from source (never drawn), committed, freshness-gated by an ordinary test under
  `./gradlew build` AND by the required `diagrams` CI check (no self-healing on main — a stale
  merge goes red and is fixed by a regeneration PR).
- **Decision records for the audit-driven follow-ups** (behavior changes shipped by their own
  changes during migration, recorded here): reinstall = left the event (config moves from
  Keychain to an App-Group file); status liveness re-derives from "event-driven, not polled" to
  a latency bound satisfied by a foreground-gated poll (the `ProtectedData` and `ProcessSignal`
  ports and all Darwin/C-callback interop are deleted from the target); the device-identity
  spec's Keychain-survival claim is annotated as non-contractual behavior.

## Capabilities

### New Capabilities
- `module-architecture`: the target module graph, the package zones inside `:domain`, the
  dependency/state/coordination/command laws, the port catalog with linkage classes and the
  `UploadPorts ⊂ AppPorts` bundles, the composition contract, the Swift-as-transcriber posture,
  and the forcing-proof convention.
- `architecture-diagrams`: the derived-diagram set, the generation command, byte-determinism
  requirements, the freshness gate, and the self-healing job on main.

### Modified Capabilities
- `ios-appstore-release`: the green guard judges REQUIRED check-runs only (derived from branch
  protection); a red non-required check — the `verify` beacon, `ios-deliver`/`ios-promote` — no
  longer blocks a release.
- `ship-command`: the merge queue and the CI watch judge required checks only.
- `branch-protection`: the `diagrams` freshness check becomes a required status check.
- `ios-photokit-upload`: the Swift result mapping is exhaustive per `CycleResult` case, with the
  compiler-mandated `default:` arm mapping to failure instead of success.
- `architecture-guards`: gains the new gate families (text-pattern zone gates with derived
  scopes, per-zone allowlists, extension-safety text gate, Kotlin shell complexity gate, Swift
  pin guard, fake-honesty gate, diagram drift gate), the fail-closed-on-novelty authoring rule,
  and the red-until-done migration-beacon posture — safe because the release guard and `/ship`'s
  watcher judge required checks only, derived from branch protection (no name list).

## Impact

- **New modules**: `:test:architecture:migration` (the beacon, detached from `check`), the
  diagram generator wiring, `architecture/` (committed generated diagrams). **No production
  module moves in this change** — placement deltas ride the follow-up migration changes, which
  also carry the audit-decided behavior deltas to `sync-status` (liveness latency bound),
  `event-rejoin-reconciliation` (reinstall = left; re-scan reconciles), `device-identity`
  (evidence annotation), and the config-store re-backing (Keychain → App-Group file).
- **CI**: one new `architecture` workflow — `verify` (non-required, red by design, burn-down in
  the summary) and `diagrams` (required, blocks stale diagrams). `ios-release.yml` and `/ship`
  now filter to required checks; a red `ios-promote` no longer blocks a release (deliberate).
- **Docs**: CLAUDE.md module section gains a pointer to the new contract of record; two factual
  corrections ride along (the APNs re-delivery comment, the two-frameworks rationale).
- **Dependencies**: detekt (shell gate; measured in the abandoned v1 attempt),
  dependency-analysis (`buildHealth`, jvm/common scope only, warn-only). No production
  dependency changes.
