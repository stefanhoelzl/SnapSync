# Design: sync-status-screen-states

## Context

Slice 1 shipped the snapshot seam (`SyncStatus(pending, completed)` + `SyncStatusSource`), a two-state screen (Idle / Uploading X of N), and the dual-pane desktop harness with display-override buttons. The seam was deliberately trimmed: fields arrive with the slice that renders them and defines their semantics. This slice is that slice for failure, suspension, history, and estimation — chosen ahead of the engine so the full display vocabulary can be designed and exercised against forged snapshots.

All decisions below were resolved in an interview/explore session on 2026-06-10.

## Goals / Non-Goals

**Goals:**

- The screen truthfully renders every standing state a real backup can be in, pass-level (not item-level).
- The seam carries only facts the engine can honestly produce, with documented freshness semantics.
- The design system gains the hero/indicator vocabulary without breaking the semantic-only philosophy.
- Every state is forgeable from the harness panel.

**Non-Goals:**

- No controls or intents ("back up now", enable toggle, permission gate) — the screen is pure display; the entire actions-seam question is deferred.
- No engine, capabilities, fakes-with-behavior, or ScenarioStep indirection (reserved for the world-controls slice).
- No failed-item counts or per-item diagnostics on screen.
- No Cupertino skin; M3 only, per the existing containment rules.

## Decisions

### D1. Six-state vocabulary, pass-level, yield-based outcomes

The hero reports the most recent pass, never item counts. Outcomes are classified by yield: `failed == 0` → complete; `completed > 0 && failed > 0` → incomplete; `completed == 0 && failed > 0` → failed. "Pass aborted" is intentionally not an outcome — on iOS, aborts are routine (extension suspended mid-cycle) and are represented by the pass still being outstanding, not by a verdict.

| State | Headline | Icon | Detail line |
|---|---|---|---|
| NeverSynced | No sync yet | ⚠ warning | — |
| InProgress | Sync in progress | ◔ progress (rough fraction) | estimate ("~2 min left" / "estimating…") |
| Suspended | Waiting to sync | ⏳ waiting (neutral) | — (bare headline; nothing honest to show) |
| Complete | Sync complete | ✓ success | relative time ("5 min ago") |
| Incomplete | Sync incomplete | ⚠ warning | relative time |
| Failed | Sync failed | ✖ error | relative time |

"Waiting to sync" (not "suspended"/"paused") because the headline describes the standing state from the user's side and must not imply a resume button exists. Suspended is expected to be the *most common* state on a real device — the OS runs the extension opportunistically, so `pending > 0` with nothing moving is the norm, and rendering it as "in progress" would be a standing lie.

### D2. Seam shape: counts + verdicts, computed classification

```kotlin
data class SyncStatus(
    val pending: Int,
    val completed: Int,
    val failed: Int,
    val active: Boolean,                    // is a pass progressing right now (source-determined)
    val estimatedRemaining: Duration?,      // minted at snapshot emission; null = not estimable
    val lastFinishedAt: Instant?,           // end of last finished pass; null = never synced
) {
    val state: SyncState                    // computed, single source of truth for classification
}
```

Classification: `pending > 0 && active` → InProgress; `pending > 0 && !active` → Suspended; otherwise by yield (D1); `pending == 0 && lastFinishedAt == null` → NeverSynced. Counts describe the most recent pass (in-flight or finished).

- **No outcome enum stored** — with yield-based semantics the outcome is fully derivable from counts; storing it would be redundant (alternative rejected: explicit outcome field as "insurance" — buys nothing the chosen semantics need).
- **`lastStartedAt` rejected** — it was motivated by a derived ETA, which became engine-supplied; nothing renders a start time. The trimming rule wins; it returns with whatever slice renders it.
- **Classification lives in `:domain:sync`** (computed property), not in presentation — it is domain truth, defined and tested once; presentation only formats. (Alternative rejected: derive in the Orbit container — hides domain semantics inside presentation.)

### D3. Sources own liveness and the future; presentation ages the past

Two fields are *verdicts* the source must mint fresh at each snapshot emission:

- `estimatedRemaining` is **never persisted**. A stored estimate is stale the instant it is written (the extension writes "~2 min", gets suspended, the app reads it an hour later). Instead, real sources persist raw inputs (counts, throughput history) and compute the estimate when emitting a snapshot — which by definition happens while someone is looking. A source that cannot estimate honestly emits `null`. Aging a stored estimate (est − elapsed) was rejected as actively wrong (nothing progressed during suspension); a TTL field was rejected as a timestamp whose only job is distrusting another field.
- `active` is the source's liveness verdict; *how* it is determined (job-state observation, progress-within-poll-window) is the platform impl's private business and never crosses the seam.

