## Why

The three participation settings a member picks at join — the capture-date **cutoff**, the
upload/download **direction**, and the per-event **album** opt-in — are today set once and never
changeable in place: the specs make the cutoff *"immutable after join in v1"* and the album *"not a
runtime toggle"*, and `JoinEvent`'s `AlreadyJoined` guard refuses to touch the config of an event you
are already in. The only way to correct a mistake (joined download-only but meant to share; set the
cutoff too high and missed photos; wanted the album) is to **leave and re-join**, which notifies the
backend, re-enrolls, and re-runs reconciliation. `photo-selection-policy` explicitly scoped immutability
to *v1* — this change is v2: let a joined member re-open those settings and change them, in place.

## What Changes

- **New `settings | share | leave` action row** in the joined layer: a flat icon-only **settings** action
  (a gear) joins the existing share and leave actions, visible in every `Joined` health state
  (including `NeedsAccess` / `DENIED`), suppressed only while an event-switch is mid-flight.
- **A reconfigure surface**: tapping the gear opens a full-screen surface that reuses the join controls
  (the Share section's two switches + cutoff selector, and the album toggle), **pre-filled** with the
  membership's current values and a read-only event-name header, committed atomically on **Save** and
  discarded on **Cancel**.
- **In-place config rewrite** — a new `ReconfigureEvent` use-case re-saves the whole `EventConfig` with
  the changed `direction` / `minPhotoDate` / `saveToAlbum` (mirroring `EventName`'s field-only whole-object
  re-save), **without leaving**: eventId, ledger, enrollment, and device identity are all preserved, and
  the `AlreadyJoined` join path is never entered. Direction is a **local-only** gate, so nothing is sent to
  the backend.
- **Cutoff becomes mutable**, still clamped to the immutable `startsAt` floor (`max(chosen, startsAt)`).
  Lowering it widens future uploads; raising it stops future ones but does not retract already-uploaded
  photos. **BREAKING** relative to `photo-selection-policy`'s v1 immutability requirement.
- **Album opt-in becomes a forward-only runtime toggle.** ON ensures the album exists and adds photos
  synced *from now on* (no retroactive backfill); OFF stops adding but neither deletes the album nor clears
  its map entry (a later ON reuses it). **BREAKING** relative to `event-album`'s "not a runtime toggle"
  requirement.
- **Arm transitions are re-driven and actively kicked on Save**: enabling an arm schedules the upload pump /
  triggers a download reconcile immediately (rather than waiting for the OS cadence) and, when the album is
  now ON, ensures the album; disabling **downloads** cancels in-flight downloads, while in-flight **uploads**
  are left to drain (bytes are event-independent — cancelling only re-uploads identical data).

## Capabilities

### New Capabilities
- `reconfigure-membership`: A joined member re-opens and changes their participation settings (direction,
  cutoff, album opt-in) in place, without leaving. Owns the reconfigure surface, the `ReconfigureEvent`
  in-place-rewrite use-case, the arm re-drive / in-flight rules, the forward-only album semantics, the
  cutoff-preset pre-fill reconstruction, and the availability rules for the settings entry.

### Modified Capabilities
- `photo-selection-policy`: the *"cutoff SHALL be immutable after join in v1"* requirement (and its
  scenario) is replaced — the cutoff is mutable in place via `reconfigure-membership`; the `startsAt` floor
  and clamp remain immutable and unchanged.
- `event-album`: the *"album opt-in SHALL NOT be a runtime toggle; a change is a leave-and-rejoin"*
  requirement is replaced with a **forward-only runtime toggle**; the existing survive-leave / reuse-on-rejoin
  album-identity scenarios are unaffected and carry over.
- `design-system`: the flat icon-only action-component inventory (currently share + leave) gains a **settings**
  action component (label/`onClick` only, glyph-by-skin, default content tint), and the joined-layer action
  requirement grows from two actions to three.
- `sync-status-screen`: the joined layer's rendered affordances grow from *(name, QR, share, leave)* to
  include **settings**; the settings action's availability across health states is specified.

## Impact

- **`:domain`**: new `feature/membership/ReconfigureEvent.kt`; new field on `model/UserCommands.kt`; the
  live command built in `compose/SnapSyncApp.kt` (wiring the config re-save + arm re-drive + album ensure);
  a new `intent { }` method + current-settings exposure on `ui/presentation`'s `StatusContainerHost`.
- **`:ui:components`**: new `SettingsButton` flat icon action component.
- **`:ui:screens`**: `SettingsButton` added to the joined action row; a new reconfigure screen reusing the
  join sub-components (switch section, cutoff-choice, album toggle) with a read-only event-name header and
  Save/Cancel.
- **Harnesses / tests**: thread the new command + screen params through `:app:desktop`
  (`StatusPane`, panel/world inspectors) and `:app:ios` (`MainViewController`); `ReconfigureEventTest`
  (mirroring `EventNameTest` / `LeaveEventTest`); `StatusScreenTest` content-description assertions; a
  `:test:integration` test asserting `UiState` + world outcomes across a direction / cutoff / album change.
- **No backend, API, or dependency changes** — direction/cutoff/album are local-only; the config write path
  (`FileBackedConfigStore.save`) and album seams already exist.
