## Why

`UiState` calls itself the screen's *"display-ready projection"*, but `StatusScreen` takes five more
data inputs beside it, and the join and reconfigure surfaces hold seven more values of their own. So
the screen is a function of the state **plus** whatever each call site remembers to pass — and when a
call site forgets, nothing says so.

That is not hypothetical. `app/desktop/.../StatusPane.kt` — the one call site shared by **both**
desktop harnesses — names seven of the eight data parameters and omits `transientError`. The harness
therefore cannot render the invalid-link banner at all. No test fails, no build breaks, and the
omission is invisible in a named-argument list that otherwise looks complete.

Each of the five params was justified by mirroring the one before it, back to a single decision
(`event-invite-qr` D3) whose two stated premises are now both false: it reasoned that the reduction
"stays the pure sync projection" and that the config seam sits outside it, while `reduceFrom` today
takes eight inputs and already folds `config.startsAt` and `config.endsAt` into `UiState`. D3 named no
expiry trigger, so the erosion went unnoticed and three later decisions were built on top of it.

## What Changes

The rule this change adopts: **what the screen SHOWS is `UiState`; how it DRAWS is local.** Named
exceptions — in-progress text content (a per-keystroke round trip fights the IME) and `:ui:components`
owning its own popups. The rule is mechanically gateable, and that is the point: with the form left in
the view, any gate would have to exempt the largest state holder in the module and would assert
nothing.

- Fold five data parameters into `UiState`: `membership` (non-null on `Joined`, which already *means*
  config-present), `inviteUrl` (precomputed, one derivation), `transientError` (coalesced into
  `CreateEvent.error`, exactly as the render site already does), `renameStatus`. `eventName` is
  **deleted** rather than folded — the heading reads `membership.name`, joining the two sites that
  already do.
- `photoPermission`, `cutoff` and `shareableCount` stay parameters. `photoPermission` is never
  rendered — it is a recompute key for the count query — and `join-share-count` has an explicit SHALL
  that the count be a *query* parameterised by the candidate cutoff, not a reduction of committed
  state.
- Collapse the join phases: four `JoinPhase` variants declare the same four event facts (16
  declarations) purely because a retry commits without passing back through the loaded phase.
  `Detailed(event, step)` states them once and keeps "Ready implies details" unrepresentable-otherwise.
- Lift the join/reconfigure form — the seven remembered choices — into the container, where the
  already-pure resolvers can run against it. The count becomes a sibling flow keyed on the form, so
  both `cutoff` and `shareableCount` leave the screen's signature.
- Add unit tests for `resolveFrom` / `resolveUntil` / `directionOf` / `nowWithinWindow`. They are
  already pure functions with **zero** direct tests; range inversion is caught today only by a Compose
  UI test.

**This proposal reverses standing decisions.** `event-invite-qr` D3, `reconfigure-membership` D4's
parameter clause, and `event-rename`'s screen-level placement are amended, not quietly contradicted.
Two spec defects found while establishing this are corrected in passing: `sync-status-screen` cites
"the config capability", which does not exist, and its "no event history" sentence lost the clause
that explained it; `join-share-count` describes a direction-parameterised query that the code does not
have.

No user-visible behavior changes. Changelog label: `internal`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-status-screen`: `UiState` carries the membership, the invite URL and the rename status; the
  join form and the reconfigure surface selection become reduced state; the "supplied as parameters"
  requirement and the rename requirement are replaced; the truncated "no event history" rationale is
  restored.
- `event-invite-qr`: the invite URL enters `UiState` as a reduced field rather than a screen
  parameter; the single-derivation guarantee is restated as the property that survives.
- `event-creation-ui`: the transient invalid-link error is carried by the create state rather than
  beside it, with the transient-wins precedence stated rather than left to a render site.
- `reconfigure-membership`: the surface's pre-fill comes from reduced state; opening and closing
  remain client-side navigation touching no port.
- `join-share-count`: the count is reduced into `UiState` from a form-keyed query; the spec's
  direction-parameterised query and its "counts zero" scenario are corrected to the render-site gate
  that actually exists.
- `event-rename`: the rename status is reduced state rather than a screen-level value, and its failure
  copy is formatted by the reduction rather than by a composable.
- `architecture-guards`: two guards are added — the screen-state gate that makes the rule above
  mechanical, and the event-name limit gate that holds the client's cap against the backend's.
- `desktop-test-harness`: the forge panel gains a rejected-event-link action, so the invalid-link message
  is reviewable at all — it previously was not, in either harness.

## Impact

- `:ui:presentation` — `UiState`, `JoinPhase`, `StatusContainerHost` (the reduction gains the form and
  count inputs; its constructor must be bundled first, at 14 parameters against a core-tier ceiling of
  15).
- `:ui:screens` — `StatusScreen` reduces to `(state, actions)`; `RangeSelection`, `ParticipationSections`,
  `JoinPhaseWindow`, `StatusOverlayState` are relocated or retired.
- `:ui:components` — `formatRange` moves here beside `appDateTimeLabel`; it is already pure.
- `:domain` — `RenameStatus` gains `@Serializable`.
- Call sites: `app/ios` `MainViewController`, `app/ios/forge` `ForgeViewController`, `app/desktop`
  `StatusPane` (where the omission is fixed by deletion).
- Tests: `StatusContainerHostTest` grows; `JoinScreenTest` and `StatusScreenTest` shed logic-through-UI
  cases in favour of unit tests on the resolvers.
