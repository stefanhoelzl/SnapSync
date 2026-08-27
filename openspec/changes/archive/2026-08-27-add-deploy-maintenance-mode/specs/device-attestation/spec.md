## ADDED Requirements

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
