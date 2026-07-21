# device-attestation Specification

## Purpose

> Full replacement of the Purpose section (one paragraph added naming the platform seam; no
> requirement changes). Apply by hand at archive time and diff.

An anti-abuse gate that lets the backend serve **only a genuine, unmodified SnapSync running on a genuine
Apple device**, and nothing else.

Every route ships against a device-facing host baked in plaintext into every IPA, and the device id is
self-asserted — so without a gate, anyone who reads the host out of the app can `PUT` arbitrary bytes into
the bunny storage zone that holds every user's photos, or `POST /events` without bound, for the cost of a
guessed UUID. Apple's App Attest is the standing answer: the app proves, cryptographically and per device,
that it is the real binary on real hardware, mints a bearer token from that proof, and carries the token on
every request. The backend admits only requests bearing a valid token; a short closed list of routes
(`/attest/*`, which issue the token, and `OPTIONS`) is necessarily ungated.

App Attest is the **iOS binding of a platform-neutral need** — prove the caller is a genuine,
unmodified client build — not a commitment to Apple hardware. A future Android client would bind its
own platform attestation (Play Integrity) behind the same attest→token mint, leaving the token
contract and every gated route unchanged; what is Apple-specific is the proof, not the gate.

This is an **anti-abuse** mechanism, not a privacy or ownership one: the token attests the *app*, not the
*user*, and says nothing about which device may read whose photos. The token is minted by the app process
(App Attest is unavailable in an extension) and shared with the upload extension through the Keychain; an
expired token stalls uploads and is renewed on the next wake, but never causes a photo to be lost.

Decision record: `changes/archive/2026-07-14-add-device-attestation`.
