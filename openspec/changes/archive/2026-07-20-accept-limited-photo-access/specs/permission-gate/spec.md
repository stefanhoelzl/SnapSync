# permission-gate — delta

## MODIFIED Requirements

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

## REMOVED Requirements

### Requirement: Full library access is required

**Reason**: Superseded — a limited grant is now a first-class working state (capability
`limited-photo-access`): the user's selection defines the membership's own-photo scope, so the original
rationale ("a partial library cannot answer *is everything shared?*") no longer holds; "In sync" over
the selected set is true. The `.limited → DENIED` collapse this requirement mandated is replaced by the
`.limited → LIMITED` mapping in the adapter requirement above.

**Migration**: platform adapters map `.limited → LIMITED` (see the modified iOS adapter requirement).
The joined-layer `NeedsAccess` affordance now covers only `NOT_DETERMINED` and `DENIED` (capability
`sync-status-screen`); under `LIMITED` the screen renders the ordinary health line plus the
"Choose more photos" affordance. The accepted managed-device risk (Settings path as a dead end on
`DENIED`/`.restricted`) carries over unchanged to the `DENIED` side.
