## Why

The forge harness (`:app:desktop:ui:run`) is meant to be a one-click visual catalog of every
status-screen state, but 12 screens are unreachable from its control panel: the entire join gate
(`UiState.JoiningEvent` × 7 `JoinPhase`s), the switch-confirmation overlay (`Joined.pendingSwitch`
× 4 dialogs), and `SyncHealth.Unattested`. These states do not derive from the fact-cells the forge
writes (config, permission, sync, creation, download): the join/switch states live in the
container's private, event-driven `pending` flow (only the gate's own methods mutate it, and the
forge wires those seams inert), and `attested` has no forge cell at all — it rides the
`AlwaysAttested` default. So the join UI can only be reviewed against the full-stack world harness,
never in the fast forge catalog.

## What Changes

- Add two forgeable input cells to the forge so the **real reduction** still produces every output
  (we forge inputs, never fabricate a `UiState`):
  - **`pending`** — expose the container's join/switch overlay state as an injected
    `MutablePendingJoinSource` (mirroring the existing `AttestedSource` pattern); the forge writes a
    chosen `JoinPhase` into it. The same cell drives full-screen `JoiningEvent` and the switch
    overlay — the reducer picks by reading the already-forgeable config cell.
  - **`attested`** — inject a `MutableAttestedSource` into the forge (the type already exists) so
    `SyncHealth.Unattested` becomes reachable.
- Add three control-panel preset groups: **Join event** (7 phase buttons, config absent),
  **Switch confirmation** (4 dialog buttons, config present), and an **Unattested** button. To avoid
  a stuck-cell trap (`!attested` outranks the sync states), every other precondition-forcing preset
  also forces `attested = true`, the same discipline by which sync presets already force
  permission-granted + config-present.
- Refresh the harness spec's stale **Display-override controls** requirement, which still describes a
  dead sync model (`NeverSynced`/`Suspended`/`Incomplete`, estimates, "estimating…", `active`
  flags), so it matches the current `SyncHealth` model the code already implements.

## Capabilities

### New Capabilities
<!-- None: this extends an existing capability. -->

### Modified Capabilities
- `desktop-test-harness`: ADD requirements for join-gate presets, switch-confirmation presets, and
  an unattested preset (with the presets-reset-attested rule); MODIFY the existing Display-override
  controls requirement to the current `SyncHealth` sync model.

## Impact

- `:domain:presentation` — new `MutablePendingJoinSource`; `PendingJoin` made public;
  `StatusContainerHost` converts its private `pending` `MutableStateFlow` to the injected source
  (~12 mechanical read/write sites, behavior-identical), and gains constructor params for both new
  cells (defaulted so production and the full-stack harness are unchanged).
- `:app:desktop` — `StatusPane` gains `attestedSource` and pending-source params (defaulted).
- `:app:desktop:ui` — `PanelController` gains the two new cells and preset methods; `ControlPanel`
  gains the three new button groups.
- Non-goal: no production behavior change — the real join gate keeps writing the default `pending`
  instance; the full-stack harness (`:app:desktop:run`) is untouched.
