## MODIFIED Requirements

### Requirement: Operator-driven download seams exercising echo-suppression

The world SHALL fake the download **execution edge** — `DownloadTransport` — and compose the **real**
`QueuedPhotoDownloadJobs` over it, rather than faking `PhotoDownloadJobs` wholesale. Faking the layer above
the orchestration would leave the real bounded in-flight window, transfer-description codec, URL guard, and
transfer-integrity check unexercised by every world test and by `:test:integration` — the world's whole
premise is that the real stack runs against it, and the download half was the one place it did not.

The fake transport SHALL record started transfers inspectably, and an operator **stage** action SHALL
deliver a finish for each in-flight transfer through the real jobs, carrying a `TransferOutcome` the
operator chooses. A default outcome SHALL describe an ordinary healthy transfer. A non-staged download SHALL
simply remain PENDING for retry — there is **no** `DownloadError` type and no terminal transfer-failure
state; a transfer whose outcome the real jobs reject leaves its resource un-staged, which **is** that
pending-for-retry state rather than a new one.

Because the transfer now runs through the real jobs, the world SHALL be constructed with the **driver's**
`CoroutineScope` — the `worldTest` scope in tests, the inspector's in the desktop harness.
`QueuedPhotoDownloadJobs` requires one at construction, and a world-owned scope would outlive its caller,
leak staging work between worlds, and be unjoinable. The world SHALL NOT offer a scope-free fallback path,
because two ways to drive downloads is a second one that can rot or lie.

An operator action SHALL be complete when it returns. The real `onStaged` is not a suspend seam — in
production it is invoked from the platform's delegate thread and must hop into a coroutine — so the world
SHALL await the staging work it launched before the stage action returns. Otherwise every download
assertion in the world becomes a race, which is the opposite of what an operator-driven harness is for.

`PhotoLibraryImporter.import` SHALL import the asset into the in-memory gallery (so it enters gallery
enumeration) and mark the download store imported, so the imported asset's id enters `suppressedLocalIds()`;
a settable import-failure SHALL yield `ImportResult.Failed`. The fake importer SHALL name each
imported resource through the **same** shared rule the iOS importer applies (`importFilename`,
capability `photo-download`), so the world can never show a human filename where a device would show
a storage object key. A fake that is *more* correct than production is the failure mode this clause
exists to close: the fake applied the published name directly while the device let PhotoKit name the
resource after its staged file, and that divergence hid the wrong name from `:test:integration`
entirely. Because the real `UploadCycle.suppressedAssetIds` and `OwnDeviceGalleryStatusSource` consult
that suppression set, a foreign asset that is downloaded and imported SHALL NOT be re-uploaded by the
own-device cycle.

#### Scenario: A downloaded-and-imported asset is suppressed from re-upload

- **WHEN** a foreign asset is discovered via the union, staged, and imported into the gallery, and the
  own-device upload cycle then runs
- **THEN** the imported asset appears in `suppressedLocalIds()` and the cycle creates no upload job for
  it (echo-suppression holds)

#### Scenario: A non-staged download stays pending

- **WHEN** an enqueued download is not staged by the operator
- **THEN** its resource remains PENDING for retry and no terminal failure is recorded

#### Scenario: An imported asset carries the naming production would give it

- **WHEN** a foreign asset whose manifest publishes a human filename is staged and imported
- **THEN** the gallery asset it creates carries that filename, derived through the same shared
  rule the device applies

#### Scenario: Import failure is surfaced without a terminal state

- **WHEN** the operator arms an import failure and import runs
- **THEN** `import` returns `ImportResult.Failed` and the asset remains importable

#### Scenario: The real download orchestration runs against the world

- **WHEN** downloads are enqueued and the operator stages them
- **THEN** they pass through the real `QueuedPhotoDownloadJobs` — its window, description codec, URL guard
  and integrity check — and only the `DownloadTransport` is fake

#### Scenario: An operator-forced bad transfer is not staged and stays pending

- **WHEN** the operator stages a transfer with a non-2xx or short-read outcome
- **THEN** the resource is not staged, no import is attempted against it, and it remains PENDING for retry
  rather than entering a terminal failure state
