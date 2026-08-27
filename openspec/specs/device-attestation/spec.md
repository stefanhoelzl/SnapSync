# device-attestation Specification

## Purpose

An anti-abuse gate that lets the backend serve **only a genuine, unmodified SnapSync running on a genuine
Apple device**, and nothing else.

Every route ships against a device-facing host baked in plaintext into every IPA, and the device id is
self-asserted — so without a gate, anyone who reads the host out of the app can `PUT` arbitrary bytes into
the bunny storage zone that holds every user's photos, or `POST /api/v1/events` without bound, for the cost of a
guessed UUID. Apple's App Attest is the standing answer: the app proves, cryptographically and per device,
that it is the real binary on real hardware, mints a bearer token from that proof, and carries the token on
every request. The backend admits only requests bearing a valid token; a short closed list of routes
(`/api/v1/attest/*`, which issue the token, and `OPTIONS`) is necessarily ungated.

App Attest is the **iOS binding of a platform-neutral need** — prove the caller is a genuine,
unmodified client build — not a commitment to Apple hardware. A future Android client would bind its
own platform attestation (Play Integrity) behind the same attest→token mint, leaving the token
contract and every gated route unchanged; what is Apple-specific is the proof, not the gate.

This is an **anti-abuse** mechanism, not a privacy or ownership one: the token attests the *app*, not the
*user*, and says nothing about which device may read whose photos. The token is minted by the app process
(App Attest is unavailable in an extension) and shared with the upload extension through the Keychain; an
expired token stalls uploads and is renewed on the next wake, but never causes a photo to be lost.

Decision record: `changes/archive/2026-07-14-add-device-attestation`.
## Requirements
### Requirement: Only an attested SnapSync instance may call the API

Every backend route SHALL require a valid **device token** — a backend-minted, HMAC-signed bearer
credential obtainable **only** by completing App Attest — except for the closed exception list in
"Ungated routes are a closed list". A request without a valid token SHALL be rejected with `401` and
SHALL perform no storage read or write.

The property the token establishes is: **a genuine, unmodified SnapSync running on a genuine Apple
device asked for this token**. It SHALL NOT be construed as proof that the named device owns the
partition it writes to — partition ownership remains capability-based on the unguessable device UUID
(see "Non-goals").

#### Scenario: An unauthenticated request is refused

- **WHEN** a request arrives at a gated route with no `Authorization` header
- **THEN** the endpoint responds `401` and issues no upstream storage read or write

#### Scenario: A forged or tampered token is refused

- **WHEN** a request carries an `Authorization: Bearer <token>` whose HMAC signature does not verify
- **THEN** the endpoint responds `401` and issues no upstream storage read or write

#### Scenario: An attested device is served

- **WHEN** a request carries a token whose signature verifies and whose expiry is in the future
- **THEN** the request proceeds exactly as it did before this capability existed

### Requirement: Attestation mints a device token

The backend SHALL expose `POST /api/v1/attest/token`, which accepts a `deviceId`, an App Attest `keyId`, and an
attestation object, and SHALL verify **all** of: the attestation's certificate chain to Apple's App
Attest root CA; that the attestation's nonce matches the challenge it was issued for; that the app-id
hash matches this app; that the signing counter is `0`; and that the `aaguid` names an accepted
attestation environment. Only when every check passes SHALL it mint a token.

The backend SHALL persist the attested public key **in the relational store, keyed by `deviceId`**
(capability `database`), so that renewal can verify a later assertion against it. It SHALL be read **only**
when renewing — never on a gated request — so that no gated route pays a read to authenticate.

The backend SHALL persist, alongside the key, the **expiry of the token it mints**. That value is what
lets the nightly sweep tell a device that may still be holding a working credential from one that cannot
(capability `scheduled-cleanup`); nothing else records it, because a minted token is verified from its own
signature and is never stored.

A route that mints a token SHALL persist before it mints, and SHALL respond `502` and mint **nothing** if
it cannot persist. A token handed out against a record the backend failed to write is a credential nothing
knows about, and the client retries at its next wake, so refusing costs nothing.

A minted token SHALL carry the `deviceId` it was minted for and an expiry, and SHALL be verifiable by
signature alone, with no storage read and no call to Apple.

