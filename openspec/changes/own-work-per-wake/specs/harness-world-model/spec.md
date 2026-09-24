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
thread still must not be blocked by a store write, so `QueuedPhotoDownloadJobs` owns the launch and tracks it,
and the world SHALL await those tracked stagings — via the feature's own `awaitOutstandingStagings` — and then
the import the stagings made possible, which is the process tail's first unit (capability `photo-download`), by
requesting that import of the **composed** tail runner the way a staged download does, before the stage action
returns. Otherwise every download assertion in the world becomes a race, which is the opposite of what an
operator-driven harness is for. Decision record: `changes/own-work-per-wake` (D1).

The world SHALL NOT re-install `onStaged` to obtain that guarantee. It previously did, because the seam was
non-suspend and the composition's fire-and-forget launch left the work unreachable — the same
unreachability that let the app's background-session handler be released while the work it announced was only
queued (capability `ios-app-shell`). Now that the feature tracks its own launches, the harness runs the production
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

**The core and the host.** The world SHALL hold the app-side graph as a real `AppCore` **and** a real status
host, produced by the **same** shared host composition the iOS app shell calls (spec
`module-architecture`, "One shared composition"). That composition calls `snapSyncApp`, constructed over an
`AppPorts` whose ports are the world's fakes and mini-edge seams. The world SHALL NOT assemble a status host,
install a subscription, or wire the HTTP client's credential and version callbacks itself.

**No second body for core machinery.** The world SHALL bind no `AppPorts` field to a body of its own that
stands in for core machinery. The provision a join performs, the attestation refresh and the push
registration are built by `snapSyncApp` and run for real in the world, exactly as on iOS — the push
registration's subscription installed as the shared host composition composes the graph, on every launch, as on
the phone (capability `ios-app-shell`, "Push registration is started by the shared composition").

**The operator surface** is the world's own levers **beside** the composed core (the operator `provision()`,
`leave()`, `relaunch()`, the inject/fail levers), never a second body for a seam the core calls:
- `onEventMinted` is a routing hook. Its default provisions the minted event through the composed Provision
  flow; the desktop inspector and the JVM host point it at the status host's pending-join gate.
- The app uploader's **units** are inert: nothing uploads on its own, and the operator plays the OS. They are
  nevertheless driven by the **real** composed tail runner from every OS entry the world delivers, and they
  count what the runner asked of them (top-ups, walks, transfer handbacks), so a test reads which units a wake
  reached, in the real order; an operator lever parks the next unit, so a test can hold a tail in flight to
  deliver an expiry or a join while it runs. The tail's import unit is the **real** download drain, so every OS
  entry's tail imports what is staged. The heartbeat the runner re-arms is counted, never run.
- The process's background time is an **operator-expirable table** of outstanding holds, over the honest
  in-memory double of the background-time port: a wake's hold is visible there until it ends, and the operator
  fires the operating system's "time is up" on every outstanding hold. Decision record:
  `changes/own-work-per-wake` (D1, D3).

The world's exposed download controller, status sources, status host, creation status, join use-case, and
user-tap command bundle SHALL be the composition's instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

**The one permitted deviation** is an operator-synchronicity concern and nothing else: the world's operator
`leave()` MAY remain a synchronous faithful edge beside the bundle's production-ordered leave (whose backend
notify is fire-and-forget by design). Tests driving the bundle's leave await the backend outcome.

The former second deviation — re-installing the composed `downloadJobs.onStaged` hook with an identical
body plus Job retention — is **withdrawn**. It existed only because the seam was non-suspend and the
composition discarded the Job; the feature now tracks its own launches, so the harness has nothing left to
re-install and the permission would only license a divergence nobody needs.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or the JVM host fires a user-tap command (create, commit-join, leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: A join in the world runs the real Provision flow

- **WHEN** a test commits a join through the command bundle
- **THEN** the join's provision step runs the composed `flow/Provision` — membership entry, upload
  transition, push registration, album ensure, status refresh and download reconcile — with no world-local
  body in its place

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, the status host, a download controller, or a
  join use-case
- **THEN** it reads the composition's instance; no second assembly of a feature graph or a status host
  exists in harness code

#### Scenario: A source the iOS host observes is missing from the world's host

- **WHEN** the iOS shell's status host observes a read-model (for example the version refusal)
- **THEN** the world's host observes it too, because both are built by the one shared host composition

### Requirement: The world relaunches its app over its durable state

The world SHALL offer a `relaunch()` lever that models process death and a cold launch:
1. It ends the current composition's scope.
2. It runs the shared host composition again over the same **durable** state, and performs the cold-start
   sequence the iOS shell performs.

The durable state is:
- the ledger store and the download store;
- the config and secure stores, and the last-registered push record (an App-Group file on a device);
- the staged files;
- the gallery and the album map;
- the backend;
- the operating-system-held transfer sessions of both transfer doubles.

Every other cell SHALL start fresh. The relaunched app installs nothing but its push registration until its
host is touched, as a background relaunch installs nothing else until a scene connects; that registration sees
the token the operating system re-delivers and publishes it only if it differs from the last one the backend
accepted (capability `push-registration`). The world SHALL classify each cell it holds as durable or process
memory in one place, and a world test SHALL pin that classification.

#### Scenario: A completion learned by a dead process

- **WHEN** an upload job completes, the world relaunches, and a cycle runs
- **THEN** the relaunched app neither re-uploads the asset nor loses it, because the ledger and the
  transfer sessions were durable

#### Scenario: A relaunch performs no status read of its own

- **WHEN** the world relaunches while joined with photos
- **THEN** the status host starts from the un-read state, as a cold-launched app does, until a trigger reads
  status

#### Scenario: A relaunch with an unchanged push token publishes nothing

- **WHEN** the world relaunches after a registration the backend accepted, and the operating system re-delivers
  the same token
- **THEN** no registration is published, because the last-registered record survived the relaunch
