## MODIFIED Requirements

### Requirement: The status screen displays the join QR with a caption
In the joined layer the screen SHALL display a **scannable** QR encoding the invite link, with a
caption, rendered through the design system's QR component (the QR-rendering library is contained to
the components module; the screen passes only the link string and the caption text). The QR SHALL
render the invite URL verbatim so another device's camera joins the same event. The QR SHALL render
**dark modules on a light card in both light and dark themes** (the design system SHALL NOT render an
inverted light-on-dark QR, which does not scan reliably — see `design-system`).

The caption SHALL be addressed to the **member looking at the screen**, and SHALL state that
**someone else** scans this code in order to join. It SHALL NOT be an instruction to scan directed at
whoever is reading it: the reader is already joined, and a scan imperative beneath a scannable code
tells them to perform an act that is not theirs to perform. Decision record:
`changes/archive/<this change>`.

The exact wording is **not** pinned by this spec — it is owned by the joined-layer screen and pinned
mechanically by that screen's tests, so the copy may be tuned without a spec change. What is pinned
is the audience and the claim.

#### Scenario: The QR encodes the invite link
- **WHEN** the joined-layer screen renders the QR
- **THEN** the QR encodes exactly the derived invite link and carries a caption

#### Scenario: The caption tells the member that someone else scans the code
- **WHEN** a joined member — host or guest, who see the identical screen — reads the caption beneath
  the QR
- **THEN** it tells them that someone else scans this code to join, and does not instruct them to
  scan anything

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
