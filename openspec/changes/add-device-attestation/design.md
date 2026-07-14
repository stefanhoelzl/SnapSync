## Context

Every backend route is ungated. The byte route reads no marker at all, the device id is self-asserted,
and the device-facing host ships in plaintext in every IPA — so an unbounded write to the storage zone
is available to anyone who reads the binary. The threat being closed is **bill/storage abuse**, not
privacy: reads are already capability-protected by unguessable UUIDs.

Four constraints shape everything below, and three of them were **measured on device** (SE2, iOS
26.5.2) rather than assumed, because each could have invalidated the design:

1. **The OS performs the upload.** On the ≥26.1 tier the extension hands a request to
   `PHBackgroundResourceUploadTask` and the OS runs it later, on its own schedule. Nothing in this
   codebase proved an *arbitrary* header survives that — the only header ever set is `Content-Type`,
   which the OS would set anyway. **Verified:** it survives. The origin observed
   `authorization: Bearer …` and a custom `x-snapsync-token` on a real photo `PUT` whose
   `user-agent` was `assetsd` — the OS's own daemon, not our process. Had this failed, no header-borne
   credential could gate the byte route at all and the token would have had to move into the URL.
2. **A CDN pull zone sits in front of the script.** It is free to strip or rewrite `Authorization`, and
   it has already been observed answering `OPTIONS` itself. **Verified:** it forwards `Authorization`
   (and a custom header) to the origin verbatim.
3. **App Attest is unavailable in the upload extension.** **Verified:** `DCAppAttestService.isSupported`
   is `false` in the appex and `true` in the app process, which ran the full ceremony
   (`generateKey` → `attestKey`, 5712-byte attestation → `generateAssertion`, 141-byte assertion) on a
   dev-signed build. This is the finding that fixes the token lifetime (D5).
4. **A failed upload is never lost.** `SyncEngine` retries forever, error-agnostically, and
   `UploadCycle` re-mints the request from the provider on retry — so a `401` from a stale token stalls
   a photo, it never strands one. This is what makes a token-gated byte route safe at all.

