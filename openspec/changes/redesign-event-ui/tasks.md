# Tasks — redesign-event-ui

The join/create UI is **already built** in the PoC (`ui/screens` + `ui/components`) and
user-approved. These tasks productionize that branch — they do **not** build the UI. Checked boxes are
what the PoC already carries; unchecked are the finishing work.

## 1. Spec deltas (this change)

- [x] 1.1 `join-event`: switches + derived direction + 3-option cutoff + standalone album ref; explainer
      names the event; cutoff-derivation preset set; switch dialog states the reset
- [x] 1.2 `event-album`: the direction-independent standalone album opt-in with its adaptive note
- [x] 1.3 `event-creation-ui`: host hero + question + stated-consequence start card + error banner
- [x] 1.4 `photo-selection-policy`: the join-time floor clamp covers the Custom pick (UI enforces it twice)
- [x] 1.5 `design-system`: switch sections, sub-section well, minor section, checkmark toggle row, drawn
      switch, drawn mark + event heroes, drawn calendar + time-wheels picker, 3-option cutoff rows,
      error banner, join-gate pieces, dialog `body`, truthfulness/accessibility/contrast; remove `AppExplainer`
- [x] 1.6 `openspec validate` (change-scoped + `--specs --strict`) green

## 2. Productionize the PoC surfaces (largely done)

- [x] 2.1 `StatusScreen` join gate: two switch sections, derived direction, both-off disables Join with a
      stated reason (never auto-flips)
- [x] 2.2 3-option cutoff (`Now` / `Event start` / `Custom`); Custom opens the floored picker directly,
      only OK commits, cancel restores; resulting instant stated once (bold) in the Share section
- [x] 2.3 Explainer names the event (hero continuity); three consent facts as card rows; "I understand"
      the only route to the system dialog
- [x] 2.4 Standalone "Create an album" minor section with the four adaptive note wordings
- [x] 2.5 Create surface: host hero, question heading, stated-consequence start section, error banner
- [x] 2.6 Switch-events dialog body states the participation reset

## 3. Sweep dead components (verify none are re-referenced, then delete)

- [ ] 3.1 `AppExplainer` (`AppExplainer.kt`) — join gate now uses `AppSummaryCard` + `AppAccessPoint`
- [ ] 3.2 `AppCheckboxRow` (`AppCheckboxRow.kt`) — superseded by `AppSummaryToggle`
- [ ] 3.3 `AppSummaryFact` / `AppSummaryLine` (in `AppSummaryCard.kt`) — unreferenced remnants
- [ ] 3.4 `AppEventStartRow` public composable (in `AppEventStartRow.kt`) — create screen uses
      `AppEventStartSection`; keep the shared `DateTimePickerDialog` / `appDateTimeLabel` / `formatStart*`
- [ ] 3.5 `AppDateTimeField` public composable (in `AppDateTimeField.kt`) — unused; keep the internal
      `DateTimePickerDialog` + `TimeWheels`/`WheelColumn` it hosts (that is the live picker)
- [ ] 3.6 `AppEventHero` public composable (in `AppEventHero.kt`) — headers use the Compact/Host/Loading
      variants + `AppMarkBadge`; keep those
- [ ] 3.7 Confirm the removed `AppDirectionSelector` / `AppCutoffSelector` (deleted in the PoC) have no
      dangling imports or spec references
- [ ] 3.8 Grep the tree for each deleted symbol (`ui/`, `app/`, `:test:*`) before removal; run the dead-type
      archive gate against the diff

## 4. Tests

- [x] 4.1 `ui/screens` `JoinScreenTest` / `StatusScreenTest` assert the new surfaces (switches, 3-option
      cutoff, no `Both`/`Upload only`/`Download only` text, banner-not-red create error)
- [ ] 4.2 Extend `ui/components` / `ui/screens` `commonTest` for the new components (switch-section role,
      checkmark-row `Role.Checkbox` incl. dimmed-but-present, cutoff `Custom` floor coercion, adaptive
      album note per switch combination, both-off Join-disabled reason)
- [ ] 4.3 `./gradlew build` green (JVM + headless Compose UI tests); `compileIosMainKotlinMetadata` clean

## 5. On-device verification (per CLAUDE.md runbooks)

- [ ] 5.1 Join gate on a real device: switches, 3-option cutoff incl. Custom picker + floor, explainer
      naming the event, standalone album section, both-off Join-disabled
- [ ] 5.2 Create surface: host hero, stated-consequence start card, error banner path
- [ ] 5.3 Light/dark contrast eyeball (measured-AA corrections; recessed wells; pinned off-switch colours)

## 6. Marketing screenshots

- [ ] 6.1 Refresh `screenshots/*.png` (6 raws, `create`/`joining`/`in_sync` × light/dark) via
      `screenshots.yml`, eyeball, and commit (drives the App Store listing + landing WebP)

## 7. OpenSpec close-out (later, not this change)

- [ ] 7.1 `apply` → implement any remaining unchecked items → `archive` (run the three archive gates:
      placeholder Purpose, delta completeness per touched module, dead types)
