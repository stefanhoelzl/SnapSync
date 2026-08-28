# event-invite-qr Specification

## Purpose

Inviting others to the joined event from within the app. A joined device already holds the
join capability — the `eventId` in the persisted config — so it re-encodes the same event link
(`encodeEventUrl`, capability `event-link`) and displays it as a scannable QR ("Scan to join this event") with
a share action, in the joined layer only. Covers the deterministic invite-URL derivation, the
joined-layer visibility rule, the QR display, the fire-and-forget share over a bare
`share: (String) -> Unit` lambda (no-op default), and the explicit acknowledgement that the
displayed QR is the full join capability (any scanner becomes an uploader; an existing member
re-scanning reconciles and uploads nothing new).
Decision record: `changes/archive/2026-08-28-make-the-screen-a-function-of-state` (the invite URL is reduced, not a parameter).

## Requirements
### Requirement: The invite link is derived from the joined event
The presentation layer SHALL derive the invite link from the persisted event config: the
configured `eventId` (from `EventConfig`) encoded via `encodeEventUrl(EventLinkPayload(eventId))`
(the `event-link` encoder, the inverse of the decode run on a scanned QR). The derivation SHALL
be deterministic and require no network call and no secret — the same `eventId` produces the same
`https://<link domain>/join#v=3&d=…` URL a scanner would receive. The link SHALL be derived in
**exactly one place**, the status reduction, and carried as a field of the joined state, so that a single
value feeds both the rendered QR and the share action and the two can never disagree. It SHALL be absent
whenever no event is configured, which the joined state already expresses by not existing.

The invite link SHALL NOT be persisted into `EventConfig`. It is not a function of the `eventId` alone:
the link origin is generated at build time, so a stored URL written by one build and read by another
would carry an origin the app no longer uses while the correct `eventId` sat unused beside it — the
`eventId` is the stable half, the encoding is not. Storing it would also create a second source that can
disagree with a fresh derivation, which is precisely what deriving once prevents, and would add a
display-only field to a type the upload extension process decodes.

**Expiry trigger:** if the invite link ever becomes **server-issued** rather than derived — a short link,
a signed link, or anything else the device cannot reconstruct from the `eventId` — it stops being a
derivation and becomes a fact about the membership, and this requirement SHALL be revisited.

Because the link is an HTTPS Universal Link, the shared string is **tappable in messengers** (which
linkify `http`/`https` only, so the retired `snapsync://` string arrived as dead text) and reaches a
recipient **without the app** — the backend redirects them to the App Store (capability `event-link`).

#### Scenario: The invite URL round-trips to the configured event
- **WHEN** an event is configured and the invite link is derived
- **THEN** decoding it yields the same `eventId`, and the URL equals the one a scanner of the event's
  QR would receive

#### Scenario: No configured event yields no invite URL
- **WHEN** no event is configured
- **THEN** there is no joined state, and no invite link is carried or rendered

#### Scenario: The QR and the shared link cannot disagree
- **WHEN** the joined layer renders the QR and the member taps share
- **THEN** both use the one derived value carried by the state, so the scanned link and the shared link
  are identical by construction

#### Scenario: The invite link is never persisted
- **WHEN** a membership is saved and read back
- **THEN** the stored config carries the `eventId` and no invite URL, and the link is derived afresh from
  that `eventId`

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
SHALL gain no module dependency: the invite-link codec lives in `:domain`'s `model/` zone, reached through the `:domain` dependency presentation already has.

#### Scenario: A host without a real share action constructs and is inert
- **WHEN** `StatusContainerHost` is constructed without injecting a real share action
- **THEN** construction succeeds and invoking `onShareInvite()` performs no platform share

#### Scenario: Presentation gains no new module dependency
- **WHEN** the presentation module's dependencies are inspected after this change
- **THEN** it depends on no new module — the invite URL is derived via the already-present
  `model/` `EventLink` encoder and the share enters as a plain lambda

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

