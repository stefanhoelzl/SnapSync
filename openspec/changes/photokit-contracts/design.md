## Context

This is phase 6 of the testing-concept sequence. Phase 3 (`changes/archive/2026-09-22-establish-port-contracts`)
built the mechanism and proved it on `SecureStore`. That change explicitly left two things open: implementing
any OS-scheduled system, and whether the app on a simulator earns a binding. This phase applies the mechanism
to the ports backed by the photo library.

Current state, verified against the tree:

- **Real PhotoKit coverage.** `PhotoKitSmokeTest` (`:adapter:ios:ext-safe` `iosTest`) runs without a grant and
  checks three things: the authorization status is answerable, `fetchAssetsWithOptions` returns without
  trapping, and both upload-job fetch actions come back empty. `PhotoKitCandidateSourceTest` pins the rule →
  `NSPredicate` translation, which is the adapter's own logic.
- **Where the adapters live.** `PhotoKitCandidateSource`, `IosDiscovery` and `IosAlbumManager` are in
  `:adapter:ios:ext-safe`. `PhotoKitAssetPresence`, `IosPhotoLibraryImporter`, `PhotoLibraryPermission` and
  `PhotoSelectionSnapshotSource` are in `:adapter:ios:app-only`. The rig-gated source set that can host an
  in-app binding exists only in ext-safe.
- **Where the doubles live.** Honest fakes exist for `CandidateSource`, `ImportedAssetPresence` and
  `PhotoSelectionChangeSource`. Every other photo double is a **levered** `:test:world` class:
  `FakeUploadDiscovery`, `FakeAlbumManager`, `FakePhotoLibraryImporter`, `MutablePhotoAccessStatusSource`.
- **The doubles already disagree with the platform, just from reading them.**
  - `FakeAlbumManager.assetIdsInAlbums` ignores `since`.
  - An asset `add`ed to an album never becomes visible to `assetIdsInAlbums`; only the `placeIn` lever
    affects that answer.
  - `add` to an album that does not exist is recorded as though it succeeded.

  `IosAlbumManager` behaves differently on all three.
- **Two bare adapters depend on a grant precondition they cannot enforce.** `PhotoKitCandidateSource`
  always answers `Readable` ("a walk that reaches here has a grant that permits it"), and
  `PhotoKitAssetPresence` answers `ABSENT` for every id PhotoKit does not return. Without a grant, PhotoKit
  returns nothing, so both would state a falsehood. Production wraps them in `PermissionAwareCandidateSource` /
  `PermissionAwareAssetPresence` (`:domain:compose`), which answer `NotReadable` / `UNKNOWN` instead.
- **`IosDiscovery` has no wrapper that reads the grant.** It maps any `Readable` to `fullEnumeration = true`.
  Without a grant, that is an empty, *authoritative* walk. The `UploadDiscovery` KDoc forbids that: "False …
  for a library the platform could not read". Only the cycles' grant gates keep it unreachable.
- **Measured host facts** (the `ios-simulator` skill):
  - `simctl privacy grant photos` writes the TCC row, and PhotoKit ignores it.
  - `applesimutils --setPermissions photos=YES` works for the app bundle.
  - A simulator has no `LIMITED`.
  - On a simulator, upload-job creation raises an uncaught `NSException` and terminates the process.
  - Deleting assets raises a system confirmation that someone has to tap (measured on the SE2 by the rig's
    wipe verb).

## Goals / Non-Goals

**Goals:**

- Every double of a PhotoKit-backed port in this phase is held to the same clauses as the real adapter,
  and CI runs both on every push.
- PhotoKit is exercised under a real full grant on CI, which nothing does today.
- Every behaviour that cannot be driven against real PhotoKit goes to one of the two destinations
  `port-contracts` names: the adapter's documentation with its evidence, or a fake-backed test of the
  project's logic. None of them becomes a clause.
- `IOS_SIM_APP` is defined once, so phase 5 binds to it rather than defining another.

**Non-Goals:**