Corollary: presentation renders the estimate verbatim (bucketed) and never ages it; the only thing presentation ages is the past — the relative "5 min ago" line, which ticks.

### D4. Presentation formats everything; UiState is display-ready

`UiState` becomes six variants carrying final display data: the fraction (Float, for the progress indicator) and pre-formatted strings (estimate bucket, relative time). The Orbit container owns an injected `Clock` (stdlib `kotlin.time` — all relative-time math is pure duration arithmetic with no calendar formatting, so kotlinx-datetime is not needed; decided at apply time) and a ~1/min tick that re-emits only when the visible relative-time text would change. Coarse buckets for both kinds of time text ("just now"/"5 min ago"/"2 h ago"…; "less than a minute left"/"~2 min left"/"~1 h left") so nothing twitches. Rationale: UI stays a dumb renderer, orbit-test asserts exact visible text, and time control lives in one place. (Alternative rejected: UiState carries Instants and the UI formats/ticks — leaks display logic into `:domain:ui` and forces UI tests to control time.)

Icon fraction basis: processed-of-total (`(completed + failed) / (pending + completed + failed)`) so the indicator always reaches the end of a pass; the outcome headline then delivers the verdict. The fraction is rough by design — it is never rendered as text.

### D5. Design system: `StatusHero` + sealed `StatusIndicator`

```kotlin
StatusHero(indicator = StatusIndicator.Progress(fraction = 0.35f),
           headline = "Sync in progress",
           detail = "~2 min left")

sealed interface StatusIndicator {
    data object Success : StatusIndicator
    data object Warning : StatusIndicator
    data object Error : StatusIndicator
    data object Waiting : StatusIndicator
    data class Progress(val fraction: Float) : StatusIndicator
}
```

One component owns the hero arrangement (indicator inline-left of the headline, muted detail line beneath — per the approved wireframes); the variant axis is a sealed semantic value because it is **runtime data** (it arrives from `UiState`, one variant carries a payload). This *refines* the "distinct components over role enums" rule rather than violating it: distinct components for design-time choices a call site picks statically (PrimaryButton); sealed semantic values for data-driven variants; enum-shaped appearance knobs remain banned. This refinement is captured in the design-system spec rewrite.

- `StatusText` and `UploadProgress` are deleted (zero call sites remain). Demand-driven inventory: the vocabulary is whatever current screens speak.
- `ScreenLayout`'s content slot becomes "body, vertically centered" — the centered hero is convention-bearing screen structure and belongs to the layout container, not the components. A future `ActionArea` slot pins to the bottom when controls arrive; centering now does not conflict.
- M3 skin gains its first icons: four hand-drawn stroke glyphs (✓/⚠/✖/clock via `Canvas`, ~60 lines, private to the skin) and a small determinate `CircularProgressIndicator` as the ◔ indicator. Decided at apply time: material-icons artifacts are no longer published for current Compose versions, and the design already allowed free substitution; a future skin swaps the glyphs without signature changes. The M3 stop-indicator cosmetic question dies with `UploadProgress`.

### D6. Harness panel: six presets, still dumb

Display-override buttons for all six states via `PanelController` (e.g. finished outcomes set `lastFinishedAt = now − 5min`; InProgress presets with and without an estimate to show "estimating…"). Display overrides remain forever outside the scenario system — no command indirection, no tests (per the standing harness decision).

### D7. CI housekeeping rides along

`.github/workflows/build.yml` action pins move off Node-20-deprecated majors (`actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle`) ahead of GitHub's 2026-06-16 forced-Node-24 date. No requirement change; folded into this PR by explicit decision.

## Risks / Trade-offs

- [Seam fields are guesses until an engine exists] → Mitigated by precedent: every field was demand-pulled by a rendered element or a documented emission-time semantic; `lastStartedAt` was dropped for exactly this reason. The `active` flag degrades gracefully if iOS turns out poll-only (source reports `active = false` unless progress observed recently).
- [Relative-time ticking couples the container to wall-clock] → Injected `Clock` + virtual-time dispatcher in tests; tick re-emits only on text change, so test assertions stay deterministic.
- [Six states × icons may strain the M3 skin's first icon pass] → Icons are contained in `:domain:ui:components`; if `material-icons-core` lacks a glyph, the skin substitutes freely — no screen or signature changes.
- [`UiState` carries pre-formatted strings, so locale/wording changes touch presentation tests] → Accepted; v1 is English-only and exact-text assertions are the point of display-ready UiState.

## Open Questions

(none — all decisions resolved in the 2026-06-10 session)
