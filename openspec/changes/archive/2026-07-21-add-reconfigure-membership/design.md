## Context

A membership's three participation settings — `direction`, `minPhotoDate` (cutoff), and `saveToAlbum`
— live on the per-event `EventConfig`, persisted whole in the App-Group `eventconfig.json`
(`FileBackedConfigStore`). They are chosen once on the join surface and, by current contract, never
changed in place: `photo-selection-policy` makes the cutoff *"immutable after join in v1"*, `event-album`
makes the album *"not a runtime toggle"*, and `JoinEvent.join` short-circuits `AlreadyJoined` on the first
line when the scanned event equals the current one — so re-scanning your own QR cannot rewrite config. The
only correction path is leave-then-rejoin.

Three measured facts (from codebase investigation) shape this design:
- **`direction` is local-only.** Enrollment (`DeviceEnroller` → `HttpEnrollment` PUT) carries a
  register-only empty manifest with no direction; the download filter is entirely client-side
  (`DownloadController.reconcile` fetches the full union, then gates locally on `includesDownload`); the
  upload gate reads direction into a `Contribution` per cycle. A direction flip touches nothing on the
  backend.
- **The album has no backfill.** Both add paths (`UploadCycle.placeInAlbum` for this cycle's completions;
  the importer's in-change-block add for downloads) only ever touch the item being synced now. There is no
  code that gathers already-synced photos, and `AlbumCoordinator` has no delete.
- **Config is read fresh each cycle** (`UploadCore.readGate` → `ConfigReader.read()`;
  `DownloadController` reads `includesDownload` live), so a config change is picked up on the next cycle for
  *new* work — but in-flight jobs are deliberately not cancelled by the gate.

The write path already exists and is one-writer-safe: `EventName.storeEventNameIfChanged` reads the current
config, guards the eventId still matches, and does `store.save(current.copy(name = …))`. This change adds a
fourth membership writer that does the same for the three participation fields.

## Goals / Non-Goals

**Goals:**
- Let a joined member re-open and change `direction`, `minPhotoDate`, and `saveToAlbum` in place, without
  leaving, preserving eventId / ledger / enrollment / device identity.
- Reuse the join controls verbatim (Share section switches + cutoff selector, album toggle), pre-filled
  with current values, committed atomically on Save.
- Re-drive and actively kick the affected arms on Save, so a switch flip has immediate visible effect
  rather than waiting for the OS upload cadence.
- Add a third joined-layer action (settings) beside share and leave.

**Non-Goals:**
- Editing the event **name** (backend-owned; no rename path exists).
- Retroactive album backfill of already-synced photos.
- Retracting already-shared or already-downloaded photos when a setting narrows scope.
- Any backend / API / wire-payload change; any change to the `startsAt` floor or the clamp.
- A new marketing/forge screenshot state for the reconfigure surface.
- Changing the leave-then-rejoin path (it remains valid; reconfigure is an additional path, not a
  replacement).

## Decisions

### D1 — In-place config rewrite, not leave-then-rejoin
`ReconfigureEvent` (new, in `feature/membership`) reads the current config, guards the eventId, and
`store.save(current.copy(direction = …, minPhotoDate = clampToFloor(chosen, startsAt), saveToAlbum = …))`.
It never enters `JoinEvent`, so the `AlreadyJoined` guard and the enrollment path are untouched, and the
real asset manifest is never clobbered to empty. **Alternative rejected:** present "edit" but internally
leave-then-rejoin — it fights `AlreadyJoined` (leave clears config first, so the re-join proceeds and
re-enrolls with an empty manifest, re-runs reconciliation, and notifies the backend of a leave that didn't
happen). In-place is strictly less machinery and matches the existing `EventName` precedent.

### D2 — New `reconfigure-membership` capability, with deltas to the specs that forbade mutation
The in-place edit is its own capability. The immutability language is normative in **two** neighbor specs
(`photo-selection-policy`, `event-album`) and both get MODIFIED deltas. `join-event` carries **no**
immutability SHALL (it owns the *join* surface and the `startsAt` clamp, both unchanged), so it needs no
delta — accounted for explicitly. `design-system` and `sync-status-screen` get deltas for the third action.
**Alternative rejected:** fold reconfigure into `join-event` — it would blur "joining" with "editing an
existing membership" and force join-event to own a surface that isn't a join.

### D3 — Reuse join controls, pre-filled, explicit Save (atomic single write)
The reconfigure surface composes the *same* design-system sub-components the join surface uses (the
switch-section, the cutoff-choice control, the album toggle). One `store.save` on Save. **Alternative
rejected:** immediate per-toggle apply — multiple partial writes, and an upload/download cycle could fire
between two toggles observing a half-applied config.

### D4 — The surface is local Compose navigation, pre-filled from the config StateFlow (no new UiState family)
Opening the settings surface touches no port, so it is client-side navigation — a local `remember`
flag in the joined layer, exactly like the existing `confirmingLeave` local dialog state. It does **not**
add a `UiState.Reconfiguring` member. Pre-fill reads the current `EventConfig` from the
`config: StateFlow<EventConfig?>` the `StatusContainerHost` already holds (the same source the invite URL
derives from), surfaced to the screen as a parameter — mirroring how the invite URL is a screen parameter,
not reduced state. Save fires a new command; Cancel flips the local flag back. **Alternative rejected:** a
new `UiState`/`JoinPhase`-style family — it would route screen-open through a flow command for a pure
navigation act, contradicting "reads don't cross flow".

### D5 — Cutoff pre-fill reconstruction is lossy by construction
The join UI's three cutoff presets (Now / Event start / Custom) are **not** persisted — only the resulting
`minPhotoDate` timestamp is. On reopen the selector is seeded: `minPhotoDate == startsAt` → **Event start**;
otherwise → **Custom** with that timestamp. The original **Now** choice cannot be reconstructed (it was a
wall-clock timestamp at join). This is expected behavior, specified so it is not read as a bug. The floor
still clamps whatever the user picks.

### D6 — On Save, re-drive the provision-side effects and actively kick newly-enabled arms
A config re-save alone would only take effect on the OS's next scheduled cycle (uploads can't be forced;
downloads have a backstop). So the reconfigure command, after the save, re-drives the same effects join's
provisioning does: ensure the album when `saveToAlbum` is now true; arm the upload producer per the new
`direction.includesUpload` + current permission and **schedule** the upload pump; trigger a
**download reconcile** when `direction.includesDownload` is now true. The command is built only in
`compose/SnapSyncApp.kt`, wiring the existing seams (`AlbumCoordinator.ensureAlbum`, the upload-arm
provision/stop, `DownloadController`).

