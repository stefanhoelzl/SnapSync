# Design: desktop-harness-ui-mock

## Context

The repo currently contains a single blank Compose window (`:app:desktop`). The full v1 architecture is locked in `docs/design.md`: multi-module KMP, snapshot seam between sync and presentation (§2.3), semantic design system (§5), dual-pane desktop harness (§5.1). This change is the first vertical slice: UI-first, no engine — every decision below was settled in the 2026-06-09/10 interview + explore sessions and is recorded in `docs/design.md`; this file summarizes the slice-relevant subset.

## Goals / Non-Goals

**Goals:**
- Real, compiler-enforced module boundaries: `:domain:sync` (contract) ← `:domain:presentation` ← `:domain:ui` ← (`:domain:ui:components`), wired by `:app:desktop`.
- The exact seam the future engine plugs into: `SyncStatusSource { val status: Flow<SyncStatus> }` — the container never changes when the real engine arrives.
- Explorable status-screen UX at ship proportions before any sync logic exists.

**Non-Goals:**
- No sync engine, no capability modules (gallery/uploader/s3/store), no SigV4, no persistence.
- No error states, permission gate, buttons, or theme tokens (each arrives with its own slice).
- No scenario-runner machinery (ScenarioStep commands/interpreter/log) — that triggers when world controls are born with the engine slice; the PR-1 panel is the permanent "display overrides" section only.

## Decisions

1. **Snapshot seam, not events** (`docs/design.md` §2.3). `SyncStatus(pending, completed)` observed via `SyncStatusSource`. Alternatives considered: UI-shaped events (Started/Progress/Completed — rejected: promises an upfront total the real engine can't know; relocates the fold into presentation) and engine-shaped events (rejected: speculative vocabulary before PhotoKit contact; every classic event-delivery problem for a level-triggered consumer). Fields are demand-driven — `failed`/`lastSync` arrive with the slices that render and define them.
2. **KMP from day one, jvm target only.** Code in `commonMain`; adding iOS targets later is a build-file change, and JVM-only APIs can't silently leak into shared code. Alternative (plain `kotlin("jvm")`, migrate later) rejected: source-set restructuring cost plus no guard against `java.*` creep.
3. **Semantic-only design system** in `:domain:ui:components` (`docs/design.md` §5): params carry data/meaning, never appearance; no `Modifier` params; distinct components over role enums; semantic slotted containers own convention-bearing arrangement; no exposed tokens. PR-1 inventory: `AppTheme`, `ScreenLayout(title)`, `StatusText`, `UploadProgress(done, total)`. Mechanical rule: no M3 type in any `App*` signature; only this module imports M3.
4. **Orbit MVI 10.0.0** (full KMP + CMP support, May 2026). Built with Kotlin 2.1.21 — fine for JVM consumption under Kotlin 2.4.0; klib compatibility re-checked at the iOS slice. Escape hatch if anything surprises: the `presentation → ui` contract is plain `StateFlow<UiState>` + actions, state-lib-agnostic.
5. **Panel = display overrides only**, all mutations through one `PanelController` into a `MutableStateFlow<SyncStatus>` wrapped as the `SyncStatusSource`. Panel is utilitarian raw Material 3, never `App*` (asymmetric investment — it's long-lived test equipment, not product).
6. **Tests only for MVP-permanent code**: container reduction via `orbit-test` (initial-state auto-assert, `expectStateOn`), Compose UI tests on the status screen (`compose.desktop.uiTestJUnit4` + `compose.desktop.currentOs`). Panel/harness scaffolding untested by design.

## Risks / Trade-offs

- [Compose Desktop UI tests need a display on Linux CI] → Add the Xvfb step from JetBrains' own compose-tests workflow (`sudo Xvfb :1 -screen 0 1920x1080x24 -extension RANDR +extension GLX &`, `DISPLAY=:1.0`). Verify with ONE trivial UI test pushed to CI before writing the rest; fallback: UI tests local-only, container tests remain the CI gate.
- [Orbit 10.0.0 first use on Kotlin 2.4.0/CMP 1.11.1 in this repo] → First compile is the confirmation; contract is state-lib-agnostic if it fails.
- [UI-first mock validates UX against forged states, not real engine behavior] → Accepted: the seam mapping (`pending`/`completed` only, totals derived) was designed against the real engine's constraints (paged change feed, jobLimit), so the mock can't promise anything the engine can't deliver.
- [`:domain:ui:components` nested under `:domain:ui`] → Both are ordinary Gradle projects; the nesting is path-only. If Gradle/IDE friction appears, flattening to `:domain:design` is a rename, not a redesign.

## Open Questions

None for this slice — deferred decisions (engine event vocabulary, ScenarioStep set, error model, SigV4 timing, exit criterion for "desktop working") deliberately belong to later slices.
