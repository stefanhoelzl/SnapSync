# Design

## Context

The text prompt sheet is a Material 3 `ModalBottomSheet`. Its busy rule lives only in the sheet's `onDismissRequest`
and its cancel button, both of which ignore the request while busy. Material 3 (Compose Multiplatform 1.9's
`ModalBottomSheet.kt`) asks `onDismissRequest` only **after** the sheet has already moved:

- **Swipe down** settles the sheet's state to hidden, then calls `onDismissRequest` if it is no longer visible.
- **Tap outside** (the scrim) asks the sheet state's `confirmValueChange(Hidden)`, then hides the sheet, then calls
  `onDismissRequest`.
- **Back** (the dialog's own dismiss, Android's back gesture) hides the sheet unconditionally, then calls
  `onDismissRequest`.

So a refusal in `onDismissRequest` comes too late: the sheet is off the screen while the app still holds it open.
Found while taking `:ui:components` to zero missed coverage (phase L8), by a test that swiped a busy sheet and found
the dismiss refused but the sheet no longer displayed.

## Goals / Non-Goals

**Goals:**
- While busy, no route moves the sheet; while idle, every route closes it as today.

**Non-Goals:**
- The other sheets (QR, info, event settings): none has a running action.
- How the sheet shows that it is working.

## Decisions

- **Refuse at the sheet's state, not after it.** The sheet state is created with a `confirmValueChange` that vetoes
  `Hidden` while busy. That stops the swipe (settling consults it) and the tap outside (the scrim asks it first). The
  busy value is read through `rememberUpdatedState`, because the state remembers its callback once.
- **Back needs its own refusal.** The back path calls `hide()` without asking `confirmValueChange`, so the sheet's
  properties set `shouldDismissOnBackPress = !busy` (and `shouldDismissOnClickOutside = !busy`, so the scrim does
  not even react). Alternative considered: re-opening the sheet after an unwanted hide — rejected, it visibly
  flickers and races the request's result.
- **One idle-only dismissal stays.** The existing idle-only handler remains the sheet's `onDismissRequest` and its
  cancel action, so the idle behaviour is untouched.

## Risks / Trade-offs

- [The veto depends on Material 3's dismissal order, which a library bump could change] → The tests assert the
  outcome on each route (the sheet still displayed after a swipe and after a tap outside while busy), so a bump that
  breaks it fails the build. Android's back gesture is not reachable from the desktop test renderer; the
  `shouldDismissOnBackPress` property is the platform's own contract for it.