- **The upload-job tier.** This covers `BackgroundTransfer`'s PhotoKit implementation,
  `SimulatorUploadJobQueue` and `UploadExtensionRegistry`. The OS drives this subsystem inside the device
  extension, a host the matrix lists as unbound and unmeasured. It is carved out as its own phase (D10).
- **Anything under a partial grant**, including `PhotoSelectionChangeSource` (D10).
- **Device recordings.** This change records nothing. No clause in it needs a state that only the device
  reaches and that could also be recorded (D10).
- **A PhotoKit recording seam** (D2).
- **`GalleryStatusSource`.** It is not backed by PhotoKit: its real implementation is
  `OwnDeviceGalleryStatusSource`, in feature code.

## Decisions

### D1. Six contracts, one per port, and what each one binds

| contract | subject | fake binding | real bindings |
|---|---|---|---|
| `CandidateSourceContract` | `CandidateSource` | `InMemoryCandidateSource` | `PermissionAwareCandidateSource(PhotoKitCandidateSource, real grant read, no snapshot)` on `IOS_SIM_KEXE` (`NO_GRANT`) and `IOS_SIM_APP` (`GRANTED_*`) |
| `UploadDiscoveryContract` | `UploadDiscovery` | `InMemoryUploadDiscovery` (new) | `IosDiscovery` on both hosts |
| `ImportedAssetPresenceContract` | `ImportedAssetPresence` | `InMemoryAssetPresence` | `PermissionAwareAssetPresence(PhotoKitAssetPresence, …)` on both hosts |
| `PhotoAccessContract` | `PhotoAccessStatusSource` + `PhotoAccessRequester` (one adapter) | `InMemoryPhotoAccess` (new) | `PhotoLibraryPermission` on both hosts |
| `AlbumManagerContract` | `AlbumManager` | `InMemoryAlbumManager` (new) | `IosAlbumManager` on `IOS_SIM_APP`, and on `IOS_SIM_KEXE` for whatever the no-grant host is measured to reach |
| `PhotoLibraryImporterContract` | `PhotoLibraryImporter` + a library observation handle | `InMemoryPhotoLibraryImporter` (new) | `IosPhotoLibraryImporter` on `IOS_SIM_APP` |

The importer's subject carries an **observation handle**. `port-contracts` admits a handle only for a
subject "that declares no reads of its own". The importer is a write-only seam, and its `ImportResult`
cannot show that the created asset exists with the requested capture date. Each binding implements the
handle over its own library: a `PHAsset` fetch by identifier, or the fake's cell. The handle observes
outcomes only (whether the asset exists, and its capture date), never which calls were made.

*Alternatives:*
- One large "photo library" contract. Rejected: one state vocabulary for six ports, and a
  `LedgerStoreContract`-style size split anyway.
- Contracting `GalleryStatusSource` here. Rejected: its real implementation is feature code, which a
  fake-backed test already covers.

### D2. `IOS_SIM_APP` is run live on CI, not recorded

On a simulator, PhotoKit under a real grant is reachable **only** from the app bundle. `applesimutils`
grants photo access by bundle identifier, and the kexe has none. There are two ways to get that evidence
into every build:

1. **Record on the simulator app and replay on the kexe.** `port-contracts` requires the recorded adapter to
   route every OS call through an internal seam, with seeding going through the same seam. Keychain's seam
   is four C calls over dictionaries. PhotoKit's is an object API: fetch results indexed lazily, `PHAsset`
   properties read one by one, `performChanges` blocks holding placeholders, `PHAssetResource` handles that
   cross the port uninterpreted. Replaying it means rewriting about 2,800 lines of adapter code across two
   modules against a data-shaped seam, and the replay would then test the seam's model of PhotoKit rather
   than PhotoKit.
2. **Run live on CI.** A macOS job builds the rig app for the simulator, grants access, launches it, and
   runs the contracts in-app over the rig channel. It needs no seam and no recording, and every push checks
   real PhotoKit.

