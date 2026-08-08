# Design — type uploaded objects by MIME

## Context

Two independent defects produce one symptom: the stored object's `Content-Type` is never a MIME type.

**The value is wrong at the source.** `RawResource` carries both `contentTypeUti` (`public.jpeg`) and
`mimeContentType` (`image/jpeg`, resolved by `UTType.preferredMIMEType`). `resourcesFrom` puts the UTI on
`Resource.contentType` and the MIME in `metadata[RESOURCE_META_MIME]`. Every other consumer already
prefers the MIME — `toLedgerRow` does `metadata[RESOURCE_META_MIME] ?: contentType`, which is why the
device manifest and the event union have always carried the right value. Only the HTTP header read the
raw field.

**The value is lost on retry.** `UploadCycle.reconstruct` rebuilds a returned job's `Resource` from its
key alone with `metadata = emptyMap()`, because on a later OS invocation the original `Resource` — an
in-memory Kotlin object from a previous process — is gone. `PlatformUploadJob.contentType` was derived
from `job.resource?.uniformTypeIdentifier ?: "application/octet-stream"`, and `job.resource` is **nil for
a succeeded job** (the system releases it). So the rebuilt request carried the default, and the OS stored
that.

Both were measured on device rather than reasoned about: `public.jpeg` observed at the origin on a first
attempt, and all 10 objects of a forced-retry run stored as `application/octet-stream`.

Constraints that shape the fix:

- The OS performs the upload. We hand it an `NSURLRequest` and it executes it on its own schedule, so
  anything the retry path needs must survive the OS's job store.
- The destination URL is already load-bearing: production recovers each job's ledger key from
  `destination.URL.lastPathComponent`, because it is the only field present in every job state.
- `Resource.contentType` is the platform's own identifier. It is not wrong — it is simply not a MIME.

## Goals / Non-Goals

**Goals:**

- The stored object's `Content-Type` is the resource's real MIME type, on both upload tiers.
- A retried upload preserves the type of the request it is retrying, rather than inventing one.
- No behaviour change for anything that reads the union, the manifest, or the ledger.

**Non-Goals:**

- Rewriting the type of objects already in storage. There is no migration; only a re-upload retypes one.
- Changing `Resource.contentType` to hold a MIME. The UTI is the platform's identifier and other code
  may legitimately want it.
- Any backend change. `api/` forwards whatever arrives and branches on nothing.
- Carrying further metadata (capture date, original filename) on the request. That is a separate design.

## Decisions

**D1 — Read the MIME from resource metadata, with the UTI as fallback.**
The provider prefers `metadata[RESOURCE_META_MIME]`, treating blank as absent, and falls back to
`Resource.contentType`.
*Alternative rejected:* make `resourcesFrom` put the MIME on `Resource.contentType` and drop the UTI.
That would flow into `PlatformUploadJob`, the ledger rows and every existing consumer at once, to fix one
header — a wide blast radius for a narrow defect, and it would discard a platform identifier that costs
nothing to keep.
*Why the fallback is load-bearing:* it is the seam the retry path arrives through. A reconstructed
`Resource` has empty metadata, so `provide` falls through to `contentType` — which, after D2, holds the
type recovered from the job. The two decisions only work together.

**D2 — Recover a returned job's type from its own destination header.**
`PlatformUploadJob.contentType` reads `Content-Type` from `job.destination.allHTTPHeaderFields`
(case-insensitively; blank treated as absent), falling back to the resource, then to
`application/octet-stream`.
*Forcing proof:* measured on device (SE2 / iOS 26.6, 2026-08-07) that `allHTTPHeaderFields` returns our
`Authorization` and `Content-Type` intact on both `.retry` and `.acknowledge` jobs. Expiry: re-measure if
the tier moves to the iOS 27 `PHBackgroundResourceUploadJobExtension`.
*Alternative rejected:* re-fetch the asset from PhotoKit at retry time to rebuild its metadata. That
costs a synchronous XPC round-trip per retried job and cannot answer under a partial grant, where the
asset may be outside the selection — so it would still need a fallback.
*Alternative rejected:* let the backend infer the type from the object key's extension. It would work,
but it moves a device-owned fact into the backend and makes the stored type disagree with the manifest's.

**D3 — Fix the app-driven tier the same way, not by leaving it.**
`IosUrlSessionUploadPlatform` hardcodes `application/octet-stream` when surfacing terminal jobs. Left
alone, a spec asserting that uploads are typed by MIME would be false on iOS 18–26.0.
*Why it is safe:* that adapter holds the resource in its own in-flight registry, so it has a real source
and does not depend on the OS round-trip D2 relies on.

**D4 — No repair pass over already-stored objects.**
The only reader of a stored object's type is a browser following a presigned URL. A repair would mean
re-uploading every object in the zone — the one storage namespace shared with real users' photos — to fix
a cosmetic header.

## Risks / Trade-offs

- **The header round-trip is an OS behaviour we now depend on** → measured, not assumed, and the fallback
  chain degrades to today's behaviour if it ever stops holding: absent header → resource → default. A
  regression costs a wrong type, never a failed upload.
- **A job created by an older build yields a UTI on retry** (that is what its stored header says) → no
  worse than today, which produced `application/octet-stream`. Both are inert; the backend branches on
  neither. Self-corrects as in-flight jobs drain.
- **Mixed fleet writes both shapes into one zone** → nothing compares them. The union's `contentType`
  comes from the manifest, never from the object.
- **The union could in principle receive a reconstructed type** → it cannot: a reconstructed row is bare
  (`creationDate = ""`) and `selectCompletedManifestRows` filters `creationDate != ''`. This matters
  because `resourceType()` on the download side **skips** any resource not prefixed
  `image/`/`video/`/`audio/`, so a UTI reaching the union would silently drop a photo on the receiving
  device. Worth stating because it is the one path where this class of bug would be harmful rather than
  cosmetic.

## Migration Plan

None. Deploy is an ordinary app release; the backend is untouched, so there is no ordering constraint
between the two. Rollback is a revert — stored objects are unaffected either way.

## Open Questions

- Should the no-app download page (`web-event-download`) set `Content-Disposition` or otherwise stop
  depending on the stored type? Out of scope here, but this fix only improves objects uploaded from now
  on, so that page still meets old objects with unrenderable types.
