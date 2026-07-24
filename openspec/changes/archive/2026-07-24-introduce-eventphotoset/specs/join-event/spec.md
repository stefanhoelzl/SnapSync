## ADDED Requirements

### Requirement: The persisted membership's capture-date ceiling is required

A persisted membership (`EventConfig`) SHALL carry a **concrete** capture-date upper bound
(`maxPhotoDate`); it SHALL NOT be absent or unbounded. On a successful details load the ceiling is
`min(chosen, endsAt)` and the event always serves `endsAt`, so a fresh join always yields one. A config
lacking the ceiling SHALL fail to decode (returning the device to the unjoined state) rather than decode to
an unbounded ceiling.

This **reverses** the prior allowance (a pre-ceiling config decoding to an unbounded ceiling pending
reconcile backfill). It is safe only because `decouple-event-window-from-lifetime` ships first and
reconciles every device's ceiling before this change's strict decode is deployed (see this change's
migration gate); it is a deliberate, recorded reversal of `EventConfig`'s decode-safety allowance,
acceptable for the controlled installed base.

#### Scenario: A config without a ceiling does not decode

- **WHEN** an `EventConfig` lacking `maxPhotoDate` is decoded by this change's build
- **THEN** it fails to decode (read as no config); it does not decode to an unbounded ceiling
