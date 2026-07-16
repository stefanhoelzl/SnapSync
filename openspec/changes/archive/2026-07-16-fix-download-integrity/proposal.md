## Why

`photo-download` already requires this, and has since the S3-direct migration:

> a response whose received body is **shorter than its `Content-Length`** (a truncated download) SHALL be
> treated as a **failed** transfer and retried, not accepted as complete — the integrity signal formerly
> guaranteed by the download proxy, now evaluated against bunny's S3 GET response.

Nothing implements it. `IosDownloadTransport.onFinished` (`capability/download/src/iosMain/.../IosDownloadTransport.kt:68`)
is four lines: take the description, ask for a destination, move the temp file, report `onStaged`. The only
failure it can observe is a filesystem move failing. `downloadTask.response` — an `NSHTTPURLResponse`
carrying both `statusCode` and `expectedContentLength` — is never read.

The seam is why. `DownloadTransportHost` exposes `destinationFor` / `onStaged` / `onCompleted(description,
error)`; **no response and no byte count ever crosses it**. This is the same shape as
`fix-upload-config-gate`: there is no wrong answer to observe, only a question that cannot be asked.

That matters because URLSession does not treat an HTTP error as an error. A non-2xx response is a
*successful transfer of an error body*: `didFinishDownloadingToURL` fires with the error document in the
temp file, and `didCompleteWithError` hands back `nil`. So a `502` from bunny stages an XML body under a
photo's staging path.

And the bytes are cached as truth. `DownloadController.onResourceStaged` calls `store.markStaged` before
anything inspects them; `importableAssets()` then reports the asset forever, because every resource is
staged and none is imported. `PHAssetCreationRequest` rejects the XML at commit, and the controller's
handling is:

```kotlin
is ImportResult.Failed ->
    log.w { "import deferred for ${ref.sourceAssetId}: ${result.message}" }  // retried later
```

There is no re-download — the store believes those bytes arrived. The asset is a **permanent poison pill**:
the import is retried on every reconcile, forever, against the same garbage file, and the photo never
arrives. Retrying a failed import is *correct* for a transient PhotoKit failure and *poison* for
permanently-invalid bytes; the integrity check is what makes that design's assumption true.

The truncation case the requirement names is the nastier variant, because JPEG is a streaming format: a
short read decodes to a valid image with a grey tail, so PhotoKit **accepts** it and half a photo lands in
the library marked done. Which of the two actually occurs on device — and how often — is not knowable by
reading; establishing it is task 1, not an assumption of this proposal.

## What Changes

- **Widen `DownloadTransportHost` to carry the transfer's outcome**, so the question becomes askable. The
  facts needed are exactly three: the HTTP status, the expected byte count, and the received byte count.
- **Decide in `commonMain`, not at the ObjC edge.** `IosDownloadTransport`'s own KDoc already fixes this:
  *"This class is the ObjC edge and nothing more — the queue, the bounded window, the transfer-description
  codec, the staging-path derivation, and the URL guard all live in `QueuedPhotoDownloadJobs`, where they
  are covered by `commonTest`."* The integrity predicate joins that list. The edge only reads
  `task.response` and the file size.
- **A rejected transfer is a failed transfer, not a staged one.** It must not reach `store.markStaged` —
  that is what makes it re-downloadable instead of a poison pill. It reports through the existing terminal
  path so the slot frees and the queue refills.
- **Tests in `commonTest`** (so they run on JVM and `iosSimulatorArm64`): non-2xx rejected, short read
  rejected, exact-length accepted, unknown length accepted, over-length accepted.
- **Compose the real download orchestration into `:test:world`.** The world fakes `PhotoDownloadJobs` —
  the layer *above* `QueuedPhotoDownloadJobs` — so the real window, URL guard, description codec **and**
  this integrity check are exercised by no world test and no `:test:integration` test. The world fakes
  `DownloadTransport` instead and composes the real jobs over it, which is what `harness-world-model`
  already promises ("the REAL platform-agnostic stack"). This requires `World` to take the caller's
  `CoroutineScope`; see design D6.

## Impact

- **Affected capabilities**: `photo-download` — the requirement already exists and is correct; this
  implements it. And `harness-world-model`, whose download seam moves down a layer so the real
  orchestration is exercised.
- **Affected code**: `capability/download` — `DownloadTransport.kt` (the seam),
  `QueuedPhotoDownloadJobs.kt` (the predicate + wiring), `IosDownloadTransport.kt` (read `response`, stop
  staging unconditionally). `:test:world` — fake the transport, compose the real jobs, take a
  `CoroutineScope`. `:test:integration` and `:app:desktop` — pass a scope at the ~51 `World()` sites; the
  world inspector gains a bad-transfer lever.
- **Not covered**: bytes that are well-formed but *wrong* (a 200 serving another photo's content). No
  length or status check sees that; it needs a content hash, which the edge URL layout does not carry.
- **Behavioral risk**: rejecting too eagerly turns a working download into an infinite re-download loop.
  `expectedContentLength` is `-1` when the server omits `Content-Length`, and that is common enough that
  treating unknown as short would break every such transfer. See design D3.
