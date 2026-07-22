# event-link Specification

## Purpose

The SnapSync event link — the HTTPS Universal Link `https://snapsync.stho.net/join#v=3&d=<payload>` that
carries an invitation to an event. It exists so an invite reaches someone who **does not yet have
SnapSync**: iOS opens the app when it is installed, and the backend redirects to the App Store when it is
not. Its payload rides in the URL **fragment**, which a browser never transmits, so the `eventId` — which
*is* the upload capability — never reaches a server even on that fallback path.

This capability owns the URL and payload contract, the pure structural decoder, the `ConfigSource`/
`ConfigStore` seams, the iOS file-backed store (the App-Group config file of record; writes are
file-only since the migration finale ended 11a's Keychain write-through, with a read-only
legacy-Keychain fallback until the post-ship Stage-2 change), the codec (`encodeEventUrl`/`decodeEventUrl`) that
is the link's single encoder-decoder pair (the in-app invite QR, capability `event-invite-qr`, renders
its output), the Apple App Site Association document the link depends on, and the `GET /join` App
Store fallback.

Supersedes `deeplink-config` and its retired `snapsync://config?…` custom scheme, whose three premises —
no domain to own, no AASA to serve, and an app that is "always installed" — had all expired.

Decision record: `changes/archive/2026-07-16-migrate-to-universal-links`
## Requirements
### Requirement: Event link URL and payload contract

The system SHALL define an HTTPS **Universal Link** whose event link has the form
`https://snapsync.stho.net/join#v=3&d=<payload>`, where `<payload>` is the base64url encoding (RFC 4648
§5, no padding) of a UTF-8 JSON object whose **required** key is `eventId` (a string) and whose
**optional** keys are `autoJoin` (a boolean), `minPhotoDate` (a string), `maxPhotoDate` (a string),
`direction` (a string), and `saveToAlbum` (a boolean) — the event-link wire payload
(`EventLinkPayload`). The `eventId` SHALL be a high-entropy **canonical UUID**; possession of it is the
upload capability (the edge endpoint authorizes by event id alone). `autoJoin` SHALL default to `false`
when absent; it is a **dev/test** hint that requests the join gate auto-confirm (see capability
`join-event`). `minPhotoDate` SHALL be absent by default; it is a **dev/test** capture-date cutoff (a UTC
`yyyy-MM-dd'T'HH:mm:ss'Z'` string, capability `photo-selection-policy`) that, on an auto-confirmed join,
forces a specific cutoff so a headless launch can observe date filtering. `maxPhotoDate` SHALL be absent by
default; it is a **dev/test** capture-date **ceiling** (a UTC `yyyy-MM-dd'T'HH:mm:ss'Z'` string, capability
`photo-selection-policy`), mirroring `minPhotoDate` from the top: on an auto-confirmed join it forces a
specific upper bound so a headless launch can observe the upper-bound filtering. Like `minPhotoDate` is
clamped to the event floor, `maxPhotoDate` SHALL be clamped to the event `endsAt` on the far side (in
`JoinEvent`, capability `join-event`), so a hostile-link value is always bounded to the event window.
`direction` SHALL be absent by default; it is a **dev/test** participation-direction override — one of
`both`, `upload`, or `download` — that, on an auto-confirmed join, forces the joined membership's direction
(see capability `join-event`). `saveToAlbum` SHALL be absent by default; it is a **dev/test** override (a
boolean) that, on an auto-confirmed join, forces whether the membership gathers its synced photos into an
event album (capability `event-album`), so a headless launch can exercise album placement without an
interactive tap. None of `autoJoin`, `minPhotoDate`, `maxPhotoDate`, `direction`, or `saveToAlbum` SHALL be
emitted by the canonical QR encoder — real invite QRs carry `eventId` only. The upload **host** SHALL NOT
appear in the payload: it is fixed at compile time by the extension's `BackgroundUploadURLBase` (capability
`ios-photokit-upload`). The payload carries **no storage credential** and **no event name** — the name is
not carried in the QR; a joined device fetches it by `eventId` (see *Event name is fetched, not carried in
the event link*). `v` SHALL be the integer format version, `3` (the optional `autoJoin`, `minPhotoDate`,
`maxPhotoDate`, `direction`, and `saveToAlbum` keys are additive within `v=3` and do not bump the version;
the migration from the retired `snapsync://config?…` form did not bump it either, because the payload is
unchanged and the URL prefix already distinguishes the forms).

