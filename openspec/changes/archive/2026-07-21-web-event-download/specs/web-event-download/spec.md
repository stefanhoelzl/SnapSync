## Purpose

The no-app browser download experience for an event's photos. SnapSync is iOS-only, so a guest who opens
an invite link on Android, a desktop browser, or an iPhone without the app cannot participate — yet the
event's shared photos exist and are theirs to keep. This capability owns the page that `GET /join` serves
to every no-app visitor: a single, static, self-contained HTML+JS page that reads the `eventId` from the
URL fragment (which a browser never transmits on its own), fetches the event's name and photo union, and
assembles **all** the photos into a zip **in the browser** for a one-click download — with no app, no
account, and no service worker. The event byte reads it depends on are un-attested by
`device-attestation`; the reads it makes carry the `eventId`, which is the read capability.

Decision record: `changes/web-event-download`.

## ADDED Requirements

### Requirement: The join path serves a static no-app download page

`GET|HEAD /join` SHALL return a single **static HTML+JS page** (`200`), not a redirect. The page SHALL be
**identical bytes for every event link**: the backend SHALL read no storage, hold no per-event state, and
carry no side effect when serving it, and SHALL NOT read the link payload (it rides in the URL fragment,
which a browser never transmits). The page MAY be served with a `public` cache directive (it is the same
constant asset for every request). It SHALL be reachable without a `device-attestation` token.

The page SHALL present, **unconditionally and with no User-Agent detection**, both a control to **download
all of the event's photos as a zip** and a link to **install SnapSync from the App Store**. No
User-Agent branching is needed because iOS-with-app never reaches `/join` — the operating system claims
the Universal Link before the request is made — so every visitor who lands on the page is a no-app
visitor.

#### Scenario: The join path returns the download page, not a redirect

- **WHEN** `GET /join` is requested (by any no-app visitor, with or without a token)
- **THEN** the backend responds `200` with the static download page — not a `302` redirect and not `401`

#### Scenario: The page is identical for every event link

- **WHEN** `GET /join` is requested for two different event links
- **THEN** the backend returns byte-identical page content and reads no event state, because the payload
  rides in the fragment and never reaches the backend

#### Scenario: The page offers both download and install

- **WHEN** the download page is rendered
- **THEN** it presents both a "download all photos (zip)" control and a "Get SnapSync" App Store link,
  with no User-Agent-conditional variation of the served bytes

### Requirement: The page resolves the event client-side from the fragment

The page's JavaScript SHALL read the `eventId` from the URL fragment and decode it using the same wire
contract as capability `event-link` (`#v=3&d=<base64url(json)>`). The `eventId` SHALL be transmitted only
to the backend's own-origin read routes (the event marker and the event union); it SHALL NOT be placed in
any request to a third-party host. When the fragment is absent, malformed, or carries an `eventId` for
which the event marker read returns "not found", the page SHALL show a clear "invalid or expired link"
state rather than an empty or broken page or an attempted download.

#### Scenario: A valid link resolves to the event

- **WHEN** the page loads with a fragment carrying a valid `eventId` for an existing event
- **THEN** the JavaScript decodes the `eventId`, fetches the event name (`GET /events/:id`) and union
  (`GET /events/:id/files`), and enables the download control

#### Scenario: A missing or unknown event shows an invalid-link state

- **WHEN** the page loads with no fragment, a malformed fragment, or an `eventId` whose marker read
  returns `404`
- **THEN** the page shows an "invalid or expired link" message and offers no download

### Requirement: Download assembles the event's primary media into an in-memory zip

On the download action, the page's JavaScript SHALL fetch, for **each asset in the event union**, that
asset's **primary resource** (`role == "primary"`) directly from its presigned URL, assemble them into a
single zip **in the browser's memory**, and trigger a normal browser download of that zip named
`<event-name>.zip`. It SHALL include **one file per asset** — the primary medium (a still image, or the
single original) — across every contributing device, because a no-app guest has no own-device scope to
filter by. A paired Live-Photo / GIF **`live`** video resource SHALL be **excluded** (the still is the
photo the guest wants; the paired video is not downloaded). The download SHALL use **no service worker**
and **no server-side zip** (Bunny Edge Scripting caps a request at 50 subrequests / 128 MB / 30 s CPU, so
a server-side zip is impossible for a real event; the browser has no per-request subrequest cap, and the
presigned URLs already answer cross-origin browser `fetch` with permissive CORS). While the download runs,
the page SHALL disable the download control and show a progress indicator.

#### Scenario: Download produces a zip of the primary media

- **WHEN** the visitor triggers the download for an event whose union has N assets
- **THEN** the browser fetches each asset's primary resource from its presigned URL and downloads a single
  `<event-name>.zip` containing N files (one primary per asset), built in memory with no service worker and
  no server-side zip step

#### Scenario: A paired Live-Photo video is not downloaded

- **WHEN** an asset in the union carries both a `primary` still and a paired `live` video resource
- **THEN** the zip contains only the `primary` still for that asset; the `live` video is excluded

#### Scenario: The control is disabled with progress while downloading

- **WHEN** a download is in progress
- **THEN** the download control is disabled and a progress indicator is shown, both restored when the
  download completes or fails

#### Scenario: A large event is bounded only by the browser's memory

- **WHEN** the event union is large enough to exceed available tab memory
- **THEN** the failure is the browser's own out-of-memory behavior — the backend performs no zip and
  imposes no per-request asset cap of its own (deferred: streaming-to-disk is a later enhancement)

### Requirement: The download page is fully self-contained

The download page SHALL load **no third-party resources**: all CSS, all JavaScript, and the zip library
SHALL be inlined into the page or served from the SnapSync origin. This is load-bearing for the
`event-link` fragment-privacy property — a subresource fetched from a third-party host would carry the
page URL (and thus the `eventId` fragment risk) off-origin via `Referer`.

#### Scenario: No off-origin subresource

- **WHEN** the download page and its assembly logic run
- **THEN** every resource it loads is inlined or same-origin, and no request is made to a third-party
  host

### Requirement: A web download has no membership side effect

Serving the download page and performing a web download SHALL register nothing on the backend: no device
record, no device manifest, no push token, and no analytics or download-tracking write. It is a **pure
read** — the visitor is invisible to the backend except in ordinary request logs.

#### Scenario: Download writes nothing

- **WHEN** a visitor loads the page and downloads the event's photos
- **THEN** the backend creates or updates no device record, manifest, push token, or analytics object —
  only reads occur