#### Scenario: A valid attestation mints a token

- **WHEN** `POST /api/v1/attest/token` receives an attestation that passes every check above
- **THEN** the attested public key and the minted token's expiry are recorded against that `deviceId`, and
  a signed token for it is returned

#### Scenario: An attestation failing any check mints nothing

- **WHEN** the certificate chain, nonce, app-id hash, counter, or `aaguid` check fails
- **THEN** the endpoint responds `401`, no public key is stored, and no token is minted

#### Scenario: A record that cannot be persisted mints nothing

- **WHEN** every check passes but the record cannot be written
- **THEN** the endpoint responds `502` and no token is returned

#### Scenario: Verifying a token touches no storage and no Apple service

- **WHEN** a gated route verifies a request's token
- **THEN** the decision is made from the signature alone — no storage object is read and no request is
  made to Apple

### Requirement: Both attestation environments are accepted

The backend SHALL accept an attestation from **either** the production (`appattest`) or the development
(`appattestdevelop`) App Attest environment. A development attestation SHALL mint a token indistinguish-
able from a production one.

This is required, not merely convenient: a sideloaded dev build attests against the development
environment, and rejecting it would leave the on-device dev loop with no way to upload — there is no
local upload rig. It is safe because the attestation still binds this app's id, so only a build signed by
our team can produce one.

#### Scenario: A dev-signed build can upload

- **WHEN** a development-signed build attests and its attestation carries the `appattestdevelop` aaguid
- **THEN** a valid token is minted and the build can call every gated route

### Requirement: Renewal is a local assertion, never a re-attestation

The backend SHALL expose `POST /api/v1/attest/renew`, which accepts a `deviceId`, an App Attest **assertion**,
and the server-issued `challenge` the assertion is over; SHALL verify that assertion against the public
key **recorded for that `deviceId`**; and SHALL mint a fresh token on success. It SHALL NOT
accept a `keyId` — the stored key is found by `deviceId`, so renewal cannot be pointed at another key
(unlike `/api/v1/attest/token`, which takes a `keyId` because it is establishing which key to store).

Renewal SHALL record the new token's expiry **before** minting it, and SHALL respond `502` and mint
nothing if it cannot — the same posture as `POST /api/v1/attest/token`, for the same reason. Renewal is
attempted at every wake, so a refused renewal is retried within hours.

Renewal SHALL NOT require a new attestation and SHALL NOT call Apple. (Apple's model attests a key
**once**; repeatedly re-attesting — or minting a fresh key per renewal — is the throttled path. Making
renewal cheap is what allows it to be attempted at every wake rather than in a narrow window near
expiry.)

The backend SHALL NOT maintain an assertion counter. A replayed assertion re-mints the same device's
token, which grants nothing the caller did not already hold.

#### Scenario: A valid assertion renews the token

- **WHEN** `POST /api/v1/attest/renew` receives an assertion that verifies against the device's stored public key
- **THEN** the device's recorded token expiry is advanced and a fresh token is minted, with no call to Apple

#### Scenario: A device with no stored key must attest

- **WHEN** `POST /api/v1/attest/renew` names a `deviceId` the backend holds no attestation record for
- **THEN** the endpoint responds `401`, and the device must complete a full attestation instead

#### Scenario: A renewal whose expiry cannot be recorded mints nothing

- **WHEN** the assertion verifies but the new expiry cannot be written
- **THEN** the endpoint responds `502` and no token is returned

#### Scenario: A re-attestation after reinstall overwrites the stored key

- **WHEN** a device whose Secure-Enclave key is gone (reinstall/restore) attests again for the same
  `deviceId`
- **THEN** the attestation succeeds and the recorded key is overwritten with the new one

### Requirement: An attestation record is the device's enrolment

The backend SHALL hold a device record **if and only if** that device has completed an attestation. The
attestation route SHALL be the only route that creates one; every other route that writes device-scoped
state SHALL update an existing record and SHALL NOT create one.

This ordering is forced rather than chosen: every route other than `/api/v1/attest/*` requires a device
token, and a token is obtainable only by attesting — so no device can reach any other device-scoped write
before it has attested.

