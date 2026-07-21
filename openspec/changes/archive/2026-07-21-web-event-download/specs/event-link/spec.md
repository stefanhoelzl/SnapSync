## MODIFIED Requirements

### Requirement: An event link without the app installed reaches the App Store

The backend SHALL answer `GET|HEAD /join` — the path a browser requests when an event link is opened on a
device that has no app to claim it — with a **static download page** (`200`), reachable without a
device-attestation token (capability `device-attestation`). The page surfaces both the SnapSync App Store
listing and a client-side "download all photos" affordance (capability `web-event-download`). The route
SHALL read no storage, hold no per-event state, and carry no side effect; because the page is the same
constant asset for every request, it MAY be served with a `public` cache directive.

The route SHALL NOT attempt to read the payload: the payload is carried in the fragment, which a browser
never transmits, so the backend receives `/join` and nothing more. It therefore SHALL be **identical for
every event link** — any per-event rendering (the event name, the photo union) is performed by the page's
own JavaScript, which reads the fragment on the client, never by the backend.

iOS performs **no deferred deep linking**: a link tapped before install is not delivered after install.
A user who installs from this page SHALL reach their event by opening the original link again.

#### Scenario: /join serves the download page without a token

- **WHEN** `GET /join` is requested without an attestation token
- **THEN** it responds `200` with the static download page, not a redirect and not `401`

#### Scenario: The page carries no event data server-side

- **WHEN** `GET /join` is requested for any event link
- **THEN** the served bytes are identical regardless of the link's payload, and the backend reads no
  event state
