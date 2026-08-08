# Type uploaded objects by MIME, not the PhotoKit UTI

## Why

Every object this app has ever uploaded is stored with a `Content-Type` no HTTP client, CDN or browser
understands. The provider sends `Resource.contentType`, which on iOS is the PhotoKit **UTI**
(`public.jpeg`), not a MIME type. Measured at the origin on device (SE2 / iOS 26.6, user-agent
`assetsd`): `content-type: public.jpeg`.

A **retried** upload is worse. `UploadCycle.reconstruct` rebuilds a job's `Resource` from the key alone,
and `PHAssetResourceUploadJob.resource` is nil for a terminal job, so the type collapsed to
`application/octet-stream` — and a retry can be the *first successful* PUT of a key, so this is not an
edge case. Measured: in a run where every first attempt was failed deliberately, **all 10** stored
objects carried `application/octet-stream`.

Nothing is visibly broken today, which is why it survived: the download path takes `contentType` from
the event union (which has always carried the real MIME), not from the stored object. The cost is paid
by the one consumer that reads the object itself — a browser following a presigned URL on the no-app
download page gets an unrenderable type and downloads instead of displaying. The deeper reason to fix it
is that the same mechanism — a rebuilt request silently inventing a value it does not have — is exactly
how a future field that *is* load-bearing would be lost without a symptom.

## What Changes

- The upload request's `Content-Type` is the resource's **MIME type** (`metadata[RESOURCE_META_MIME]`,
  resolved iOS-side by `UTType.preferredMIMEType`), falling back to `Resource.contentType` when absent.
  This is the preference `toLedgerRow` already applies, so the header finally agrees with the device
  manifest and the event union.
- A returned PhotoKit job's `contentType` is **recovered from the job's own stored destination header**
  rather than from `job.resource` (nil for terminal jobs). The destination is the one field present in
  every job state — the ledger key is already recovered from it for that reason. Measured on device that
  `allHTTPHeaderFields` round-trips intact on both `.retry` and `.acknowledge` jobs.
- The app-driven tier stops fabricating `application/octet-stream` when surfacing a terminal job, so a
  recreated upload on iOS 18–26.0 preserves its type too.

Not breaking. The backend never branches on `Content-Type` (it forwards it verbatim, defaulting when
absent), so no request shape is rejected and no consumer changes behaviour. Objects already stored keep
their existing type; only a re-upload retypes one.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `edge-upload-provider`: the requirement *"Returned request shape — Content-Type and Authorization, no
  metadata"* states `Content-Type` comes **from `resource.contentType`**. It becomes the resource's MIME
  metadata with `resource.contentType` as fallback. The neighbouring prohibition — `resource.metadata`
  SHALL NOT be emitted **as headers** — is unchanged and must stay explicit: this reads one metadata
  *value* to populate a header the contract already requires, and still emits no metadata headers.
- `ios-photokit-upload`: the requirement covering recovery of a returned job's identity from its
  destination URL gains the content type, and states why the resource is not a usable source (nil once
  a job succeeds).
- `ios-url-session-upload`: the `Background-URLSession BackgroundTransfer implementation` requirement's
  `fetchAckJobs()` clause gains the same guarantee — a surfaced terminal job carries the type its request
  was created with, not a fabricated default.

## Impact

- **Code**: `EdgeUploadRequestProvider` (`:domain` `model/`), `IosPhotoKitUploadPlatform`
  (`:adapter:ios:ext-safe`), `IosUrlSessionUploadPlatform` (`:adapter:ios:app-only`), and
  `EdgeUploadRequestProviderTest`.
- **Backend**: none. `api/` is untouched; the byte route already forwards whatever it receives.
- **Data**: new uploads only. No migration, no rewrite of stored metadata; the objects already in the
  `snap-sync-dev` zone keep their current type indefinitely.
- **Already implemented**: the first two bullets landed in `f7cc4879` (with tests) and were verified on
  device. The app-driven tier's half is outstanding — it was left out deliberately because that tier was
  not exercised by the measurement.
- **Not addressed**: `Resource.contentType` remains the UTI. That is correct — it is the platform's own
  identifier and other code may want it; only the HTTP header needed a MIME.