A route that requires the record and finds none SHALL respond `401`. This widens `401` from "no valid
token" to also mean "**the backend holds no attestation for this device**". The two are one answer to the
client because they have one remedy — attest afresh — and the shipped client already takes it: a `401`
drops the token, triggers a refresh, and re-sends what the refused request carried. Responding anything
else would require a client change, and a client that ignored the new status would lose the write
permanently.

The check SHALL NOT be performed by the token gate. Verifying a token touches no storage, and that is what
keeps the streaming byte-upload path free of a per-request round-trip; a route that needs the record reads
it itself, after the gate has passed.

#### Scenario: A device-scoped write with no attestation on file is refused

- **WHEN** a request bearing a valid token writes device-scoped state for a device the backend holds no
  attestation record for
- **THEN** the endpoint responds `401` and creates no record

#### Scenario: The client recovers without operator action

- **WHEN** the client receives that `401`
- **THEN** it discards its token, completes a fresh attestation — which creates the record — and re-sends
  the refused write, which then succeeds

#### Scenario: The record check is the route's, not the gate's

- **WHEN** a request reaches a route that requires the device's record
- **THEN** the gate has already admitted it on the token's signature alone, and the record is read by the
  route rather than by the gate — so a route that needs no record pays no read

### Requirement: The device-token lifetime is independent of the event lifetime

The device token's lifetime SHALL be configured independently of the event lifetime and the event window
maximum, and SHALL NOT be derived from either.

The three values may hold the same number, and today do. That agreement is a **coincidence**, not a fact:
the event lifetime and window are product rules the host's experience can argue up, while the token
lifetime bounds how long a backup-extracted token remains a usable write credential and therefore may
never be lengthened for a product reason. Deriving one from the other would make a decision to run longer
events silently a decision to widen that window.

#### Scenario: Lengthening the event lifetime does not lengthen the token

- **WHEN** a deployment raises the event lifetime or the event window maximum
- **THEN** the device-token lifetime is unchanged

### Requirement: Challenges are server-issued and time-bounded

The backend SHALL expose `GET /api/v1/attest/challenge`, returning a challenge that binds an attestation or
assertion to a bounded window. A challenge SHALL be verifiable **without** server-side state (it carries
its own authentication), so that issuing one performs no storage write. The backend SHALL reject an
attestation or assertion whose challenge is unrecognised or outside its window.

#### Scenario: Issuing a challenge writes nothing

- **WHEN** `GET /api/v1/attest/challenge` is called
- **THEN** a challenge is returned and no storage object is written

#### Scenario: A stale challenge is refused

- **WHEN** an attestation or assertion presents a challenge outside its validity window
- **THEN** the endpoint responds `401` and mints no token

### Requirement: Ungated routes are a closed list

Exactly the routes named below SHALL be reachable without a token, and the list SHALL be closed — a route
not named here SHALL require the token:

1. `GET /api/v1/attest/challenge` — it issues the input to attestation and touches no storage.
2. `POST /api/v1/attest/token` — self-authenticating (it carries the attestation).
3. `POST /api/v1/attest/renew` — self-authenticating (it carries the assertion).
4. `OPTIONS` on any path — the pull zone is free to answer the preflight itself, so the script cannot
   gate it; and a `401` there would break the plain-`PUT` fallback the iOS uploader depends on.
5. `GET /` and `HEAD /` — the public marketing/landing page (capability `marketing-site`). This exception
   SHALL be **exact-path and method-scoped**: it applies only when the method is `GET` or `HEAD` **and**
   the path is exactly `/`. It SHALL NOT be a path prefix and SHALL NOT admit any other method, so no
   gated route can be reached through it. The page reads no storage and carries no side effect, so serving
   it unauthenticated grows neither the bill nor the storage this gate protects.
6. `GET /.well-known/apple-app-site-association` and `HEAD` on the same path — the Apple App Site
   Association document that makes the event link a Universal Link (capability `event-link`). Apple's CDN
   and the device fetch it with no `Authorization` header and cannot be made to send one, so a gated route
   here would silently defeat every event link. This exception SHALL be **exact-path and method-scoped**
   on the same terms as entry 5. The document is a static, source-owned constant: it reads no storage and
   carries no side effect.
