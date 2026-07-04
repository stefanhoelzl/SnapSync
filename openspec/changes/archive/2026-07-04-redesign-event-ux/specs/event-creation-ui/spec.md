## MODIFIED Requirements

### Requirement: Create mints an event then provisions it like a scanned QR

The capability SHALL provide a create use-case that, on `create(name)`, sets `creationStatus` to
`InFlight`, calls the backend `POST /event` with the trimmed name via an injected client, and on a
`201 { eventId, name, createdAt }` funnels the returned `eventId` **and** `name` into the **existing**
provision path — the same `onProvision(previousEventId, newEventId)` switch-reset a scanned deeplink
uses — saving `EventConfig(eventId, name)` **directly** (the create path already has the name, so it
performs **no** `GET /event/:id` fetch — see `deeplink-config`). On any failure (non-2xx, transport, or
parse) it SHALL set `creationStatus` to `Failed(reason)` and SHALL NOT save config. The use-case MUST
NOT inspect `PermissionStatus`.

#### Scenario: Successful create provisions the event with its name
- **WHEN** `create("My Party")` is invoked and the backend returns `201` with `{eventId, name}`
- **THEN** the event is provisioned through the same `onProvision` path as a scanned QR,
  `EventConfig(eventId, name)` is saved (no metadata fetch), config becomes present, and the existing
  join/reconcile flow runs

#### Scenario: Create ignores permission
- **WHEN** `create(name)` is invoked while photo permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the create proceeds (mints + provisions) without inspecting permission, and the missing
  permission surfaces afterward via the joined-layer `NeedsAccess` status line (per `sync-status-screen`)

#### Scenario: A failed create leaves config untouched
- **WHEN** `create(name)` is invoked and the backend request fails (non-2xx, transport, or parse)
- **THEN** `creationStatus` becomes `Failed(reason)`, config is unchanged, and no join is started

## ADDED Requirements

### Requirement: Sharing framing in create and status copy

The create-layer and joined-layer user-facing copy SHALL frame the app as **sharing/syncing event
photos**, not as personal photo backup. Copy SHALL NOT describe the app's function as "backing up" the
user's library; it SHALL use "sync"/"share" language. (Exact strings are an implementation concern;
this requirement pins the framing, not the wording.)

#### Scenario: Copy avoids backup framing
- **WHEN** the create screen or the joined status line renders its descriptive copy
- **THEN** the copy frames the action as sharing/syncing event photos and does not describe it as
  backing up the user's photo library
