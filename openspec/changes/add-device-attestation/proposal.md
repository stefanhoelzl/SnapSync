## Why

Every backend route is open. Anyone who learns the device-facing host — it ships in plaintext inside
every IPA — can `PUT /files/devices/<any-uuid>/<any-name>` and stream arbitrary bytes into the bunny
storage zone, or `POST /events` without bound. The device id is self-asserted and the byte route reads
no marker at all, so possession of a UUID is the only thing standing between a stranger and an
unbounded bill on the zone that also holds every user's photos. The specs have carried "App Attest is
the hardening path" as a noted gap since `2026-06-30-dedup-files-device-manifests`; this discharges it.

The goal is precisely and only this: **the API may be called by a genuine, unmodified SnapSync running
on a genuine Apple device, and by nothing else.** It is an anti-abuse gate, not a privacy or ownership
mechanism.

## What Changes

- **New `device-attestation` capability.** The app attests once via App Attest (`DCAppAttestService`),
  the backend verifies the attestation object and mints a **device-scoped, HMAC-signed bearer token**
  (`{deviceId, exp}`, 30 days). The token rides in `Authorization: Bearer` on every request, including
  the OS-performed byte upload. Verifying it on a request is one HMAC compare — no storage read, no
  Apple call, no added latency on the streaming upload path.
- **Renewal is a local assertion, not a re-attestation.** The backend stores the attested public key
  (`devices/<deviceId>.attest.json`, written once); renewal presents a Secure-Enclave assertion the
  backend verifies against it. No Apple round-trip, so renewal is cheap enough to attempt at **every**
  app-process wake (launch, foreground, silent push, the existing `BGTask`s) rather than in a narrow
  window near expiry.
- **Renewal is app-process-only.** Proven on device: `DCAppAttestService.isSupported` is **`false`
  inside the upload extension** and `true` in the app. The extension is strictly read-only — it takes
  whatever token the shared Keychain holds and never blocks on a refresh.
- **BREAKING (device-visible): every route requires the token**, with a closed exception list —
  `GET /attest/challenge`, `POST /attest/token`, `POST /attest/renew` (each self-authenticating), and
  `OPTIONS` (the pull zone may answer it itself, so the script cannot gate it). Cutover is hard: an
  un-updated build's uploads `401` and retry (nothing is lost, only delayed) until it updates.
- **BREAKING (deployment): a third env secret**, `ATTEST_TOKEN_KEY`. It **MUST be set in the bunny
  script's environment before the code that reads it merges to `main`** — `readConfig` throws on a
  missing secret, and CI ships code but cannot ship config.

## Capabilities

### New Capabilities

- `device-attestation`: the attest → mint → renew contract; the token's shape, lifetime, and Keychain
  storage; the staleness check at every app wake; the closed list of ungated routes; and the accepted
  non-goals (no key→deviceId binding, no assertion counter, no per-request assertion).

### Modified Capabilities

- `bunny-upload-endpoint`: the byte `PUT` and the device-manifest `PUT` require a valid token (`401`
  otherwise); the `OPTIONS` preflight explicitly does not.
- `bunny-list-endpoint`: the per-device list and the event union require a valid token. Their existing
  `Cache-Control: no-store, no-cache, max-age=0` becomes load-bearing for **authorization**, not merely
  freshness — the pull zone forwards `Authorization` but does not vary its cache key on it, so a
  cacheable gated `GET` would serve one device's authorized response to another.
- `edge-upload-provider`: today it requires `headers` to contain **exactly** `Content-Type` and
  explicitly **no** authorization header, on the stated grounds that the byte route is ungated. That
  requirement inverts: the provider now emits `Authorization: Bearer <token>`, re-read per attempt so a
  retry picks up a refreshed token.
- `event-creation`: `POST /events` and `GET /events/<id>` require a valid token.
- `device-config-endpoint`: `PUT /devices/<id>` requires a valid token.
- `event-notify-endpoint`: `POST /events/<id>/notify` requires a valid token.
- `event-leave-endpoint`: the `DELETE` requires a valid token, and the leave cascade's per-device GC
  additionally deletes `devices/<deviceId>.attest.json` alongside `devices/<deviceId>.json`.
- `backend-deployment`: adds the `ATTEST_TOKEN_KEY` secret (fail-closed at boot, with the
  set-before-merge ordering constraint) and Apple's App Attest root CA as a **source constant** — it is
  a public fact, which is exactly the criterion the config-in-source doctrine already uses.

## Impact

- **New module `:capability:attest`** — tested `commonMain` logic (token cache, staleness policy, the
  challenge/attest/renew HTTP calls, error reduction) behind an `AttestKey` seam whose
  `DCAppAttestService` implementation is thin and wired in `:app:ios`. Token persistence goes through
  `:domain:keychain` (the only module permitted to touch `SecItem*`), in the shared access group under
  `kSecAttrAccessibleAfterFirstUnlock` — the extension must read it on a locked device — and, like the
  device id, **restorable from an encrypted backup** (not `…ThisDeviceOnly`).
- **Backend**: three new routes; a token guard on the rest; `cbor` + `@peculiar/x509` bundled (the
  `deno bundle` npm path already proven by `hono`/`aws4fetch`); one new storage object per device.
- **iOS**: one request interceptor on the shared Darwin/Ktor client covers create, join, union,
  manifest, device-config, leave, and notify; the OS-performed byte `PUT` is covered by the upload
  provider. Both upload tiers are served by the same mechanism — the app renews on both, so no
  per-tier branching.
- **Failure surface**: attestation/`401` failure reduces into the **existing** visible error state (no
  new screen, no new `App*` component). A device whose token expires while the app is never opened
  stalls its uploads — `retry`-forever means nothing is lost, and the visible error is the signal —
  until the next app wake renews. This is an accepted, bounded degradation (see design).
- **Dev loop**: the backend accepts **both** attestation environments (`appattest` and
  `appattestdevelop`); rejecting the development one would break the sideloaded dev-IPA loop, which has
  no local upload rig to fall back on.
- **Not covered**: presigned S3 download URLs are fetched directly from bunny and cannot be gated — but
  they are only obtainable *through* a gated listing route, so the bill surface stays closed.
