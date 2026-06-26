## MODIFIED Requirements

### Requirement: Status screen renders UI state

The status screen SHALL render each state as a centered hero via the design system's `StatusHero`: a
single LED-style status dot above one count line, with an optional muted detail line. The dot is carried
by a **semantic** `StatusIndicator` — no color, shape, or style appears in any `App*` signature; the
Material 3 skin in `:domain:ui:components` maps the semantic indicator to pixels (`InProgress` → a
yellow dot, `Complete` → a green dot). `NothingToSync` uses the `Complete` (green) indicator. There is
no headline line and no progress ring. `UiState.Loading` SHALL render an **indeterminate** progress
indicator with the text "Loading …", no dot, no detail line and no button (the user has no action; it
auto-resolves).

The synced and total counts SHALL appear as text. For InProgress, the detail line SHALL carry a second
caption: the in-progress count rendered as `"{inProgress} in progress"` **only when `inProgress > 0`**
(omitted when nothing is actively uploading), followed by `" · {finishedAgo}"` only when a completion
exists (`finishedAgo` non-null). When both are absent there is **no detail line**. The screen renders:

| State | Indicator | Count line | Detail |
|---|---|---|---|
| Loading | Loading (indeterminate), no dot | "Loading …" | — |
| InProgress | InProgress (yellow dot) | "{synced} of {total} images synced" | "{inProgress} in progress" when inProgress > 0, joined by " · " to "{finishedAgo}" when not null; absent when neither applies |
| NothingToSync | Complete (green dot) | "Nothing to sync yet" | — |
| Completed | Complete (green dot) | "{total} images synced" | relative time |

#### Scenario: Loading state shows an indeterminate indicator
- **WHEN** the UI state is Loading
- **THEN** the screen shows an indeterminate progress indicator and the text "Loading …",
  with no dot, no detail line and no button

#### Scenario: In-progress state shows the count and the in-progress caption with last-sync time
- **WHEN** the UI state is InProgress with `synced = 12`, `total = 47`, `inProgress = 35`, and `finishedAgo = "5 min ago"`
- **THEN** the screen shows the yellow dot, the line "12 of 47 images synced", and the muted detail
  "35 in progress · 5 min ago", with no headline and no progress ring

#### Scenario: In-progress with no prior completion shows the in-progress caption and no time
- **WHEN** the UI state is InProgress with `synced = 0`, `total = 47`, `inProgress = 47`, and `finishedAgo = null`
- **THEN** the screen shows the yellow dot, the count line, and the muted detail "47 in progress" with
  no relative time appended

#### Scenario: In-progress with nothing actively uploading omits the in-progress label
- **WHEN** the UI state is InProgress with `synced = 3`, `total = 5`, `inProgress = 0`, and `finishedAgo = "2 min ago"`
- **THEN** the detail line shows just "2 min ago" — the "0 in progress" label is omitted

#### Scenario: In-progress with nothing uploading and no completion shows no detail line
- **WHEN** the UI state is InProgress with `inProgress = 0` and `finishedAgo = null`
- **THEN** the screen shows the count line with no detail line

#### Scenario: Nothing-to-sync state
- **WHEN** the UI state is NothingToSync
- **THEN** the screen shows the green dot and the line "Nothing to sync yet" with no detail line

#### Scenario: Completed state shows total and relative time
- **WHEN** the UI state is Completed with `total = 47` and relative time "5 min ago"
- **THEN** the screen shows the green dot, the line "47 images synced", and the muted detail "5 min ago"

The screen is composed under the rules of the `design-system` capability (semantic components
only; Material 3 containment; `ScreenLayout` owns screen structure).