Option 2. `port-contracts`' record/replay requirement applies to "a host CI cannot run", and after this
change the simulator app is a host CI runs. The cost is one more macOS job per push (D8). The host
identity is platform × process kind × entitlements: the app bundle, ad-hoc signed with the App Group
only. It is not the kexe, so it is a distinct `Host` value.

*Alternatives:*
- A device recording. Its state entry cannot clean up: every seeded asset stays in the operator's
  library unless someone taps a delete confirmation.
- Hosting the rig app inside the existing `ios-test` job. It would serialise a second xcodebuild behind
  the Gradle test run, and couple two failure modes in one check.

### D3. The photo grant is a precondition of the run, not a state a binding enters

A binding enters its state at construction. A photo grant is TCC state keyed by bundle ID and set from
outside the process, so nothing in a run can change it. Each real binding therefore declares a **fixed**
grant:
- The kexe bindings reach only the no-grant states.
- The simulator-app bindings reach only the `GRANTED` states.
- A binding asked to run in a process holding a different grant **refuses** before any clause runs, and
  the rig answers `409`. This is the refusal `SecureStore`'s device binding already makes in a simulator
  process (`CONTRACT_REFUSED`), generalised.

A refused run records no outcomes, so a mis-granted CI launch cannot report `Passed` for clauses that never
ran. The run also cannot turn every clause into `NotRunHere`: that would make the runner's declaration
check fail all of them, which is correct, but it would read as "the adapter is wrong" when the launch was.

The kexe's grant is **measured at the first run**, not assumed. The handoff's "a kexe cannot be granted
photo access" is an inference from `nsurlsessiond` refusing a process with no bundle ID, not a measurement
of TCC for photos. The expected answer is `NOT_DETERMINED`, and the state vocabularies name one `NO_GRANT`
state, because every composed implementation treats `DENIED` and `NOT_DETERMINED` identically. If the kexe
reads `DENIED`, the clauses stand unchanged, and the measurement goes into the host matrix.

*Alternatives:* a separate host per grant (`IOS_SIM_APP_GRANTED`, …). Rejected: a grant is authorization
state, not process kind or entitlement, and a host enum that multiplies by every TCC service does not stay
closed.

### D4. A live binding binds what production composes

Where production wraps an adapter in a composition that **owns part of the port's contract**, the real
binding is that composition over the real adapter and the real grant read (`currentPhotoPermission()`). For
`CandidateSource` and `ImportedAssetPresence`, the grant-dependent half of the contract (`NotReadable`,
`UNKNOWN`) lives in `PermissionAware*`. A contract binding the bare adapter could either say nothing about
the no-grant state, or assert the falsehood the bare adapter tells there. Neither licenses the fake, which
production callers only ever meet through the composition.

`UploadDiscovery` is the exception: production binds `IosDiscovery` bare in the extension root, and under
`SelectionScopedDiscovery(…, Unrestricted)` in the app. No composition reads the grant, so the adapter must
be honest on its own. **`IosDiscovery` claims `fullEnumeration` only when `currentPhotoPermission()` is
`GRANTED`.** A partial grant's fetch returns only the selection, which is not evidence of deletion either
(`limited-photo-access`). The change is behaviour-preserving on every shipped path, because both cycles are
grant-gated. It moves a safety property from two callers' gates into the adapter, which is the component
that states it.

This is the phase's first expected finding, and it is recorded the way `SecureStore`'s was. The clause
lands first, and its `Failed` outcome on the kexe is captured in the commit message. The fix follows in the
next commit.

*Alternatives:*
- Wrapping `IosDiscovery` in a new `PermissionAwareDiscovery`. Rejected: a third copy of the grant switch,
  for an adapter that can read the grant itself.
- Binding the bare adapters with a precondition clause. Rejected: `port-contracts` has no notion of a
  partial implementation, and adding one to license two adapters would weaken every other contract.

### D5. Clauses share one library, so each clause's addresses derive from its id

