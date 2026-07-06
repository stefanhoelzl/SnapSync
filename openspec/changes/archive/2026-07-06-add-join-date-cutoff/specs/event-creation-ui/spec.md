## MODIFIED Requirements

### Requirement: Create mints an event then provisions it like a scanned QR

The capability SHALL provide a create use-case that, on `create(name)`, sets `creationStatus` to
`InFlight`, calls the backend `POST /events` with the trimmed name via an injected client, and on a
`201 { eventId, name, createdAt }` **routes the returned `eventId` into the existing pending-join gate**
— the same gate a scanned deeplink opens (see capability `join-event`) — rather than provisioning the
config directly. The route SHALL be an **auto-routed but not auto-confirmed** pending join: the creator
is taken to the join surface (which fetches the just-minted event's details, shows its name, and offers
the capture-date cutoff row) and completes the **same** confirm-to-enroll-and-provision flow every joiner
uses. Because the `POST` has already minted the event, the gate holds a **real** `eventId`, performs a
real details fetch, and enrolls normally; the config is saved (with name and the chosen cutoff) by the
gate's provision step, not by the create use-case. On a successful mint `creationStatus` SHALL return to
`Idle` (the pending join drives the reduction from there); on any failure (non-2xx, transport, or parse)
it SHALL set `creationStatus` to `Failed(reason)`, SHALL NOT open the gate, and SHALL save no config. A
cancelled or abandoned join after a successful mint SHALL leave the minted event as a harmless
member-less marker (no rollback). The use-case MUST NOT inspect `PermissionStatus`.

#### Scenario: Successful create opens the join gate for the minted event
- **WHEN** `create("My Party")` is invoked and the backend returns `201` with `{eventId, name, createdAt}`
- **THEN** the returned `eventId` is routed into the pending-join gate, the join surface loads the event and shows the cutoff row, and the config is provisioned only when the creator confirms

#### Scenario: Create ignores permission
- **WHEN** `create(name)` is invoked while photo permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the create proceeds (mints + opens the gate) without inspecting permission, and the missing
  permission surfaces afterward via the joined-layer `NeedsAccess` status line (per `sync-status-screen`)

#### Scenario: A failed create leaves config untouched and opens no gate
- **WHEN** `create(name)` is invoked and the backend request fails (non-2xx, transport, or parse)
- **THEN** `creationStatus` becomes `Failed(reason)`, config is unchanged, and no pending join is opened

#### Scenario: A cancelled join after a mint leaves a harmless marker
- **WHEN** the mint succeeds, the gate opens, and the creator cancels before confirming
- **THEN** no config is saved, the device is not enrolled, and the minted event remains as a member-less marker with no rollback

### Requirement: Config-absent reduces to the create layer

The presentation reduction SHALL gate the top rung on config presence: whenever `config == null` **and no
interactive join is pending**, the screen SHALL reduce to the create layer derived from `creationStatus`,
**regardless of permission or snapshot** — `InFlight` SHALL reduce to `UiState.CreatingEvent`;
`Failed(reason)` SHALL reduce to the create-input state carrying the matching inline error; `Idle` SHALL
reduce to the create-input state with no error. When `config == null` **and an interactive join is
pending** — whether from a scanned deeplink or an auto-routed create — the screen SHALL reduce to the
`JoiningEvent` family for that pending join (see capability `join-event`), which takes precedence over the
`creationStatus`-derived create layer. Only when `config` is present do the existing rungs (permission →
join → sync hero, see `sync-status-screen`) apply. The reduction MUST depend only on the latest source
values; the container's initial UI state SHALL be computed from the sources' current values at
construction.

#### Scenario: No config and no pending join shows the create input
- **WHEN** config is `null`, no join is pending, creation status is `Idle`, and permission is `GRANTED`, `DENIED`, or `NOT_DETERMINED`
- **THEN** the UI state is the create-input state, not a permission, join, or sync state

#### Scenario: A pending join takes precedence over the create layer
- **WHEN** config is `null` and an interactive join is pending (from a scan or an auto-routed create)
- **THEN** the UI state is the `JoiningEvent` family for that pending join, not the create-input or `CreatingEvent` state

#### Scenario: In-flight create shows the creating state
- **WHEN** config is `null`, no join is pending, and creation status is `InFlight`
- **THEN** the UI state is `UiState.CreatingEvent`

#### Scenario: Failed create shows the input with an inline error
- **WHEN** config is `null`, no join is pending, and creation status is `Failed(reason)`
- **THEN** the UI state is the create-input state carrying the inline error matching the reason

#### Scenario: Config present leaves the create layer
- **WHEN** config becomes present after a confirmed join
- **THEN** the reduction leaves the create layer and the existing permission/join/sync rungs apply