The payload SHALL be carried entirely in the link's **fragment** — never the query string. Because a
browser never transmits the fragment component to a server, the `eventId`, which is the upload
capability, never reaches the backend, its CDN, or their access logs, even when the link is opened on a
device that does not have the app. Under the retired `snapsync://` scheme this property was incidental —
no server could observe a custom-scheme URL at all — but under a Universal Link it is **deliberate and
purchased**, and moving the payload to the query string would silently forfeit it. There is no server
round-trip and no token: the link remains self-contained.

#### Scenario: Canonical event URL shape
- **WHEN** an event link is constructed for an `EventLinkPayload` with no optional keys
- **THEN** it is `https://snapsync.stho.net/join#v=3&d=<base64url(json)>` and the decoded JSON has the
  string key `eventId` and no other keys (no `autoJoin`, no `minPhotoDate`, no `maxPhotoDate`, no
  `direction`, no `saveToAlbum`, no `name`, no host, no credential)

#### Scenario: The payload rides in the fragment, not the query
- **WHEN** an event link is constructed for any `EventLinkPayload`
- **THEN** everything after `/join` is carried after `#`, and the URL's query component is empty — so a
  request for the link's path carries no payload

#### Scenario: A dev link carries the autoJoin flag
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true }`
- **THEN** the decode succeeds carrying that `eventId` and `autoJoin == true`

#### Scenario: A dev link carries an explicit cutoff
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "minPhotoDate": "2026-07-06T14:32:11Z" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `minPhotoDate` cutoff string

#### Scenario: A dev link carries an explicit capture-date ceiling
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "maxPhotoDate": "2026-07-21T23:59:59Z" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `maxPhotoDate` ceiling string

#### Scenario: A dev link carries an explicit direction override
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "direction": "download" }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `direction` override `download`

#### Scenario: A dev link carries an explicit saveToAlbum override
- **WHEN** an event link is decoded whose payload JSON is `{ "eventId": <uuid>, "autoJoin": true, "saveToAlbum": true }`
- **THEN** the decode succeeds carrying that `eventId`, `autoJoin == true`, and the `saveToAlbum` override `true`

### Requirement: Pure structural decoder

The capability SHALL provide a pure, platform-agnostic (`commonMain`) function that decodes a
raw event-link URL string into either a valid `EventLinkPayload` or a typed failure, performing
**structural-only** validation and **no** network I/O. Validation SHALL require: the URL begins with the
canonical origin and path (`https://snapsync.stho.net/join#`, matched against the single-sourced
`LINK_ORIGIN` constant); `v == 3`; `d` is valid base64url; the decoded bytes are
valid UTF-8 JSON with the key `eventId` present, **non-empty**, and a **canonical UUID**
(`8-4-4-4-12` hex, case-insensitive), and **at most** the additional optional keys `autoJoin` (a boolean),
`minPhotoDate` (a non-empty string), `maxPhotoDate` (a non-empty string), `direction` (one of the strings
`both`, `upload`, `download`), and `saveToAlbum` (a boolean). Any deviation — including a retired
`snapsync://` URL, a foreign origin, a version other than `3` (so the v1/v2 S3 payloads are rejected), a
missing/empty `eventId`, a **genuinely unknown** key (anything other than
`eventId`/`autoJoin`/`minPhotoDate`/`maxPhotoDate`/`direction`/`saveToAlbum`), a `direction` outside the
allowed set, or an `eventId` that is not a canonical UUID — SHALL produce a typed
failure result, never a partially-populated payload and never a thrown exception that escapes the
decoder. On success the result SHALL carry the `eventId`, the resolved `autoJoin` value (defaulting to
`false`), the `minPhotoDate` cutoff when present (else absent), the `maxPhotoDate` ceiling when present
(else absent), the `direction` override when present (else absent), and the `saveToAlbum` override when
present (else absent).

The decoder SHALL match the origin **strictly**, as a single prefix, and SHALL NOT parse the URL with a
structured URL type. A foreign origin cannot reach the decoder in production — `.onOpenURL` fires for a
Universal Link only when the app's own entitlement names the domain, and the dev launch-environment
trigger requires a developer launch — so strict matching is chosen for being *less* code than searching
for the path inside an arbitrary string, not as a security control.

