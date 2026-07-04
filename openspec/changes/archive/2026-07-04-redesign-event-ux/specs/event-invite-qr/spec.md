## MODIFIED Requirements

### Requirement: The invite deeplink is derived from the joined event

The presentation layer SHALL derive the invite deeplink from the persisted event config: the
configured `eventId` (from `EventConfig`) encoded via `encodeConfigUrl(EventLinkPayload(eventId))`
(the `deeplink-config` encoder, the inverse of the decode run on a scanned QR). The derivation SHALL
be deterministic and require no network call and no secret — the same `eventId` produces the same
`snapsync://config?v=3&d=…` URL a scanner would receive. The invite deeplink SHALL be exposed as
observable state sourced from `ConfigSource` (so a single source feeds both the rendered QR and the
share action), and SHALL be absent (`null`) whenever no event is configured. It SHALL NOT enter
`UiState`; the snapshot→state reduction is unchanged by the invite feature.

#### Scenario: The invite URL round-trips to the configured event
- **WHEN** an event is configured and the invite deeplink is derived
- **THEN** decoding it yields the same `eventId`, and the URL equals the one a scanner of the event's
  QR would receive

#### Scenario: No configured event yields no invite URL
- **WHEN** no event is configured
- **THEN** the derived invite deeplink is `null`

#### Scenario: The reduction is untouched
- **WHEN** the invite feature is present
- **THEN** the invite URL enters the screen as a parameter, not as reduced state

### Requirement: Invite affordances appear only in the joined layer

The invite QR, its caption, and the share action SHALL be presented to the user in the **joined
layer** — defined as **config present** (the `UiState.Joined` state) — and SHALL NOT be presented in
the loading or create-layer states. Crucially, the affordances SHALL render whenever an event is
configured **including when permission is not `GRANTED`** (the `NeedsAccess` health): sharing the
invite requires no photo access, so a host can share the moment they create or join, before granting
access. The gate SHALL be the same config-present predicate that scopes the leave action.

#### Scenario: The joined layer presents the invite affordances
- **WHEN** the screen is in `UiState.Joined` (any health, including `NeedsAccess`, `Syncing`, `InSync`)
- **THEN** the invite QR, its caption, and the share action are presented

#### Scenario: Permission-off still shows the invite
- **WHEN** config is present and permission is `NOT_DETERMINED` or `DENIED`
- **THEN** the QR, caption, and share action are still presented (the invite does not require access)

#### Scenario: Non-joined states present no invite affordances
- **WHEN** the screen is in the loading or create-layer state
- **THEN** no invite QR, caption, or share action is presented, even if an event is being created

### Requirement: The status screen displays the join QR with a caption

In the joined layer the screen SHALL display a **scannable** QR encoding the invite deeplink, with the
caption "Scan to join this event", rendered through the design system's QR component (the QR-rendering
library is contained to the components module; the screen passes only the deeplink string and the
caption text). The QR SHALL render the invite URL verbatim so another device's camera joins the same
event. The QR SHALL render **dark modules on a light card in both light and dark themes** (the design
system SHALL NOT render an inverted light-on-dark QR, which does not scan reliably — see
`design-system`).

#### Scenario: The QR encodes the invite deeplink
- **WHEN** the joined-layer screen renders the QR
- **THEN** the QR encodes exactly the derived invite deeplink and carries the "Scan to join this event"
  caption

#### Scenario: Scanning the displayed QR joins the same event
- **WHEN** another device scans the displayed QR
- **THEN** it receives the same `snapsync://config?v=3&d=…` deeplink and provisions the same `eventId`

#### Scenario: QR stays dark-on-light in dark theme
- **WHEN** the app renders in its dark theme
- **THEN** the QR is presented as dark modules on a light card (not inverted), so it remains scannable
