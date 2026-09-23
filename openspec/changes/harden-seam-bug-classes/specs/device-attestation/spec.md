## MODIFIED Requirements

### Requirement: Challenges are server-issued and time-bounded

The backend SHALL expose `GET /api/v1/attest/challenge` and `GET /api/v2/attest/challenge`, returning a
challenge that binds an attestation or assertion to a bounded window. A challenge SHALL be verifiable
**without** server-side state (it carries its own authentication), so that issuing one performs no storage
write. The backend SHALL reject an attestation or assertion whose challenge is unrecognised or outside its
window.

How a stale challenge is refused differs by version, deliberately. Under `/api/v1`, which is frozen, the
refusal SHALL remain `401`. Under `/api/v2` it SHALL be `409` with the body `stale challenge`, because a
`401` is the answer that means "your credential is rejected" and a stale challenge rejects no credential:
answering it with `401` is what let a client read a renewal's expired challenge as a revoked token. The
v2 attest routes therefore SHALL be served by the v2 router rather than shared with v1, so that the two
versions' answers can differ while each version's route table stays closed.

#### Scenario: Issuing a challenge writes nothing

- **WHEN** `GET /api/v2/attest/challenge` is called
- **THEN** a challenge is returned and no storage object is written

#### Scenario: A stale challenge is refused under v2

- **WHEN** an attestation or assertion sent to `/api/v2/attest/token` or `/api/v2/attest/renew` presents a
  challenge outside its validity window
- **THEN** the endpoint responds `409` with the body `stale challenge` and mints no token

#### Scenario: A stale challenge is refused under v1, unchanged

- **WHEN** an attestation or assertion sent to `/api/v1/attest/token` or `/api/v1/attest/renew` presents a
  challenge outside its validity window
- **THEN** the endpoint responds `401` and mints no token, exactly as before

## ADDED Requirements

### Requirement: Only a rejected credential is invalidated, and only that one

The client SHALL treat a response as a rejection of its credential only when the request **carried** a
device token **and** was sent to a gated route; a `401` from an ungated route (the `/attest/*` bootstrap)
SHALL be handled by the attestation flow as that route's own outcome, never as a credential rejection. HTTP
outcomes SHALL be classified per route by the adapter that owns the route, and the shared interceptor SHALL
react only to the classified credential rejection.

A credential rejection SHALL name the token that was sent. The store SHALL clear the token only if it still
holds that same token (compare-and-clear), so a late rejection of a superseded token cannot erase its
replacement — in either process, since both read the one shared Keychain item. A refresh triggered by a
rejection SHALL be one refresh per rejected token, not one per rejected request.

#### Scenario: A renewal's challenge expires while the app is suspended

- **WHEN** the app fetches a challenge, is suspended past its window, and the renewal is refused as stale
- **THEN** the stored token is not cleared, the renewal is recorded as failed with that cause, and it is
  retried at the next wake

#### Scenario: Late rejections after a renewal

- **WHEN** several requests carrying token T1 are in flight, the first is rejected, a refresh stores T2,
  and the remaining requests are rejected afterwards
- **THEN** T2 stays stored, and no further refresh is triggered for T1

#### Scenario: The extension's rejection races the app's renewal

- **WHEN** the extension's request carrying T1 is rejected after the app has stored T2
- **THEN** the extension's compare-and-clear leaves T2 in place

#### Scenario: A gated route rejects the current token

- **WHEN** a request carrying the stored token to a gated route is answered `401`
- **THEN** that token is cleared and one refresh is triggered, as before