#### Scenario: Well-formed payload decodes
- **WHEN** a `https://snapsync.stho.net/join#v=3&d=…` URL whose payload carries a single non-empty
  canonical-UUID `eventId` is decoded
- **THEN** the result is a success carrying the `EventLinkPayload` with that exact `eventId`, `autoJoin == false`, no `minPhotoDate`, no `maxPhotoDate`, no `direction`, and no `saveToAlbum`

#### Scenario: Malformed payload fails cleanly
- **WHEN** the URL has a foreign origin, a wrong path, a version other than `3` (including a legacy `v=2`
  S3 payload), undecodable base64url, non-JSON bytes, a missing/empty `eventId`, a genuinely unknown key
  (other than `eventId`/`autoJoin`/`minPhotoDate`/`maxPhotoDate`/`direction`/`saveToAlbum`), a `direction`
  outside `both`/`upload`/`download`, or an `eventId` that is not a canonical UUID
- **THEN** the decoder returns a typed failure and no `EventLinkPayload`, without throwing

#### Scenario: A retired snapsync:// link is rejected
- **WHEN** a `snapsync://config?v=3&d=…` URL is decoded
- **THEN** the decoder returns a typed failure, so a link shared before the migration fails closed and
  visibly rather than provisioning

#### Scenario: Legacy S3 config is rejected
- **WHEN** a `v=2` S3 payload (`bucket`/`region`/`accessKeyId`/`secretAccessKey`) is decoded
- **THEN** the decoder returns a typed failure (unsupported version), so an upgraded device falls
  through to the "not joined" create layer and the user rescans the new event QR

### Requirement: Apple App Site Association is served for the link domain

The backend SHALL serve an Apple App Site Association (AASA) document at
`GET|HEAD /.well-known/apple-app-site-association` for the link domain, with `Content-Type:
application/json` and **no redirect**, and it SHALL be reachable without a device-attestation token
(capability `device-attestation`). The document SHALL declare exactly the app's `appID`
(`<TEAM_ID>.app.snapsync`) and SHALL match the path `/join` using the `components` form, matching on the
**path only** — not on the query and not on the fragment. The background upload extension SHALL NOT
appear in the document: it never handles URLs.

Matching on the path alone is deliberate. A malformed or truncated event link then opens the app and
surfaces the invalid-link error (capability `join-event`) rather than dead-ending silently in a browser —
a visible failure is preferred to an invisible one. The narrow path also SHALL NOT match `/`, the API
routes, or `/attest/*`, so the marketing page and the API continue to open in a browser.

The document SHALL be **source-owned** (embedded in the deployed bundle, no runtime file read), and its
domain SHALL agree with the app's `applinks:` entitlement and the app's `LINK_ORIGIN` (capability
`architecture-guards`, *The event-link domain agrees across the app and the backend*).

#### Scenario: The AASA is served unauthenticated as JSON
- **WHEN** `GET /.well-known/apple-app-site-association` is requested without an attestation token
- **THEN** it is answered `200` with `Content-Type: application/json` and no redirect

#### Scenario: The AASA declares the app and the /join path only
- **WHEN** the served AASA document is parsed
- **THEN** its `appIDs` contain exactly the app's `<TEAM_ID>.app.snapsync` (and not the extension), and
  its `components` match the path `/join` with no query or fragment constraint

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

### Requirement: Config source and store seams

