# permission gate Specification

## Purpose

The photo-library grant is the **single switch the user ever flips**: once it is on and an event is
joined, sharing runs on its own — no upload button, no per-photo consent. This capability owns that
switch's contract so the rest of the app never touches a platform authorization API and never has to
reason about a half-grant.

It reduces the platform's authorization vocabulary to **four** values — `NOT_DETERMINED`, `DENIED`,
`LIMITED`, `GRANTED` — where `GRANTED` means a **full** library grant and `LIMITED` a **partial** one
(iOS's `.limited`: the user hand-picks the photos the app may see). A partial grant is a first-class
working state, not a refusal: the selection **defines** the membership's own-photo scope (capability
`limited-photo-access`), so "is everything shared?" is answerable — the selection *is* "everything",
and "In sync" over the chosen set is true. (v1 collapsed `.limited` to `DENIED` on the argument that a
partial library cannot answer that question; the selection-defines-scope reframe dissolved it —
decision record `changes/archive/2026-07-20-accept-limited-photo-access`.)

It exposes exactly two ports, split by kind: `PhotoAccessStatusSource` (state — a level-triggered
`StateFlow` whose latest value is the whole truth, readable synchronously) and `PhotoAccessRequester`
(command — `request()` / `openSettings()`, **fire-and-forget**, returning nothing and never suspending).
The asymmetry is the contract: a command can only *provoke* a change; the change itself is only ever
observed on the state port. That is what keeps the OS's out-of-app Settings round-trip from being a
special case.

Permission is **not a state of the app**. Two surfaces reach the system dialog, and neither one blocks
the product behind it:

- the **joined layer's inline `NeedsAccess` status line** (capability `sync-status-screen`) — tapping it
  requests on `NOT_DETERMINED` and opens Settings on `DENIED`, while the name, QR, share, and leave keep
  rendering in full;
- the **join gate's photo-access explainer** (capability `join-event`) — shown on a first join before the
  dialog has ever been raised, so the user learns what the grant is *for* (their photos share
  automatically; others' photos land in their library) before iOS's one-shot prompt appears.

Both are **CTA-only priming**: the system dialog fires from a deliberate user action, never from merely
observing `NOT_DETERMINED` — iOS raises it at most once, and a prompt spent before the user understands
it is spent for good.

Decision record: `changes/archive/2026-06-27-permission-on-status-screen`;
the `LIMITED` state: `changes/archive/2026-07-20-accept-limited-photo-access`.
## Requirements
### Requirement: Permission domain contracts

The permission domain SHALL define `PermissionStatus` with exactly four values — `NOT_DETERMINED`, `DENIED`, `LIMITED`, `GRANTED` — in `:domain`'s `model/` zone, and two ports in its `ports/` zone (both seated by migration step 3a):

- `PhotoAccessStatusSource` (state port): exposes `permission: StateFlow<PermissionStatus>`, a level-triggered state holder whose current value is always available synchronously. Every emission is the whole truth; consumers depend only on the latest value.
- `PhotoAccessRequester` (command port): `fun request()` and `fun openSettings()`. Both are fire-and-forget — they MUST NOT return values and MUST NOT suspend. Status changes resulting from a command arrive exclusively via `PhotoAccessStatusSource`.

`LIMITED` means a **partial** library grant: the platform scopes reads to a user-picked selection, which the `limited-photo-access` capability treats as the membership's own-photo scope. `GRANTED` continues to mean a **full** library grant and nothing less. Consumers that gate behavior on "the app can read photos" SHALL treat `GRANTED` and `LIMITED` as the granted side; consumers that gate on "the app can read the **whole** library" (the autonomous walk paths) SHALL require `GRANTED` exactly. Boolean comparisons of the form `permission != GRANTED` are therefore no longer self-evidently correct and every such site SHALL state which reading it intends.

Implementations MAY be a single object implementing both ports, but consumers SHALL depend on each port separately. These contracts are consumed by the **status screen's joined-layer status line** (capability `sync-status-screen`), which renders the missing-permission state **inline** (the `NeedsAccess` health) and routes its intents — permission is no longer a hero-replacing gate; the joined layer (name, QR, share, leave) renders regardless of permission.

#### Scenario: Truth arrives only via the state port
- **WHEN** `request()` is invoked and the platform resolves the request
- **THEN** the new status is observed as an emission of `PhotoAccessStatusSource.permission`, and `request()` itself communicates nothing

#### Scenario: Duplicate requests are harmless
- **WHEN** `request()` is invoked twice before the first resolves
- **THEN** no error occurs and the source ends up holding the single resolved status

#### Scenario: Limited is on the granted side for can-read gates
- **WHEN** a consumer asks whether the app may read photos at all (e.g. whether syncing is operational)
- **THEN** both `GRANTED` and `LIMITED` answer yes, and only the whole-library walk paths distinguish them

### Requirement: iOS PhotoKit permission adapter

The permission domain SHALL provide an iOS platform adapter (`PhotoLibraryPermission` in `:adapter:ios:app-only`, seated there by migration step 4 — the permission dialog is app-process-only surface) — a single object implementing **both** `PhotoAccessStatusSource` and `PhotoAccessRequester` against PhotoKit. It SHALL seed its `permission` `StateFlow` synchronously from `PHPhotoLibrary.authorizationStatus(for: .readWrite)` at construction (the platform exposes the current status synchronously, so the permission seam keeps its synchronous-real guarantee). It SHALL request the `.readWrite` (full-library) access level. The status mapping SHALL be: `.authorized → GRANTED`, `.limited → LIMITED`, `.notDetermined → NOT_DETERMINED`, `.denied/.restricted → DENIED`.

`request()` SHALL invoke `PHPhotoLibrary.requestAuthorization(for: .readWrite)` and update the source from its completion callback; `openSettings()` SHALL open the app's system settings surface (`UIApplication.openSettingsURLString`). Both are fire-and-forget per the port contract.

#### Scenario: Initial status is read synchronously
- **WHEN** the adapter is constructed
- **THEN** `permission.value` immediately reflects the current PhotoKit authorization status, mapped per the contract, without waiting for an emission

#### Scenario: Granting via the system dialog updates the source
- **WHEN** `request()` is invoked and the user grants full access in the system dialog
- **THEN** `permission` emits `GRANTED`, and `request()` itself returns nothing

#### Scenario: Limited grant maps to LIMITED
- **WHEN** the user grants *limited* (partial) library access — from the system dialog's "Limit Access…" or a later Settings change
- **THEN** `permission` holds `LIMITED`

### Requirement: Permission liveness across the system Settings round-trip

The iOS permission adapter SHALL keep its `permission` `StateFlow` current with changes made outside the app. Because PhotoKit exposes no authorization-change observer and the user can change access in system Settings while the app is backgrounded, the adapter SHALL treat the app returning to the foreground as a refresh trigger: it SHALL observe `UIApplication.didBecomeActiveNotification`, re-read `authorizationStatus`, and emit the mapped status. The observer SHALL be registered for the app's lifetime.

#### Scenario: Revoking access in Settings is reflected on return
- **WHEN** access is `GRANTED`, the user backgrounds the app, revokes photo access in system Settings, and returns to the foreground
- **THEN** the app re-reads the status and `permission` emits `DENIED`

#### Scenario: Granting access in Settings is reflected on return
- **WHEN** access is `DENIED`, the user backgrounds the app, enables full photo access in system Settings, and returns to the foreground
- **THEN** the app re-reads the status and `permission` emits `GRANTED`