7. `GET /join` and `HEAD /join` — the no-app download page a browser reaches when an event link is opened
   on a device with no app to claim it (capabilities `event-link`, `web-event-download`). By definition
   its audience holds no attestation. This exception SHALL be **exact-path and method-scoped** on the same
   terms as entry 5. The page is a static, source-owned constant: it reads no storage, holds no per-event
   state, and carries no side effect — and it cannot read the link's payload even in principle, because
   that payload is carried in the URL fragment, which a browser never transmits.
8. `GET /api/v1/events/<eventId>/files` and `HEAD` on the same path — the event photo **union read** (capability
   `api-endpoints`), which the no-app download page fetches from a browser that holds no attestation
   (capability `web-event-download`). This exception SHALL be **method-scoped**: it admits only `GET` and
   `HEAD` on the union path; every non-`GET`/`HEAD` method on any `/api/v1/events/<eventId>/…` path (device
   manifest write, leave, notify) SHALL remain gated.
9. `GET /api/v1/events/<eventId>` and `HEAD` on the same path — the event **marker/metadata read** (capability
   `api-endpoints`), which the download page fetches to show the event name. This exception SHALL be
   **method-scoped**: it admits only `GET` and `HEAD`; `POST /api/v1/events` (creation) SHALL remain gated.

Entries 8 and 9 restate the gate's posture rather than carve a hole in it: attestation was never a
read-authorization mechanism (it "says nothing about which device may read whose photos", and the
presigned bytes it fronts were always ungated). It gates **writes** (byte `PUT`, event creation, manifest,
leave, notify) and — until this change — **existence-probing** on reads. Opening the two read routes
authorizes **event reads by `eventId`-possession alone**: the `eventId` is the read capability. This is an
accepted, eyes-open cost — it lets any HTTP client that learns an `eventId` read the whole event union
(billed as storage egress), and because the union mints a fresh presigned URL on every call, a leaked
`eventId` becomes a **perpetual** read grant rather than the inert value it is today. It is accepted with
no per-event opt-in and no rate limit. Decision record: `changes/archive/2026-07-21-web-event-download`.

This requirement absorbs five per-endpoint duplicates deleted by this change — `bunny-upload-endpoint`'s
*Writes require a device token*, `event-creation`'s *Event routes require a device token*,
`event-leave-endpoint`'s *Leave requires a device token*, `event-notify-endpoint`'s *Notify requires a
device token*, and `device-config-endpoint`'s *The device-config write requires a device token*. They
existed only because there were five endpoint specs; the rule and its closed list of exceptions were always
stated here. **No route's gating changes.** `api-endpoints`' route table carries a `gated` column as a
reader's summary and defers to this list as the authority.

#### Scenario: A new route defaults to gated

- **WHEN** a route not named in the list above receives a request without a valid token
- **THEN** it responds `401`

#### Scenario: OPTIONS is answered without a token

- **WHEN** an `OPTIONS` preflight arrives without an `Authorization` header
- **THEN** it is answered normally, advertising no resumable upload, and the uploader proceeds with a
  plain `PUT`

#### Scenario: The marketing page is served without a token

- **WHEN** a `GET /` (or `HEAD /`) request arrives without a valid token
- **THEN** it is answered with the landing page (`200`), not `401`

#### Scenario: The root exception does not leak to other paths

- **WHEN** a `GET` request without a valid token arrives for any path other than exactly `/`, or a
  non-`GET`/`HEAD` method arrives for `/`
- **THEN** it responds `401`

#### Scenario: The AASA is served without a token

- **WHEN** a `GET /.well-known/apple-app-site-association` (or `HEAD`) request arrives without a valid
  token
- **THEN** it is answered with the AASA document (`200`, `application/json`), not `401`

#### Scenario: The join download page is served without a token

- **WHEN** a `GET /join` (or `HEAD /join`) request arrives without a valid token
- **THEN** it is answered with the static download page (`200`), not `401`

#### Scenario: The event union read is served without a token

