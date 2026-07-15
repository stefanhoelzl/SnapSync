## Context

The forge harness (`:app:desktop:ui:run`, module `:app:desktop:ui`) previews the real
`StatusScreen` in a phone frame and forges any UI state through `PanelController`'s stand-in cells.
`StatusContainerHost.reduceFrom` combines eight flows into a `UiState`; the forge writes five of
them (config, permission, sync, creation, download). The remaining three are unreachable from the
forge:

- **`pending`** (`MutableStateFlow<PendingJoin?>`, `StatusContainerHost.kt:134`) is **private** and
  event-driven — only the gate's own methods (`onOpenUrl → startPending → loadInto`,
  `onConfirmJoin`, `onCancelJoin`, …) write it, and they need the `loadJoinDetails`/`commitJoin`
  seams, which the forge wires inert. It reduces to `UiState.JoiningEvent` (config absent) or
  `Joined.pendingSwitch` (config present).
- **`attested`** has no forge cell at all — `StatusContainerHost`'s `attestedSource` defaults to
  `AlwaysAttested`, which the forge accepts, so the `!attested → SyncHealth.Unattested` branch is
  dead.

The full-stack harness (`:app:desktop:run`) already drives the real join gate against a world, so
these states are testable *there* — but not in the fast forge catalog, which is the tool for
reviewing every screen without a device or a world.

Separately, the `desktop-test-harness` spec's "Display-override controls" requirement describes a
**dead sync model** (`NeverSynced`/`Suspended`/`Incomplete`, estimates, "estimating…", `active`
flags) that the code replaced with the `SyncHealth` model in an earlier change that never updated
this spec.

## Goals / Non-Goals

**Goals:**
- Make all 12 currently-unreachable screens (7 `JoiningEvent` phases + 4 switch dialogs +
  `Unattested`) forgeable in `:app:desktop:ui:run` with one click each.
- Keep the harness honest: forge the *input* cells and let the real reduction produce the output —
  never fabricate a `UiState`.
- Bring the `desktop-test-harness` spec fully current (refresh the stale sync-model requirement).

**Non-Goals:**
- No production behavior change. The real join gate keeps writing the default `pending` instance;
  the full-stack harness is untouched.
- No new UI states, screens, or reduction branches — this only reaches existing ones.
- Not reworking every harness requirement — only the clearly-dead sync-model one is refreshed.

## Decisions

### Forge `pending` as an injected cell, not fabricate the `UiState`

`pending` is genuinely one of the reducer's eight input flows; `UiState.JoiningEvent`/
`Joined.pendingSwitch` are the *output*. Writing a `JoinPhase` into a `pending` cell is therefore
exactly parallel to writing a `SyncStatus` into the sync cell — the reduction still runs and still
picks full-screen vs. switch-overlay from the config cell. This satisfies the spec's standing
principle ("forging the input rather than the output is what keeps the harness honest").

*Alternative rejected — expose the phase as an output the forge sets directly*: would bypass the
one meaningful reduction branch (config presence → full-screen vs. overlay) and break the harness's
input-not-output invariant everywhere else.

*Alternative rejected — forge drives the real gate via `onOpenUrl` + controllable
`loadJoinDetails`/`commitJoin` fakes*: this is what the full-stack harness does, but it makes the
transient `Loading`/`Committing` phases flash past (needs a hold/release mechanism) and cannot land
on a specific phase in one click. The user's goal is a one-button-per-screen catalog, so direct cell
writes win.

### Seam shape: a single `MutablePendingJoinSource`, mirroring `AttestedSource`

`AttestedSource`/`MutableAttestedSource` (`AttestedSource.kt`) is the established pattern for an
injectable cell with a mutable test-side impl and a default. `pending` differs in one way: the
container must both **read and write** it (its gate methods mutate it), whereas the container only
reads `attested`. So the constructor takes a concrete `MutablePendingJoinSource` (holding the
`StateFlow<PendingJoin?>` + a `set()`), defaulting to a fresh internal instance. Production and the
full-stack harness pass nothing → the default instance → the gate's methods keep working exactly as
today. The forge injects its own instance, writes phases via `set()`, and passes the same instance to
`StatusPane` so the container reduces from it.

`PendingJoin` (currently `private` in `StatusContainerHost.kt`) becomes public so the source can
carry it. It stays in `:domain:presentation` — no module boundary is crossed.

### Container rewire is mechanical and behavior-preserving

The private `pending` field is replaced by the injected source; the ~12 read/write sites become
`pendingSource.state.value` (reads, the `combine` input, the first-frame seed) and
`pendingSource.set(...)` (writes). No control flow changes; existing `StatusContainerHostTest` and
`:test:integration` cover the gate.

### `attested` cell + presets-reset-attested

The forge injects `MutableAttestedSource` (already exists with `.set()`). Because `!attested`
outranks the sync states, an "Unattested" preset that only set the cell false would silently mask any
later sync preset. So every precondition-forcing preset (sync, not-started, join, switch) also forces
`attested = true` — the same discipline by which sync presets already force permission-granted +
config-present. This keeps each preset's intent ("show me this screen") fully realized and avoids a
stuck cell.

*Alternative rejected — a persistent Attested/Unattested toggle*: more expressive for precedence
review, but requires the operator to remember to flip it back; the one-click-per-screen goal favors a
preset + auto-reset.

### Button layout

Three new `ControlPanel` groups: **Join event (config absent)** — 7 phase buttons; **Switch
confirmation (joined)** — 4 dialog buttons (force config present + granted + settled sync);
**Attestation** — one **Unattested** button. Switch presets are explicit buttons (not "flip the
config toggle under a join preset") so each of the 12 screens is literally one click.

## Risks / Trade-offs

- **Core-code touch in `StatusContainerHost`** → The rewire is a straight substitution with no
  control-flow change; existing container and integration tests guard it. `./gradlew build` (JVM +
  offscreen UI tests) and `compileIosMainKotlinMetadata` (iOS proxy) run before merge.
- **Forged `JoinPhase` payloads could be incoherent** (e.g. switch dialog needs a *different* event
  name than the current config) → canned payloads use distinct names/`startsAt`, chosen so each
  rendered surface reads sensibly.
- **Spec-refresh scope creep** → the MODIFIED requirement is bounded to the one dead sync-model
  requirement; other requirements (invite/leave/not-started/creation) already match reality and are
  left untouched.
- **Making `PendingJoin` public widens the module's surface** → acceptable; it is a small data class
  already central to the reduction, and it stays within `:domain:presentation`.

## Open Questions

- Whether to expose `Ready` with both a future and a past `startsAt` (the cutoff selector's
  "Now"-disabled render branch differs) — resolvable at implementation time; defaulting to a past
  `startsAt` (the common case) unless a second button proves worth it.
