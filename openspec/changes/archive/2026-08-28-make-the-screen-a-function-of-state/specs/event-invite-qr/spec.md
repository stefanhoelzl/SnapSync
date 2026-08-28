## MODIFIED Requirements

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