(The instrumentation that produced 1 and 2 was a temporary `/__spike` route, shipped and reverted:
PRs #96/#97, reverted by #98.)

## Goals / Non-Goals

**Goals:**

- Only a genuine, unmodified SnapSync on a genuine Apple device may call the API.
- Zero added latency and zero storage reads on the streaming byte-upload hot path.
- One mechanism serving **both** upload tiers, with no per-tier branching.
- The sideloaded dev-IPA loop keeps working.

**Non-Goals:**

- **Partition ownership.** The token proves *"a genuine SnapSync instance asked for a token for
  deviceId X"*, **not** *"device X owns partition X"*. Binding key→deviceId first-claim-wins would
  reintroduce exactly the per-device state we are avoiding, and would break legitimately on reinstall
  (the Secure-Enclave key dies, the Keychain device id survives) — the unbinding path needed to fix
  that *is itself the hole*. Ownership stays capability-based on the unguessable device UUID, exactly
  as today.
- **Assertion replay protection.** No counter (see D3).
- **Per-request proof of liveness.** No per-request assertion (see D2).
- **Gating downloads.** Presigned S3 GET URLs are fetched directly from bunny and cannot be gated. They
  are only obtainable *through* a gated listing route, so the bill surface is still closed; a leaked URL
  remains usable for its 7-day life. Accepted.
- **Privacy.** Attestation adds nothing here and is not claimed to.

## Decisions

### D1. App Attest, not DeviceCheck

DeviceCheck (`DCDevice`) proves "a real Apple device" and gives two persistent bits; the backend must
redeem every token against `api.devicecheck.apple.com`. It does **not** identify the app, and it puts an
Apple round-trip (and Apple's availability) inside our request path. App Attest proves "a genuine,
unmodified instance of *this* app on a genuine Apple device" and, after one attestation, needs no Apple
call at all. DeviceCheck's real use is remembering a fact about a device across reinstalls — not gating
an API. *Rejected: DeviceCheck; both.*

### D2. A minted bearer token, not a per-request assertion

The credential on the wire is an HMAC-signed `{deviceId, exp}` token in `Authorization: Bearer`.
Verification is one HMAC compare: no storage read, no Apple call, nothing on the streaming path.

A per-request App Attest assertion was rejected on two independent grounds. First, it would force a
read-modify-write of a per-device counter on **every photo upload** — which a last-write-wins object
store cannot do atomically. Second, and fatally: the request headers are baked when the extension
enqueues the job, and the OS may run it hours later. "Freshness" on an OS-scheduled path is a fiction,
so a per-request assertion would buy nothing while costing a storage round-trip per resource.

*Rejected: per-request assertion; presigned upload URLs* (the latter contradicts the deliberate
"stable, no expiry — the provider re-derives the identical destination locally" property of the upload
URL, and would need a network round-trip per resource inside the extension).

### D3. Store the attested public key; no counter

`devices/<deviceId>.attest.json` holds `{keyId, publicKey}`, written once at first attestation and read
**only** on renewal — never on the hot path.

The zero-state alternative (verify the attestation, mint, throw the key away) was attractive and was
rejected on a hard constraint: without the stored key, renewal requires a **full re-attestation**, and
re-attestation is the throttled operation (Apple's model is `generateKey` → `attestKey` *once per key*
→ `generateAssertion` thereafter). Rare renewal forces a narrow renewal window near expiry, which is
precisely the failure mode this design must avoid. Storing the key makes renewal a local Secure-Enclave
assertion — no Apple round-trip, no throttle — cheap enough to attempt at *every* wake.

No counter: a replayed assertion merely re-mints the same device's token, which is harmless, and the
counter is the part that cannot be maintained atomically in a LWW object store.

### D4. Renewal is app-process-only; the extension is read-only

Forced by constraint 3 above. The extension takes whatever token the shared Keychain holds, never
blocks on a refresh, and never attests. A stale token yields a `401`, which the retry path heals by
re-minting the request from the provider on the next cycle.

This also keeps the two upload tiers symmetric: on iOS 18–26.0 uploads run in the app process, which
can attest anyway. One mechanism, no per-tier branching.

### D5. 30-day TTL, renewed by a staleness check at every app wake

Renewal is attempted whenever the app process is **already** awake — launch, foreground, silent-push
wake, the `download.backstop` `BGTask`, the `upload.heartbeat` `BGTask` — rather than by a dedicated
scheduled task. A dedicated `BGTask` was rejected: iOS budgets them per-app, so a third identifier would
compete with the two that exist, and it would still fire only when iOS felt like it. Checking on every
wake gets strictly more chances to renew than any scheduled task could.

**The accepted degradation, stated plainly.** The extension cannot renew, and the silent push that most
reliably wakes the app is itself triggered by a *successful* upload (`onBatchUploaded` → notify → APNs,
which has no sender exclusion, so an uploading device pushes itself). An expired token therefore
deadlocks that loop: `401` → no completion → no notify → no push → no wake → no renewal. The exits are
the user opening the app, or another member uploading. The 30-day TTL is what makes falling into this
rare; retry-forever means nothing is lost when it happens; and the visible error state (D11) is the
signal that it has.

*Rejected: a self-wake.* The extension can read `exp` locally (the token is signed, not encrypted) and
could POST `notify` — auth'd on a valid *signature* with `exp` ignored — to push-wake its own app,
making recovery deterministic and a 7-day TTL safe. It was rejected as a moving part not worth its
weight for a personal TestFlight app, and a recovery path that almost never runs is a recovery path that
rots. **If the stall is ever observed in practice, this is the fix to reach for**, and `notify` is the
right route to relax because it is the one gated route that cannot grow the bill: it sends pushes, it
never writes a byte.

*Rejected: a 7-day TTL without the self-wake* — it buys nothing against the stated threat (a scripted
abuser has no token at all) and multiplies the deadlock's frequency.

### D6. `Authorization: Bearer`, not a custom header

Verified through the real pull zone (constraint 2). A custom `X-SnapSync-Token` was the safer bet
*a priori* — CDNs special-case `Authorization` — but the measurement removed the reason to deviate from
the standard header.

**Consequence the specs must carry:** the pull zone forwards `Authorization` but does **not** vary its
cache key on it. The gated `GET`s (per-device list, event union) already send
`Cache-Control: no-store, no-cache, max-age=0`, so nothing is cached — but that directive is now
load-bearing for **authorization**, not merely freshness. A future change that makes a gated `GET`
cacheable would serve one device's authorized response to another.

### D7. Every route gated; a closed exception list

Ungated, and exhaustively: `GET /attest/challenge` (a stateless HMAC nonce, no storage touch),
`POST /attest/token` and `POST /attest/renew` (self-authenticating — they carry the attestation /
assertion), and `OPTIONS` (the pull zone may answer it itself, so the script *cannot* gate it; a `401`
there would break the plain-`PUT` fallback the uploader depends on). Everything else requires the token.
The list is closed in the spec so a future route cannot land ungated by omission.

### D8. Accept both attestation environments

A dev-signed build attests against Apple's **development** environment (`appattestdevelop`); a
TestFlight/App Store build against production (`appattest`). There is one backend. Rejecting the
development aaguid would kill the on-device dev loop, which has no local upload rig to fall back on.
Accepting it is safe: the attestation still binds `TEAMID.app.snapsync`, so only a build signed by our
team can produce one. (Confirmed on device: the dev-signed build attested successfully.)

### D9. A third env secret, `ATTEST_TOKEN_KEY`, with an ordering constraint

The token's HMAC key is a new secret. This deliberately re-arms a known footgun: **CI ships code but
cannot ship config** (bunny issues no scoped API key, so only the human holds the account key), and
`readConfig` throws on a missing secret — which is exactly how this backend stayed dead for two weeks.

**Therefore: the secret MUST be set in the bunny script's environment *before* the code that reads it
merges to `main`.** Merging first means the script fails to boot and the whole backend is down until it
is set by hand.

*Rejected: deriving the key via HKDF from `BUNNY_STORAGE_ACCESS_KEY`* with a domain-separating `info`
string — which would have needed no new env var and no manual deploy step at all. Chosen against in
favour of clean key separation, with the ordering constraint accepted as the price.

### D10. Bundled CBOR + X.509; Apple's root CA as a source constant

Attestation verification (CBOR decode; X.509 chain to Apple's App Attest root; nonce, appId-hash,
counter, and aaguid checks) uses `cbor` + `@peculiar/x509`, bundled by `deno bundle` exactly as `hono`
and `aws4fetch` already are. Apple's App Attest **root CA** becomes an 8th **source constant** in
`config.ts`: it is a public fact, which is precisely the criterion the config-in-source doctrine uses.

*Rejected: hand-rolling minimal DER/CBOR parsing* against attacker-controlled bytes.

### D10b. A REJECTED token is not the same as an EXPIRED one

A `401` from a gated route drops the stored token (keeping the `keyId`), so the next wake obtains a new
one. This is a distinct trigger from the expiry-based staleness check, and it is **not** redundant with it.

Found while setting up the on-device stale-token test, before it could reach production. The staleness
check reads the token's `exp` — but a token is **rejected while nowhere near expiry** in at least two real
situations: the server-side signing key is rotated, and the leave cascade collects this device's
attestation record. In both, `isStale()` reports the dead token as perfectly healthy, the app never renews,
and the device re-sends the same rejected credential **forever** — 401ing silently behind a screen that
says "Syncing", with `SyncHealth.Unattested` never firing because attestation never *failed*, it was never
*attempted*. A key rotation would have bricked every device for up to 30 days.

The `keyId` is deliberately **kept** on rejection: the Secure-Enclave key is still valid, so recovery is a
cheap assertion rather than Apple's throttled re-attestation.

The **extension** also acts on a `401` — not by renewing (it cannot), but by *dropping* the token. That is
what lets the app re-mint on its next wake: `isStale(null)` is true, whereas a rejected-but-unexpired token
would have looked fine indefinitely.

### D10c. One refresh at a time

`ensureFresh` is serialized behind a mutex, and re-checks staleness **inside** the lock so a caller that
queued behind a successful refresh does nothing.

Also found on device: a rejected token `401`s *every* in-flight request, and each `401` independently asked
for a refresh — a single rejection produced **three concurrent `/attest/renew` calls**. Harmless in that
run, but it is a thundering herd aimed at the one path Apple throttles, and every refresh after the first
is pure waste.

### D11. Failures reduce into the existing error state; the harness stays token-blind

Attestation and `401` failures become a sealed domain error reduced into the **existing** visible error
state — no new screen, no new `App*` component. `:test:world`'s mini-edge does **not** assert the token
(it has no secret and no Apple), so no test catches a dropped header; the runtime error is the signal
instead. This pairing is deliberate and is the reason the error must stay *visible*: with silent errors
*and* a token-blind world, a dropped header would produce "pending forever" with no signal anywhere.

### D12. Hard cutover; the token is restorable, like the device id

Ship the app and the enforcing backend together. Un-updated builds `401` and retry — delayed, never
lost — until they update. A personal TestFlight app on devices we control.

The token's Keychain item takes the **same** class as the device id: `kSecAttrAccessibleAfterFirstUnlock`
(the extension must read it on a locked device) and **not** `…ThisDeviceOnly` — so it survives an
encrypted-backup restore, consistent with the device id restored alongside it.

*Rejected: `…ThisDeviceOnly`*, which would have closed the backup-extraction vector entirely (a restored
device would simply re-attest on next launch, at no real cost). Consistency with the device id was
preferred. **Consequence:** a token lifted from a backup is a working write credential until it expires,
so the 30-day TTL is now the *only* bound on that vector — which is a reason never to lengthen it.

## Risks / Trade-offs

- **Expired token deadlocks the renewal loop** (D5) → 30-day TTL makes it rare; retry-forever means no
  photo is lost; the visible error state is the signal; opening the app heals it. The self-wake in D5 is
  the pre-analysed fix if it is ever observed.
- **`ATTEST_TOKEN_KEY` forgotten → the backend does not boot at all** (D9) → the ordering constraint is
  a numbered task *before* the code merges, and `readConfig`'s throw makes the failure loud and total
  rather than silent and partial.
- **No test asserts the token is sent** (D11) → the visible runtime error is the only signal. Accepted
  with eyes open; the cheap fix (assert-present in `:test:world`'s mini-edge) stays available.
- **A backup-extracted token is a usable write credential until expiry** (D12) → bounded by the TTL; do
  not lengthen the TTL.
- **A genuine app instance could mint a token naming another device's UUID** (non-goal) → it must first
  *know* that UUID (unguessable), and reaching the protocol's theoretical looseness requires modifying
  the app — which is the very thing App Attest prevents. Practically unreachable; documented so it is
  not later mistaken for an undiscovered hole.
- **`@peculiar/x509` / `cbor` on the bunny edge runtime is unproven** (bundle size; WebCrypto surface).
  This is the one residual unknown and is the **first task**: prove verification of a real attestation
  in the bundled script before anything depends on it.
- **Hard cutover stalls un-updated devices** (D12) → accepted; devices are ours.
- **Rotating `ATTEST_TOKEN_KEY` invalidates every issued token** → devices recover automatically via D10b
  (401 → drop → re-mint at the next wake). Before D10b this would have bricked every device until its token
  expired, which is precisely why D10b exists.

## Migration Plan

1. Prove the verification stack bundles and runs (the residual unknown above).
2. **Set `ATTEST_TOKEN_KEY` in the bunny script environment by hand** — before any code reading it
   merges to `main`.
3. Ship the attest routes and the token guard together with the app build that attests. Un-updated
   devices `401` and retry until updated.
4. **Rollback**: revert the guard (the routes are inert without it) and the app reverts to sending a
   header the backend ignores. The `devices/<id>.attest.json` objects are harmless if orphaned; the
   leave cascade GCs them.

## Open Questions

- None blocking. The `@peculiar/x509`/`cbor`-on-bunny question is scheduled as task 1 rather than left
  open, because a negative answer changes the verification approach (hand-rolled DER against a pinned
  intermediate) but not the architecture.
