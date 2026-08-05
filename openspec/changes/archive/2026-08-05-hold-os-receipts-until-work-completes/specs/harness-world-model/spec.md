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

An operator action SHALL be complete when it returns. `onStaged` **is** a suspend seam: the delegate
thread still must not be blocked by an import, so `QueuedPhotoDownloadJobs` owns the launch and tracks it,
and the world SHALL await those tracked imports — via the feature's own `awaitOutstandingImports` — before
the stage action returns. Otherwise every download assertion in the world becomes a race, which is the
opposite of what an operator-driven harness is for.

The world SHALL NOT re-install `onStaged` to obtain that guarantee. It previously did, because the seam was
non-suspend and the composition's fire-and-forget launch left the work unreachable — the same
unreachability that let the app's background-session handler be released while its imports were only queued
(capability `ios-app-shell`). Now that the feature tracks its own launches, the harness runs the production
wiring unshadowed: one fewer place it can diverge from the app.

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


#### Scenario: The harness does not shadow the composed staged-resource hook

- **WHEN** the world is constructed
- **THEN** `downloadJobs.onStaged` is the hook `snapSyncApp` installed, not a world-local replacement

### Requirement: The world composes the app graph through snapSyncApp

The world SHALL hold the app-side graph as a real `AppCore` produced by the **same** `snapSyncApp`
composition the iOS app shell calls (spec `module-architecture`, "One shared composition"),
constructed over an `AppPorts` whose ports are the world's fakes and mini-edge seams and whose
shell-supplied lambdas are the world's operator surface: `provision` writes the config cell,
`notifyLeave` is the real backend DELETE seam, `onEventMinted` is a settable routing hook (default:
provision the minted event directly; the desktop inspector points it at the status host's
pending-join gate), and `uploadProducer` is inert (nothing auto-runs — the operator plays the OS).
The world's exposed download controller, status sources, creation status, join use-case, and
user-tap command bundle SHALL be `AppCore`'s instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

**One** named deviation is permitted, an operator-synchronicity concern and nothing else: the world's
operator `leave()` MAY remain a synchronous faithful edge beside the bundle's production-ordered leave
(whose backend notify is fire-and-forget by design); tests driving the bundle's leave await the backend
outcome.

The former second deviation — re-installing the composed `downloadJobs.onStaged` hook with an identical
body plus Job retention — is **withdrawn**. It existed only because the seam was non-suspend and the
composition discarded the Job; the feature now tracks its own launches, so the harness has nothing left to
re-install and the permission would only license a divergence nobody needs.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or an integration test fires a user-tap command (create, commit-join,
  leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, download controller, or join use-case
- **THEN** it reads the composed `AppCore`'s instance; no second assembly of a feature graph exists
  in harness code
