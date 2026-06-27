## ADDED Requirements

### Requirement: The invite deeplink is derived from the joined event

The presentation layer SHALL derive the invite deeplink from the persisted event config: the
configured `eventId` encoded via `encodeConfigUrl(EventConfigPayload(eventId))` (the
`deeplink-config` encoder, the inverse of the decode run on a scanned QR). The derivation SHALL be
deterministic and require no network call and no secret — the same `eventId` produces the same
`snapsync://config?v=3&d=…` URL a scanner would receive. The invite deeplink SHALL be exposed as
observable state sourced from `ConfigSource` (so a single source feeds both the rendered QR and the
share action), and SHALL be absent (`null`) whenever no event is configured. It SHALL NOT enter
`UiState`; the snapshot→state reduction is unchanged.

#### Scenario: The invite URL round-trips to the configured event
- **WHEN** an event is configured and the invite deeplink is derived
- **THEN** decoding it yields the same `eventId`, and the URL equals the one a scanner of the event's
  QR would receive

#### Scenario: No configured event yields no invite URL
- **WHEN** no event is configured
- **THEN** the derived invite deeplink is `null`

#### Scenario: The reduction is untouched
- **WHEN** the invite feature is added
- **THEN** `UiState` gains no variant and `reduceFrom` gains no branch — the invite URL enters the
  screen as a parameter, not as reduced state

### Requirement: Invite affordances appear only in the joined layer

The invite QR, its caption, and the share action SHALL be presented to the user **only** in the
joined-layer states — `InProgress`, `NothingToSync`, and `Completed` — and SHALL NOT be presented in
the loading, setup-gate, permission-blocked, joining, or join-failed states. The gate SHALL be the
same joined-layer predicate that scopes the leave action; the invite URL may be non-`null` before the
joined layer (config present but permission not granted), yet the affordances SHALL still not render
outside the joined layer.

#### Scenario: Joined-layer states present the invite affordances
- **WHEN** the screen is in `InProgress`, `NothingToSync`, or `Completed`
- **THEN** the invite QR, its caption, and the share action are presented

#### Scenario: Non-joined states present no invite affordances
- **WHEN** the screen is in the loading, setup-gate, permission-blocked, joining, or join-failed state
- **THEN** no invite QR, caption, or share action is presented, even when an event is configured

### Requirement: The status screen displays the join QR with a caption

In the joined layer the screen SHALL display a **scannable** QR encoding the invite deeplink, with the
caption "Scan to join this event", rendered through the design system's QR component (the QR-rendering
library is contained to the components module; the screen passes only the deeplink string and the
caption text). The QR SHALL render the invite URL verbatim so another device's camera joins the same
event.

#### Scenario: The QR encodes the invite deeplink
- **WHEN** the joined-layer screen renders the QR
- **THEN** the QR encodes exactly the derived invite deeplink and carries the "Scan to join this event"
  caption

#### Scenario: Scanning the displayed QR joins the same event
- **WHEN** another device scans the displayed QR
- **THEN** it receives the same `snapsync://config?v=3&d=…` deeplink and provisions the same `eventId`

### Requirement: Sharing the invite is fire-and-forget

The presentation layer SHALL expose an `onShareInvite()` intent that hands the invite deeplink string
to the platform share. The share action SHALL be injected into `StatusContainerHost` as a bare
`share: (String) -> Unit` lambda (not a named seam type), and `onShareInvite()` SHALL invoke it with
the current invite URL when one exists. The share SHALL be fire-and-forget: the screen SHALL NOT
observe or react to the share's completion, cancellation, or dismissal, and `UiState` SHALL be
unaffected by sharing (the status projection stays correct while and after any platform share UI is
presented).

#### Scenario: Sharing hands the deeplink to the platform
- **WHEN** `onShareInvite()` is invoked with an event configured
- **THEN** the injected `share` lambda is called with the invite deeplink string

#### Scenario: Sharing does not change the status projection
- **WHEN** the platform share UI is presented over the status screen and then dismissed (shared or
  cancelled)
- **THEN** the status screen reflects the same live `UiState` it would have shown regardless, with no
  share-driven state to restore

### Requirement: The container share action defaults to a no-op

`StatusContainerHost` SHALL accept the share action as an injected `share: (String) -> Unit` lambda
with a no-op default, mirroring the leave action. The composition root binds it to the platform share;
hosts and tests that do not exercise sharing (the desktop harness's real-share path, presentation
tests) SHALL construct unchanged, and a share in those contexts SHALL be inert. The presentation layer
SHALL gain no module dependency beyond the `:capability:config` dependency it already has.

#### Scenario: A host without a real share action constructs and is inert
- **WHEN** `StatusContainerHost` is constructed without injecting a real share action
- **THEN** construction succeeds and invoking `onShareInvite()` performs no platform share

#### Scenario: Presentation gains no new module dependency
- **WHEN** the presentation module's dependencies are inspected after this change
- **THEN** it depends on no new module — the invite URL is derived via the already-present
  `:capability:config` encoder and the share enters as a plain lambda

### Requirement: The displayed QR carries the full join capability

Displaying the invite QR SHALL be understood to expose the event's **full join capability**: the
`eventId` it encodes is the upload authorization, so any party that scans the displayed QR joins the
event and becomes able to upload to it. This is an accepted, deliberate consequence — the app holds no
finer-grained credential to withhold. An **existing** member re-scanning the QR SHALL be idempotent:
the `event-rejoin-reconciliation` join seeds already-stored photos as `COMPLETED`, so a re-scan
uploads nothing already present. There SHALL be no access control on who may scan.

#### Scenario: A new scanner becomes an uploader
- **WHEN** a party that has not joined scans the displayed QR
- **THEN** it provisions the event and is able to upload to it (the QR is the join capability)

#### Scenario: An existing member re-scanning uploads nothing new
- **WHEN** a member already joined to the event re-scans the displayed QR
- **THEN** the join reconciles against storage, seeding already-stored photos as `COMPLETED`, and
  nothing already present is re-uploaded
