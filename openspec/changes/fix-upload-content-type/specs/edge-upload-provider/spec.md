## MODIFIED Requirements

### Requirement: Returned request shape — Content-Type and Authorization, no metadata

`UploadRequest.headers` SHALL contain exactly `Content-Type` and
`Authorization` (`Bearer <token>`, the device token of capability `device-attestation`) — and nothing
else: **no** `Host` (URL-implied) and **no** custom metadata headers (the bunny native Storage API has
none; `resource.metadata` SHALL NOT be emitted as headers). `UploadRequest.url` SHALL be the complete
edge URL `<host>/files/devices/<deviceId>/<encoded-filename>` with no query string (no signature, no
expiry parameters) — the credential rides in the header, never in the URL, so the URL stays **stable
with no expiry** and a retry re-derives a byte-identical destination.

`Content-Type` SHALL be the resource's **MIME type**, taken from `resource.metadata`'s
`RESOURCE_META_MIME` entry (resolved platform-side — on iOS by `UTType.preferredMIMEType`), treating a
blank value as absent and falling back to `resource.contentType`. It SHALL NOT be `resource.contentType`
by default: on iOS that field is the PhotoKit **UTI** (`public.jpeg`), which is not a media type and
which no HTTP client, CDN or browser interprets — every object uploaded before this rule was stored
typed with it (measured at the origin, SE2 / iOS 26.6). This is the same preference every other consumer
of a resource already applies (`toLedgerRow`), so the stored object's type agrees with the device
manifest and the event union rather than contradicting them.

Reading one metadata **value** to populate a header the contract already requires is distinct from
emitting metadata **as headers**, which stays prohibited above.

The fallback to `resource.contentType` is load-bearing rather than defensive: the retry path rebuilds a
`Resource` from the job key alone with empty metadata, and the platform supplies the type recovered from
the job's stored request — so the fallback is the seam through which a retried upload keeps its original
type instead of acquiring a default.

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

#### Scenario: Content-Type is the MIME type, not the platform UTI

- **WHEN** a resource carries `RESOURCE_META_MIME` of `image/jpeg` and a `contentType` of `public.jpeg`
- **THEN** the request's `Content-Type` is `image/jpeg`

#### Scenario: A resource with no MIME metadata falls back to its content type

- **WHEN** a resource carries no `RESOURCE_META_MIME` entry, or a blank one — as a `Resource` rebuilt
  from a job key on the retry path does
- **THEN** the request's `Content-Type` is `resource.contentType`, so a retried upload keeps the type its
  platform recovered rather than acquiring a default

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
