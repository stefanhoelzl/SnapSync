## Context

The backend can mint events (`POST /event`, open/no-auth, returns `{ eventId, name, createdAt }`;
the archived `add-event-creation` change). The app cannot start one — `config == null` reduces to the
two-card **setup gate** (`setup-gate`), whose storage card is a passive "scan a QR" instruction. The
whole app is built on one invariant, stated in `setup-gate`: every `UiState` is a **pure projection
of seam values** — `f(config, permission, eventStatus, snapshot)` — which is what lets the desktop
harness forge any state and tests assert exact text.

This change makes the app create events in-app, reversing `docs/design.md §2` ("no event creation
in-app"). The join half already exists: the deeplink path in `SnapSyncRoot.onOpenUrl` is
`decode → joinEvent.onProvision(prev, id) → store.save(config)`, after which the permission-grant
collector runs `ensureJoined` (reconcile). So creating is just **mint an eventId, then provision it
exactly like a scanned QR**.

## Goals / Non-Goals

**Goals:**
- A create-event landing screen (name → Create) that auto-joins on success, reusing the existing
  provision + reconcile machinery with no new join path.
- Preserve the pure-reduction invariant: the create flow's async states become observable seam
  values, not transient container state.
- Converge create-users and QR-scanners on one downstream path (permission, join, sync) so there is
  no create-specific permission handling.

**Non-Goals:**
- QR generation / sharing the created event (separate workspace).
- Any backend change (`POST /event` already exists).
- A start-date or any field beyond `name` (deeplink stays v3 `{ eventId }`; event start is the
  server's `createdAt`, unchanged).
- Creating a second event while joined (create is reachable only when `config == null`; switching
  events is still leave-then-create/scan).

## Decisions

### D1 — Create is "mint, then provision-like-a-QR" (reuse the join path)
On `201`, the use-case feeds the returned `eventId` into the **same** `onProvision` + `ConfigStore.save`
path the deeplink uses. The config flip drives the existing `Joining → reconcile → hero`. A freshly
created event has no stored objects, so the reconcile seeds nothing and the creator's in-scope photos
upload normally. *Alternative — a bespoke create→join path — rejected:* it would duplicate switch-reset
and reconcile logic that already exists and is tested.

### D2 — Permission is irrelevant to create (no gate on submit)
Create never inspects `PermissionStatus`. It mints + provisions unconditionally; once `config != null`
the existing reduction surfaces `PermissionBlocked` if permission is not `GRANTED` — the identical
warning a QR-scanner-who-hasn't-granted already sees (`NOT_DETERMINED` → "Allow access" priming,
`DENIED` → "Open Settings"). *Alternative — gate the create on permission with resume-after-grant —
rejected:* it forced a `NeedsPermission` state, CTA-priming gymnastics, and surprising auto-create on
the Settings round-trip, all to special-case what the post-config path already handles. An event
existing on the backend before its creator grants access is acceptable — it is the same state a
not-yet-granted scanner is in.

### D3 — New seams keep the reduction pure (option B)
A command/state port pair mirroring the codebase's existing patterns:
- `EventCreator { fun create(name: String) }` — fire-and-forget, like `PermissionRequester`.
- `CreationStatusSource { val creationStatus: StateFlow<CreationStatus> }` — observable, like
  `EventStatusSource`. `CreationStatus = Idle | InFlight | Failed(reason)`.

The in-flight and error states thus become seam values, so the reduction stays
`f(config, permission, eventStatus, snapshot, creationStatus)`. *Alternative — hold in-flight/error
as transient Orbit container state — rejected:* it breaks the stated pure-reduction invariant and the
harness's forge-from-seams model. There is **no** `Succeeded` state: success flips config, which
moves the reduction off the create layer.

### D4 — The create layer is the top reduction rung
When `config == null`: `InFlight → CreatingEvent`, `Failed(r) → CreateEvent(error = r)`, `Idle →
CreateEvent(input)`. Config-absent outranks everything (permission/join/snapshot are meaningless with
no event), exactly as `setup-gate` formerly held for `UiState.Setup`. Rungs 2–4 (permission → join →
hero) are unchanged and already owned by their capabilities.

### D5 — Retire `setup-gate`; bundle screen + seams in `event-creation-ui`
Nothing of `setup-gate` survives intact: the storage card is superseded by create, the standing
permission step is already handled post-config by `PermissionBlocked`, and the deeplink intent +
invalid-link error move to the create screen. So `setup-gate` is removed rather than bent. The new
`event-creation-ui` capability bundles the screen, the two seams, the create→provision use-case, the
HTTP `EventCreator`, and the config-absent reduction rung — mirroring how `event-rejoin-reconciliation`
bundles `JoinEvent` + its HTTP client + `EventStatusSource`. *Alternative — split the HTTP client into
its own `:capability` — rejected:* nothing else here does, and the join twin keeps client + use-case
together.

### D6 — One inline error surface on the create screen
The `CreateEvent` state carries one optional error line, shared by two causes: create failures (sticky
until retry/edit — `400` → "That name isn't valid", `502`/network → "Couldn't reach the server") and a
malformed deeplink (transient, self-clearing — the error formerly on the setup gate's storage card).
The create screen owns the `onOpenUrl` intent. *Alternative — a separate transient toast channel —
rejected:* one screen, one error region.

### D7 — `AppTextField`, and removing the now-dead `SetupCard`
The create input needs the app's first text field: a semantic `AppTextField` (value, onValueChange,
placeholder, enabled, maxLength) with no appearance/`Modifier` params, Material 3 contained in the
component. The name field disables Create until the trimmed value is non-empty and hard-caps at 100
chars (mirrors the backend, so a `400` is near-unreachable). Retiring `setup-gate` leaves `SetupCard`
with no consumer (the create screen uses `AppTextField`/`PrimaryButton`; `PermissionBlocked` renders a
hero), so `SetupCard` is removed from the design system as part of this change.

### D8 — Host and client shape
`EventCreator`'s HTTP impl is the `HttpEventFilesSource` twin: an injected Ktor `HttpClient` + host,
`POST <host>/event` with `{ name }`, parse `{ eventId, name, createdAt }`, any non-2xx/transport/parse
→ a failed result the use-case maps to `CreationStatus.Failed`. Host comes from the app's Info.plist
`BackgroundUploadURLBase`, exactly as the rejoin client already does in `SnapSyncRoot`. Tested in
`commonTest` with `MockEngine`.

## Risks / Trade-offs

- **Orphaned event on no-permission create** → Accepted (D2): identical to a not-yet-granted scanner;
  the backend event is harmless and becomes usable the moment access is granted.
- **Two error lifetimes share one slot** (sticky create-failure vs transient deeplink flash) → the
  `CreateEvent` error field plus a self-clearing effect for the deeplink case; presentation tests pin
  both lifetimes.
- **Central reduction edit** (rewriting `sync-status-screen`'s reduce requirement and removing
  `setup-gate`) touches the most-tested seam → mitigated by the `:test:integration` seam↔UI tests and
  presentation tests asserting the new config-absent rung and that rungs 2–4 are unchanged.
- **`POST /event` is unauthenticated** → out of scope here (possession-is-capability model, per the
  backend capability); no new exposure beyond what the backend already ships.
- **First text input on Compose MP / iOS** (focus, IME, done action) → contained in `AppTextField`;
  exercised in the desktop harness and on-device per the headless loop.
