## REMOVED Requirements

### Requirement: snapsync:// URL scheme and payload contract
**Reason**: The `snapsync://` custom scheme is retired (design.md §2). An invite dead-ends for anyone
without the app, and the three premises that justified a custom scheme have expired: we own
`snapsync.stho.net`, the backend serves public HTML, and "the app is always installed" stopped being true
when the link became something you hand to other people.
**Migration**: Replaced by *Event link URL and payload contract* in capability `event-link`, which carries
the identical `v=3` `EventLinkPayload` at `https://snapsync.stho.net/join#v=3&d=…`. The payload format and
version are unchanged; only the URL form moves, and the payload now rides in the **fragment** so the
`eventId` still never transits a server.

### Requirement: Pure structural decoder
**Reason**: Renamed with the capability; the accepted prefix changes from `snapsync://config?` to the
HTTPS origin and `/join#`.
**Migration**: Replaced by *Pure structural decoder* in capability `event-link`. The decoder stays pure,
`commonMain`, structural-only, and typed-failure-never-throw; it gains rejection of retired `snapsync://`
URLs and of foreign origins.

### Requirement: Config source and store seams
**Reason**: Renamed with the capability. No behavior change.
**Migration**: Carried forward verbatim as *Config source and store seams* in capability `event-link`,
with the wire type described as the "event-link wire payload" rather than the "deeplink wire type".

### Requirement: iOS Keychain-backed config store
**Reason**: Renamed with the capability. No behavior change.
**Migration**: Carried forward verbatim as *iOS Keychain-backed config store* in capability `event-link`.

### Requirement: An unreadable config is not an absent config
**Reason**: Renamed with the capability. No behavior change.
**Migration**: Carried forward verbatim as *An unreadable config is not an absent config* in capability
`event-link`.

### Requirement: Authoritative QR generator
**Reason**: Renamed with the capability; the single encoder now emits the HTTPS event link.
**Migration**: Replaced by *Authoritative QR generator* in capability `event-link`, emitting
`https://snapsync.stho.net/join#v=3&d=…`. It remains the single authoritative encoder and still emits no
host and no credential.

### Requirement: Event name is fetched, not carried in the deeplink
**Reason**: Renamed with the capability. No behavior change.
**Migration**: Carried forward as *Event name is fetched, not carried in the event link* in capability
`event-link`.

### Requirement: Switching events leaves the previous event first
**Reason**: Renamed with the capability. No behavior change.
**Migration**: Carried forward as *Switching events leaves the previous event first* in capability
`event-link`, with "config deeplink" reworded to "event link".