The capability SHALL define a persisted, joined-event state type **`EventConfig { eventId: String,
name: String, minPhotoDate: String?, direction: Direction, saveToAlbum: Boolean }`** (distinct from the
event-link wire type `EventLinkPayload`): `eventId` is the joined event; `name` is the human-readable event
name — a **required, non-null** value (the join gate only provisions from a loaded phase that carries a
name, capability `join-event`; a legacy item persisted without a name decodes to an empty string and is
refreshed on foreground, so the type is never null); `minPhotoDate` is this device's chosen capture-date
cutoff for the event (capability `photo-selection-policy`), **nullable** (absent = whole-library scope);
`direction` is this device's chosen participation direction — a `Direction` enum with values `Both`,
`UploadOnly`, `DownloadOnly` — that **SHALL default to `Both`** when absent from persisted or decoded
state; `saveToAlbum` is whether this membership gathers its synced photos into an event album (capability
`event-album`) and **SHALL default to `false`** when absent (so an `EventConfig` persisted before this
field existed reads as `false`, today's no-album behavior). The capability SHALL define a `ConfigSource`
state port exposing `config: StateFlow<EventConfig?>` — a level-triggered holder whose current value (the
active config, or `null` when none) is always available synchronously — and a `ConfigStore` command port
with `suspend fun save(config: EventConfig)` that persists the config and updates the source, and
`suspend fun clear()` that removes it and updates the source to `null`. `save` of a config equal to the
current one (same `eventId`, `name`, `minPhotoDate`, `direction`, **and** `saveToAlbum`) SHALL be an
idempotent no-op; a `save` differing in any of those SHALL replace it and emit (a name-only change updates
the title without any ledger effect; the switch-reset on an `eventId` change is orchestrated by the
provision path, not this seam). `clear` SHALL remove the persisted config and set the source to `null`,
and SHALL NOT touch the ledger, **and SHALL NOT clear the event-album map** (capability `event-album`,
which persists `eventId → albumLocalId` in a separate store that survives leave); `clear` when absent
SHALL be an idempotent no-op. Consumers SHALL depend on each port separately.

#### Scenario: Source seeds the current config synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a config is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfig` (eventId, name, any minPhotoDate, its direction, and its saveToAlbum) without waiting for an emission

#### Scenario: A pre-existing config without saveToAlbum reads as false
- **WHEN** a `ConfigSource` is constructed over a persisted `EventConfig` serialized before the `saveToAlbum` field existed
- **THEN** `config.value.saveToAlbum` is `false` (the default), preserving today's no-album behavior

#### Scenario: A pre-existing config without a name decodes non-null
- **WHEN** a `ConfigSource` is constructed over a persisted `EventConfig` serialized without a name
- **THEN** `config.value.name` is a non-null empty string (refreshed on the next foreground fetch), never a decode error

#### Scenario: Saving a name-only update emits without a switch
- **WHEN** `save` is invoked with the same `eventId`, `minPhotoDate`, `direction`, and `saveToAlbum` and a newly-fetched `name`
- **THEN** the persisted config's name is updated and `config` emits, with no ledger reset

#### Scenario: Saving a different event hot-swaps the source
- **WHEN** `save` is invoked with a different `eventId`
- **THEN** the persisted config is replaced and `config` emits the new `EventConfig`

#### Scenario: Saving an identical config is a no-op
- **WHEN** `save` is invoked with a config equal to the current value (same eventId, name, minPhotoDate, direction, and saveToAlbum)
- **THEN** no change and no redundant emission occur

#### Scenario: Clearing removes the config but keeps the album map
- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the persisted config is removed and `config` emits `null`, with no change to the ledger and no change to the event-album map

### Requirement: An unreadable config is not an absent config

The config seam SHALL distinguish three outcomes: a **readable** config, a **definitely absent**
config, and an **unreadable** config. An unreadable config SHALL NOT be reported as an absent
config.

The distinction is grounded on the App-Group config file: **definitely absent** SHALL mean
exactly that the file read failed with the **not-found error class**
(`NSFileReadNoSuchFileError` 260 / `NSFileNoSuchFileError` 4 / POSIX `ENOENT`) **and** the
read-only legacy-Keychain fallback reported item-not-found (while the fallback lasts — it is the
installed base's update path, and its deletion is a designated post-ship change; capability
`event-rejoin-reconciliation` records the staging). A read that fails for **any other reason** —
notably the permission-class failure of a protected-file read before first unlock, an unreadable
legacy-Keychain fallback, a missing App-Group container, or file content this build cannot
positively interpret (a foreign envelope version or an undecodable current-version payload) —
SHALL be **unreadable**. The absence class is
a closed whitelist, deliberately: an unrecognized error shape lands on the unreadable side, where
the cost is a deferred cycle, not a false leave.

A reader that acts on the absence of a config — in particular the re-join reconciliation, for
which "no event configured" means *the device left the event* and triggers clearing the persisted
`joinedEventId` marker (capability `event-rejoin-reconciliation`) — SHALL act **only** on a
definitely absent config. On an unreadable config **the upload cycle** SHALL skip entirely: it
SHALL NOT reconcile, SHALL NOT clear the join marker, SHALL NOT reset the discovery cursor, and
SHALL NOT create upload jobs; the cycle SHALL complete cleanly and the next cycle SHALL retry.

This SHALL hold on **every upload tier and at every trigger**, not only where the OS is the
invoker. The tiers differ in who invokes a cycle — the OS on iOS ≥26.1, the app on iOS 18–26.0 —
and not in what an unreadable membership means. A tier SHALL NOT reach this decision through a
two-state read that cannot express "unreadable"; the three-state read is the only permitted path
(capability `upload-lifecycle`, which owns where the decision is made).

Conflating the two is what makes an ordinary locked-device wake perform a *false leave*: the
marker is cleared, and the next readable cycle sees a marker mismatch and pays for a full re-join
reconciliation (a device listing, an atomic ledger clear-and-seed, and a discovery-cursor reset
that forces a complete library re-enumeration) — repeatedly, without the marker ever settling.

#### Scenario: An unreadable config does not clear the join marker

- **WHEN** an upload cycle reads the config and the read fails because protected data is
  unavailable (the file read fails permission-class before first unlock)
- **THEN** the cycle is skipped, the reconciliation is not invoked, the persisted `joinedEventId`
  marker is left intact, the discovery cursor is not reset, and the cycle completes cleanly

#### Scenario: A definitely-absent config still drives the leave path

- **WHEN** an upload cycle reads the config, the file is missing by the not-found error class, and
  the read-only legacy-Keychain fallback reports no such item
- **THEN** the reconciliation runs for the no-config case and clears the `joinedEventId` marker,
  exactly as a leave requires

#### Scenario: A missing file with an unreadable legacy Keychain stays unreadable

- **WHEN** an upload cycle reads the config, the file is definitively missing, and the fallback
  read fails (protected data unavailable)
- **THEN** the read reports unreadable — a pre-11a joined install on a locked device is
  indistinguishable from a left device here, so absence is unproven — and the cycle skips

#### Scenario: A joined device stays settled across locked wakes

- **WHEN** a joined device runs cycles repeatedly while locked and its config is unreadable
- **THEN** its join marker still matches its configured event on the next readable cycle, so no
  re-join reconciliation, ledger re-seed, or full re-enumeration is performed

#### Scenario: The app-driven tier skips rather than leaves

- **WHEN** the app-driven tier (iOS 18–26.0) runs a cycle from any trigger — foreground, background task,
  silent push, or session events — and the config read fails because protected data is unavailable
- **THEN** the cycle is skipped, the `joinedEventId` marker is left intact, and the membership survives —
  the same outcome the OS-invoked tier produces

### Requirement: Event name is fetched, not carried in the event link

The event `name` SHALL be obtained by `eventId`, never from the QR. On **scan-provision** (a decoded
`EventLinkPayload`), the join gate (capability `join-event`) SHALL fetch `GET /events/:id` for the
event's `name` **and** `createdAt` before the confirm, and provision (save the config, with the name and
the chosen cutoff) only on confirm. On **create**, the create path SHALL route the minted `eventId` into
that **same** join gate (capability `event-creation-ui`), which fetches `GET /events/:id` for the `name`
and `createdAt` and provisions on confirm — the create path itself SHALL save no config directly. In both
paths the fetched `createdAt` SHALL seed the default cutoff (capability `photo-selection-policy`). The event
`name` is **required and non-null**: the join gate treats a details response lacking a name as a
retryable failure, never a loaded phase, so a provisioned `EventConfig` always carries a real name (the
backend enforces name-required on create, capability `event-creation`). The name SHALL be refreshed by
re-fetching `GET /events/:id` on **foreground entry**. A failed or unreachable fetch SHALL leave the
last-known name unchanged and SHALL NOT affect syncing.

