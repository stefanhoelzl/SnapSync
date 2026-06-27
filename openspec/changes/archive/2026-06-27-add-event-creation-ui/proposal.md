## Why

The backend can now mint events (`POST /event`, the archived `add-event-creation` change), but the
app still has no way to *start* one — a user can only join an event someone else handed them via QR.
This is the deliberate follow-up that change called out: the on-device UI to name an event and
auto-join it. It reverses the v1 "contribute-only, no in-app creation" framing (`docs/design.md §2`):
the app now creates events, not just joins them.

## What Changes

- **New create-event landing screen** — when no event is connected (`config == null`), the app's
  first screen is a name input + **Create** button, plus a passive "scan a QR to join" hint. This
  **replaces the setup gate** as the not-connected surface.
- **Create = mint, then provision-like-a-QR** — tapping Create calls `POST /event { name }`; on
  `201` the returned `eventId` is funneled into the **existing** provision path (`onProvision` +
  `ConfigStore.save`), so the app auto-joins exactly as a scanned QR would. No new join path.
- **Permission is irrelevant to create** — create never inspects photo permission. Once config flips
  non-null, the **existing** downstream reduction takes over unchanged: a missing permission surfaces
  as the `PermissionBlocked` warning the app already shows post-join; a granted one flows into
  `Joining` → reconcile → status hero. Create-users and QR-scanners converge on one path.
- **New client seams** — an `EventCreator` command port (`fun create(name)`, fire-and-forget, like
  `PermissionRequester`) and a `CreationStatusSource` state port (`creationStatus: StateFlow`, like
  `EventStatusSource`), with `CreationStatus = Idle | InFlight | Failed(reason)`. The reduction stays
  a pure projection of seam values.
- **New `AppTextField`** in the design system — the app's first text input (appearance-free
  signature, per the design-system containment rule).
- **Inline error on the create screen** — create failures render there (`400` → "That name isn't
  valid", `502`/network → "Couldn't reach the server"; sticky, cleared on retry/edit). The
  invalid-deeplink transient error (formerly on the setup gate's storage card) **moves here** too.
- **BREAKING (internal): the setup gate is retired** — `config == null` no longer reduces to
  `UiState.Setup`; the two-card gate is gone. Its storage card is superseded by create; its
  permission step is already handled post-config by `PermissionBlocked`; its deeplink intent and
  invalid-link error move to the create screen.
- **Desktop harness** forges the new `CreationStatus` states and fakes the two new seams.

## Capabilities

### New Capabilities
- `event-creation-ui`: the app-side create surface — the create-event landing screen, the
  `EventCreator` / `CreationStatusSource` seams and `CreationStatus` model, the create→provision
  use-case (mint then `onProvision`+`save`), the `config == null → create layer` reduction rung
  (top of the precedence chain), and the create screen's ownership of the `onOpenUrl` deeplink intent
  and the (shared) inline error surface for create failures and invalid links.

### Modified Capabilities
- `setup-gate`: **removed** — fully superseded. `config == null` reduces to the create layer, not
  `UiState.Setup`; the two checkable cards, the standing permission step, and the storage-card
  deeplink/invalid-link handling are deleted (relocated per the new capability and the existing
  `PermissionBlocked` path). (Applied by deleting `openspec/specs/setup-gate/` directly at archive
  time: the CLI cannot empty-then-delete a whole capability — removing every requirement fails the
  "spec must have ≥1 requirement" rule — so whole-capability removal is a manual delete.)
- `design-system`: add the `AppTextField` semantic component (appearance-free input).
- `sync-status-screen`: the config-absent state now renders the create screen, not the setup gate;
  the post-config `PermissionBlocked` / join / hero precedence is otherwise unchanged.
- `desktop-test-harness`: the control panel can forge the create states (`Idle` / `InFlight` /
  `Failed`) and inject a fake `EventCreator` / `CreationStatusSource`.

## Impact

- **Code**: new `:capability:event-creation-ui` module (seams, `CreationStatus`, create use-case,
  HTTP `EventCreator` over an injected Ktor `HttpClient` mirroring `HttpEventFilesSource`,
  `commonTest` with `MockEngine`); new `AppTextField` in `:domain:ui:components`; `:domain:ui` create
  screen; `:domain:presentation` container gains `onCreateEvent(name)` + the new reduction rung;
  `:app:desktop` harness control panel; `:app:ios` `SnapSyncRoot` wires the real `EventCreator`
  (host from Info.plist `BackgroundUploadURLBase`, as rejoin does) into the existing provision path.
- **Backend**: none — `POST /event` already exists and is open (no auth).
- **Docs**: `docs/design.md §2` corrected (in-app creation now exists; setup gate replaced by the
  create landing screen).
- **Removed**: `openspec/specs/setup-gate/` (retired on archive of this change).
