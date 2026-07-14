## MODIFIED Requirements

### Requirement: Returned request shape — Content-Type only, no auth, no metadata

`UploadRequest.headers` SHALL contain exactly `Content-Type` (from `resource.contentType`) and
`Authorization` (`Bearer <token>`, the device token of capability `device-attestation`) — and nothing
else: **no** `Host` (URL-implied) and **no** custom metadata headers (the bunny native Storage API has
none; `resource.metadata` SHALL NOT be emitted as headers). `UploadRequest.url` SHALL be the complete
edge URL `<host>/files/devices/<deviceId>/<encoded-filename>` with no query string (no signature, no
expiry parameters) — the credential rides in the header, never in the URL, so the URL stays **stable
with no expiry** and a retry re-derives a byte-identical destination.

The token SHALL be re-read on **every** call to `provide`, never captured once at construction: the
engine re-mints the request from this provider on each retry, and that is precisely what allows an
upload that failed on an expired token to succeed once the app has renewed, with no special-casing
anywhere in the upload path.

When no token is available, `provide` SHALL still return a request (omitting the header) rather than
failing. The resulting `401` is a retryable failure like any other; refusing to build a request would
strand the resource instead.

#### Scenario: Content-Type and Authorization are carried

- **WHEN** `provide` returns and a token is available
- **THEN** `headers` contains exactly `Content-Type` and `Authorization: Bearer <token>` — no `Host` and
  no `x-*-meta-*` entries, even when `resource.metadata` is non-empty

#### Scenario: URL carries no auth query string

- **WHEN** `provide` returns
- **THEN** `url` is `<host>/files/devices/<deviceId>/<encoded-filename>` with no `?`-query parameters

#### Scenario: A retry picks up a refreshed token

- **WHEN** an upload fails with `401` on an expired token, the app then renews, and the engine re-mints
  the request for that resource
- **THEN** the rebuilt request carries the **new** token, and the URL is byte-identical to the original

#### Scenario: A missing token still yields a request

- **WHEN** `provide` is called and no token is present in the shared Keychain
- **THEN** a request is returned with `Content-Type` and no `Authorization` header, and the upload is
  allowed to fail and be retried rather than being abandoned
