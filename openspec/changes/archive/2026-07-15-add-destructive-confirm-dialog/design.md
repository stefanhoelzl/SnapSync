## Context

The design system (`:domain:ui:components`) is deliberately semantic: `App*` components carry
data/meaning only, never appearance, and **emphasis is a design-time choice expressed as a distinct
component** (`PrimaryButton`, not `AppButton(role=…)`) — codified in the `design-system` spec's
"Runtime-data variants use sealed semantic values" requirement. `AppConfirmDialog` owns its two buttons
internally (label + callback in, no button composable passed by the screen), rendering the confirm as a
green filled `PrimaryButton`. The leave affordance on the status screen is a flat, icon-only component
(`LeaveButton`, a `Logout` glyph). `AppTheme` intentionally leaves `colorScheme.error` at the Material 3
default, with the comment *"Destructive actions keep the Material error red by convention."*

Today nothing uses that red: the corner leave glyph and every confirm button (Leave, Switch, Retry, OK)
render neutral/brand-green. This change makes the two genuinely destructive surfaces read as destructive.

## Goals / Non-Goals

**Goals:**

- The flat leave icon renders in the error accent.
- Destructive confirmations (Leave, and the switch dialog's Ready-phase "Switch") render their confirm
  button filled with the error accent.
- Do so **without** introducing any appearance parameter on an `App*` signature, and without adding a
  custom color to `AppTheme` (reuse the M3 `error` role the theme comment already blesses).

**Non-Goals:**

- No new bespoke `DestructiveRed` in the color scheme — `colorScheme.error` (Material default) is the role.
- No change to behavior, state reduction, UI-state, or any endpoint. This is skin + contract only.
- Not making the switch dialog's non-destructive phases (invalid-invite "OK", load/commit "Retry") red —
  those dismiss or re-attempt; they are not destructive.
- No new public button component. The destructive filled button has no screen-level caller today and
  stays an internal skin helper.

## Decisions

### Decision 1: A distinct `AppDestructiveConfirmDialog`, not a `destructive: Boolean` flag on `AppConfirmDialog`

Every call site knows *statically* whether a confirmation is destructive — the leave dialog is born
destructive, the retry dialog is born safe; nothing at runtime flips one into the other. The
`design-system` spec's variant rule is explicit: **design-time variant axes SHALL be distinct
components** (runtime-data-driven ones become sealed value params). A boolean `destructive` flag is
exactly the `AppButton(role=…)` shape the rule outlaws, just moved up one nesting level onto the
container that hosts the button.

**Alternative considered — `AppConfirmDialog(…, destructive: Boolean = false)`:** defensible by analogy
to the existing `enabled` param ("meaning, not appearance"). Rejected because `enabled` earns its
param-hood by being genuinely *runtime*-driven (a button greys out from state), whereas `destructive` is
always static per call site — so the very thing that licenses `enabled` as a param is absent here.

The duplication objection that usually favors a flag is neutralized: both dialogs delegate to a private
`ConfirmDialogScaffold` (owns the modal scrim, the side-by-side `Row`, the outlined `SecondaryButton`
cancel on the left) and differ only in the confirm composable passed in. Two public components, one body,
zero copy-paste. The two public signatures are **identical** to each other — destructiveness is carried
purely by which component the screen picks.

### Decision 2: The destructive filled button is an internal skin helper, not public `App*` inventory

The only destructive filled button in the app lives *inside* the dialog scaffold. Screens compose
`PrimaryButton`/`SecondaryButton` directly, but never a filled *destructive* button — the status
screen's destructive affordance is the flat `LeaveButton` **icon**, and screens never pass a button into
a dialog (that's the point of the label+callback design). With no screen caller, promoting it to the
public inventory would add contract surface for no consumer. It stays private; it can graduate to a
public `App*` component under the same demand-driven rule if a screen ever needs a bare destructive
button (itself a questionable pattern — destructive actions here route through confirmations).

### Decision 3: The red is the M3 `error` role, pinned in the spec but folded into existing requirements

The two color facts (leave glyph → error tint; destructive confirm → filled error) touch no signature.
The spec already sets precedent for pinning skin-local color as a requirement — "Status accents unified
on the brand primary." We record these the same way, but **folded into the existing requirements** next
to their components (the flat-icon requirement gains the leave-tint scenario; the semantic-components
requirement gains `AppDestructiveConfirmDialog` and its filled-error confirm), rather than as a new
standalone "destructive accent" requirement. The role is named `error` (not a hex), consistent with how
"brand primary" is named without a value, and with `AppTheme`'s existing convention comment — so
Material owns the exact red and a future skin swap keeps the semantic.

### Decision 4: Only the two truly-destructive call sites move

`StatusScreen.kt` leave dialog (`:120`) and the switch dialog's **Ready** phase (`:440`, confirm
"Switch" — it leaves the current event) switch to `AppDestructiveConfirmDialog`. The switch dialog's
`NotFound` ("OK"), `LoadFailed`/`CommitFailed` ("Retry") stay on `AppConfirmDialog` — they dismiss or
re-attempt, so red would misrepresent them.

## Risks / Trade-offs

- **A permanently-red leave glyph could read as an error state rather than a neutral affordance** → The
  user explicitly chose the red icon; iOS convention accepts destructive tinting on leave/delete
  affordances, and the confirmation (the point of no return) carries the stronger filled-red weight.
- **Two dialog components could drift** → Mitigated by the shared private `ConfirmDialogScaffold`: the
  only difference is the confirm button, so scrim/layout/cancel can never diverge.
- **`sync-status-screen` overlap** → Verified none: that spec owns *which* affordances render from *what*
  state, never appearance; the confirm dialog is screen-local (never enters UiState) and below its
  altitude. No cross-reference needed.
