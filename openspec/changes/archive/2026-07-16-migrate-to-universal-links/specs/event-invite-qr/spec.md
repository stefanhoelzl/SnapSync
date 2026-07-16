## RENAMED Requirements

- FROM: `### Requirement: The invite deeplink is derived from the joined event`
- TO: `### Requirement: The invite link is derived from the joined event`

## MODIFIED Requirements

### Requirement: The invite link is derived from the joined event
The presentation layer SHALL derive the invite link from the persisted event config: the
configured `eventId` (from `EventConfig`) encoded via `encodeEventUrl(EventLinkPayload(eventId))`
(the `event-link` encoder, the inverse of the decode run on a scanned QR). The derivation SHALL
be deterministic and require no network call and no secret — the same `eventId` produces the same
`https://<link domain>/join#v=3&d=…` URL a scanner would receive. The invite link SHALL be exposed as
observable state sourced from `ConfigSource` (so a single source feeds both the rendered QR and the
share action), and SHALL be absent (`null`) whenever no event is configured. It SHALL NOT enter
`UiState`; the snapshot→state reduction is unchanged by the invite feature.

Because the link is an HTTPS Universal Link, the shared string is **tappable in messengers** (which
linkify `http`/`https` only, so the retired `snapsync://` string arrived as dead text) and reaches a
recipient **without the app** — the backend redirects them to the App Store (capability `event-link`).

#### Scenario: The invite URL round-trips to the configured event
- **WHEN** an event is configured and the invite link is derived
- **THEN** decoding it yields the same `eventId`, and the URL equals the one a scanner of the event's
  QR would receive

#### Scenario: No configured event yields no invite URL
- **WHEN** no event is configured
- **THEN** the derived invite link is `null`

#### Scenario: The reduction is untouched
- **WHEN** the invite feature is present
- **THEN** the invite URL enters the screen as a parameter, not as reduced state

### Requirement: The status screen displays the join QR with a caption
In the joined layer the screen SHALL display a **scannable** QR encoding the invite link, with the
caption "Scan to join this event", rendered through the design system's QR component (the QR-rendering
library is contained to the components module; the screen passes only the link string and the
caption text). The QR SHALL render the invite URL verbatim so another device's camera joins the same
event. The QR SHALL render **dark modules on a light card in both light and dark themes** (the design
system SHALL NOT render an inverted light-on-dark QR, which does not scan reliably — see
`design-system`).

#### Scenario: The QR encodes the invite link
- **WHEN** the joined-layer screen renders the QR
- **THEN** the QR encodes exactly the derived invite link and carries the "Scan to join this event"
  caption

#### Scenario: Scanning the displayed QR joins the same event
- **WHEN** another device that has SnapSync installed scans the displayed QR
- **THEN** it receives the same `https://<link domain>/join#v=3&d=…` link, iOS opens the app via the
  associated domain, and it provisions the same `eventId`

#### Scenario: Scanning without the app reaches the App Store
- **WHEN** a device **without** SnapSync scans the displayed QR
- **THEN** no app claims the link, the browser requests `GET /join`, and the backend redirects to the
  App Store listing (capability `event-link`) — rather than dead-ending as the retired custom scheme did

#### Scenario: QR stays dark-on-light in dark theme
- **WHEN** the app renders in its dark theme
- **THEN** the QR is presented as dark modules on a light card (not inverted), so it remains scannable
