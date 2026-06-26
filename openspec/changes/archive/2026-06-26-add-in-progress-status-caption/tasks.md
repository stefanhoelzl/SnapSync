> Implementation landed ahead of this proposal (live iteration) and is green under `./gradlew build`
> (JVM tests + offscreen `:domain:ui` render tests + the iOS `commonMain` compile proxy). Boxes are
> checked to reflect the working tree on branch `ui-state`.

## 1. Presentation: carry the in-progress count

- [x] 1.1 Add `inProgress: Int` to `UiState.InProgress` (between `total` and `finishedAgo`); document it as the asset-counted `pending`, which may be lower than `total - synced`.
- [x] 1.2 In `StatusContainerHost.toUiState`, map `inProgress = pending` for the `IN_PROGRESS` branch.

## 2. UI: render the second caption

- [x] 2.1 In `StatusScreen`, compose the InProgress detail as `"${inProgress} in progress"` plus `" · ${finishedAgo}"` when `finishedAgo` is non-null, and pass it to `StatusHero` (no `StatusHero`/`App*` signature change).

## 3. Harness: make it reviewable off-device

- [x] 3.1 Give `PanelController.progress(...)` a `pending` parameter (default 0) and have `showInProgress()` forge a non-zero `pending` so the caption renders in `:app:desktop:run`.

## 4. Tests

- [x] 4.1 Presentation: assert the `pending → inProgress` mapping (e.g. `pending = 35 → inProgress = 35`) and add `inProgress` to every existing `InProgress` expectation.
- [x] 4.2 UI (`:domain:ui` jvmTest): assert the merged detail "35 in progress · 5 min ago" with a completion, and "47 in progress" (count only) at a virgin "0 of N".

## 5. Verify

- [x] 5.1 `./gradlew build` green; spot-check the caption via `./gradlew :app:desktop:run` (the "In progress" preset).