- **WHEN** a `GET /api/v1/events/<uuid>/files` (or `HEAD`) request arrives without a valid token
- **THEN** it is answered by the union route (subject to that route's own existence gate), not `401`

#### Scenario: The event metadata read is served without a token

- **WHEN** a `GET /api/v1/events/<uuid>` (or `HEAD`) request arrives without a valid token
- **THEN** it is answered by the metadata route (its fields or `404`), not `401`

#### Scenario: A write method on an ungated read path stays gated

- **WHEN** a request without a valid token uses a non-`GET`/`HEAD` method on an `/api/v1/events/<uuid>/…`
  path
  (e.g. `PUT`/`DELETE` a device manifest, `POST …/notify`) or `POST /api/v1/events`
- **THEN** it responds `401` — only the `GET`/`HEAD` reads are ungated

#### Scenario: The static exceptions do not leak to other paths or methods

- **WHEN** a request without a valid token arrives for a path that merely begins with `/join` or
  `/.well-known/` but is not exactly one of the two named paths, or a non-`GET`/`HEAD` method arrives for
  either named path
- **THEN** it responds `401`

### Requirement: The maintenance window pre-empts this gate, and changes no route's gating

The maintenance gate (capability `backend-deployment`) SHALL be answered **before** the device-token gate,
so a request under `/api/` during a deploy window receives `503` rather than `401` — whether or not it
carries a valid token, and whether or not its route is on the ungated closed list.

**This does not widen the closed list, and does not narrow it.** The list answers *which routes require a
token*; the window answers *whether the device API is being served at all*. During a window the answer to
the second is "no", so the first is never reached. Outside a window — which is every moment except a
migrating deploy — the closed list decides exactly as it does today.

The window therefore covers the three `/attest/*` issuers too, even though the list names them ungated.
That is deliberate rather than incidental: `POST /attest/token` and `POST /attest/renew` **write a
`devices` row**, so they are precisely the traffic that must not run against a store mid-migration. Being
ungated makes a route reachable without a credential; it does not make it exempt from the service being
unavailable.

Answering `503` before verification is also the truthful order. A `401` would tell a caller its
credentials were the problem when they were not, and it would cost an HMAC verification to say something
false.

#### Scenario: A window answers before the token is examined

- **WHEN** a request under `/api/` arrives during a maintenance window with no bearer token
- **THEN** it is answered `503`, not `401`

#### Scenario: A valid token does not pass the window

- **WHEN** a request under `/api/` arrives during a maintenance window carrying a valid device token
- **THEN** it is answered `503` — the window is not an authorization decision

#### Scenario: The ungated issuers are inside the window

- **WHEN** an `/attest/*` route is requested during a maintenance window
- **THEN** it is answered `503`, because it writes the device's row and the store is being migrated

#### Scenario: Outside a window the closed list is unchanged

- **WHEN** the serving bundle carries no maintenance flag
- **THEN** every route's gating is exactly what the closed list above states

### Requirement: The token rides in the Authorization header, on every request

The device SHALL send the token as `Authorization: Bearer <token>` on every request to a gated route —
including the byte `PUT` that the **operating system** performs on the app's behalf.

This is sound because it was measured, not assumed: the OS-performed upload carries the extension's
custom request headers to the wire, and the bunny pull zone forwards `Authorization` to the origin
unmodified. Decision record: `changes/archive/2026-07-14-add-device-attestation` (see design, constraints 1–2).

#### Scenario: The OS-performed upload carries the token

- **WHEN** the OS runs a background upload job the extension enqueued
- **THEN** the request that reaches the origin carries the `Authorization: Bearer` header the extension
  set

#### Scenario: The token survives the pull zone

- **WHEN** a gated request traverses the CDN pull zone fronting the Edge Script
- **THEN** the `Authorization` header reaches the origin unmodified

### Requirement: A gated response is never cached

A gated route's response SHALL NOT be cacheable by the pull zone. The pull zone forwards `Authorization`
but does **not** vary its cache key on it, so a cacheable gated `GET` would serve one device's authorized
response to another. The `Cache-Control: no-store, no-cache, max-age=0` on the listing routes is
therefore load-bearing for **authorization**, not merely for freshness, and SHALL NOT be relaxed.

#### Scenario: A gated listing is not served from cache to a different device

- **WHEN** two devices request the same gated listing URL with different tokens
- **THEN** each response is produced by the origin for that request, and neither device is served the
  other's cached response

### Requirement: The device token is minted by the app process and shared with the extension

The token SHALL be obtained by the **app** process and persisted in the shared Keychain access group, so
the upload extension reads the same value.

The extension SHALL NOT attest and SHALL NOT renew: App Attest is **unavailable** in the extension
process (`DCAppAttestService.isSupported` reports `false` there, and `true` in the app). The extension
SHALL read whatever token the Keychain holds, SHALL NOT block on a refresh, and SHALL send it as-is —
including when it has expired.

The token's Keychain item SHALL use an accessibility class permitting reads while the device is **locked**
once it has been unlocked since boot (`kSecAttrAccessibleAfterFirstUnlock`), because the extension runs on
an idle — therefore usually locked — device. It SHALL NOT be restricted to the device, so that it is
restorable from an encrypted backup alongside the device id.

#### Scenario: The extension reads the app's token

- **WHEN** the extension builds an upload request
- **THEN** it reads the token the app persisted in the shared Keychain access group and sends it

#### Scenario: The extension never attests

- **WHEN** the extension finds no token, or an expired one
- **THEN** it attests nothing and renews nothing; it proceeds with what it has (or none) and the request
  fails, to be retried

#### Scenario: A locked background upload reads the token

- **WHEN** the OS invokes the extension while the device is locked, and the device has been unlocked at
  least once since boot
- **THEN** the token is read successfully

### Requirement: The app renews on every wake, well before expiry
The app SHALL check the token's remaining lifetime at **every** point its process is already awake — a
launch, a foreground entry, a silent-push wake, and each `BGTask` handler — and SHALL renew when the
token is absent, expired, or nearing expiry.

Renewal SHALL NOT depend on a dedicated scheduled background task. (iOS budgets background task
identifiers per app; a dedicated task would compete with the app's existing ones and would still run only
when the system chose. Checking at every wake yields strictly more opportunities to renew.)

A renewal that fails SHALL record the cause it actually has, and SHALL NOT attribute the failure to a
party that was not involved. The assertion is produced locally by the Secure Enclave and the refusal comes
from the backend; these are different failures with different remedies, and a device log that names one for
the other makes the difference unrecoverable after the fact. Where the platform supplies an error value —
an error domain, code, and description — that value SHALL reach the device log rather than being discarded
in favour of a generic message.

#### Scenario: A wake with a stale token renews it

- **WHEN** the app process wakes for any reason and the token is absent, expired, or near expiry
- **THEN** the app obtains a fresh token and persists it to the shared Keychain

#### Scenario: A wake with a fresh token does nothing

- **WHEN** the app process wakes and the token is comfortably within its lifetime
- **THEN** no attestation and no renewal is performed

#### Scenario: A renewal that fails names the party that failed

- **WHEN** the local assertion cannot be produced, so no renewal request is ever sent
- **THEN** the device log records the platform's own error value and does not state that the backend
  refused the renewal

### Requirement: An expired token stalls uploads; it never loses a photo
When the token is expired or absent, a gated upload SHALL fail with `401` and the resource SHALL be
retried, never abandoned — the engine's retry policy is error-agnostic and the upload request is re-minted
from the provider on each attempt, so a refreshed token is picked up on the next cycle without any
special-casing.

The failure SHALL be **visible and never silent**, reduced into `UiState` rather than thrown to the UI.
This is the only signal a stalled device gives: an expired token prevents the successful upload whose
completion notification is what would otherwise wake the app to renew, so recovery depends on the next app
wake from another source (the user opening the app, or another member's upload).

**Which** state renders it is not this capability's to say. An unusable token surfaces as the joined layer's
`Unattested` health, specified by `sync-status-screen`, which owns the health precedence and ranks it. What
this capability requires is only that the stall reach the screen at all: an attestation failure that showed
nothing would leave a device reporting "Syncing" while every upload `401`s — invisible, and unfixable by a
member who cannot know it is happening.

**"Unusable" is a narrower test than "due for renewal", and the two SHALL NOT share one predicate.** A token
is unusable when it is **absent, unparseable, or past its expiry** — and only then. A token that is merely
inside the renewal margin still authorises every gated request until the instant it expires, so a failed
renewal against one is not a stall and SHALL NOT be surfaced as one. (Verification on the backend is an
expiry check plus one HMAC comparison; nothing else can make an unexpired, well-formed token stop working
except a rejection, which is a different path.) The renewal margin governs *when the app spends a renewal*
and SHALL remain wide; it is not a statement about whether uploads can proceed.

**A surfaced verdict SHALL NOT outlive the refresh that produced it.** The app checks attestation only at
its wakes, and a process may hold an outcome from one wake across an arbitrary suspension before a surface
renders it. The health a surface shows SHALL therefore derive from a refresh attempted no earlier than that
surface's own entry: on entry the prior outcome SHALL be discarded, and the state SHALL be re-established
by the attempt the entry triggers. Otherwise a member is shown a verdict formed under conditions — network,
backend, key — that no longer hold.

Interactive failures need no new surface: a gated create or join that `401`s already reduces into
`UiState.CreateEvent(error)` and `JoinPhase.LoadFailed`/`CommitFailed`. It is the **background** stall that
had none.

**Non-goal — a rejected but unexpired token.** When the backend rejects a token that has not expired (its
signing key was rotated, or the leave cascade collected this device's attestation record), the token is
dropped and the next refresh obtains a new one. If that refresh keeps **succeeding** while the backend keeps
rejecting what it mints, this requirement surfaces nothing and the screen reads healthy through a permanent
`401` loop. Detecting it needs evidence this capability does not currently collect. It is named here so the
`Unattested` state is not read as covering it.

Decision record: `changes/archive/2026-07-14-add-device-attestation` — see its `tasks.md` 4.5, which records
why the background half needed a state of its own after this change's D11 had promised it would not.

The unusable-vs-stale split, the freshness rule, and the renewal-diagnostics rule above are recorded in
`changes/archive/2026-08-25-correct-attestation-health-surfacing` (D1, D2, D5) — the change
that corrected a status line claiming sharing was paused while the token had six days left.

#### Scenario: A stale token stalls rather than strands

- **WHEN** the OS performs an upload carrying an expired token and the endpoint responds `401`
- **THEN** the resource is not marked complete, is retried, and uploads successfully once the app has
  renewed — no photo is lost

#### Scenario: The stall is visible

- **WHEN** no usable token can be obtained and uploads are failing because of it
- **THEN** it is surfaced on the joined layer as the `Unattested` health (capability `sync-status-screen`) rather than failing silently behind a screen that reads "Syncing"

#### Scenario: A merely stale token is not an error

- **WHEN** the token is stale but the next wake renews it successfully
- **THEN** nothing is surfaced — a renewal that works is a non-event, and flashing an error for it would be noise

#### Scenario: A token inside the renewal margin that fails to renew is not a stall

- **WHEN** the token is inside the renewal margin but has not expired, and the wake's renewal fails for any
  reason — no network, a refused assertion, a refused attestation
- **THEN** nothing is surfaced: the token still authorises every gated request, so no upload is stalled and
  the screen SHALL NOT state that sharing is paused

#### Scenario: A verdict from an earlier wake is not shown at a later entry

- **WHEN** a wake concludes that no usable token can be obtained, the process is suspended, and a surface is
  later entered
- **THEN** that earlier conclusion is not rendered; the surface shows the outcome of the refresh its own
  entry triggers

### Requirement: Non-goals

The following SHALL NOT be inferred from this capability:

- **Partition ownership.** A token names the `deviceId` it was minted for, but nothing binds an
  attestation key to a `deviceId` first-claim-wins. A genuine instance that knew another device's UUID
  could mint a token naming it. This is accepted: the UUID is unguessable, and reaching this would require
  modifying the app — which is what App Attest prevents. Binding key→deviceId would reintroduce per-device
  state and break legitimately on reinstall, where the Secure-Enclave key dies but the Keychain device id
  survives.
- **Download protection.** Presigned S3 GET URLs are fetched directly from bunny and are not gated. They
  are obtainable only *through* a gated listing route, so the abuse surface this capability closes stays
  closed; a leaked URL remains usable for its lifetime.
- **Privacy.** This capability adds no confidentiality guarantee.

#### Scenario: A leaked presigned URL still works

- **WHEN** a presigned download URL obtained from a gated listing is used by an unattested client
- **THEN** it serves the object, because the bytes are fetched from bunny's S3 endpoint and never traverse
  a gated route

