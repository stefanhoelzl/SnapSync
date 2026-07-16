# device-attestation Specification

## Purpose

An anti-abuse gate that lets the backend serve **only a genuine, unmodified SnapSync running on a genuine
Apple device**, and nothing else.

Every route ships against a device-facing host baked in plaintext into every IPA, and the device id is
self-asserted — so without a gate, anyone who reads the host out of the app can `PUT` arbitrary bytes into
the bunny storage zone that holds every user's photos, or `POST /events` without bound, for the cost of a
guessed UUID. Apple's App Attest is the standing answer: the app proves, cryptographically and per device,
that it is the real binary on real hardware, mints a bearer token from that proof, and carries the token on
every request. The backend admits only requests bearing a valid token; a short closed list of routes
(`/attest/*`, which issue the token, and `OPTIONS`) is necessarily ungated.

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

The backend SHALL expose `POST /attest/token`, which accepts a `deviceId`, an App Attest `keyId`, and an
attestation object, and SHALL verify **all** of: the attestation's certificate chain to Apple's App
Attest root CA; that the attestation's nonce matches the challenge it was issued for; that the app-id
hash matches this app; that the signing counter is `0`; and that the `aaguid` names an accepted
attestation environment. Only when every check passes SHALL it mint a token.

The backend SHALL persist the attested public key at `devices/<deviceId>.attest.json` so that renewal
can verify a later assertion against it. This object SHALL be read **only** when renewing — never on a
gated request — so that no gated route pays a storage read to authenticate.

A minted token SHALL carry the `deviceId` it was minted for and an expiry, and SHALL be verifiable by
signature alone, with no storage read and no call to Apple.

#### Scenario: A valid attestation mints a token

- **WHEN** `POST /attest/token` receives an attestation that passes every check above
- **THEN** the attested public key is stored at `devices/<deviceId>.attest.json` and a signed token for
  that `deviceId` is returned

#### Scenario: An attestation failing any check mints nothing

- **WHEN** the certificate chain, nonce, app-id hash, counter, or `aaguid` check fails
- **THEN** the endpoint responds `401`, no public key is stored, and no token is minted

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

The backend SHALL expose `POST /attest/renew`, which accepts a `deviceId`, a `keyId`, and an App Attest
**assertion** over a server-issued challenge, SHALL verify that assertion against the public key stored
at `devices/<deviceId>.attest.json`, and SHALL mint a fresh token on success.

Renewal SHALL NOT require a new attestation and SHALL NOT call Apple. (Apple's model attests a key
**once**; repeatedly re-attesting — or minting a fresh key per renewal — is the throttled path. Making
renewal cheap is what allows it to be attempted at every wake rather than in a narrow window near
expiry.)

The backend SHALL NOT maintain an assertion counter. A replayed assertion re-mints the same device's
token, which grants nothing the caller did not already hold.

#### Scenario: A valid assertion renews the token

- **WHEN** `POST /attest/renew` receives an assertion that verifies against the device's stored public key
- **THEN** a fresh token is minted, with no call to Apple

#### Scenario: A device with no stored key must attest

- **WHEN** `POST /attest/renew` names a `deviceId` with no `devices/<deviceId>.attest.json`
- **THEN** the endpoint responds `401`, and the device must complete a full attestation instead

#### Scenario: A re-attestation after reinstall overwrites the stored key

- **WHEN** a device whose Secure-Enclave key is gone (reinstall/restore) attests again for the same
  `deviceId`
- **THEN** the attestation succeeds and `devices/<deviceId>.attest.json` is overwritten with the new key

### Requirement: Challenges are server-issued and time-bounded

The backend SHALL expose `GET /attest/challenge`, returning a challenge that binds an attestation or
assertion to a bounded window. A challenge SHALL be verifiable **without** server-side state (it carries
its own authentication), so that issuing one performs no storage write. The backend SHALL reject an
attestation or assertion whose challenge is unrecognised or outside its window.

#### Scenario: Issuing a challenge writes nothing

- **WHEN** `GET /attest/challenge` is called
- **THEN** a challenge is returned and no storage object is written

#### Scenario: A stale challenge is refused

- **WHEN** an attestation or assertion presents a challenge outside its validity window
- **THEN** the endpoint responds `401` and mints no token

### Requirement: Ungated routes are a closed list

Exactly the routes named below SHALL be reachable without a token, and the list SHALL be closed — a route
not named here SHALL require the token:

1. `GET /attest/challenge` — it issues the input to attestation and touches no storage.
2. `POST /attest/token` — self-authenticating (it carries the attestation).
3. `POST /attest/renew` — self-authenticating (it carries the assertion).
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
7. `GET /join` and `HEAD /join` — the App Store fallback a browser reaches when an event link is opened
   on a device with no app to claim it (capability `event-link`). By definition its audience holds no
   attestation. This exception SHALL be **exact-path and method-scoped** on the same terms as entry 5. The
   route is a constant redirect: it reads no storage, holds no per-event state, and carries no side
   effect — and it cannot read the link's payload even in principle, because that payload is carried in
   the URL fragment, which a browser never transmits.

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

#### Scenario: The App Store fallback is served without a token

- **WHEN** a `GET /join` (or `HEAD /join`) request arrives without a valid token
- **THEN** it is answered with the App Store redirect, not `401`

#### Scenario: The new exceptions do not leak to other paths or methods

- **WHEN** a request without a valid token arrives for a path that merely begins with `/join` or
  `/.well-known/` but is not exactly one of the two named paths, or a non-`GET`/`HEAD` method arrives for
  either named path
- **THEN** it responds `401`

### Requirement: The token rides in the Authorization header, on every request

The device SHALL send the token as `Authorization: Bearer <token>` on every request to a gated route —
including the byte `PUT` that the **operating system** performs on the app's behalf.

This is sound because it was measured, not assumed: the OS-performed upload carries the extension's
custom request headers to the wire, and the bunny pull zone forwards `Authorization` to the origin
unmodified. Decision record: `changes/archive/<id>` (see design, constraints 1–2).

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

#### Scenario: A wake with a stale token renews it

- **WHEN** the app process wakes for any reason and the token is absent, expired, or near expiry
- **THEN** the app obtains a fresh token and persists it to the shared Keychain

#### Scenario: A wake with a fresh token does nothing

- **WHEN** the app process wakes and the token is comfortably within its lifetime
- **THEN** no attestation and no renewal is performed

### Requirement: An expired token stalls uploads; it never loses a photo

When the token is expired or absent, a gated upload SHALL fail with `401` and the resource SHALL be
retried, never abandoned — the engine's retry policy is error-agnostic and the upload request is re-minted
from the provider on each attempt, so a refreshed token is picked up on the next cycle without any
special-casing.

The failure SHALL be reduced into the **existing** visible error state (a sealed domain error → `UiState`),
never thrown to the UI and never silent. This is the only signal a stalled device gives: an expired token
prevents the successful upload whose completion notification is what would otherwise wake the app to renew,
so recovery depends on the next app wake from another source (the user opening the app, or another member's
upload). Decision record: `changes/archive/<id>`.

#### Scenario: A stale token stalls rather than strands

- **WHEN** the OS performs an upload carrying an expired token and the endpoint responds `401`
- **THEN** the resource is not marked complete, is retried, and uploads successfully once the app has
  renewed — no photo is lost

#### Scenario: The stall is visible

- **WHEN** attestation fails, or uploads are failing because the token is expired or absent
- **THEN** the error is reduced into the existing visible error state rather than failing silently

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