### D7 — In-flight jobs: uploads drain, downloads cancel
When a flip **disables** an arm: in-flight **uploads** are left to drain — the byte URL is
device-partitioned and event-independent, so an upload in flight stays valid and cancelling it only
re-uploads identical bytes (the codebase's existing rationale on `UploadArm`). In-flight **downloads** are
cancelled via `DownloadController.onLeaveOrSwitch` (→ `jobs.cancelAll()`), so foreign photos stop arriving
after the user turns "receive" off. New work in both directions is gated off by the next cycle's fresh
config read regardless.

### D8 — Album is a forward-only toggle; OFF never deletes
ON ensures the album (`ensureAlbum`, reusing the stored id if it still resolves) and adds photos synced
from Save onward. OFF makes `albumIdFor` return null and the `UploadCycle` album guard false — additions
stop, but the album and its `eventId → albumLocalId` map entry persist (there is no delete seam, and even
leave doesn't clear the map). A later ON reuses the same album. Helper text on the album row states that
already-synced photos are not gathered.

### D9 — Availability: whenever Joined, suppressed mid-switch
The settings action renders whenever the joined layer renders (any `SyncHealth`, including `NeedsAccess`
/ `DENIED`) — a download-only or access-blocked member is exactly who most needs to adjust participation,
and enabling "share" without access simply does nothing until access is granted (same as join). It is
suppressed while an event-switch is in flight (`pendingSwitch`) to avoid concurrent config writes.

### D10 — Naming: avoid the `openSettings` collision
The existing `UserCommands.openSettings` / `onOpenSettings` opens the iOS **system** Settings page (the
DENIED permission affordance). The new command and screen callback take a distinct name (e.g.
`reconfigure` / `onOpenEventSettings`) so the two never blur.

## Risks / Trade-offs

- **Lowering the cutoff re-widens upload scope** (the exact hazard the required-cutoff design guards
  against) → it is the member's explicit, floored (never below `startsAt`) choice, surfaced with helper
  text; admits-on-doubt still filters origin exclusions, and the ledger prevents re-upload of already-sent
  photos.
- **Album ON after join looks incomplete** — already-synced photos aren't in the new album → helper text
  states forward-only; retroactive backfill is a deliberate non-goal (it would need enumerating synced
  assets, risking the LIMITED-access autonomous-read landmine).
- **A fourth config writer** (`ReconfigureEvent`) joins join/leave/name → each guards the eventId still
  matches before saving, and the UI serialises reconfigure against switch (D9), preserving one-writer
  discipline within `feature/membership`.
- **Cutoff preset can't round-trip "Now"** → specified (D5), not a defect.
- **Save re-arming races an in-flight OS cycle** → config is read whole each cycle and written whole, so a
  cycle observes either the old or the new config, never a torn one; re-arm is idempotent.

## Migration Plan

No data migration: `EventConfig` and its store are unchanged; existing memberships gain an editable surface
with no schema change. The change is purely additive to the app (a new action + surface + command +
use-case) plus the two immutability-requirement replacements. Rollback is removal of the action/surface;
existing configs remain valid either way.

## Open Questions

None outstanding — the interview settled scope (direction, cutoff, album; not name), mechanism (in-place),
edit model (reuse controls + Save), warnings (inline helper text), album ON (forward-only), in-flight
(uploads drain / downloads cancel), spec home (new capability), presentation (full-screen), and
availability (whenever joined).
