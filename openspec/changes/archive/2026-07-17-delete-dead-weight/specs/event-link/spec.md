# event-link — delta for delete-dead-weight

## REMOVED Requirements

### Requirement: Authoritative QR generator

**Reason**: The Gradle QR tool (`generateConfigQr` / `QrGeneratorMain`, zxing) is deleted
(migration step 1's deletion ledger). Link authority rests where it always executed: the pure
codec (`encodeEventUrl` / `decodeEventUrl`) is the single encoder/decoder pair, and the in-app
invite QR (capability `event-invite-qr`), rendered from `encodeEventUrl` output, is the only QR
surface. A dev link is composed by hand from the documented payload contract (see the runbooks in
CLAUDE.md), which exercises the same codec on decode.

## MODIFIED Requirements

### Requirement: Switching events leaves the previous event first

The provisioning flow SHALL fire a best-effort backend leave of the previous event before persisting a
new event's config, whenever a valid event link provisions an event whose `eventId` **differs**
from the currently provisioned one (a switch). That leave issues
`DELETE /events/<previousEventId>/devices/<deviceId>` via the same `HttpLeaveNotifier` the explicit
Leave uses. The previous `eventId` SHALL be
read before it is replaced. Provisioning an event link for the **same** event that is already configured
SHALL remain an idempotent no-op and SHALL NOT fire a leave. The backend leave SHALL be best-effort — a
failure SHALL NOT prevent the switch — so the device always ends up provisioned to the new event. The
switch fires the leave **without** a confirmation dialog (the leave-confirm-on-switch dialog is a
separate change).

#### Scenario: Provisioning a different event leaves the previous one

- **WHEN** an event link provisions an `eventId` different from the currently configured event
- **THEN** the flow issues `DELETE /events/<previousEventId>/devices/<deviceId>` best-effort, then persists the new event's config

#### Scenario: Re-provisioning the same event fires no leave

- **WHEN** an event link provisions the `eventId` already configured
- **THEN** provisioning is an idempotent no-op and no backend leave is issued

#### Scenario: A failed switch-leave still switches

- **WHEN** the previous-event `DELETE` fails during a switch
- **THEN** the failure is logged and the new event's config is still persisted (the device is provisioned to the new event)
