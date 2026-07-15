## Why

Leaving an event is irreversible (a leave-then-rejoin re-runs the whole join), yet the affordance and
its confirmation are styled identically to benign actions — the corner leave glyph and the "Leave" /
"Switch" confirm buttons are the neutral/brand-green treatment. Destructive actions should read as
destructive so a member can tell the point of no return from an ordinary tap.

## What Changes

- The flat leave **icon** action renders in the **error accent** (still flat, no background, no
  appearance parameter) instead of the default neutral tint.
- A new `AppDestructiveConfirmDialog` semantic component joins the design system — signature-identical
  to `AppConfirmDialog`, but its confirm button is **filled with the error accent** (cancel stays the
  outlined secondary). Emphasis is carried by *which component the call site picks*, never by a
  parameter — consistent with the design system's rule that design-time variants are distinct
  components, not appearance flags.
- The status screen's two **destructive confirmations** switch to it: the plain **Leave** dialog and
  the **switch** dialog's **Ready-phase "Switch"** confirmation (which leaves the current event). The
  switch dialog's non-destructive phases (invalid-invite "OK", load/commit "Retry") stay on
  `AppConfirmDialog`.

No behavior, state reduction, or API changes — this is a design-system skin/contract change only.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `design-system`: adds `AppDestructiveConfirmDialog` to the semantic-component inventory (its confirm
  button filled with the error accent); extends the flat icon action requirement so the leave icon is
  rendered in the error accent. Both are skin-local color facts pinned in the spec (mirroring the
  existing "Status accents unified on the brand primary" requirement) and introduce **no** appearance
  parameter on any `App*` signature.

## Impact

- `:domain:ui:components` — new public `AppDestructiveConfirmDialog`; new **internal** skin helpers
  (a private `ConfirmDialogScaffold` shared by both dialogs, a private destructive filled button);
  `LeaveButton` glyph tinted `colorScheme.error`. No new color in `AppTheme` — reuses Material 3's
  default `error` role (per the existing `AppTheme` convention comment).
- `:domain:ui` — `StatusScreen.kt` swaps the Leave dialog and the switch Ready-phase dialog to
  `AppDestructiveConfirmDialog`.
- `sync-status-screen` spec — unaffected (it owns *which* affordances render from *what* state, never
  their appearance; the confirm dialog is screen-local and below its altitude).
- Tests: `:domain:ui:jvmTest` (offscreen Compose render) for the new component and the swapped call
  sites; `:test:architecture` Material-3-containment guard continues to hold (all M3 stays in
  `:domain:ui:components`).
