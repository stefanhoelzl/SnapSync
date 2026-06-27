## ADDED Requirements

### Requirement: Listing gated on event existence

The endpoint SHALL determine whether the event exists before listing, by reading the event marker
`events/<eventId>.json` (a bunny native Storage `GET` carrying the configured `AccessKey`). When the
marker is absent, the endpoint SHALL respond `404` and SHALL NOT perform the directory LIST. When the
marker is present, the endpoint SHALL proceed to list the event directory. A genuine upstream failure
reading the marker (any non-`404` error or timeout) SHALL be surfaced as `5xx` and SHALL NOT be
treated as "event absent". A created event with no stored objects SHALL still respond `200` with an
empty array `[]` — existence (marker present) and emptiness (no objects) are distinct.

#### Scenario: Unknown event yields 404

- **WHEN** a `GET /event/<uuid>/files` arrives for an event whose marker `events/<uuid>.json` is absent
- **THEN** the endpoint responds `404` and performs no directory LIST

#### Scenario: Created-but-empty event yields empty array

- **WHEN** a valid event's marker exists but its directory `<uuid>/` contains no objects
- **THEN** the endpoint responds `200` with `[]`

#### Scenario: Created event with objects yields the array

- **WHEN** a valid event's marker exists and its directory contains files
- **THEN** the endpoint responds `200` with the flat array of those files

#### Scenario: Marker read failure is not treated as absence

- **WHEN** the marker read returns a non-`404` upstream error or times out
- **THEN** the endpoint responds `5xx` and does not return `404` or an array

## MODIFIED Requirements

### Requirement: Single-directory event listing

The endpoint SHALL return the objects stored under the event as a single flat array obtained from a
**single** bunny native Storage List Files request against the event directory `<zone>/<eventId>/`.
Files are direct children of the event directory (the key is `<eventId>/<filename>`), so no
sub-directory discovery or per-directory fan-out is performed. Directory entries (if any) SHALL NOT
appear in the result (only files). The List request SHALL carry the storage zone's `AccessKey`
header from configuration and never the account API key. A single event-existence read (the marker
`GET` of `events/<eventId>.json`) precedes this List per the existence gate; that read is separate
from and does not relax the single-LIST rule for the file listing itself.

#### Scenario: Event directory files are returned

- **WHEN** the event directory `<zone>/<eventId>/` contains files
- **THEN** the response is a flat array of those files, with no directory entries, obtained from one
  List request

#### Scenario: Directory listing uses the storage AccessKey

- **WHEN** the endpoint lists the event directory
- **THEN** the upstream List request carries the configured `AccessKey` header and never the account
  API key

### Requirement: Authorization by event id only

Authorization to list an event SHALL be possession of the event id alone — the endpoint SHALL NOT
require any token. The endpoint now consults the event registry (the marker) to determine
**existence** and SHALL respond `404` for an event that was never created; consulting the registry is
an existence check, not an authorization step — any caller possessing a valid, existing event id is
authorized to list it. The endpoint SHALL NOT expose or forward the bunny account API key.

#### Scenario: No token required

- **WHEN** a `GET /event/<uuid>/files` carries a valid, existing event id but no authorization token
- **THEN** the listing is returned (the event id is the capability)

#### Scenario: Account API key never exposed

- **WHEN** the endpoint lists an event
- **THEN** no response or upstream-facing surface exposes the bunny account API key

## REMOVED Requirements

### Requirement: Empty or unknown event yields an empty array

**Reason**: A server-side event registry now exists (the `events/<eventId>.json` marker), so the
endpoint can and does distinguish an unknown event from an empty one. This requirement's "do not
distinguish unknown from empty, never 404 for a well-formed id" contract is replaced by the
"Listing gated on event existence" requirement: unknown ⇒ `404`, created-but-empty ⇒ `200 []`.

**Migration**: Callers that relied on `200 []` for a never-created event MUST now treat `404` as
"event does not exist" and continue to treat `200 []` as "exists, no objects yet". The on-device
rejoin/auto-join flow (follow-up change) consumes this distinction.
