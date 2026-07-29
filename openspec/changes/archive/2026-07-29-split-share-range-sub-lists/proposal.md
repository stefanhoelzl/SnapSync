## Why

The join surface's capture-date range renders its **From** and **Until** handles as one continuous
recessed well with a caption dropped in the middle: the last From row ("Custom — Pick your own start.")
flows straight into the "Share until" caption with no seam, so five rows governing **two different
bounds** read as one list. The captions also sit at the same horizontal inset as the row labels, inside
the well, so "Share from" reads as a disabled first row rather than a heading for what follows.

The range is the one decision on the surface that determines which of a member's photos leave the phone.
Its two bounds should be two visibly separate objects.

## What Changes

- The range preset selector renders its two handles as **two separate grouped sub-lists**, each in its own
  recessed sub-section well, instead of two caption-separated groups sharing one well.
- Each group's caption (**"Share from"** / **"Share until"**) moves **above** its well, onto the enclosing
  card's surface at the card's own text inset — the iOS inset-grouped-list idiom, aligned with the
  section's existing note and value lines — and steps down one type level so it subordinates to the row
  labels it heads.
- Each caption carries **heading** semantics, so assistive technology can jump between the From and Until
  groups — the navigation the visual split creates.
- The selector **owns both wells**: the two screens that compose it (join and reconfigure) drop their own
  sub-section wrapper. Its public signature is unchanged — same parameters, still appearance-free, still
  no card of its own.
- Each well carries a test tag, so the grouping is pinned structurally rather than by eye.

No behavior changes: the same rows, the same selection callbacks, the same window-constrained pickers with
the same commit-on-confirm-only semantics, the same defaults and clamping, the same single bold statement
of the resolved range above the groups.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `design-system`: the cutoff-preset selector requirement — the two handles render as two captioned,
  grouped sub-lists (each in a recessed well, caption above it, marked as a heading) rather than as one
  run of stacked rows; and the switch-header-section requirement — a section may hold **more than one**
  recessed sub-section well, so its wording no longer implies exactly one per section.

## Impact

- `:ui:components` — `AppCutoffSection.kt` (`AppRangePresetChoices`: two wells + relocated captions +
  heading semantics + group tags); `AppToggleSection.kt` (`AppSubSection` doc — it now recesses *a* group
  of second-level rows, of which a section may have several). `AppSubSection` stays public and keeps its
  signature; it simply moves from being called by the screens to being called by the selector.
- `:ui:screens` — `StatusScreen.kt`, both call sites (`ReadyLayout` join surface and `ReconfigureScreen`)
  drop their `AppSubSection { … }` wrapper and its import.
- Tests — `AppRangePresetChoicesTest` gains containment assertions over the two tagged groups; existing
  row-tag tests are unaffected (row tags are unchanged).
- **Not** affected: `join-event` (defaults, phase derivation, clamping and the "never seeded at mount"
  requirement are untouched), `photo-selection-policy`, `reconfigure-membership` (it keeps reusing the
  same controls — both surfaces change together, which is what preserves "one decision surface"),
  `join-share-count`.
- Deliberately **out of scope**: refreshing `screenshots/*.png`. Those raws already predate the range
  selector entirely (they show the superseded single-cutoff UI), so they are stale either way; refreshing
  them is its own change with its own App Store listing consequence.
