## Context

The join gate (capability `join-event`) is two surfaces for a first-time guest: the `ExplainAccess` phase
(a four-point card, "I understand" raises iOS's dialog) and the `Ready` decision surface. `Ready` renders
the shared `ParticipationSections` — a Share `AppToggleSection` holding the exclusions note, the bold
"Sharing …" line, the count, and `AppRangePresetChoices` (two recessed wells: From = Event start / Now /
Custom, Until = Event end / Custom); a Receive `AppToggleSection`; the album as an `AppMinorSection`
checkmark row — then the retention note. Measured in the forge harness at 390×844: the first view holds
the header, the Share card and the five range rows; Receive, the album and retention are below the fold.
The reconfigure screen renders the same `ParticipationSections`.

The form is reduced state: `RangeForm` (switches + `FromChoice`/`UntilChoice` presets + a custom
wall-clock behind each), resolved against the event window by `RangeResolution` into `ResolvedRange`.
The create screen already owns a calendar range dialog, `DateTimeRangePickerDialog` (tap start day, tap
end day, two time-wheel pairs, optional `[minimum, maximum]` window, confirmed instants coerced into it).

The design was settled with the user over mockups (interview, 2026-09-28): direction "A" — keep the
choices on the join screen but make them compact — over a summary-plus-options screen (B) and a
no-choices-at-join screen (C).

## Goals / Non-Goals

**Goals:**
- The whole join decision fits one phone screen with no scrolling at the default state.
- One join screen for every guest: the access explainer stops being a step.
- One range choice (whole event / from now / custom calendar range) replacing two preset lists.
- The reconfigure screen stays the same decision surface as the join screen.

**Non-Goals:**
- Changing what a range admits (capability `photo-sharing`), the switches' semantics, the count, the
  both-off rule, or anything after the confirm (commit, failures, full event, switch flow).
- Stating retention anywhere else. Dropping it is a deliberate product call, not a relocation.
- Redesigning the create screen; it only donates its dialog.

## Decisions

### D1. One range preset instead of two

`FromChoice` + `UntilChoice` collapse into one `RangeChoice { WHOLE_EVENT, FROM_NOW, CUSTOM }`, and
`RangeForm` keeps one custom pair (`customFrom`, `customUntil`) behind `CUSTOM`. `FROM_NOW` resolves to
`[now, windowEnd]` and is offered only when `nowAvailable`, exactly as `NOW` is today; `WHOLE_EVENT` to
the window; `CUSTOM` to the pair, clamped into the window by the existing resolution.

*Alternative:* keep the two enums and map the chips onto them. Rejected — the old combinations
(`EVENT_START` + custom until, etc.) survive as unreachable-by-UI states that the reduction, the rig's
`/user` vocabulary and the tests would still have to carry. One enum makes the surface's three choices
the model's three values.

Reconfigure pre-fill: a saved range equal to the window is `WHOLE_EVENT`; anything else is `CUSTOM` with
the saved bounds (a past "from now" is just a custom start — "now" has moved on).

### D2. Reuse the create screen's calendar dialog, with preset chips

✎ on the range row opens `DateTimeRangePickerDialog` with `minimum = windowStart`,
`maximum = windowEnd`, extended with an optional preset-chip row ("Whole event" · "From now", the latter
hidden when `!nowAvailable`). A chip selects its preset and closes; tapping days edits a custom range and
OK commits it as `CUSTOM`; Cancel changes nothing. The create screen passes no chips.

*Alternative:* a new bottom sheet with segmented From/Until controls (the first mockup). Rejected by the
user in favour of the calendar, which the app already has and tests (`DateTimeRangePickerTest`).

### D3. The explainer becomes a notice + sheet; the confirm raises the dialog

`JoinPhase.Detailed.Step.ExplainAccess` and `onAcknowledgeAccess` are removed; `deriveLoadedPhase`
always yields `Ready`. Whether the Ready surface shows the access notice and the "Join & allow photos"
label is derived in the reduction from the SAME rule `deriveLoadedPhase` uses today (no event configured
∧ permission `NOT_DETERMINED`) and exposed on the join layer as one boolean, `asksAccessOnJoin`.

`onConfirmJoin`: when `asksAccessOnJoin`, call `commands.requestAccess()` and then `commit()` in the same
intent. `requestAccess()` cannot suspend or report an outcome (capability `photo-access`), so the join
does not wait on the answer — iOS's dialog lands modally over Committing / the joined screen, and a later
grant starts sharing through the existing permission subscription ("Granting access later starts sharing
without any further step"). `onRetryJoin` does not re-request: by then iOS has an answer.

*Alternative:* raise the dialog first and commit only after it is answered. Rejected — the answer is only
observable through the permission source, which would make the confirm a two-stage wait with a hang if
the observation never arrives, for no user-visible gain: the spec joins whatever the answer is.

The ⓘ opens the existing four-point card as a modal sheet (a new `App*` sheet composable wrapping the
card; "Got it" dismisses). The 4th point's copy changes ("Only photos in the range you chose"). Opening
it is local UI state, not an intent — it changes nothing a reduction needs to know.

### D4. Layout

`ParticipationSections` becomes: one card holding the Share switch row (range row: dates + "<preset> ·
<count>" + ✎; exclusions in the smallest note type; off → "Nothing of yours leaves this phone.") and,
under a divider, the Receive switch row with its note; then the album as its own `AppToggleSection`.
The standalone count row folds into the range row's subtitle (`Counting…`, `N photos`, `0 photos` + the
"new photos are shared as you go" note; nothing when unavailable). The retention note and `ReadyLabels.
deletes` go; `ResolvedRange.deletesLocal` goes if no other surface reads it.

The detekt `ui` tier ceilings may move (see `docs/architecture.md` on Compose trading `LongMethod`
against `LongParameterList`); any raise needs its forcing proof in the PR.

### D5. Harness, rig and screenshots follow

The forge's "Explain access" preset becomes "Ready (access not asked)"; the rig's `/user` range intents
move to the one-preset vocabulary; the `joining` marketing screenshot is re-captured after the change.

## Risks / Trade-offs

- [iOS's dialog appears over the joined screen instead of over the choices] → That is the intended
  order now; the joined screen already handles every access answer (capability `photo-access`,
  "Missing access never hides or blocks the event").
- [Retention is no longer stated anywhere in the app] → Accepted by the user as a product call; the
  promise stands in `event-lifetime`. Recorded in the proposal as BREAKING so a later reader sees it was
  deliberate.
- [A guest who wanted "from event start until a custom end" now picks both ends on the calendar] → The
  calendar pre-fills with the current range, so it is one extra tap on the end day.
- [Single-month calendar vs. a 30-day window spanning two months] → The dialog already navigates months;
  verify the window bound greys correctly across the boundary in its test.
- [The first-time guest no longer reads the explanation unless they ask] → The notice + the button
  label state the one fact that matters at the moment (iOS asks next); the facts remain one tap away.

## Migration Plan

UI-state only; no persisted form, no backend or membership change. `RangeForm`/`JoinPhase` are
serialized only over the rig wire, whose both ends build from the same source. Ships in one PR;
rollback is a revert.

## Open Questions

- None blocking. Exact copy of the notice and the sheet is settled in the mockups and may be polished in
  review.