#### Scenario: Scan routes to the gate, which fetches name and createdAt
- **WHEN** a valid event QR is scanned
- **THEN** the join gate fetches `GET /events/:id` for the `name` and `createdAt`, seeds the cutoff default from `createdAt`, and provisions the config (non-null name + chosen cutoff) on confirm

#### Scenario: Create routes to the gate, which fetches details
- **WHEN** an event is created and `POST /events` returns `{eventId, name, createdAt}`
- **THEN** the minted `eventId` is routed into the join gate, which fetches `GET /events/:id` for the `name` and `createdAt`, and the config is saved only on confirm

#### Scenario: A failed name fetch does not block the joined sync
- **WHEN** the `GET /events/:id` fetch fails or the device is offline after provisioning
- **THEN** the config's name remains its last-known value, and sync proceeds normally, and a later foreground refresh may update the name

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

### Requirement: iOS file-backed config store

The capability SHALL provide an iOS adapter (`iosMain`, `:adapter:ios:ext-safe` — both processes
link it) implementing `ConfigSource`, `ConfigStore`, and the three-state `ConfigReader` against a
**single file in the App-Group container root** — filename `eventconfig.json`, a pinned
runtime-identity literal (capability `architecture-guards`) — holding a **versioned envelope**
`{"v": 1, "payload": <serialized EventConfig>}`. The payload carries the whole `EventConfig` (its
`eventId`, `name`, its **required, non-null** `minPhotoDate`, its `startsAt`, its `direction`, and
its `saveToAlbum`), so the background upload extension reads the `eventId`, the cutoff, and the
album flag from the same file the app writes. The envelope codec and the read algorithm SHALL be
pure `:domain` functions covered in `commonTest` (JVM **and** iOS simulator); the adapter SHALL
contain only file IO and error mapping.