A live clause gets a fresh *implementation*, but the photo library is shared across clauses and cannot be
emptied without a human tap. Isolation therefore follows `port-contracts`' determinism rule ("addresses
derived from the clause id"):
- Each clause owns a **capture-date window** derived from its id: a fixed epoch plus the clause's ordinal
  times 30 days. It seeds only inside that window, and its policy selects only that window.
- Album titles are `snapsync-contract-<CLAUSE_ID>`.
- Imported assets carry the clause's window date.

Each CI run starts from a freshly created simulator, so the library is empty at the first clause, and
**no clause deletes anything**.

Seeding goes through PhotoKit's own creation API inside the binding's `create(state)`: a
`PHAssetCreationRequest` from a committed fixture with an explicit `creationDate`. `simctl addmedia` is not
used. It runs outside the process, it dates assets from EXIF, and it cannot be tied to a clause.

What a creation request **cannot** seed marks where the contract stops:
- `mediaSubtypes`: screenshots and screen recordings.
- Album membership made by another app.
- Resolution classes the fixture does not have.

The subtype exclusions stay pinned where they are, in `PhotoKitCandidateSourceTest` (the translation) and
`PhotoKitAssetFactsTest` (the SDK constants). The parser facts stay in `predicateFor`'s KDoc, with their
device evidence. None becomes a clause.

### D6. Honest fakes are extracted from `:test:world`; the levers stay behind as wrappers

Four new `internal` fakes go into `:adapter:generic:fake`. Each takes its state through its constructor,
and each has a factory, like the existing ones:

| new fake | extracted from | lever that stays in the `:test:world` wrapper |
|---|---|---|
| `InMemoryUploadDiscovery` | `FakeUploadDiscovery` | `makeWalkUnreadable` |
| `InMemoryAlbumManager` | `FakeAlbumManager` | `holdAdds`/`releaseAdds`, `delete`, `placeIn`, the inspection lists |
| `InMemoryPhotoLibraryImporter` | `FakePhotoLibraryImporter` | `failNextImport`, `failNextImportAfterCreating`, `suspendNextImport` |
| `InMemoryPhotoAccess` | `MutablePhotoAccessStatusSource` | settable status |

The divergences found in the Context are fixed **in the fake**, because the real adapter is the reference:
- `since` bounds `assetIdsInAlbums`.
- `add` makes an asset findable by its album's title.
- `add` to an album that does not exist is a no-op.

A test that depended on the old behaviour was testing the fake, and it is corrected in the same commit.

`FakeHonestyTest` already enforces the shape. `harness-world-model`'s module requirement is updated to stop
naming these classes as levered fakes.

*Alternatives:* binding the `:test:world` classes directly. Rejected: a binding enters state at
construction, and those classes admit state only through levers, which is the shape the fake-honesty gate
exists to forbid.

### D7. Placement

| piece | home | why |
|---|---|---|
| contracts, state vocabularies, the importer's observation handle | `:test:contracts` `commonMain` | as `port-contracts` places every contract |
| fake bindings | `:adapter:generic:fake` `commonTest` | the fakes are `internal` |
| kexe bindings for the ext-safe adapters | `:adapter:ios:ext-safe` `iosTest` | beside the implementation |
| kexe bindings for the app-only adapters | `:adapter:ios:app-only` `iosTest` | beside the implementation |
| simulator-app bindings, for the adapters of both modules | `:adapter:ios:app-only`'s rig-gated source set | they must be non-test and app-linked, and in-app is the only place the grant exists. **Deviation found at implementation:** without the property a rig directory compiles into its own module's `iosTest`, and one module's tests cannot see another's, so shared seeding can live in only one module. App-only sees both modules' public adapters, so all simulator-app bindings live there |
| composed bindings (`PermissionAware*`) | beside the PhotoKit adapter they wrap, with a test/rig-only dependency on `:domain:compose` | the adapter is the implementation under test |
| the simulator-app registry and `/contract` routing | `:test:rig`, fed by `:app:ios`'s rig hook | unchanged shape |

