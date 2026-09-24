## MODIFIED Requirements

### Requirement: Returned request shape — Content-Type and Authorization, no metadata

`UploadRequest.headers` SHALL contain exactly `Content-Type`, `Authorization` (`Bearer <token>`, the device
token of capability `device-attestation`) and the **app-version header** carrying the calling build's
marketing version (capability `min-app-version`) — and nothing else: **no** `Host` (URL-implied) and **no**
custom metadata headers (the bunny native Storage API has none; `resource.metadata` SHALL NOT be emitted as
headers).

The app-version header is required here and not only on the shared HTTP client because **the OS performs
this request**: it is handed to the platform's background-upload subsystem and issued later, outside any
client this app controls, so a header the client adds cannot reach it. A v2 request that does not declare
the version is refused `426`.

`UploadRequest.url` SHALL be the complete edge URL described above, carrying the mandatory `filename` query
and **no credential parameters** (no signature, no expiry) — the credential rides in the header, never in
the URL, so the URL stays **stable with no expiry** and a retry re-derives a byte-identical destination.

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

The token SHALL be read from its injected source on **every** call to `provide`, never captured once at
construction. That source MAY serve the process's in-memory copy of the token between the re-reads capability
`device-attestation` bounds ("The device token is minted by the app process and shared with the extension"):
the app re-reads at every wake, the extension at every invocation, and both on a credential rejection. A
**retry** SHALL NOT be minted from that copy: the engine re-mints a failed upload's request through
`provideForRetry` (see "A retry's request carries the credential from its store of record"), which reads the
token from its store of record. That is precisely what allows an upload that failed on an expired token to
succeed once the app has renewed — whichever process renewed, and however recently — with no special-casing
anywhere in the upload path.

When no token is available, `provide` SHALL still return a request (omitting the header) rather than
failing. The resulting `401` is a retryable failure like any other; refusing to build a request would
strand the resource instead.

#### Scenario: Content-Type, Authorization and the app version are carried

- **WHEN** `provide` returns and a token is available
- **THEN** `headers` contains exactly `Content-Type`, `Authorization: Bearer <token>` and the app-version
  header — no `Host` and no `x-*-meta-*` entries, even when `resource.metadata` is non-empty

#### Scenario: The version rides on the OS-performed request

- **WHEN** the composed request is handed to the platform's background-upload subsystem and issued later
- **THEN** it declares the app version itself, because no client this app controls issues it

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
- **THEN** `url` carries the mandatory `filename` parameter and no signature or expiry parameters

#### Scenario: A retry picks up a refreshed token

- **WHEN** an upload fails with `401` on an expired token, the app then renews, and the engine re-mints
  the request for that resource
- **THEN** the rebuilt request carries the **new** token, and the URL is byte-identical to the original —
  also when the process that re-mints still holds the expired token in memory

#### Scenario: A missing token still yields a request

- **WHEN** `provide` is called and no token is available to it
- **THEN** a request is returned with `Content-Type` and the app-version header but no `Authorization`
  header, and the upload is allowed to fail and be retried rather than being abandoned


## ADDED Requirements

### Requirement: A retry's request carries the credential from its store of record

The provider SHALL offer `provideForRetry(resource)` beside `provide(resource)` (the `UploadRequestProvider`
seam, capability `sync-engine`). It SHALL return a request with the same URL and the same headers `provide`
composes for that resource, except that its `Authorization` token SHALL be read from the token's **store of
record** (the shared Keychain item), bypassing any in-process copy. A retry is exactly when a copy is most
likely stale: the failure may have been the `401` of a token the other process has since renewed, or cleared
after a rejection. So a retry pays one uncached credential read, and every first request is spared one.

The provider SHALL take the two reads as two injected sources — the token, and the fresh token — each required,
so a composition with no credential states `{ null }` for both rather than inheriting a default. Reading the
fresh token remains the provider's only side effect. The provider still mints nothing, calls no platform API
itself, and builds no signature. When the fresh read finds no token, `provideForRetry` SHALL still return a
request without the header, exactly as `provide` does.

Decision record: `changes/own-work-per-wake` (D13).

#### Scenario: A retry reads past a stale in-memory copy

- **WHEN** the process's in-memory copy holds token T1, the shared item now holds T2, and
  `provideForRetry` is called for a resource
- **THEN** the request carries `Authorization: Bearer T2`

#### Scenario: A retry addresses the same destination

- **WHEN** `provide` and `provideForRetry` are called for the same resource with the same configuration
- **THEN** the URLs are byte-identical, and the headers differ at most in the token they carry

#### Scenario: A first request does not pay the uncached read

- **WHEN** `provide` is called
- **THEN** it reads the token source only, and the fresh-token source is not read