Writes SHALL be **atomic** (temp file + rename) under
`NSFileProtectionCompleteUntilFirstUserAuthentication` — readable while the device is locked once
it has been unlocked since boot, because the OS invokes the upload extension while the device is
idle and therefore usually locked (the same class as the sibling App-Group stores).

**No config value is ever written to the Keychain again; the read keeps the legacy fallback.**
The migration finale ended the 11a Keychain **write-through**: `save` SHALL write the file alone,
so the revert direction is sacrificed, consistent with fix-forward. `clear` SHALL delete the
legacy Keychain item **first** and the file second (both idempotent) — the 11a clear contract's
surviving half, load-bearing while the fallback lasts: a file-only clear would leave exactly the
missing-file + item-present state the fallback resurrects, silently undoing the leave on every
migrated device; a crash between the two leaves the file present, so this build stays joined and
the leave retries. The READ SHALL keep the adapter-resident migration fallback through the
legacy-Keychain seat (`KeychainConfigReader` — read + the leave-path delete only, no save; it may
repair a legacy item's accessibility class in place, value untouched): a
read whose file is **definitively missing** (the not-found error class only) SHALL consult it,
and a found config is returned **and atomically written into the file** (best-effort: a failed
migration write returns the fallback's answer and retries on the next read; after the write the
fallback is re-checked — compare-and-repair — and a value a concurrent save/clear superseded is
repaired to, and answered with, the fresh state); a definitively-absent item reads as no config;
an unreadable item reads as unreadable. The fallback SHALL live in the adapter — not in app
startup — so it runs in **whichever process reads first** (the OS can schedule the upload
extension before the user ever opens the updated app; app + extension update atomically).

The fallback outlives the write-through **because of the ship model**: this branch reaches the
installed base as one merge, so at ship (update) time every joined production device is a
pre-11a device whose file has never existed — without the fallback, the update itself would read
every joined device as left. Deleting the fallback (and only then retiring the pair's
runtime-identity pin) is the designated post-ship Stage-2 change, gated on production soak
(capability `event-rejoin-reconciliation`).

One accepted Stage-1 divergence, on record: because `save` no longer maintains the legacy item, a
migrated device that **switches** events leaves a stale legacy item behind (holding the previous
membership), and a reinstall before Stage 2 then resurrects that *previous* membership rather
than the current one — bounded (the device was genuinely a member of it; the switch already
issued its best-effort backend leave; re-scanning converges) and it dies with the Stage-2
fallback deletion. Maintaining the item on save would be the write-through this change ends.

**Version handling.** Decoding SHALL ignore unknown keys on both the envelope and the payload (a
same-version additive change needs no version bump, and the `EventConfig` legacy-field defaults
apply exactly as before — an item without `saveToAlbum`/`direction` decodes to `false`/`Both`, a
missing `name` to the empty string). A **current-version** payload lacking `minPhotoDate` SHALL
fail to decode and read as **unreadable** — no default cutoff substituted, the failure logged, no
upload until the user re-scans (a save overwrites the file). The Keychain legacy-item rule — an
undecodable item reads as no config — deliberately does NOT transfer to the file: the adapter's
own atomic writes make an unusable current-version file unreachable, so one is an unexplained
state, and an unexplained state defers rather than driving a leave; the rule stays in force on
the read-only fallback side. A file whose envelope
version is **not** this build's, or whose content is not an envelope at all, SHALL read as
**unreadable** — never as absent, never a crash — so a build that opens a successor's file defers
instead of reading a leave.

The adapter SHALL seed its `config` `StateFlow` synchronously at construction from the same read
(mapping both *absent* and *unreadable* to `null` — acceptable for the UI, never for the
reconciler, which uses the three-state `ConfigReader`), and SHALL expose a `reload()` the trigger
flows call before acting (migration step 12 — the trigger-time membership re-read replaced the
protected-data unlock hook; see `ios-app-shell`): a background construction before first unlock
seeds `null` (the protected read fails permission-class → unreadable) and is repaired at the next
trigger. `reload()` SHALL apply the pure, tested merge rule (`configAfterReload`): a conclusive
read (joined / definitively absent) replaces the `StateFlow` value; an **unreadable** read
**retains** the last good one — at trigger cadence a transient read failure must not clear a good
membership and flip the screen to the setup gate. The persisted file
SHALL survive app updates and process death. It is **not excluded from device backups** — the
membership's backup/restore continuity is deliberate, matching the Keychain item's non-ThisDeviceOnly
posture (decision record: `changes/archive/migrate-config-to-app-group-file`, D6).

#### Scenario: Persisted config survives relaunch from the file

- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfig`, read from the
  App-Group file without consulting the Keychain

#### Scenario: The extension reads the config file on a locked device

- **WHEN** the OS invokes the upload extension while the device is locked, and the device has been
  unlocked at least once since boot
- **THEN** the file is read successfully and the cycle proceeds with the persisted config

#### Scenario: Save writes the file alone

- **WHEN** `save` persists a config
- **THEN** only the App-Group file is written — no Keychain item is touched (the write-through is
  ended) — and `config` emits the new value

#### Scenario: Clear removes the legacy item first, then the file

- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the legacy Keychain item is (best-effort, idempotently) deleted before the file,
  `config` emits `null`, and at no crash point does the store rest in the missing-file +
  present-item state the read fallback would resurrect

#### Scenario: A pre-file joined device migrates on first read, in whichever process runs first

- **WHEN** a device joined under a Keychain-era build updates to this build (legacy item present,
  no file) and either process — the app **or** the OS-scheduled upload extension — performs the
  first read
- **THEN** the read returns the legacy config (never a false not-joined), writes it atomically
  into the App-Group file, and subsequent reads answer from the file alone

#### Scenario: A failed migration write does not fail the read

- **WHEN** the fallback finds a legacy config but the file write fails
- **THEN** the read still returns that config, and the next read retries the migration

#### Scenario: A future-version file reads as unreadable, never a leave

- **WHEN** a read finds a file whose envelope version is not this build's (e.g. a revert build
  opening a successor's file)
- **THEN** the read reports **unreadable** — the cycle skips, no marker is cleared, no upload runs
  — and never reports no-config

#### Scenario: A current-version file without a cutoff reads as unreadable

- **WHEN** a read finds a current-version envelope whose payload lacks `minPhotoDate`
- **THEN** the decode fails, the failure is logged, the read reports **unreadable** (never
  no-config — no marker is cleared), no default cutoff is substituted, and no upload occurs until
  the user re-joins

#### Scenario: A trigger-time reload retains the membership on a transient failure

- **WHEN** a trigger flow's `reload()` runs while the file read transiently fails (unreadable, not
  absent) and the `StateFlow` holds a joined config
- **THEN** the `StateFlow` retains the joined config — the screen does not regress to the setup
  gate — and a later conclusive read replaces it

#### Scenario: A reinstall while the read fallback lasts resurrects from the legacy item

- **WHEN** the app is deleted and reinstalled (the App-Group file is wiped; the legacy Keychain
  item survives uninstall) while the read-only fallback is still in force
- **THEN** the first read falls back to the surviving legacy config, migrates it into the file,
  and the device remains joined — indistinguishable from an update-in-place, by design; the
  "reinstall = left the event" end state takes effect only when the post-ship Stage-2 change
  deletes the fallback (capability `event-rejoin-reconciliation`)

#### Scenario: No config anywhere reads as null

- **WHEN** the adapter is constructed with no file and no legacy Keychain item present
- **THEN** `config.value` is `null`