`:adapter:ios:app-only` gains a rig-gated source directory under the existing containment law ("A
build-time-only module is contained by compilation, not by a runtime check"). The law already admits the
shape generally. `module-architecture`'s module-set requirement names only the ext-safe directory, so it is
amended to name both.

### D8. The `ios-contracts` job, and how the coverage gate trusts it

A new job in `ios.yml`, on `macos-26`, parallel to `ios-build` and `ios-test`:
1. Build the app with `-Psnapsync.rig=true` for the iOS simulator.
2. Sign it with `scripts/sim-sign`.
3. Create a fresh simulator, install the app, grant photo access with a pinned `applesimutils`, and launch.
4. Call `GET /contract`, which lists the registry for this host, then `POST /contract/<name>` for each
   entry.
5. Fail on any `Failed` outcome, a refusal, or an empty registry.

The job posts the `ios-contracts` context. It becomes a third merge gate, with no `needs:` edges, for the
reason `ios-ci` gives for the other two.

The coverage gate cannot see CI, so it trusts `IOS_SIM_APP` live bindings through a checkable link: a
binding naming `IOS_SIM_APP` counts **only** if it is registered in the in-app registry the job runs. A
binding left out of the registry fails the gate. Registering is an ordinary source reference the gate can
read.

### D9. The initial clauses (the contract code is authoritative once written)

- **`CandidateSource`:**
  - `NO_GRANT` → `NotReadable`.
  - `GRANTED`, window seeded with N assets → `Readable`, holding at least those N (a superset is allowed, a
    subset never).
  - `GRANTED`, empty window → `Readable(emptyList())`, never `NotReadable`.
  - `DenyAll` → `Readable` with no candidate outside the clause's window.
  - A candidate's `resources()` yields each resource's upload key.
- **`UploadDiscovery`:**
  - `NO_GRANT` → `fullEnumeration = false` with no candidates. This is the expected first finding (D4).
  - `GRANTED`: `discover` returns every seeded in-window asset with `fullEnumeration = true`.
  - `resourcesFor` over seeded keys plus one departed key returns exactly the seeded resources, each with
    `filename` equal to its key, and never throws.
- **`ImportedAssetPresence`:**
  - `NO_GRANT` → `UNKNOWN` for every id, never `ABSENT`.
  - `GRANTED`: a seeded id is `PRESENT`, an unknown id is `ABSENT`, and every id asked about has an entry.
- **`PhotoAccess`:**
  - The status reads the process's grant synchronously.
  - `GRANTED`: `request()` raises nothing and leaves the status `GRANTED`, including a duplicate call.
- **`AlbumManager`:**
  - `ensureCreated` returns an id that `exists`.
  - `exists` of an unknown id is `false`.
  - `add` then `assetIdsInAlbums(title)` returns the normalized ids. Titles match case-insensitively and
    trimmed.
  - An asset captured before `since` is excluded.
  - `add` to a missing album is a no-op, never a throw.
  - An empty title set returns an empty set.
- **`PhotoLibraryImporter`:**
  - A valid fixture → `Imported(id)`, and the handle sees the asset at the requested capture date.
  - An invalid resource file → `Failed(consumedResources = true)`, measured on iOS 26.2 as
    `InvalidResource`.
  - A request whose shape the library refuses → `Failed(consumedResources = false)`, if a shape that
    reproduces `ChangeNotSupported` is found at implementation. Otherwise the clause is not written, and the
    measurement stays in `ImportResult.Failed`'s KDoc.

Every clause declares its state. Each binding declares, as a literal, exactly what its host reaches after the
kexe measurement (D3).

### D10. What is excluded, and where each item goes instead

| behaviour | why no clause | destination |
|---|---|---|
| `BackgroundTransfer`'s PhotoKit tier, `SimulatorUploadJobQueue`, `UploadExtensionRegistry` | the OS drives the subsystem in the device extension process, which no `Host` names; creating a job terminates a simulator process and starts a real upload on a device | a later phase of its own. `PhotoKitSmokeTest`'s job-fetch test stays until then |
| everything under `LIMITED`: selection snapshots, the partial-grant candidate read and presence, registration refused with 3311 | a simulator has no `LIMITED`; on a device it is entered only by a human in Settings, and one recording per host cannot hold two grant states | `limited-photo-access`'s measured record; the fake-backed tests of `PermissionAware*` and `SelectionScopedDiscovery` |
| `PhotoAccessRequester.openSettings()` / `choosePhotos()` | each hands control to another surface; an outcome exists only after a human decides | adapter documentation |
| an enumeration that throws (the world's `failNextEnumeration`) | PhotoKit reads return fetch results; this lever tests callers' defensive code | fake-backed test (unchanged) |
| `LIMIT_EXCEEDED` | upload-job tier | as the upload-job tier |
| the suspended / interleaved import (`SNAPSYNC-9`) | changes state mid-run, which `port-contracts` excludes from clauses | fake-backed test (unchanged) |
| an import that reports failure after creating | produced by a platform race nobody can trigger | fake-backed test (unchanged) |

## Risks / Trade-offs

- **[The simulator's PhotoKit is not the device's]** → The difference is provenance, not host identity, and
  the matrix records the simulator's iOS version. Device-only facts (the predicate parser's constraints,
  `LIMITED`) keep their device evidence in the adapter documentation. A simulator pass is never claimed as
  device evidence.
- **[A macOS job per push costs minutes and can flake]** → It parallels the existing two, and every step is
  already exercised by the `ios-simulator` skill's loop. A launch that never answers on the rig port fails
  with the device log attached rather than timing out silently.
- **[A system alert blocks a launch]** → Access is granted before the first launch, so `request()` under
  `GRANTED` raises nothing. On a stall, the job captures a screenshot first, as the `ios-simulator` skill
  directs.
- **[A third required context]** → `/ship` accumulates contexts from what actually ran. The job must be
  green on this PR before it is required.
- **[`applesimutils` is a third-party Homebrew tap]** → Pin it by version. If it breaks, TCC cannot be
  granted, the grant precondition (D3) refuses, and the job is red, never falsely green.
- **[The fakes' fixes ripple through world-backed tests]** → Each divergence gets its own commit, with the
  affected tests corrected in that commit.
- **[Phase 5 defines its own simulator host in parallel]** → `IOS_SIM_APP` is named in this change's
  proposal for phase 5 to bind to. Whichever merges second rebases onto the first's definition.

## Migration Plan

Additive except the fake extraction. Order:
1. Extract the honest fakes and move the world onto wrappers, with world tests green.
2. Add the contracts and fake bindings.
3. Add the kexe bindings, with the grant measurement and the `IosDiscovery` finding and fix.
4. Add the rig-gated source set in app-only, the simulator-app bindings and the registry.
5. Add the `ios-contracts` job, green before it is required.
6. Update the coverage gate.
7. Shrink `PhotoKitSmokeTest`.
8. Update docs.

Rollback is a revert. The only production change is `IosDiscovery`'s grant check, which is unreachable on
every shipped path.

## Open Questions

- ~~The kexe's measured photo grant.~~ **Measured 2026-09-23** (simulator iOS 26.5): the kexe reads `DENIED`
  (`PHAuthorizationStatus` 2), not `NOT_DETERMINED`. The `NO_GRANT` state covers both, so no clause changed.
  `AlbumManagerContract` and `PhotoLibraryImporterContract` have no no-grant clause, so their no-grant
  creation behaviour was not measured and binds nothing on the kexe.
- ~~Whether a request shape reproducing `ChangeNotSupported` exists (D9).~~ **Decided at implementation:** the
  clause is not written. No ordinary request shape reproduces it, and a contrived one would test the contrivance,
  so the measurement stays in `ImportResult.Failed`'s KDoc with its evidence. The first live run in the simulator
  app (iOS 26.5, 2026-09-23) passed every other clause of all six contracts.
- The upload-job tier's host: whether the device extension process becomes `IOS_DEVICE_EXT`, and how its
  runs are triggered. This is for the carved-out phase.
