# SnapSync architecture

How the app and the backend are put together, which rules hold them in shape, and what enforces each
rule. This is an engineering doc, not a contract. What a user can observe is specified in
`openspec/specs/`. The reasons behind a rule live in the decision records cited inline
(`openspec/changes/archive/<id>`). Testing lives in `docs/testing.md`, and build, deploy and release
live in `docs/deployment.md`.

**The gates are the authority, and this doc explains them.** Almost every rule below is a check that
fails `./gradlew build` (or `deno task lint` / `deno task test` for `api/`). Where the doc and a gate
disagree, the gate wins. Fix the doc. A rule marked **review** has no gate, and only a reviewer who
knows it can hold it.

Main decision record: `changes/archive/2026-07-17-establish-target-architecture`. Seam, failure, state
and concurrency rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.

---

## 1. Overview

```
api/        the backend: Deno + Hono on bunny Edge Scripting, a relational store, a byte store (section 8)
site/       the Astro landing and no-app download pages (served by api/ from the storage zone)
iosApp/     the Xcode project: the app target and the upload-extension target (Swift shells only)
domain/ adapter/ ui/ app/ test/ tools/    the Gradle modules (below)
architecture/   GENERATED diagrams (section 6)
```

### The module set: three groups

Every module in `settings.gradle.kts` belongs to exactly one group. The group names the law that
justifies the module's existence. A structure that no law justifies is a **package** (with a derived
text gate if it needs one), not a module. The enumeration lives in `ModuleSetTest`
(`:test:architecture`). Adding a module means adding the `include` **and** placing the module in one
group, with the group's argument in the commit.

| group | why it exists | members |
|---|---|---|
| **Withholding** | withholds a dependency (third-party, platform, or another core zone) from its consumers by compile error | `:domain:model` `:domain:inbound` `:domain:ports` `:domain:scheduling` `:domain:services` `:domain:feature` `:domain:entrypoints` `:domain:presentation` `:domain:compose` `:domain:host` · `:ui:state` `:ui:screens` `:ui:components` · `:adapter:ios:ext-safe` `:adapter:ios:app-only` `:adapter:generic:app` `:adapter:generic:mock` `:adapter:generic:sentry` `:adapter:generic:motionphoto` · `:test:levers` (the mocks withheld from the tests that call their typed faces) · `:app:ios` `:app:ios:extension` `:app:desktop` |
| **Contained** | exists so that something is **absent** from a production build, and is linked only under a build property | `:test:rig`, `:test:contracts`, `:test:launch-adapters` (`-Psnapsync.rig`) |
| **Support** | never linked into a shipped-format binary, exempt from production-module laws | `:app:jvm` `:test:integration` `:test:architecture` `:test:harness-driver` `:test:edge` `:test:control` `:test:cli` `:tools:diagrams` `:tools:detekt-rules` |

Key placements:

- `:ui:components` is the only module that may depend on Material 3.
- `:ui:state` is the screen's vocabulary — `UiState` (display values only) and `UiIntent` — and references
  nothing project-internal, so `:ui:screens` and `:ui:components`, which see it and nothing of the domain, cannot
  name a domain type. Presentation maps the domain into it.
- The host, `:domain:host`, is the only module that sees both the core's `compose/` zone and
  presentation, so neither gains the other.
- `:test:contracts` is the only module whose main code may assert (it withholds `kotlin-test`).
- `:test:control` and `:test:integration` compile against `model/`, presentation and `feature/`, each
  declared explicitly (the zones export nothing transitively), and never against `ports/`, `entrypoints/`,
  `compose/`, the host or the mocks. Within `feature/`, `ReadModelImportsTest` confines them to the
  `readmodel` packages. The compile boundary and that gate are the integration surface's read-model rule.

The per-module one-liners are in `CLAUDE.md` ("Modules"). The live graph is `architecture/modules.md`.

### The core and its zones

The core is the modules under `domain/` — its zones, its layers and the host — each with its package at
`app.snapsync.<zone>` (the host's is `app.snapsync.host`). "The core" names that grouping; `domain` names one layer
of it, `:domain:domain`, whose package is `app.snapsync.domain` (the grouping is to be renamed `core/` in phase 5). Each declares only the zone edges its law
permits, always with `implementation()`, so a forbidden reference does not resolve. That covers
fully-qualified names, typealiases and generated source, which a text scan would miss. The permitted
edges are `ModuleSetTest`'s `permitted` map.

```
inbound  <-  ports  <-  scheduling                       (the layers work enters and runs on — docs/patterns.md)
model    <-  ports  <-  services  <-  feature  <-  entrypoints  <-  compose
                                         ^          (+ inbound, scheduling)    ^
                                         +-- presentation --------------------+-- host
domain   <-  every zone above that names its values  (MIGRATING; it depends on nothing)
```

`feature/` also sees `scheduling/` (the runtime its outliving work and a sequence's pass run on), and `entrypoints/`
sees `feature/` and `services/` **migrating**: an entry point's steps still call today's features and services, until
phase 4 moves the work under sequences and each edge goes.

`domain/` is the first of the layers phase 4 moves the old zones into (`docs/patterns.md`, "The domain"). The old
zones depend on it while their types move — `model`, `services`, `feature`, `presentation`, `entrypoints`, `compose`
and the host, each edge **migrating** — and it depends on nothing, so the direction is one-way. Phase 5 deletes the
old zones with their edges. `data`, `application` and `query` are created by the first phase that moves code into
each.

| zone | holds | may reference |
|---|---|---|
| `domain/` | values and pure policy, by concept (`time`, `event`, `sharing`, `asset`, `device`): the timestamp, the event's and a membership's dates and capture range, the identities. No format: the boundary reads and writes them (`docs/patterns.md`, "Formats live at the boundary") | nothing — no module, no library (`DomainPurity`) |
| `model/` | vocabulary, pure domain services and codecs, `UserCommands`/`UserQueries` bundle types (and the `EventCreator` command), logging helpers (`logAt`, `bestEffort`), and **every pure-data type a port carries** | nothing project-internal |
| `inbound/` | how work **enters** the core: the four inbound kinds an event port delivers through (`Inbound`, `Background`, `Granted`, `Answered`), the handles they carry (`Completion`, `Running`), the `Trigger` that names the work, the structured `Log`, and the opt-in markers (`@InboundImplementation`, `@CompositionOnly`, `@AppProcessOnly`) — `docs/patterns.md` | nothing project-internal |
| `ports/` | every port interface — outbound, and the event ports the platform delivers through (`EventPort<H>`, registered with `inbound(handlers)`), with their `*Handlers` bundles typed by the inbound kinds — and nothing else (`PortsHoldInterfacesOnlyTest`). Every port extends the `Port` marker (`PortBundleTest`), `BuildInfo` among them: what the running build is | `model/`, `inbound/` |
| `scheduling/` | how work **runs**: the process's `Runtime` (the supervised scope, the background-time holds, the expiry), the entry-point bases, `steps { }`, single-flight `Sequence`s, `outliving`, `Observation` — `docs/patterns.md` | `inbound/`, `ports/` (`BackgroundTime`, `ThreadLabel`) |
| `services/` | the shared capabilities built over the thin ports: what a store holds, when it is opened, what a failure means — each a **concrete** class a feature takes as it is, with an interface only where there are several implementations or a decorator. The storage services (`LedgerService`, `DownloadService`, `SuppressionService`, `ConfigService`, `StagingService`, `DeviceManifestService`, `LogTailService`, `AlbumMapService`, `PersistedDeviceIdentity`) and their SQLDelight databases (the `.sq` files and generated code live here, so the zone's compile boundary covers them); the gallery services (`GalleryAccessState` — what the grant means; `GalleryDiscovery`, `WalkMemo`, `GalleryAssetPresence`, `GalleryCandidateSource`, the permission-aware decorators, `GalleryAlbums`, `GalleryImporter`); the transfer services (`OsQueueTransfer` and `SessionTransfer`, `ExtensionRegistration`, `DownloadJobs`, `SessionDrains`); the push record (`PushRegistrationRecord`, `PushTokenSource`); the app's foreground facts (`AppForeground`); the backend services; and the process's crash reporting (`CrashReporting`, `ProcessAccount`) and log writers (`SinkLogWriter` for Kermit lines, `SinkLines` for the structured `Log`) | `model/`, `ports/`, `inbound/` |
| `feature/` | business rules, one package per feature, mutually blind. A type consumed outside `feature/` lives in that feature's `readmodel` package (`feature/<feature>/readmodel/`) — the package is the definition of a read-model. **Features see services, never ports**: the compiler holds it, since the zone does not depend on `ports/` | `model/`, `services/`, `scheduling/` |
| `presentation/` | the UI-state reduction: `StatusContainerHost` reduces the facts its taps act on into a `ScreenModel` (the `UiState` it shows plus the facts behind it, `Shown`) and shows only its `UiState`; the create draft and the range calendar's rules are presentation's, so the screen states what it is told | `model/`, `feature/` read-model packages only, and `:ui:state` |
| `entrypoints/` | one **entry point** per operating-system trigger, named for it (`OnSilentPush`), each a short list of steps that orders and never decides (`AppEntryPoints.kt`, `ExtensionEntryPoints.kt`), the work several share (`WakeWork`, `ForegroundWork`, `Prelude`, `Provision`, `UploadEngine`) and the wake's sequences (`ImportReceived`, `TopUp`, `UploadOwn`) | `inbound/`, `scheduling/`, `model/`, `ports/` (the deliveries' vocabulary — it calls no port), `feature/` and `services/` (migrating) |
| `compose/` | the shared composition (`snapSyncProcess`, `snapSyncApp`, `snapSyncExtension`, the one cycle assembly `uploadCycle`), the services each builds over its ports (`AppServices`, the extension's), the process's `Runtime`, decorators, and every app event port's registration (`AppCore.inbound`, handing each port its entry points) | every zone but `presentation/` and the host, and `:ui:state` (the `Ui` port is bound to it in `AppPorts`) |
| host (`host/`) | the shared host composition, `snapSyncHost`: the core plus the status host over it; it calls `AppCore.inbound` once the host can be assembled, and registers the UI handlers itself, which need the status host | `model/`, `inbound/`, `scheduling/`, `entrypoints/`, `ports/`, `compose/`, `presentation/`, `feature/` read-model packages only, and `:ui:state` |

A **pure-data type** is a data class, an enum class, or a sealed class or interface that references no
port and carries no logic beyond its own members. A port's pure-data types are declared in `model/`, so an
adapter and a feature share them without either naming the other's zone.

**Allowed targets.** The core's modules and the `:ui:*` modules compile for exactly `jvm`, `iosArm64`,
`iosSimulatorArm64` and `android`, declared once by the `snapsync.targets` convention plugin in the `build-logic/`
included build. No such module lists its own targets; it may configure one the plugin declared (its test
runtime, say). Adding a target is an edit to the plugin and this list, not to any law.

The `android` target is its own plugin, `snapsync.android` (AGP's `com.android.kotlin.multiplatform.library`), which
`snapsync.targets` applies and which every other multiplatform module the Android app links applies beside its own
`jvm`/iOS list (`:adapter:generic:*`, and the rig's `:test:rig`, `:test:launch-adapters`, `:test:contracts`). It
reads the SDK levels from `libs.versions.toml` (minSdk 30 — scoped storage enforced, one code path, and the level the device tests run at too) and **lowers the bytecode
to `android-jvmTarget` (17)** while the toolchain stays JDK 25: D8/R8 dexes what the Android compilations emit, and
R8 over the whole graph is built on every push (`ci.yml`'s `android-build`, the plain release) and run by every
Android journey (`journeys (android)`, the rig release). It declares no host tests —
`commonTest` runs on the JVM — and device tests only where a module has `src/androidDeviceTest` (the adapters'
contract bindings), run on a Gradle-managed emulator by `androidPlatformTest`. The Android app itself is ONE process with ONE composition
(`:app:android`'s `SnapSyncRoot`, built in `Application.onCreate`) and ONE adapter module (`:adapter:android`):
iOS splits its adapters because its upload extension is a second process with linkage limits, and Android has no
second process.

`domain/` is a path grouping, not a module. The zone split is the one place where withholding is done
by an internal boundary. It was chosen because the text gates it replaced had to enumerate violation
forms, could not see generated code, and passed green when their directory was renamed.

### Adapters

The adapter tree is two levels deep, `adapter:<platform-axis>:<linkage-leaf>`, and the prefixes are
paths, not modules. Each axis names the question that separates its leaves:

- **ios axis = process linkage.** `ext-safe` may link into the upload-extension process. `app-only`
  must not. It owns the app-process `URLSession` adapters, whose OS-reattached session ids make an
  extension-side link unsafe. `ui` is app-only too and holds what needs Compose and UIKit's UI: the `Ui`
  adapter (the Compose scene SwiftUI pulls, and the scene rule), the `Lifecycle` adapter, and the
  main-confined `SceneRecord` they share.
- **generic axis = shippability.** `app` ships in the app and extension binaries, and so does `sentry` — the crash
  reporter, the `CrashReporter` port over the Sentry KMP SDK, ONE seat for iOS (both processes) and Android, because it
  is where "nothing unshaped leaves" is enforced. It reads no platform API: the build's facts arrive in `CrashOptions`
  from `BuildInfo`. It is its own module because it withholds the SDK. `mock` never ships. Each mock
  is a durable state, a per-process port face and a separate operator face (`docs/testing.md`, "Mocks"); the
  classes behind the port faces are all `internal` and every face is port-typed, so honesty is checked by the
  compiler: an app is only ever handed a port.

Adapters are named for the technology and hold implementations only. Finer structure is packages.

### Binaries

| binary | composition root | calls |
|---|---|---|
| iOS app (`SnapSyncKit`) | `:app:ios` `SnapSyncRoot` | `snapSyncHost` from `:domain:host`, over `AppPorts` |
| iOS upload extension, iOS 26.1 and later (`SnapSyncUploadKit`) | `:app:ios:extension` `UploadExtensionRoot` | `snapSyncExtension`, over `ExtensionPorts` |
| Android app | `:app:android` `SnapSyncRoot` | `snapSyncHost`, over `AppPorts` |
| JVM root — the desktop world harness, the JVM rig host | `:app:jvm` `JvmApp`, over adapters its caller chooses (the mocks by default) | `snapSyncHost`; `snapSyncExtension` beside it, each over its own process ports (a process that supplies no log sinks installs no log writer: Kermit's list is JVM-global) |

---

## 2. The laws

One line each. The authority is the named gate. Gates live in `:test:architecture`
(`test/architecture/src/test/kotlin/app/snapsync/architecture/`) unless noted. **review** = no gate.

### Structure

| law | enforced by |
|---|---|
| The build's module set equals the three groups, and each module is in exactly one group | `ModuleSetTest` |
| A core zone references only its permitted zones, through `implementation()` edges only | the compiler (zone modules) + `ModuleSetTest` "the core declares only permitted zone edges" |
| The core names no platform API and imports only its per-zone allowlisted libraries | the compiler. The allowlist is each zone's `build.gradle.kts` dependency block |
| Features are mutually blind (no feature references a sibling) | `ZoneFeatureBlindnessTest` (text; features enumerated from the directory) |
| Outside `feature/`, presentation, the host, every `:ui:*` module and `:test:control` (main and test) name only a feature's `readmodel` package. `:app:desktop` is exempt until 11g2 rewires it | `ReadModelImportsTest` (text: the line runs inside the one `:domain:feature` module, which no module edge can draw) + a non-vacuity twin + a ratchet that fails once the desktop exemption is no longer needed |
| A port's pure-data types live in `model/` | **review** (`model/` compiles without `ports/`, so a moved type cannot reference a port) |
| Core and `:ui:*` modules declare no target list of their own | **review** (the `snapsync.targets` plugin declares them) |
| Only `:ui:components` sees Material 3, its icons, or the QR library | the compiler (`implementation` dependencies in `ui/components/build.gradle.kts`) |
| `:domain` has no `iosMain` source directory | **ungated** (visible in review) |
| `:domain` declares no top-level mutable state (no allowlist) | **ungated.** The old spec named a "core-purity gate", but none exists |

### Ports and seams

| law | enforced by |
|---|---|
| Anything touching an external system (time, files, network, platform) goes through a port in `ports/`, named for the **need**, never the technology. The process-level ones are one external system each: `CrashReporter`, `ProcessMetrics`, `LogSink`, `Clock` (now and the device zone), `Crypto` (the platform's primitives: secure random bytes, HMAC-SHA256, AES-256-GCM of one segment), `ProcessInfo` (protected-data availability, `UNKNOWN` where it cannot be asked), `SystemUi` (share sheet, open a URL, the app's Settings page), `BackgroundTime` (a hold on the process's background time, begun with the run its end expires) and `ThreadLabel` (a per-thread slot: the trigger the runtime's lane labels each task's thread with, which a Kermit line reads) | **review** (partly the compiler: `ports/` cannot import Ktor, nor any of SQLDelight but its runtime interfaces, which `Databases` carries) |
| **A port answers every expected failure as a value** in its signature — a sealed result (`ProveResult`, `GalleryRead`, `FileResult`, `SecureStoreRead`, `WriteOutcome`, `Handoff`, …), never a throw. A failure only our own code, manifest or build could cause is a **precondition**: the adapter guards it, and its contract pins it where a host can reach it — or a type makes it unrepresentable (`AesKey`, `GcmNonce`). An adapter catches what its platform library throws once, with typed catches, and translates it into the port's answer; `Databases` alone hands out SQLDelight's driver, whose exceptions the services above it catch. An Objective-C exception cannot be caught from Kotlin at all, so an adapter checks first what would raise one (`UploadSession.upload`, an unreadable file) | `PortGridTest` (no port member declares `@Throws`) + each port's contract |
| A storage port is one external system and decides nothing (`Databases`: open by name, read-write or read-only; `Files`: read, tail, range, write, append, delete, exists, locate within an area — range and append so a file too large to hold is read and written a piece at a time; `Preferences`: get, set, remove). What a store holds, when it opens and what a failure means is a service's, in `services/` | **review** |
| The photo library is one thin port: `GalleryReader` (both processes: assets by policy or id, resources by id, albums, album members, create, add, and the `albumKind` — a `COLLECTION` a photo is added to on iOS, the `FOLDER` a photo lives in on Android) and `Gallery` (the app: plus the access request, the selection picker, the partial grant's selection observer, the import and the change token). It answers what the platform shows, and `NotReadable` — never an empty answer — when no grant lets the process read. Whether a walk is authoritative for deletion, which grant may say a photo is gone and which albums are denied are the gallery services' (`services/`); what the event album may hold under each kind, and when a folder album counts as deleted, is `AlbumCoordinator`'s (`feature/album`). Ids cross as the platform's own (`PlatformAssetId`), assets as the platform describes them (`GalleryAsset`: a capture instant or none, the media facts, a pixel area or none) and resources by `ResourceKind` (`PHOTO`, `VIDEO`, `PAIRED_VIDEO` — an original only) under an opaque `GalleryResourceHandle` only the adapter that handed it out reads; the app's `AssetFacts` and upload `Resource`s are the gallery services' reading of them | `GalleryReaderContract`, `GalleryContract`, `GalleryImportContract` (live on `IOS_SIM_APP`, the no-grant state on `IOS_SIM_KEXE`), `FolderAlbumContract` (live on `ANDROID_EMU`) + `GalleryServicesTest` |
| A photo's identity is one canonical `AssetId` (`model/`): non-empty, RFC 3986 unreserved characters only, never `..` — a URL path segment the backend accepts as it is, an object-name fragment, and free of the download tag's `\n`. The core, both stores, the manifest and the backend carry it unchanged; there is no translation on either side of the wire. It is minted from the platform's own id by ONE reversible encoding in the gallery services (`/` → `_`, `~` escaping the rest), the identity on every id stored before it — PhotoKit's `{UUID}/L0/NNN` is `{UUID}_L0_NNN`, a media-store id is itself — so every platform id has one, and no adapter mints it. A value from outside (a backend reply, an OS-held task tag) that is not canonical is a malformed reply or an undecodable tag, never a crash | construction (`AssetId`'s `init`) + `AssetIdTest`, `AssetIdsTest` |
| The transfers are thin ports, one external system each. An upload goes through one of two, which differ in what the system keeps and tells: `OsUploadQueue` (the OS's job queue — PhotoKit's: create a job sending a library resource; the first failures offered for their free retry, and every job that ended presented until acknowledged; retry, acknowledge — a job is known by the destination it was made with, the one fact the OS keeps) and `TransferSession` (the app's own session — a background `URLSession`, Android's in-process PUTs: start a transfer of a file, or of a resource where it `takesResources`, tagged; list and cancel the live ones; every end told once, as it happens, under its tag, and a relaunch's events handed over then drained) — so neither carries the other's vocabulary. A failed transfer is one fact, `TransferFailure(detail)`: nothing above the adapter decides on why; `Download` (start, cancel everything the session holds, and a finish reported with its facts and temporary file), `Wake` (schedule and cancel a `WakeId`, and hear the wake — a `Granted` delivery, whose `Running` the adapter expires when the operating system withdraws the time) and `ExtensionRegistry` (set and read the upload extension's registration, `Unsupported` on a platform without one — so no root holds an `if` around it). What a job means for the ledger, what a body may be staged as and when the heartbeat is armed are the services' (`OsQueueTransfer` and `SessionTransfer` behind the cycle's `BackgroundTransfer`, chosen per `Uploader`; `Heartbeat`) and the features' and entry points' (the download jobs, the wake's sequences). A platform's in-flight limit is its own and surfaces as `LIMIT_EXCEEDED`: the services create until refused. An OS completion handler crosses as a `Completion`, handed with the delivery it rides and answered once by the run it starts. A transfer session's relaunch is two deliveries: `onBackgroundEvents` (a `Background<SessionRelaunch>`, carrying how many drain reports came before it) and `onEventsDrained` (an `Inbound<SessionDrain>`, numbered), so the relaunch's run waits for the first drain report after it however the two race to the core (`SessionDrains`) | `OsUploadQueueContract` (creation, the limit and the presented states recorded in the extension on a device; what asks the system nothing on `IOS_SIM_KEXE`), `TransferSessionContract` and `DownloadContract` (live on `IOS_SIM_APP` and `ANDROID_EMU`, their relaunches recorded), `WakeContract` and `ExtensionRegistryContract` (recorded, under their pre-11f names) + `OsQueueTransferTest`, `SessionTransferTest`, `UploadJobDecisionsTest`, `HeartbeatTest` |
| The origin exclusions' tuning — the two resolution floors and the album denylist — is one `SelectionCalibration` value in `model/`, product policy rather than a platform fact: no composition supplies its own, so the app and the extension cannot disagree | **review** |
| `SecureStore` writes answer a `WriteOutcome`; the identity and attestation services throw where the old throwing store did, so a refused write fails the operation (the device id is unavailable and never used unsaved; a token is not accepted; a keyId is refused). A write may replace by delete-then-add, so after a refused one the old value may be gone | `SecureStoreContract` (`INACCESSIBLE_WRITE_REFUSES`) + `PersistedDeviceIdentityTest`, `AttestStateTest` |
| How a date reads is one thin port, `DateFormatting`, in the app's bundle only (the extension renders nothing): `deviceLocale()` states the device's language and region, and `formats(DateLocale)` answers a `DateFormats` (`model/`) in that locale over the platform's CLDR data — `java.time` on the JVM, ICU on Android, `NSDateFormatter` on iOS. Which locale a screen formats in is the core's: services' `formatsFor(languageTag)` resolves it once (`resolveDateLocale`, `model/`). Each root builds a `ScreenDates` (`:ui:components`) over it, as `CutoffFormatter` over `Clock`, and hands that to its UI adapter — which may take no port — and on to `StatusScreen`; a rig build keeps the platform's, since it keeps no state to mock | `DateFormattingContract` (live on `JVM`, `IOS_SIM_KEXE`, `ANDROID_EMU`) + `DateLocaleTest`, `DateFormatsForTest` |
| Cryptography is one thin port, `Crypto`: primitives only, each answered by the platform's own implementation (CryptoKit through cryptography-kotlin's bridge and CommonCrypto on iOS, the JCA provider on Android and the JVM). A cipher key and nonce are exact by type — `AesKey` (32 bytes) and `GcmNonce` (12), minted by `EncryptedFileFormat` (`of` for outside key material) — so no member has a wrong length to refuse, and a seal that does not authenticate opens to `null`. No adapter knows the encrypted file format: its layout is `EncryptedFileFormat` (`model/`, pure; HKDF over the port's HMAC) and its files are sealed and opened a segment at a time by `FileCipher` (`services/crypto`). The TypeScript twin the edge and the event page run is `api/src/encrypted-file.ts` | `CryptoContract` (published vectors; live on `JVM`, `IOS_SIM_KEXE`, `ANDROID_EMU`) + both halves held to `test/vectors/encrypted-file.json` and, on the JVM, to Google Tink itself (`EncryptedFileFormatTest`, `api/test/encrypted-file.test.ts`) |
| Ports never call ports: no adapter's constructor takes a port. Combining two external systems is a service's job (`services/`) or a feature's, so no decision hides inside an adapter where a mock cannot see it and a second platform would have to re-make it | `PortsNeverCallPortsTest` (every constructor parameter of the adapter modules' production source sets typed as a `ports/` interface; the rig's decorators are exempt). Its allowlist is exact both ways and only shrinks: 11d, 11e and 11f emptied their holdings; what remains is named with its owner |
| The backend is reached over ONE plain port, `HttpTransport`: send a request, answer what came back — a status, headers and text, or `Unreachable` — whatever the status, and never throw. `KtorHttpTransport` is its one implementation on every platform, over the engine each composition supplies. What a route is, which token it carries and what an answer means are not the transport's: they are the backend boundary's ("The backend boundary", §8) | `HttpTransportContract` (live on `JVM`; `ANDROID_EMU` and the simulator compile it) |
| The backend's protocol and verdicts are `BackendGateway`'s, in `services/`, and nowhere else (`BackendExchange` is it without a credential, for the `/attest/…` issuers): every request declares the build's version; the credential is read per request; an `Unauthorized` to a gated request that carried a token drops THAT token, recovers, and retries the request once with the recovered token; an `AppTooOld` on any route refuses the build; a `Success` clears the refusal. The app's credential re-attests; the extension's only drops the token and never retries. Every need-shaped backend service (directory, join, manifest, leave, union, device files, create, change, push token) sits over it and reads its route's generated reply; attestation reaches the open issuers through the exchange, so recovery never re-enters itself | `BackendGatewayTest`, `BackendServicesTest`, `CredentialRecoveryWorldTest` (the loop, composed), `BackendContract` (the real gateway against `api/` and the mock) |
| `DeviceIntegrity` only proves (`prove(challenge, handle?)`: a fresh key's attestation, or an assertion by an existing one), answering a `ProveResult` — `Ok`, or `Refused` naming why (`NotAvailable` in a process without the service, `UnknownKey`, `Failed`). Only the app process holds one; the extension has none. The attestation service decides when, and `AttestState` (behind the services' `AttestStore` seam) keeps the token and key handle | `DeviceIntegrityContract` (`NotAvailable` live on `IOS_SIM_KEXE`; the ceremony and `UnknownKey` recorded on a device, replayed every build; `UnknownKey` and `Failed` live on `ANDROID_EMU`) + `DeviceAttestationTest` |
| Paths are area-relative (`FileArea.SHARED`/`PRIVATE`); only an adapter resolves a platform path, and `locate` is the one exit, for a platform API that must be handed a file. No absolute container path is stored | **review** (the download store's staged paths: `DownloadStoreMigrationTest`) |
| `Files` answers `NotFound` for a definite absence only: a present file that cannot be read is `Denied`, never absent — while the legacy membership file is still copied into the shared database, a file read as absent is a false leave | `FilesContract` (`DENIED_IS_NEVER_NOT_FOUND`, live on JVM and `IOS_SIM_KEXE`) |
| **Storage, by kind.** Event and membership state — anything keyed by an event, or that has to commit together with the membership or the upload rows — lives in the shared database (`ledger.db`, `SharedDatabase`): the membership, the uploaded manifest, the pending leaves, the event albums, the event checks, beside the ledger rows, so one consistency domain (SQLite's transactions) covers them. Device-scoped values, independent of any event and never committed with anything, live in `Preferences` (the mobile-data setting, the push registration record). Bytes live in `Files`; secrets in `SecureStore`, and an event key is written before the membership row that names it. The membership is definitively absent only when the database answered with no row: a database that cannot be opened is unreadable, never "not joined" | **review**; `ConfigServiceTest`, `LegacyRecordsTest` |
| Building a composition opens no database: a storage service opens through `Databases` on first use and keeps only a successful open, so a locked launch's failure is retried on the next use — the membership too, which is seeded at its first read, never at construction | `UploadCycleIntegrationTest` (the databases the device saw opened) + `LedgerServiceOpenTest` + `ConfigServiceTest` |
| What another process committed reaches a process through `Databases.inbound` (`DatabaseHandlers.onChangedElsewhere`, naming the database): a commit is SQLDelight's notice to the driver's listeners — after the commit, never for a rollback — and a process's own commits never come back to it. On iOS each opened database posts and observes a Darwin notification (libnotify, on a queue of its own), and SQLite's `data_version`, read on the driver's writer, tells another connection's commit from this process's own; on the JVM and in the mocks another process is another instance; Android runs one process and delivers nothing. A missed notice costs freshness, never correctness: every entry re-reads what it acts on. No composition registers a consumer yet (a known gap; phase 4.9 adds one) | `DatabasesContract` (`COMMIT_ELSEWHERE_NOTIFIES_ITS_DATABASE`, `READ_ONLY_OPEN_HEARS_CHANGES`, `ROLLBACK_ELSEWHERE_IS_SILENT`, `OWN_COMMIT_IS_NOT_ELSEWHERE`; live on JVM and `IOS_SIM_APP` — a simulator test executable is no launchd job, and libnotify answers it `NOTIFY_STATUS_OPT_DISABLE`) |
| The download store has one writer and one migrator, the app. The upload extension opens it read-only: no store suppresses nothing; an older schema **pauses** the cycle (`CycleResult.Paused`, answered to iOS as *processing*) and never runs it without suppression; an unopenable one skips it. The pause is asked only after the extension's admission, so a partial grant never pauses | `SuppressionServiceTest`, `CycleGateTest`, `UploadCycleIntegrationTest` (a withheld cycle opens no download store), `DatabasesContract` |
| A received photo's library name is the sender's with a SnapSync mark (`ReceivedPhotoName`: a hash of the source ref, never an id), given to the library by the gallery services' `GalleryImporter` — the library stores the name it is handed, and a join records each marked photo of the event's union that the store has no row for — read from the event window of the library (`GalleryReader.assets`; on Android the event albums' folders are part of the default gallery like any folder) — as that ref's import, BEFORE the config save and for every direction. **Nothing of a membership is shared before its recognition:** a pass that READ the union records `recognised(eventId)` in the download store, and `CycleGateRead`'s `recognitionGate` withholds the cycle — in the app and in the extension, which reads the record read-only — and the status total stays uncounted until then. A union it cannot read settles nothing, so the next pass (foreground, wake, import drain, network return) asks again. A selection change that brings marked photos the store does not record withdraws the record before the snapshot is published and recognises them first (`ReceivedPhotoAdoption.holdForNewlyVisible`). The mark is read, never a selection rule: suppression stays `NotEcho` over the store | `ReceivedPhotoNameTest` (the token is a stored format, pinned), `GalleryImporterTest` (the import names each file with the mark), `GalleryImportContract` (`AN_IMPORT_KEEPS_THE_NAME_IT_WAS_GIVEN`), `DownloadServiceTest` (adoption, recognition), `ReceivedPhotoAdoptionTest`, `CycleGateReadTest`, `StatusRefreshTest`, `MembershipEntryTest`, `ReinstallIntegrationTest` |
| A platform's magic values, ABI integers and error tables stay in adapters, never in `model/`/`ports/`/`feature/` | `PhotoKitAbiContainmentTest` (PhotoKit media ABI). The JVM target rejects Apple types. Otherwise **review** |
| A composition bundle (`AppPorts`, `ExtensionPorts`, `ProcessPorts`, `AppDevicePorts`, `ExtensionDevicePorts`) holds ports and nothing else: the composition builds every service over them, reads the build's constants from `BuildInfo`, and names no main lane. A root decides nothing for the core by handing it a service, a constant or a lambda | `PortBundleTest` (reflection over the compiled bundles; and every `ports/` interface extends `Port`, its exceptions listed with their reason) |
| A function type is a seam only for an in-process, non-throwing callback into the core. Anything that leaves the process, can throw, or needs a lane is a port | `CompositionSeamTest`: every function-typed field of a `*Ports` bundle (none, today) and every function-typed constructor parameter in `feature/`/`compose/` is pinned with its reason, exact in both directions |
| A process-constant value (build version and number, baked host, store page, push environment, OS version, device model) is read from the `BuildInfo` port — raw, as the platform states it — never handed to a composition as a value or a thunk; whether the OS carries the upload-extension mechanism is the `ExtensionRegistry`'s `supported`. The boot banner and a dump's environment are composed from these facts (`snapSyncProcess`, `diagnosticEnvironment`); a root logs only what it alone knows, after composing | `PortBundleTest`, `BuildInfoContract`, `ExtensionRegistryContract` |
| No function-typed `var` (callback slot) in production. Callbacks are bound at construction | `LambdaSeamShapeTest` |
| No default on a function-typed constructor parameter (except `@Composable`) | `LambdaSeamShapeTest` |
| What the operating system tells a process arrives through **entry ports**, each an event port named for one external system: the app's `Lifecycle` (became active / is leaving it), `Links` (a link delivered, raw), `PushNotifications` (the token, its failure, a silent push with its `Completion`), `Ui<S, I>` (show a state; a person's intent, a live screen — generic over the screen's vocabulary, bound to `UiState`/`UiIntent` in `AppPorts`, so the port names no screen type) and `DevControls` (the build's switches and reset), and the extension's `ExtensionHost` (an invocation, answered as the platform's three-case `ProcessingAnswer`, and its end). An adapter hands deliveries over raw and in platform-independent values, through the inbound kind its handler is typed by (`docs/patterns.md`); what one runs is the composition's entry point. Each root keeps one-line forwarders for its Swift callbacks and holds no entry of its own | `EntryIntegrationTest` + `OsCompletionIntegrationTest` (the entry points' promises over the JVM root, through the rig) + `ExtensionEntryTest` + `ListenDoorTest` + shell gates. Naming is **review** |
| **Host-first entries**: only two deliveries assemble the status host — `Lifecycle.onForeground` (`OnForeground`'s step after the token request, on the composition lane, before its own work) and `Ui.onLive` — each a person reaching the app. A link is not one: `OnLink` keeps it in the `OpenedLinks` read-model, which the host opens once assembled. A push token, a silent push, a scheduled or transfer wake builds no host, so a cold background start installs no permission-grant subscription | `EntryIntegrationTest`, `OsCompletionIntegrationTest` (from a background launch, read off the OS's record) |
| An adapter's constructor takes no function. What the platform says arrives through `inbound`, what the core asks is a port method; a function on a constructor is a callback wired past both | `AdapterConstructorTest` (every class implementing a port interface in an adapter module's production and rig source sets, the mocks included), with no exemptions |
| `ports/` holds interfaces — plain, `fun` and sealed (with a sealed interface's cases) — the `*Handlers` bundles its event ports are registered with, and the ports' **data vocabulary**: value, data and enum classes that carry values and nothing more (no function beyond a `toString`, no nested object), so an adapter can depend on the ports alone. A decision or helper belongs in `services/` or `model/`, an implementation in an adapter, an inert binding (`NoProcessMetrics`, `NoBackgroundTime`) in `compose/`. The vocabulary moves in port by port; what is still in `model/` moves when its port is reshaped | `PortsHoldInterfacesOnlyTest` (no allowlist) |
| **Events arrive through `inbound`.** An event port extends `EventPort<H>`; its `*Handlers` bundle — whose constructor is `@CompositionOnly`, every field one of the inbound kinds — is built and registered with `inbound(handlers)` by a composition only, once per adapter, after the graph it delivers into is built: the app's by `AppCore.inbound` (`compose/AppInbound.kt`), which the host zone calls once the host can be assembled, beside the UI handlers it builds itself (they need the status host); the per-process `ProcessMetrics` by `snapSyncProcess`, because every composition sets its process up first; and the extension's `ExtensionHost` by `snapSyncExtension`, because that process has no host zone. `CrashReporter` is no event port: its `listen(CrashHandlers)` registers the callbacks that shape what its SDK sends. A rig decorator's forwarding `inbound` counts as the composition's single one. `inbound` only registers: it runs no handler and builds no feature. Every handler is an entry point (the table below), or, for the UI, a presentation intent. A `*Handlers`-typed `var` exists only behind an `override fun inbound` (or `listen`) | `ListenDoorTest` (both halves, non-vacuous) + `LambdaSeamShapeTest` + `CompositionOpensNoDatabaseTest` + the `@CompositionOnly` opt-in (a compile error elsewhere) |
| The partial grant's selection observer opens on composition (`Gallery.observeChanges`), on every start — a background one included, so a wake that builds no screen still learns the selection and its uploads are not withheld; the latest snapshot is kept for a consumer that attaches late | `EntryIntegrationTest` + `SelectionSnapshotLaneTest` |
| A read that can be unknown returns a sealed result with an explicit *unreadable*, never folded into a known answer. Predicates are named for the states they accept | **review** |
| Absence is never silent: a seam that can answer "nothing" separates *nothing* from *could not tell* wherever the consequences differ, or states why the collapse is safe for every cause | **review** (design discipline) |

**The entry table** — every delivery an event port makes, the inbound kind it is, and the entry point that takes it (`entrypoints/`, `docs/patterns.md`):

| port | handler | kind | entry point — what it runs | runs on |
|---|---|---|---|---|
| `Gallery` | `onImportPlaceholder(ImportPlaceholder)` | `Inbound` | `OnImportPlaceholder`: the import marker's write, synchronous — it must land inside the change block, before the asset is observable | the platform's change block |
| `Gallery` | `onImportSettled(ImportSettled)` | `Inbound` | `OnImportSettled`: the import's confirm or clear, persisted inline — the platform reports it once, even when the requester is gone | the platform's completion |
| `Wake` | `onWake(id, completion)` | `Granted` | `OnScheduledWake`: the prelude, then the wake's work (`WakeWork`); it holds no background time of its own — the delivery is the grant, whose withdrawal expires the run | the platform's launch handler |
| `TransferSession` | `onFinished(FinishedTransfer)` | `Inbound` | `OnUploadFinished`: the session transfer's guarded terminal write, inline — reported once — then, when the app may fill the freed slot, a detached `TopUp` pass | the session's delegate queue |
| `TransferSession` | `onBackgroundEvents(SessionRelaunch, completion)` | `Background` | `OnUploadSessionEvents`: waits for the drain report after it, answers the relaunch, and runs the wake's work detached | the platform's relaunch callback |
| `Download` | `onBackgroundEvents(SessionRelaunch, completion)` | `Background` | `OnDownloadSessionEvents`: waits for the drain report after it and for the stagings its events caused, answers the relaunch, and runs the wake's work detached | the platform's relaunch callback |
| `TransferSession`, `Download` | `onEventsDrained(SessionDrain)` | `Inbound` | `OnSessionDrained`: records the session's numbered drain report | the session's delegate queue |
| `Download` | `onFinished(FinishedDownload)` | `Inbound` | `OnDownloadFinished`: the download jobs' integrity check and the move into staging, inline — the platform deletes the file when it returns | the session's delegate queue |
| `Download` | `onCompleted(CompletedDownload)` | `Inbound` | `OnDownloadCompleted`: frees the window slot | the session's delegate queue |
| `Lifecycle` | `onForeground(completion)` | `Background` | `OnForeground`, host-first: records the return, asks the push service for the token again, assembles the host, runs the foreground's own work (`ForegroundWork`) and then the wake's work, all under the process's background time | the main thread (UIKit's notification) |
| `Lifecycle` | `onBackground()` | `Inbound` | `OnBackground`: records the departure, stops the foreground's watches, records the memory footprint | the main thread |
| `Links` | `onLink(LinkDelivery)` | `Inbound` | `OnLink`: services' `LinkOpening` over the sealed `LinkDelivery`, offered to the `OpenedLinks` read-model — a `Link` is opened whole, an `InstallReferrer` carrying an invite opens that invite and one carrying none opens nothing, a `NotALink` or `NoUrl` is logged by name; the host opens what is pending once assembled | the scene delegate's or SwiftUI's thread (main); the activity's on Android |
| `PushNotifications` | `onToken(PushToken)`, `onTokenFailure(PlatformError)` | `Inbound` | `OnPushToken` (the token reaches the source the push registration observes), `OnPushTokenFailure` (logged) — no host | the application delegate (main) |
| `PushNotifications` | `onMessage(PushMessage, completion)` | `Background` | `OnSilentPush`: the prelude, the push's own receive, and — for the active event only — the wake's work, detached; the completion is answered after the push's own work — no host | the application delegate (main) |
| `Ui` | `onIntent(intent)`, `onLive()` | `Inbound` | the container's intent table (a presentation intent); `onLive` is host-first, synchronous and idempotent — it assembles the host and answers its current state before the screen's first frame | the main thread |
| `DevControls` | `onReset(completion)` | `Background` | `OnDevReset`: the device-state reset | the control channel's lane |
| `ProcessMetrics` | `onReport(ProcessMetricPayload)` | `Inbound` | `OnMetricReport`: the report accounted for before the callback returns | the provider's thread |
| `ExtensionHost` | `onProcess()` | `Answered` | `OnProcess`: one upload cycle, answered as the platform's `ProcessingAnswer` on the invoking thread, which the runtime blocks; any throwable answers `FAILED` | the operating system's invoking thread |
| `ExtensionHost` | `onTerminate()` | `Inbound` | `OnTerminate`: the invocation's end, logged | the operating system's thread |

State whose change starts work is followed, not delivered: `Observation.follow` hands each emission to an entry point
as a delivery, and the next waits for that run — the partial grant's selection (`OnSelectionChanged`, after the
received-photo recognition withholds what it must) and, once the host is assembled, a returned network
(`OnNetworkReturned`).

### State and authority

| law | enforced by |
|---|---|
| Authority lives behind ports. After process death every fact is recoverable from a durable store or the external system. This binds adapters too | **review** |
| A delivery the platform makes **once** is persisted before the entry point returns, citing proof (API contract, vendor doc, or measurement) that it is once-only. A MetricKit report is handled **inline** on the thread MetricKit delivers on (the lane law's one exception for it): `ProcessAccount` attaches it — with the app's own last memory readings, `FootprintTrail` — to the crash channel and logs it before the callback returns | **review** |
| Each kind of write to a durable port has exactly one owner (a feature use case or a port's guarded write), written as one transaction with the guard in the statement. Two processes running that owner is fine | **review** |
| No transport adapter holds the ledger, not even the narrow `TransferRecord`: the upload service records a transfer's end, through that guarded write, where the platform reports it | `TransportLedgerGateTest` |
| No OS completion handler is stored in a mutable field (Kotlin and Swift): an adapter wraps the block in an immutable `Completion` and delivers it with its event, and the run answers it once — after its work, or after the expiry's grace | `OsHandlerContainmentTest` (a plain ban, no licensed owner) + the runtime (`EntryPointTest`) |
| The walk memo is built in one place (`appUploadDiscovery`) and bound only by the app's uploader, never by the extension or the shared `uploadCycle` | `@AppProcessOnly` on its constructor, carried on by `appUploadDiscovery`; `OptInPinsTest` pins the uploader's one opt-in |
| Mutable state touched from an OS callback thread is confined to a named lane (`@ConfinedTo`) or is a thread-safe primitive | `ConfinementGateTest` (heuristic) |

### Entry points, commands and queries

| law | enforced by |
|---|---|
| Rules live in features. Entry points order work and never decide: one per operating-system trigger, named for it, whose `run` is a short list of steps (`docs/patterns.md`) | the `core` complexity tier + **review** (the entry-point detekt rules are phase 4's) |
| Every delivery reaches the core through a `scheduling/` entry-point base, which labels, times and contains it: only the bases implement an inbound kind | the compiler (`@SubclassOptInRequired(InboundImplementation)`; the host's two UI handlers opt in at their site) |
| Work a run does not await is `detached` — labelled with the run's trigger, under its own background-time hold — never launched past the runtime | **review** (`Runtime.scope` is still public for the old zones' own launches, until phase 4) |
| Work several triggers ask for runs single-flight (`Sequence`): one more pass however many join, and a waiter whose time is up withdraws without cancelling the pass others wait for | `SingleFlightTest` |
| A step either stops the run (`required`) or is contained and logged (`isolated`); `concurrently` children are each isolated, and the step ends when all have | `StepsTest` |
| Commands cross one door: every command is an entry point, a service call, or a presentation intent — OS callbacks cross `entrypoints/`, and an event port's handler is exactly one entry point (or, for the UI, an intent). Presentation gets the `UserCommands` bundle and never calls a feature command directly | `ReadModelImportsTest` (a feature command lies outside every `readmodel` package) + the compiler (presentation has no `entrypoints/` edge) |
| Only a composition builds an event port's `*Handlers` (`compose/`, or `host/` where the host must exist first) | `ListenDoorTest` (with its twin: at least one site seen) + the `@CompositionOnly` constructor |
| Presentation's only function-typed port-reaching reads are the `UserQueries` bundle | **review** |
| Every `UserCommands`/`UserQueries` member is implemented through a lane-declaring decorator, with no default lane | `CommandLaneTest` (and the compiler: decorators take no default) |
| `:ui:screens` takes no `suspend` function parameter or field | `ScreensTakeNoSuspendSeamTest` |
| What a screen shows is `UiState`. Compose-remembered state in `:ui:screens` is allowlisted per file with its reason | `ScreenStateContainmentTest` |

### Composition and shells

| law | enforced by |
|---|---|
| One shared composition: a root hands its composition ports and nothing else — `AppPorts` to `snapSyncHost` (app), `ExtensionPorts` to `snapSyncExtension` (extension), each nesting the process's `ProcessPorts` — and the composition's FIRST act is `snapSyncProcess`: it installs the log writers (Kermit's and the structured `Log`'s) and the boot banner, then starts crash reporting before any other wiring, then registers the process metrics' entry point; one crash reporter, one process-metrics source, one `Files`, one `Clock`, one `ThreadLabel` and one `BuildInfo` per process. Its second argument is the process's `Runtime`, which the composition builds over the process's `BackgroundTime` and `ThreadLabel` and every entry point runs under. No root calls it, so none can compose in a process whose crash reporting has not started. The composition builds every service over the ports (`AppServices`; the extension's READ_ONLY identity, read-only suppression view and drop-only credential), and the app's uploader reads the app's own instances. The app root builds the process's ONE `CutoffFormatter` (the zone read once) and hands the same instance to the UI adapter and to `snapSyncHost`. A root never builds the status host or installs subscriptions. Only a process that supplies log sinks installs writers into Kermit's global list (a device root does; the JVM root's processes, many in one JVM, supply none unless their log is read back) | structural (one function) + `PortBundleTest` + **review**. The wiring graph is not unit-tested. It is smoke-tested by the integration surface, over the JVM root |
| A platform-mechanism decision (today: may the upload extension be registered, `extensionRegistrable`) is a pure, total, unit-tested function of runtime state, re-evaluated when an input changes. Target-fixed facts are not inputs | the compiler (exhaustive `when`) + `ProducerExclusivityTest` (no cell true below iOS 26.1) |
| Upload transitions stop in-flight work only at a leave (no deregister or cancel on revoke/reconfigure/launch; no registration write under a partial grant; enable always goes disable→enable) | `ProducerExclusivityTest` |
| Shells (`:app:ios`, `:app:ios:extension`, the JVM root `:app:jvm`, the host `:domain:host` and the shared composition `:domain:compose` — core zones, but wiring every root calls — rig-contributed shell source) hold zero decisions: the composition constructs and connects, and every decision, guard, failure arm and piece of per-event state it once held is a feature's or a service's, unit-tested there | `detektAppShell` (cyclomatic threshold 2, gating) + `KotlinShellGuardTest` (roots exist, `@Suppress` inventory exact both ways) |
| The build's adapter set is chosen at build time: a root builds its real adapters as one lazy device bundle — `AppDevicePorts` or `ExtensionDevicePorts`, each naming exactly the ports its process has, none defaulted — and calls `platformAdapters()` (app) or `extensionPorts()`/`extensionHost()` (extension), compiled from the root's `src/prod`/`src/entries` or — only under `-Psnapsync.rig=true` — from the control channel's source, which hands back the launch-time adapters' ports (`docs/testing.md`), decorates the UI (`RigUi`), supplies its own `DevControls` and runs a requested contract in the extension. No flag is read, no inert stub ships and no shipped binary links a mock | the build scripts + `DevControlsContainmentTest` + `MockContainmentTest` |
| Source a build script contributes into a shell's source set is shell source for the gates | the root `build.gradle.kts` `appShellSources` list, mirrored in `KotlinShellGuardTest` |
| Swift is a transcriber: decision keywords only at pinned occurrences, and every Swift shell function forwards to Kotlin | `SwiftShellGuardTest` |
| A tap crosses from a platform UI as a `UiIntent`: the screen's one tap table (`statusActions(dispatch)`) names each tap, and the container's one exhaustive table (`StatusContainerHost.onIntent`) decides what it means. No UI binds a container method itself | `HostStatusActionsTest` (the table clicked is the table that ships) + the compiler (the intent table is exhaustive) |
| The scene-mode resolver (`SceneRecord.resolve`) has one caller, the UI adapter's pull, and the scene generation one writer; SwiftUI pulls the scene at one site and binds only the generation `onSceneActive()` answers | `SceneRecordCompletenessTest` + `SwiftShellGuardTest` |
| The credential-recovery loop is composed, never wired by a shell: `snapSyncApp` builds the backend gateway over the `HttpTransport` port a root supplies, with the attestation service as its credential. (It used to need a text pin on the iOS root, which handed the core's rejection hook to the HTTP client — a cycle `compose/` could not close) | `PushIntegrationTest` (the JVM root composes the phone's loop) + `BackendGatewayTest` |

### Concurrency and failure

| law | enforced by |
|---|---|
| A **lane** is a dispatcher with a fixed role and fixed threads, and rules for what may run on it; the root names which dispatcher plays each lane. Three lanes: **main** (platform UI only, and the one UIKit completion handler UIKit requires there — a background `URLSession`'s, released by its adapter's `Completion`), **CPU** (presentation reduction: Orbit's event loop, which `StatusContainerHost` is handed as `reduction` — never left to Orbit's default), **composition** (a dedicated serial dispatcher for blocking calls, network, stores; its thread pinned to `QOS_CLASS_USER_INITIATED` by its first task, logged once). The composition names only the last two: **main is adapter-internal** — a platform-UI adapter (the share sheet, the URL opener, the Settings page, the permission prompt and picker) hops there itself, so a command that hands something to the platform's UI runs on the composition lane like every other. The upload extension has a lane of its own, which its root names (`snapSyncExtension(ports, lane)`). The live scope is never UI-bound, in any binary | **review** for the scope. The main lane is gated below; the CPU lane by `StatusContainerHostTest`'s pin |
| **The lanes are the app's only source of timers and threads.** The app code — the core's zones, and the generic adapters and mocks the JVM host composes over — names no dispatcher, thread or timer: every `delay` and `withTimeout` runs on the lane of the code that asks, so a host that owns the lanes owns the app's time. The control channel's JVM host runs every lane on one loop with mocked time (`docs/testing.md`, "How a test waits"); a platform adapter's own hop to a platform thread is the platform's, and outside this law | `TestsWaitOnEventsTest` (the app code the JVM host runs names no dispatcher or thread; one JVM adapter the host does not run is exempt, with its reason) |
| One adapter-owned hop lane beside them: the **PhotoKit read lane** (`photoKitReadLane`, `:adapter:ios:ext-safe`), a single dedicated thread pinned to `USER_INITIATED`, used by the discovery walk and the candidate source. A dedicated thread, because a pooled worker left at `USER_INITIATED` would carry the class into unrelated work | **review** |
| The main thread is named only by allowlisted platform-UI adapters (Kotlin `Dispatchers.Main`, `MainScope()`, `dispatch_get_main_queue`, `NSOperationQueue.mainQueue`; Swift `DispatchQueue.main`). `runBlocking` appears only where an `Answered` delivery is answered: the runtime's `AnsweredEntryPoint` (the extension's `process()` is synchronous by the operating system's contract — its work runs on the delivering thread, or on the lane a host names as the runtime's `answerOn`, while that thread waits) and the rig's `ContractRunningExtensionHost` | `MainLaneContainmentTest` |
| An adapter's dispatcher hop means throughput, never safety (the composition already keeps work off main) | **review** |
| Two entry-port exceptions run on the main thread by design: the iOS `SceneRecord` is written only there (UIKit's activation notification and SwiftUI's calls both arrive there, and the scene rule reads it there), and `Ui.onLive` runs there synchronously, because SwiftUI's pull must return a controller whose first frame already has a state. Foreground host assembly is NOT one of them: it is a step of `OnForeground`, on the composition lane | **review** (`SceneRecordCompletenessTest` pins the record's single writer) |
| No `runCatching` / `catch (Throwable\|Exception)` outside the cancellation-keeping and ObjC-boundary helpers and the runtime's containment sites (`Runtime`, `Steps`, `SingleFlight`; `docs/patterns.md`, "Containment"); the old zones' catch-alls are a per-file baseline that only shrinks. A cancellation is never reported as a failure | `CatchSitesTest` + `CatchGateTest` |
| No Kotlin throw escapes into ObjC (delegate overrides and blocks run through the boundary helper), and no ObjC `Boolean`/`NSError**` result is dropped (checked-call helper) | `ObjCBoundaryGateTest` (heuristic) |
| A multi-step use case declares each step required (stops and returns failure) or best-effort (logs and continues) | **review** |

### Platform, runtime identity and containment

| law | enforced by |
|---|---|
| The core (`:domain:*`) and the UI (`:ui:*`) hold no main source set but `commonMain`: platform code lives in an adapter, behind a port. Test source sets are unrestricted | `PlatformCodeContainmentTest` (modules from `settings.gradle.kts`, source sets from each module's `src/`) |
| All Keychain API use lives in `:adapter:ios:ext-safe` (import or fully-qualified) | `KeychainContainmentTest`. That module's own tests pin the accessibility class on every query |
| Neither entitlements file raises `default-data-protection` to `NSFileProtectionComplete` (it would make every App-Group file unreadable while locked, which kills background sync) | `DataProtectionEntitlementTest` |
| Extension-linked Kotlin references only allowlisted `platform.*` frameworks (scope derived from the extension's dependency closure) | `ExtensionSafetyTest` |
| Runtime-identity literals (strings the OS or installed base holds) appear exactly once, with pinned non-Kotlin copies agreeing | `RuntimeIdentityTest` (inventory in the test; see section 9) |
| Apple enums decoded with a fallback arm are pinned to the platform klib's declared set | `PlatformVocabularyPinTest` |
| The device (`iosArm64`) actual of the transfer session names the background configuration, and the simulator actual does not | `TransferSessionBindingTest` + an executable simulator test in `:adapter:ios:app-only` |
| The device actuals of the upload-job subsystem name the PhotoKit APIs, and the simulator actuals name neither (PhotoKit job creation traps on a simulator) | `UploadJobSubsystemBindingTest` |
| The event-link domain is generated from one resolved deployment everywhere (entitlement, `LINK_ORIGIN`, AASA, upload host, site URLs). No hand-written host literal. The extension claims no associated domain | `EventLinkDomainTest` |
| The Swift shell keeps both event-link delivery paths (`.onOpenURL` and the scene delegate's cold and warm halves) | `EventLinkDeliveryTest` (its failure message carries the device evidence) |
| A build-time-only module is contained **by compilation**: without its property, a build contains none of its source. There are no runtime flags or inert stubs. A needed shell call site is contributed by the module under the same property | the build scripts + CI's gated compile (`docs/testing.md` section 1). Shape is **review** |
| Production Kotlin declares no `"SNAPSYNC_*"` literal (no launch triggers) | `RunbookSkillsTest` |
| Every runbook skill `CLAUDE.md` points at exists, with a matching frontmatter name | `RunbookSkillsTest` |
| The control channel binds the loopback address only | `ControlChannelLoopbackTest` |
| Client and backend event-name caps agree, and no screen states the cap as a literal | `EventNameLimitTest` |
| The client's gated-route predicate matches the backend's closed list in `api/src/app.ts` | `GatedPathPinTest` |
| Retired dead weight stays dead (zxing/kotlincrypto, the `capability/` tree, the manifest accumulator, a second `*Enrollment` uploader, ...) | `DeletionLedgerTest`. Bringing one back means deleting its row in the same commit |

### Feature invariants held by guards

These pin one feature's structural rule that no type can state:

| guard | holds |
|---|---|
| `SelectionPolicyContainmentTest` | the capture-date bounds are compared in exactly one place |
| `SelectionPolicyConstructionTest` | a `SelectionPolicy` is built only by its one derivation (which always emits the capture floor) |
| `EventPhotoSetSourceTest` | production candidates come from the policy-taking read seam |
| `BackgroundTeardownTest` | no background entry point can tear a membership down (only the foreground, on confirmed absence) |
| `CrashScrubTest` (`:domain:model`, a unit test since the scrub is pure) | only the operator-confirmed diagnostic dump bypasses the UUID scrub: the dump declares the exemption, and the scrub reads it before redacting anything |
| `JvmRootBootsColdTest` | constructing a launch of the JVM root forces no `AppCore` member |

### Tests verify requirements

The specs (`openspec/specs/`) say what a user observes; the code is laid out by its own laws, not by the specs'
cut. They are linked in one direction only: a test that verifies a requirement says so with
`@Verifies(spec = "…", requirement = "…"[, scenario = "…"])` (`:test:control`), keyed on the spec's directory
and the requirement's title. `./gradlew :test:architecture:verifiesReport` lists which requirements a test
claims and which none does (`build/reports/verifies.md`, non-gating, never committed).

| law | enforced by |
|---|---|
| Every `@Verifies` names a spec, requirement and scenario that exist, so a renamed requirement fails the build and names the tests that claimed it | `VerifiesGateTest` (text: every test source set, the journeys included) |
| Production code cites no spec: no capability named in backticks, in a comment or a string, in any `.kt`/`.kts` outside a test source set | `NoSpecCitationTest` (text, heuristic: a paraphrase is not caught) |

### Disciplines with no gate

- **Necessity claims carry forcing proofs.** "The platform forces X" cites an API contract, a vendor
  doc, or a device measurement, never the current code, and names the trigger that would dissolve it.
  A gate that pins an exception carries the proof in its failure message.
- **A capability claim is settled by a compile, not a symbol table.** A klib records what ships, not
  what is callable (`Dispatchers.IO` is in the Native klib and is `internal`). To learn what an Apple API
  *declares* (enum sets, nullability), read the klib (`CLAUDE.md`, "Reading the Apple SDK from Linux").
  What the *device* does needs a measurement.
- **Expiry of the lane reasoning.** The three-lane argument is iOS-shaped (two processes). A platform
  whose background work runs in-process (Android) re-derives it rather than inheriting it.

---

## 3. How a gate is written

- **It fails closed on novelty.** A gate derives its scope from the repository at test time (directory
  listings, package patterns, "everything not allowlisted", the extension's dependency closure). It
  never scans a hand-maintained inclusion list. New code is in scope with zero gate edits.
- **It is never vacuous.** Every source-scanning gate asserts that it scanned something, per derived
  group. A renamed directory fails the gate. It does not pass forever and does not report "pending".
- **Pins are not scope lists.** A gate may pin an **expected value** (the identity inventory, Apple's
  enum sets, the `@Suppress` inventory, the seam inventory). Each pin states why its value is fixed and
  what would change it. Exact-both-ways pins shrink with the code.
- **Text gates match references, not imports.** Fully-qualified references import nothing. Where a
  gate reads comments as code, prose must describe a forbidden shape rather than quote it.
- **Heuristic gates say so in their source**, together with what they cannot see.
- **Dead-edge analysis** (`buildHealth`, dependency-analysis plugin) runs warn-only for jvm/common
  edges. iOS-only edges have no upstream support.

---

## 4. Ports and contracts

A port's obligations are stated once, as executable **clauses**, and run against every implementation:
the mock and each real adapter. A double therefore cannot quietly answer differently from the
system it stands in for. The contract code **is** the specification of a port's clauses, and no
`openspec/` spec restates them.

- **Contract** = a hand-written list of clause values in `:test:contracts` `commonMain` (id, required
  state, the port-grid cells it covers, body with `kotlin.test` assertions). Nothing records or generates an expectation.
- **Binding** = one implementation × one `Host` (`JVM`, `IOS_SIM_KEXE`, `IOS_SIM_APP`,
  `IOS_DEVICE_APP`, `IOS_DEVICE_PHOTOKIT_EXT`) × one kind (`Fake`, `Live`, `Replay`), declaring **as a
  literal** the states it reaches. It enters a state at construction or answers `Unreachable`. Bindings
  live beside their implementations.
- **Outcomes** are `Passed`, `Failed`, `NotRunHere`, `Diverged` (replay met an unrecorded OS call) and
  `NotWithin` (a real-clock bounded wait expired). `Failed`, `Diverged` and `NotWithin` fail the run on
  every binding kind.
- **Every clause runs against something real** on some host: a `Live` binding CI runs, or a committed
  device recording. `ContractCoverageTest` enforces this.
- **Every clause declares the port-grid cells it covers** (`covers = cells { … }`, typed references), and
  every declared cell is one the grid derives from `:domain:ports`. `ClauseCoversTest` enforces this
  (`docs/testing.md`, "Every clause declares the cells it covers").
- **Every grid cell is covered**: declared outright by a clause that runs against something real, with no list
  of exceptions. `ClauseCoversTest` enforces this too, so new port surface without a clause fails the build
  (`docs/testing.md`, "Every cell is covered").
- **Every adapter is covered**: every production class implementing a grid port has a real binding that names it,
  and every cell it owes is proven by one, or claimed in a `@NeverAnswers` the compiled code is checked to support.
  The mocks and the simulator substitutes answer what the real adapters they stand in for are proven to answer.
  `AdapterCoverageTest` enforces this, reading the compiled code of every target (`docs/testing.md`, "Every adapter
  is covered").
- **A declared cell must occur.** Each binding wraps its implementation in the port's recording proxy, and
  the runner fails a clause whose declared cell never occurred during its body, on every host it runs.
  `ProxyCompletenessTest` holds every proxy to the grid (`docs/testing.md`, "A declared cell must occur").
- Hosts CI cannot reach are **recorded at the OS boundary** on a device (through the rig, under
  `-Psnapsync.rig=true`) into `test/contracts/recordings/<Contract>@<HOST>[.<GRANT>|.<PRECONDITION>].rec`, and
  **replayed** against the current adapter on every build. An adapter recorded this way routes its OS
  calls through an `internal` seam (`KeychainApi`, `AppAttestApi`, `BackgroundTaskApi`, ...).

- **An entry port's contract states what its adapter delivers, never what a delivery runs.** `LifecycleContract`,
  `LinksContract`, `PushNotificationsContract`, `ExtensionHostContract` and `DevControlsContract` hold each adapter to
  handing the platform's deliveries to the handlers registered on it — raw, once, in order, through the inbound kind
  each is typed by, a completion with the message it came with — driven through the adapter's own platform entries (the calls the OS's callbacks make). What an entry
  point promises — which work runs, when a completion is answered, which deliveries assemble the host — is the
  composition's, pinned by rig tests over the JVM root (`EntryIntegrationTest`, `OsCompletionIntegrationTest`) and
  `ExtensionEntryTest`. Until the entry surface became event ports (11g1) those handler promises were contracted as
  inbound ports (`PlatformEntriesContract`, `ExtensionEntriesContract`); the delivery contracts came with clause
  completeness, which holds every port's cells to a real host.

Measured platform facts belong in clauses (so a fact that stops being true fails a test) or, where no
host can exercise them, in the adapter's KDoc with their evidence. The full mechanism, the host matrix
and the runners are in `docs/testing.md` section 4. Decision record:
`changes/archive/2026-09-22-establish-port-contracts`.

---

## 5. Complexity and coverage ratchets

Both are **ratchets carried by a written contract, not proofs**. A number may move one way. Nothing
mechanical stops a change from moving it the other way, and a diff shows it if one does. An exact-match
("fail when you improve") check was rejected for both, because it teaches people to route around the
gate.
Coverage's unit measurement is not a ratchet: a module at zero is held there by a gate with no number to move. Its
integration measurement's exceptions are one.

### Complexity ceilings (fall only)

- The detekt tier tasks gate `build`, one per scope, registered by build-logic's `snapsync.detekt-tiers` plugin
  (`DetektTiersPlugin`): `detektShellTier`, `detektComposeTier`, `detektEntryPointsTier`, `detektSchedulingTier`, `detektCoreTier`, `detektUiTier`, `detektHarnessTier`, `detektTestsTier`,
  `detektBuildScriptsTier`. There is one task per scope because a detekt 1.x rule carries one threshold
  per config.
- Every tier layers `config/detekt/_base.yml` (readings of a rule, uniform everywhere, **never** a
  number) under its own `config/detekt/<tier>.yml` (ceilings). A tier's own file is **optional**, and
  its absence means the scope sits at the baseline. The files present are the scopes still carrying
  debt. Each file opens with the contract: lowering is ordinary work, and raising needs a stated forcing
  proof in the PR. Per-site rules ratchet by a scope exclusion list that may only shrink.
- Tier membership is derived from the live Gradle model (the plugin's tier map).
  `DetektTierCoverageTest` fails if a subproject is in zero or two tiers, or if a config file belongs to
  no tier. Each task fails if it scanned zero files.
- `compose/` has its own tier. The `flow` tier went with its zone. `entrypoints/` and `scheduling/` have their own,
  and their files are the first that state a **claim**, written with the code, rather than debt measured after it:
  an entry point orders steps and decides nothing (cyclomatic and cognitive 4), and the runtime stays small enough to
  read in one sitting (7 / 9). `domain/`'s claim is the entry points' (4 / 4), and it was measured before it was
  written: every pure rule bound for the layer was at most 6 / 4 in `model`, and each case above 4 re-checked a
  nullable or sentinel input (`docs/patterns.md`, "The domain"). `inbound/` sits in `core`.
- **`detektAppShell` is not a tier** and must never be folded into one. It is the shells' decision-free
  proof, and its value comes from the number being 2.
- Compose trades `LongMethod` against `LongParameterList`. Extracting composables can raise tier totals
  (`CLAUDE.md` explains why `ui.yml` still holds three).
- `api/`: cyclomatic complexity only, via the project-local `deno lint` plugin
  `api/src/lint/complexity.ts` (tested by `lint-complexity.test.ts`). Length, parameter and nesting
  rules are deliberately not mirrored. Swift has no ceiling (it is pinned by `SwiftShellGuardTest`), and
  `site/` is out of scope.
- Constraint: detekt's embedded compiler lags the repo's Kotlin. Newer syntax can fail to parse
  everywhere at once. This expires with detekt's next stable major.

Decision record: `changes/archive/2026-08-27-add-repo-wide-complexity-gates`.

### Format, Compose and platform lint

- The same tier tasks also run ktlint's formatting rules (`detekt-formatting`) and Compose's rules
  (`compose-rules`, the 0.4 line, the last built for detekt 1.x). Their readings sit in `_base.yml`.
  A formatting finding is fixed by the tool: `./gradlew <tier task> -Psnapsync.detektAutoCorrect`.
  A source-scanning guard must tolerate the wrapped shape ktlint writes (one argument per line, a
  trailing comma), or it silently stops seeing what it scans.
- Android Lint gates `build` through `:app:android:check` → `lintRelease`: the store variant and every
  module it links (`checkDependencies`), any warning failing, no baseline. It is the nearest local
  equivalent of Google Play's release recommendations, which have no API — it does not reproduce them
  (Play's SDK Index judges transitive dependencies; lint only the declared ones).
- Xcode treats warnings as errors in both iOS targets (`Config.xcconfig`), enforced where Xcode builds:
  CI's `ios-build`.

### Coverage

Every module's coverage is described in ONE place: the `coverage { }` block in the root build file
(`snapsync.coverage`, `build-logic`'s `CoveragePlugin` and `CoverageDsl`). An entry names a module and settles each of
two measurements, with a target or with a `skipped(…)` and its reason:

```kotlin
coverage {
    integrationGoal(instructions = 95, branches = 85)
    ":domain:services" {
        skipped("SQLDelight's generated code: …", packages = listOf("app.snapsync.services.ledger.db", …))
        unit(alsoCoveredBy = listOf(":adapter:generic:mock", ":adapter:generic:app", ":test:feature"))
        integration(instructions = 80, branches = 58)
    }
    ":ui:screens" { unit(); integration { skipped("Compose: the suite stays at the intent level") } }
    ":adapter:generic:mock" { skipped("test equipment: …") }
}
```

- **`unit(…)`: the ZERO gate.** The module's `coverageZero` task (on `check`) reads its own Kover `report.xml` and fails
  on any missed `INSTRUCTION` or `BRANCH`. No number, no percentage: a miss is fixed by a test. The report is filtered
  to the module's OWN classes, so another module's tests count only when the entry names it in `alsoCoveredBy` (below,
  "Credit").
- **`integration(…)`: what `:test:integration` ALONE covers.** The suite's `integrationCoverage` task (on `check`,
  right after the suite) reads the suite's own Kover binary report through Kover's report library, against each
  module's JVM classes less its skipped packages, and compares whole percentages. It credits no module and changes no
  unit measurement: it judges the SUITE. The goal is `integrationGoal`; a target left out is the goal, a higher one is
  stricter, and a lower one is an **exception**: the module's measurement on the day it was set, rounded down. An
  exception only rises: lowering one needs a reason stated in its PR, a ratchet carried by review like the complexity
  ceilings, not by a check. What IS checked: an exception the module no longer needs (it meets the goal) fails the
  build, so the list shrinks on its own and cannot go stale. A module with no branches meets any branch target. Every
  run writes `test/integration/build/reports/integration-coverage/summary.md`: each module's measurement, its target,
  and the gap to the goal. A measurement that moves between two runs of the same code is a flaky test to fix, never a
  margin. The glue the zero gate excuses (below) is not excused here: a percentage absorbs it.
  The wiring modules are held at 100/100, the suite being the test written for them, and the entry points too —
  less the one line the suite cannot yet reach, `OnMetricReport`'s body (the JVM host delivers no process-metric
  report), so their instructions sit at an exception of 99 until it can.
- **`skipped(reason, packages)`: the one way to leave something out**, its reason read in review. WHERE it stands
  decides what it skips: in the entry, the module from every measurement; inside `unit { }` or `integration { }`, only
  that measurement. With `packages`, only those packages; without, everything at its level. Today it removes generated
  code (SQLDelight's, the wire's, Compose Resources' `Res` accessors), the integration measurement of the adapters and
  of the Compose modules, and the test equipment — and, until phase 3g, the integration measurement of
  `:domain:scheduling` and `:domain:inbound`, which the polling tests reach only in a process death's race window, so
  it moves with the machine's timing (`pollingRace` in the root build file). A module whose measurement swings is
  skipped, never held at a lowest-seen floor.
- **The block is complete.** `coverageEntries` (on `check`) fails when a module with a JVM target outside `:test:*` and
  `:tools:*` has no entry, or an entry names a module Kover cannot measure; an entry that leaves a measurement
  unsettled, or contradicts itself, fails configuration. So no module is measured — or left out — by nobody's decision.

**What the zero gate excuses: compiler-generated glue, recognised by its shape.** One category: code a compiler
emits that no input can reach. Nothing a person wrote is ever excused, there is no opt-in annotation or comment
marker, and a shape joins only as code in `CoverageZero` with tests pinning both what it excuses and what it must not.
Every excusal is printed by the task, so the glue's growth stays visible. The shapes today:

*Compose declaration glue, only on declaration lines.* The Compose compiler attributes its generated glue — restart groups, the `$changed`/`$default` bits, `skipToGroupEnd`, `updateScope` — to the declaration
lines of a `@Composable` function, from `fun` through the body's opening `{`. Those lines' misses are excused and
counted; the task prints, per module, how many lines, instructions and branches it excused, so growth is visible. A
body line is excused only by the two shapes below. `@Composable` itself is the mark: there is no opt-in annotation and no comment marker.
Composable **lambdas'** own restart glue never shows as a miss — measured 2026-10-07, every miss inside one of
`:ui:*`'s 67 is a branch someone wrote (`if (body != null)`, `when (cause)`). The declaration ranges come from a source scan (bracket-balanced,
so a function-type parameter's `->` or a default value's `=` ends nothing), **checked against the bytecode**: every
composable method in the report must match a scanned declaration in the same file and the reverse, or the gate fails.
A scanner that misreads a signature therefore breaks the build rather than excusing the wrong lines.

*A remembered lambda's arms, on body lines.* A lambda literal in a composable body is cached, and the cache's test — the
`$changed` mask checks (read through the compiler's `$dirty` copy) and `Composer.changed`/`changedInstance`, feeding one
stored boolean — takes its other arms only on a recomposition, which no screen test performs. The gate reads the shape
from the bytecode and allows the line at most that shape's instructions and two arms per jump; any more fails. Because
the allowance is an upper bound, it is withheld from a line that also holds a user branch, or code of another method the
report does not list as run in full. Kover leaves a lambda whose body sits on its caller's line out of its method list
while counting that body in the line (measured 2026-10-08), so **a lambda's body goes on its own line** — there an unrun
body is a miss of its own. The gate's failure says so.
The compiler's trace guard, `if (isTraceInProgress()) traceEventEnd()`, shares a composable's last body line (a
`return remember(…) { … }` included); Kover filters it, so it is not counted as that line's user branch — recognised
exactly, an `ifeq` on `isTraceInProgress` skipping only the trace call. A branch someone writes on it stays theirs.

*The dead arm of an exhaustive `when`, on body lines.* Kotlin ends a `when` over a sealed or enum subject with a
`NoWhenBranchMatchedException` no value reaches; inside a composable the compiler wraps it in a replace group. Kover
filters the throw, but not the group calls or a conditional jump into the block (a sealed `when`'s last `is` arm). The
allowance is exact: those instructions, and one arm per such jump. A word-picking `when` is not left to this rule but
restructured: a plain mapping function chooses (testable with no Compose) and the composable renders once —
`:ui:screens`' `Phrase`.

*kotlinx-serialization's zero-mask missing-field check.* In a `@Serializable` class whose every field has a default,
the plugin's deserialization constructor still opens with `if ((0 & seen) != 0) throwMissingFieldException(…)`: the
required-field mask is the constant 0, so no document can take that branch. It is recognised in the **bytecode** — a
constructor taking a `SerializationConstructorMarker` whose code opens `iconst_0; iload_1; iand; ifeq` and whose
skipped block ends in the plugin's throw — and only that block's instructions and its one branch are excused, on the
class's header line; whatever else the line misses still fails. A class with a required field has a non-zero mask,
and is never excused: its check is real, and a test that leaves the field out reaches it. The task prints each site.

The gate also
refuses a report whose per-line counts do not add up to its own totals, so a Kover upgrade that moves the counts fails
loudly instead of passing vacuously. Logic: `build-logic`'s `CoverageZero` and `ComposeGlue` (tested by
`CoverageZeroTest` and `ComposeGlueTest`, which the root `check` runs).

**Inline functions need no exemption.** Kover credits inlined execution back to the inline function's own source
lines; an uncovered inline helper is untested, not unmeasurable. An inline body with many branches is still awkward to
measure — the branches a test reaches through one copy were not credited together with another copy's (`invocation`,
2026-10-08) — so keep such decisions in an ordinary function the inline one calls (`logEntered`/`logReturned`).

**Credit: `alsoCoveredBy`, and only where a placement rule put the tests elsewhere.**
- A module's own tests always count. Another module's count only through its entry's `alsoCoveredBy`, which merges
  that module's coverage data (Kover's `kover` dependency) — never its classes. The modules whose tests cover others
  apply Kover themselves: `:adapter:generic:mock`, `:test:feature`, `:test:integration`.
- Unit placement: the `:domain:*` zones are also covered by `:adapter:generic:mock` and `:test:feature`, `:domain:services`
  by `:adapter:generic:app` too, and `:ui:components` and `:domain:presentation` by `:ui:screens`.
- **The wiring**: `:domain:compose`, `:domain:host`, `:domain:entrypoints` and `:app:jvm` are also covered by
  `:test:integration`. "One shared composition" (§2) says the wiring graph is not unit-tested and IS smoke-tested by
  the integration surface, so for these the integration tests are the tests written for the code. The entry points
  join them for the same reason (and are covered by the mocks' suite too, which composes the process's metric-report
  entry point): each orders its trigger's steps and decides nothing (the `entrypoints` tier and `EntryPointShape`), and
  the integration surface delivers every trigger from the operating system's side, which is exactly what they are for.
  The suite covers no other module's unit measurement: a thick integration suite cannot stand in for a thin unit suite
  — which is why what it covers of the rest is a measurement of its own (`integration`), judged apart.
  What integration reaches in the wiring is construction and connection only: it holds no decision (`detektAppShell`),
  so every branch the graph needs is a unit-tested feature's or service's, and the JVM root composes through the same
  device bundles (`AppDevicePorts`, `ExtensionDevicePorts`) the phone's roots do.
- The block lives in the root build file because `ModuleSetTest` forbids a `:domain:*` build file to name a module,
  and `Zones.kt` reads those files as text, where an edge to the covering module would render backwards. Name leaf
  modules: `:domain` is an empty container, and a report filtered to it measures nothing.

**Outside the block**: the test and tool modules (`:test:*`, `:tools:*`; the mocks and `:app:desktop` are in it, skipped
as test equipment), the non-JVM shells (`:app:ios`, `:app:ios:extension`, `:app:android`) and the iOS/Android adapters, which Kover cannot see
(it is JVM-only) and which the port contracts hold to clause completeness instead (§4).

Decision record: `changes/archive/2026-08-28-add-coverage-bounds`, **partly superseded** by this section. D1 (Kover's
default engine), D3 (`INSTRUCTION` and `BRANCH`, never `LINE`) and D5a (credit follows placement) stand. D2 (aggregate
+ package-floor percentages) gives way to the own-`report.xml` gate — the very alternative D2 deferred — module by
module as each reaches zero; every module reached it, and the floors are gone. D4 stands except that integration now
covers the wiring modules and the entry points, and has a measurement of its own. D5 (`compose/`
unbounded) rested on Kover crediting per project while `compose/` was a package of `:domain`; it is its own module now,
so it is measured. D6 (seed at `floor(measured)`) gives way to zero.

---

## 6. Diagrams

`architecture/` is **generated, never drawn**: `./gradlew architectureDiagrams`, Mermaid-in-Markdown,
byte-deterministic (code-point sort, `\n`, UTF-8, no timestamps or absolute paths).

| file | what |
|---|---|
| `modules.md` (+ `.modules-inputs.txt` sidecar) | module graph from the Gradle model's **build** configurations. `kover*` is excluded, and the file header says so |
| `zones.md` | zone/feature graph (derived from `build.gradle.kts` text, so never declare a report-aggregation edge in a scanned module's build script; the root build is the place) |
| `ports.md` | port × adapter matrix, including a fake-and-contract column |
| `features.md` | feature cards |
| `di.md` | DI wiring graphs and the binary × port matrix |

- There are no per-trigger diagrams: the trigger flows and their transcriber went with `flow/`. What happens on
  each trigger is `entrypoints/`' two files, read top to bottom (`docs/patterns.md`).
- Freshness: `:tools:diagrams:test` regenerates and diffs against the committed files (inputs include
  `settings.gradle.kts` and every `build.gradle.kts`). In CI, `ci.yml`'s `build` gate regenerates
  on a clean checkout and fails on any difference, including untracked files. Stale diagrams block the
  PR. Regenerate and commit.
- The module-graph renderer has a byte-identical twin in `:tools:diagrams`. Keep them in step.

---

## 7. UI design-system rules

Screens (`:ui:screens`) are written only against the `App*` components in `:ui:components`. All
Material 3 styling lives in the components module (the "skin"), so a future skin (for example,
Cupertino) or a swap of the QR library changes one module.

- **Material 3 containment**: only `:ui:components` depends on Material 3, the icon artifact and the QR
  library. The compiler enforces this (`implementation` edges). `:app:desktop`'s control panels are test
  equipment and use raw Material 3, never `App*`.
- **Semantic-only signatures**: an `App*` parameter carries data and meaning (text, fractions, sealed
  semantic values, callbacks, `@Composable` slots). It never carries appearance: no color, text style,
  shape, elevation, `Modifier`, or Material 3 type. **review** (ungated).
- **Design-time variants are distinct components** (`PrimaryButton` vs `SecondaryButton`,
  `AppConfirmDialog` vs `AppDestructiveConfirmDialog`), never a role or flag parameter. **Runtime-data
  variants are sealed values** (`StatusIndicator`). **review**
- **Containers own convention-bearing arrangement.** Insets, title placement, centering, the bottom
  action cluster (`ScreenLayout`), hero layout (`StatusHero`) and QR plus caption (`AppQrCode`) come
  from containers. Raw layout primitives are only for meaning-free geometry. **review**
- **Accessibility and consequence lines**: every interactive row has an explicit role and state and is
  one tap target, a dimmed control stays in the semantics tree, reduce-motion is honoured, and a
  consequence line is derived from the state passed in, never hard-coded. Held by `:ui:screens` UI tests
  and review.
- **Palette**: the skin meets measured AA contrast in both themes over a frozen palette, with no new
  colour token per component. Skin-local, no signature change.
- The inventory grows only when a screen needs it. KDoc on each component is its reference.
  `./gradlew :app:desktop:run` reaches every state through the world inspector's levers.

### Localization

The app ships in English (the base) and German, and is built so that another language is a translated file rather than a
code change.

- **Every word a person reads is a string resource.** `:ui:screens` and `:ui:components` each carry
  `src/commonMain/composeResources/values/strings.xml` (Compose Multiplatform Resources), read with
  `stringResource` / `pluralStringResource`. `values/` is the base language; a translation is
  `values-<lang>/strings.xml` with the same names. A screen's words live in `:ui:screens`, the design
  system's own (the status line, the calendar) in `:ui:components`. Gate: the `HardCodedUiText` detekt
  rule (`:tools:detekt-rules`, scoped in `config/detekt/_base.yml`) fails on a letter-bearing string
  literal in either module's main code. A string nobody reads (a bug report's screen label) says so with
  `@Suppress("HardCodedUiText")`.
- **Only `ui/` turns state into words.** The domain carries facts: `UiState` holds a typed
  `ScreenMessage` where it once held an English sentence, and `CutoffFormatter.coarseDuration` answers a
  `CoarseDuration`, not "2 weeks". So `:domain:*` has no resources dependency, and a message nobody
  worded is a missing `when` branch at compile time (`Words.kt` in `:ui:screens`).
- **Dates are the platform's.** `UiState` carries dates as values (`LocalDateTime`, `LocalDate`), never as text.
  `DateFormats` (`model/`) formats a CLDR skeleton (`yMMMd`, `jm`); the `DateFormatting` port answers one through
  `java.time` on the JVM, ICU on Android and `NSDateFormatter` on iOS. `:ui:components` holds no platform code: the
  root hands the screen a `ScreenDates` over the port, and `StatusScreen` provides the formatter for the resolved
  language as `LocalDateFormats`. Month names, day/month
  order and the 12/24-hour clock follow the user. It formats in the language the strings resolved to
  (`date_language` in each translation) and in the device's region, so an English app on a German phone
  reads `5 Oct`, 24-hour time, and never German month names next to English words. `StatusScreen` provides
  it; nothing in the design system spells out a month. Write a skeleton's letters in the order
  `y M E d j m`, which the JVM requires.
- **Text the OS shows is generated from the same file.** Keys prefixed `ios_` / `android_` in
  `:ui:screens`' `strings.xml` are not shown by a screen. The `snapsync.native-strings` plugin writes them
  into iOS's `InfoPlist.xcstrings` (plus the base value in `Info.plist`) and the Android adapter's
  `res/values/strings.xml`. Gate: `HardCodedSystemText` fails on a literal passed to a notification's
  text.
- **The language list is declared once**: `nativeStrings { locales }` in `ui/screens/build.gradle.kts`,
  base first. The plugin turns it into iOS's `CFBundleLocalizations` and Android's
  `locales_config.xml`, so the OS offers exactly those languages, including in its per-app language
  setting. There is no language picker in the app. The generated files are committed:
  `./gradlew nativeStrings` writes them, and `nativeStringsCheck` (under `check`) fails while one is
  stale, or while either UI module's `values-*` directories disagree with the list.
- **Tests find nodes by resource, not by copy.** Screen tests look a node up by
  `str(Res.string.x)` (`TestStrings.kt`), so a copy edit breaks no test. The UI test JVMs pin `en-GB`,
  so a date assertion holds on any machine.

**Adding a language:** add `values-<lang>/strings.xml` to both UI modules (including `date_language`),
add the tag to `nativeStrings { locales }`, and run `./gradlew nativeStrings`. The store listing, its
screenshots and the web join page stay English for now.

---

## 8. Backend (`api/`)

A **streaming proxy** in Deno/TypeScript with **Hono** on **bunny Edge Scripting**. The device-facing
origin is `snapsync.stho.net`, a CNAME in our Bunny DNS zone to the pull zone that fronts the script.
Owning the name means a runtime swap is a DNS repoint, never an iOS build. It mints events, streams
upload bytes into a bunny native Storage zone, records facts in a relational store (bunny Database,
libsql), and serves listings. Downloads are **presigned S3 GET URLs** fetched straight from bunny's S3
endpoint (no proxy, no on-device SigV4). bunny is load-bearing: there is no standby, and the client
retries forever, so an outage delays uploads but loses none.

> A CDN pull zone sits between every device and the script. Anything the device relies on must hold
> **as observed through the pull zone**. The pull zone may answer `OPTIONS` itself, and it caches on
> the origin's `Cache-Control`. That is why listings send `no-store, no-cache, max-age=0` (bunny
> documents `no-cache`).

**The HTTP API is not a user contract.** It is bounded by the minimum app version (`426`, below),
and each version is frozen only while builds that speak it are served. `/api/v1`, what builds below 0.4
spoke, is no longer served: every device runs 0.4 or later.
`/api/v2` is frozen for **compatible changes only** since 0.4, its first App Store build: an addition 0.4
ignores (a new route, a new optional response field) is fine, and anything 0.4 would misread is breaking.
A breaking change goes to `/api/v3`, or ships with a deliberate `MIN_APP_VERSION` bump that sends 0.4 to
the update notice. `v2.test.ts`'s existing tests must pass unedited; new ones may be added.

### Where state lives

**The database holds the facts. Storage holds the bytes.** Every row is **rebuildable** by a device
round-trip (a manifest republish or a re-attestation), and no user-visible fact exists only in the
database. That is what makes the platform's public-preview limits acceptable (1 GB per database, a
10 s data-loss window on failover, 32,766 bound parameters per statement). One gap is stated: under v2,
a lost `resources` row is repaired only by a re-upload, which happens only if the device does not
believe the upload landed.

```
events        id, name, created_at, starts_at, ends_at, capacity (a placeholder nothing reads), lifetime_seconds,
              closed_at?, completed_at?, last_landed_at?, zone? (migration 0009),
              key_id? (migration 0011: an encrypted event's key id; NULL = plain),
              open_until? (migration 0014: backfilled; NULL only for a row the previous bundle wrote)
memberships   (event_id -> events CASCADE, device_id), state in {sharing, settled, done, left} (CHECK),
              joined_at, manifest_version?, received_position? (migration 0014)
event_assets  (event_id, device_id -> memberships CASCADE), asset_id, creation_date, roles (JSON array)
              + index (device_id, asset_id)
resources     (event_id, device_id -> memberships CASCADE), asset_id, role, path (the full storage
              path), content_type, filename; PK (event_id, device_id, asset_id, role) (migration 0010)
devices       device_id, created_at, attest_* (NOT NULL; attest_platform ios|android, what proved the
              key), push_* (nullable together), app_version? (the last v2 version declared)
union_log     seq AUTOINCREMENT, (event_id -> events CASCADE), kind in {gained, removed, fetch},
              device_id?, asset_id?, trigger?, cursor_from?, cursor_to?, served?, at
              + index (event_id, seq)
```

The generated snapshot is `api/schema.sql` (section "Database" below).

- **Existence is a row.** An event exists iff its `events` row does. No route deletes on touch, and
  only the nightly sweep deletes. So a `404` is a real deletion, and a client may use it as a witness
  for self-leave. A **completed** event (below) keeps its row until its deadline precisely so it can
  answer "completed" instead of `404`.
- **Completion, with no close** (`changes/early-event-completion`, `changes/back-dated-events`). Every event is
  **open until** a date fixed at its create (`open_until`): its range end, or — for an event created after its
  end — the date its host chose, at most the window maximum after the later of the end and now. After `ends_at`
  each device publishes its manifest with `final: true` — its membership becomes `settled`; the bytes may still be
  uploading — and reports the union position up to which it holds every photo of the others
  (`PUT …/received`, monotonic, `received_position`). Nothing closes and nothing is stamped: a member is **done**
  when it is `settled`, every role it declares has landed and its `received_position` reaches the event's union
  head; an event is **finished** when it is past `open_until`, someone joined it, and every member still in it is
  done. Both are SQL predicates in `src/db.ts`, read on every use (the sweep, the event read), so a late join, a new
  own photo or another member's gain makes them false again by itself. Joining, renaming and changing an asset set
  keep working until the photos are deleted. An event closed by the OLD rule keeps its `closed_at` and the old
  refusals (`410 {error:"closed"}` for join and rename, `409 {error:"closed"}` for a changed set) until it is
  dropped; and the event read serves builds that predate open-until dates a **derived** `closedAt` — the stored
  stamp, else `open_until` once it has passed and every member still in it has shared everything — which is when
  such a build may leave without missing a photo (`PENDING_CLEANUPS.md`). A leave is recorded before it is sent
  (`PendingLeaves`) and re-sent by every wake until the backend confirms it — which is what makes EMPTY
  dependable. The byte route stamps `last_landed_at`; the **clock** is
  `max(open_until, last joined_at, last_landed_at) + 3 days`.
- **The host's zone is a label, beside the instants** (migration 0009). `starts_at`/`ends_at` stay canonical
  `…Z` instants, because they are compared lexicographically as capture cutoffs and installed apps parse that
  shape. `zone` is the host's IANA name (`Europe/Berlin`), sent with the create, which only the event page
  reads, to name the dates as the host chose them. An absent or unusable zone stores NULL (rendered in UTC)
  and never refuses the create; it is never on the wire.
- **Membership is one state column** (migration 0008), never inferred from objects: `sharing` (joined,
  not settled), `settled` (its last publish after the end declared its share settled; it follows each
  publish both ways), `done` (left having everything: settled, every declared role landed, and the
  device's `?received=true` — it holds every photo of the others), `left` (left with anything missing).
  `sharing`/`settled` are PRESENT (pushed, counted in the waiting line, they hold an event unfinished);
  `done`/`left` are GONE, and are told apart for observability only — nothing decides on the difference.
  A manual leave and the app's own leave are the same request. A gone member's assets stay in the
  union; a join puts any state back to `sharing`.
- **A resource belongs to one event** (migration 0010, `changes/per-event-storage-layout`): a child of
  its membership, so completion and a dropped event take it. Its `path` is where the bytes are —
  `files/<eventId>/<sha256>` for every byte written since, `files/devices/<deviceId>/<key>` for one written
  before — and every reader follows it; none composes a layout. A photo in two events is two resources and
  two objects. The wire's `key` (`<assetId>-<role>.<ext>`, the union's) is derived from
  `asset_id`/`role`/`filename` (`objectNameFor`), so installed clients see the string they always did.
  It hangs off the membership, not `event_assets`: a publish deletes and re-inserts those, and a byte may
  land before its declaration.
- **Row existence is the upload record.** There is no upload-state column, and the backend records only
  bytes it watched arrive. "Pending" = declared by a manifest with no `resources` row.
- **The union log is what makes a union read incremental** (`changes/incremental-union`). Its `seq` is
  the cursor a client reads from. A `gained` row is written exactly where the server already learns that
  the union gained an asset, which is also where the push fires: the v2 byte route (the events
  `eventsCompletedBy` answered, committed in ONE batch with the `resources` row) and the v2 publish (the
  `gained` set of `publishUnionChanges`, inside the publish batch under its version and closed-event
  gates). The publish also logs `removed` for a servable asset it stops declaring or leaves incomplete;
  no read serves those. A delta is the `gained` rows past the cursor, put through the union's own
  declaration and completeness filter. Every union read logs a `fetch` row, best-effort. The log is
  deleted when an event completes (with the photos, capability `privacy-security`), and cascades with
  the event row. Assets a v1 build wrote before v1's retirement are in no `gained` row; they reach readers
  through full reads.
- **A `devices` row exists iff the device attested.** Push registration is an `UPDATE` and never
  creates a row.
- **Bounds** (policy in `deployments/components/policy.json`): capture window `[startsAt, endsAt]` of
  at most 30 days, which bounds uploads only and closes nothing. Lifetime `lifetime_seconds` (30 days)
  is stamped as a duration. The delete-by is derived per read as `max(createdAt, openUntil) + lifetime`
  (`src/lifecycle.ts`, shared with the sweep): 30 days after the event stops being open, so a 30-day event is not
  cut off on its last day and a back-dated one is not born expired. An event admits any number of devices; the
  `capacity` column is a placeholder kept for a rollback and for released apps (`PENDING_CLEANUPS.md`). What
  grows with the member count is bounded per wake instead: one wake reaches at most `FANOUT_MAX_RECIPIENTS`
  members, chosen at random (`deployment.md`, "Edge Scripting limits worth knowing").
- The **nightly sweep** (`src/scripts/sweep.ts`, a GitHub Actions workflow, since Edge caps requests at
  50 subrequests / 30 s CPU) gives each event one `sweepVerdict` (`src/lifecycle.ts`): **drop** the row
  past its delete-by; **complete** an ever-joined event that is finished, empty (no member present) or past
  its clock — memberships (and so `event_assets`) and the union log deleted, `closed_at`/`completed_at` stamped, the row
  kept — then deletes the byte folder `files/<eventId>/` of every event that no longer lives (one recursive
  DELETE; any such folder it finds, so a crash or a byte that landed after the completion is healed by the next
  run), and collects a `devices` row only once no token minted for it can still verify. Its delete decision,
  and the read of which events live, run in an interactive transaction (primary), not an ordinary read.
  `files/devices/` (the bytes written before migration 0010) is never touched by it.
  Details are in `docs/deployment.md`.

### The request pipeline

Top-level Hono middleware in `src/app.ts`, in this order:

0. **Request log** (`src/request-log.ts`, below). First, so every request writes its record, a gate's
   refusal included.
1. **Maintenance gate.** While the bundle carries the maintenance flag, everything under the `/api/`
   and `/web/` **prefixes** answers `503` with `Retry-After` and no-cache, touching no store. It runs before
   authentication. The other root routes keep serving.
2. **Version gate** (v2 only). The `x-snapsync-app-version` header must be at least `minAppVersion`
   (in source, `src/config.ts`, pinned by `min-app-version-floor.test.ts`, deliberately not config).
   Absent, unparseable and too old all get `426 {error, minAppVersion}`.
3. **Token gate.** Every route requires a device token: a backend-minted, HMAC-signed bearer that can
   only be obtained through App Attest. **Verifying touches nothing** (one HMAC compare, no store read,
   no Apple call), because it sits on the streaming upload hot path. A route that needs the device record
   reads it itself afterwards. The **closed list** of exceptions: the three `/api/vN/attest/*` issuers;
   `OPTIONS` anywhere; `GET`/`HEAD` on `/`, `/join`, `/join/<one segment>` (the event's own page),
   `/.well-known/apple-app-site-association`, `/health`; `GET`/`HEAD` on exactly `/web/events/<id>/photos`
   (the event page's own read, below — a browser cannot attest, so possession of the event id is its read
   capability, `changes/separate-event-page-from-device-api` D1); `GET`/`HEAD` on `/api/vN/events/<id>` and
   `/api/vN/events/<id>/files` (read by app builds that send no token; the event page no longer reads them); and `GET`/`HEAD` on the
   download redirect `/api/vN/events/<id>/files/devices/<d>/<asset>/<role>` (`isDownloadRedirect`, which
   the version gate exempts too: the OS's download transports fetch it with no app header). The gate
   normalizes the `/api/vN` prefix before matching. The two event reads check a token **themselves** when
   one is sent (`401` for a bad one), so their `401` is a verdict on that token although the gate never runs
   (`verifiesToken` on the client); the app sends one on both. `GatedPathPinTest` keeps the client's copy of this list in step.

4. **Device binding** (per route, `actsFor` in `src/routes/support.ts`). The token names the device it was minted for,
   and every route that names a device in its path (join, leave, manifest, byte upload, device listing, push
   registration) refuses any other id with `403 not this device`, before reading or
   writing anything. It is `403`, not `401`: the credential is valid, and a `401` from a gated route makes
   the client drop its token and re-attest, which would loop. Device ids are not secret (the ungated union
   lists every member's), so without this any genuine install could act as any member. The binding judges
   the router's DECODED parameter, so a percent-encoded id cannot slip past it.

What the binding does not close: `/attest/token` mints for whatever `deviceId` its body names, so a genuine
install can still attest AS a known id (overwriting that device's key) and then hold a token bound to it.
Refusing a re-attestation for a known id would also refuse the legitimate one after a reinstall, which
keeps the Keychain device id but loses the App Attest key.

### The request log

Every request produces **exactly one record**, when its response body has finished, and that record is
the only thing the edge script writes (decision record `changes/api-request-log`). The sink writes it twice:
as one console line (bunny's Edge Scripting log in production; the console under `deno task dev:local` and
the ephemeral test backend) and as one row of the store's `request_log` table (migration 0012;
`src/request-log-store.ts`), in production and the dev rig alike. Tests collect lines instead.

```
<ISO> [<reqid>] <METHOD> <url> <status> <ms>ms v=<declared app version|-> in=<bytes|-> out=<bytes> <fields…> [errors=<tag>,…]
```

**Why the row.** bunny keeps a ring of the script's **last 100 console lines**, not a time window, so a burst
of errors pushes out everything before it. The row is the history: kept 30 days (the nightly sweep deletes
older ones, `docs/deployment.md` §3), read with `deno task logs` (filters and statistics, §2 "Reading the
api's log"). The columns are the line's facts (`at`, `reqid`, `method`, `url`, `status`, `ms`, `version`,
`bytes_in`, `bytes_out`); `fields` is one JSON object — a key recorded once holds its value, one recorded
again an array of its values — and `errors` holds the fault tags, space-joined and each once, `NULL` when
there were none (`' ' || errors || ' ' LIKE '% fanout %'` finds one).

**The response's end waits for the row.** The sink's insert is a subrequest the platform may cut off once
the response is out, and bunny offers no `waitUntil`, so the counted response stream closes only after the
insert settled (a body-less answer is returned after it). Fire-and-forget would save that round-trip, but
whether bunny lets a post-response subrequest finish is unmeasured. A refused insert never fails the
request: it is said on a second console line, `[<reqid>] logdb=failed detail="…"` — the one exception to
one line per request. Rows are operator-only like the line, and belong to no event: deleting an event
deletes none of them.

- `url` is whole, query included (`?filename=`). The line never carries the caller's address or
  User-Agent (capability `privacy-security`).
- `ms` runs until the body finishes, so it includes the transfer. `out=` counts the bytes actually sent.
  `in=` counts the bytes a route read, or is the declared `Content-Length` (or `-`) when none was read.
  A body abandoned or broken mid-stream adds `cut=true` (and the `body-cut` fault when it broke).
- A field value is bare when it is one plain token (`[A-Za-z0-9._:/-]+`), otherwise JSON-quoted.
- `reqid` is six random hex characters per request: the key that ties a line to the same request's
  error report.

**Routes record; nothing else writes.** A handler adds `key=value` with `c.var.log.field(k, v)` and a
server fault with `c.var.log.error(tag, detail)`: the tag — one of the closed `ERROR_TAGS` in
`src/request-log.ts`, so a misspelt one does not compile — joins the record's `errors`, and the detail is
kept as a field under the tag's name (`fanout="notify: …"`). Both only buffer. Only the handler touches the
context:
- a helper takes the values it needs (path params, the token's device, the declared version, the body);
- a helper whose outcome is a failure reply **throws a `Refusal`** (`src/refusal.ts`: status, the reply
  body byte-identical to before, an optional `fault` (tag + detail), optional fields), which `onError`
  answers and records;
- a best-effort helper **returns** its outcome or failure for the handler to record (`notifyMembers` →
  `{recipients, pushed, unsent, errors}`).

`deno lint`'s `no-console` holds this. The request log's write is its one exception, and the
command-line tools in `src/scripts/` and `src/dev/` opt out per file.

The fields today:

| Where | Fields |
|---|---|
| a wake (byte landing, publish) | `recipients= pushed= [unsent=<why ×n, …>]` |
| byte upload (v2) | `completed=<n>` + one wake's per completed event; `aborted=true` when the phone's body broke off (answered `400 upload aborted`: the client's network, not a 5xx) |
| manifest (v2) | `refused=older-version version=<n>` + the wake's |
| union read | `served=<n> trigger=<why\|->` |
| event page read (`/web/events/<id>/photos`) | `served=<n>` |
| push registration | `refused=not-attested` |
| attest | `attested=<platform>/<environment>` · `rejected="<format> (<reason> (<detail>)): <error>"` |
| renew | `refused=not-attested\|vanished` · `rejected="<platform> assertion: <error>"` |
| any 5xx or best-effort failure | `<tag>="<what>: <error>"` + `errors=<tag>` (the tags below) |
| an exception no route caught | `bugsink=<event id>` where the service reports (below), else the `uncaught` fault; answered `500 Internal Server Error` |

The fault tags:

| Tag | Fault | Request answered |
|---|---|---|
| `upstream` | a call to the store or to storage threw | `502` |
| `upstream-rejected` | storage answered a non-2xx | `502` |
| `uncaught` | an exception no route caught, where nothing reports | `500` |
| `body-cut` | the response body broke off mid-stream | as it was |
| `completion-lookup` | the byte route could not ask which events the upload completed (it wakes nobody) | `201` |
| `landing-stamp` | the byte route could not stamp the landing time | `201` |
| `union-position` | a wake could not read the position it announces (sent without one) | as it was |
| `fanout` | a wake's fan-out failed or timed out (the write stands) | as it was |
| `app-version` | the declared app version was not recorded | as it was |
| `fetchability` | the manifest publish could not look up which declared assets are fetchable | as it was |
| `read-log` | a list read was served but its union-log row was not written | `200` |
| `event-page` | the event page could not read its event | as it was |
| `store-down` · `zone-down` | `/health`: the store / the storage zone did not answer (both always checked) | `503` |

Per-push failures are not faults: they are counted by reason in `unsent=`. The byte upload is the one route
that can record several: two of its own and two per event it completed and woke.

### Failure reports

An exception no route caught is reported to the operator's Bugsink, project 1, the one the apps report
to (`src/error-report.ts`, over `@sentry/deno`, tagged `platform=api`). It is answered `500` as before, and
its line carries only `bugsink=<event id>` (capability `privacy-security`, "The service reports its own
failures to the operator"). A `Refusal` is an answer, never a report. Caught upstream and best-effort
failures stay faults on the record. A day's **5xx** reach Bugsink too, as one nightly report from the
kept log (`docs/deployment.md` §3).

- **Only the deployed service reports.** `main.ts` starts the SDK only when the bundle carries a DSN,
  which the resolver renders for `prod` and `maintenance` (`docs/deployment.md`). `createApp` takes the
  reporter as a dependency, so a dev rig, the ephemeral backend and the tests run without one.
- **What a report carries:** the stack, the request as it arrived (URL with query, all headers: the
  browser or app, `cdn-requestcountrycode`, `cdn-ja4`) and the request's outbound calls as `fetch`
  breadcrumbs. It is tagged `reqid`, `route` (the Hono pattern) and `v`. It never carries the
  requester's address: `beforeSend` drops `user.ip_address` and every address-bearing header.
- **Per request.** The request-log middleware runs each request in `Sentry.withIsolationScope`, because
  bunny serves through `Bunny.v1.serve`, which the SDK does not instrument. `onError` captures, then
  awaits `Sentry.flush(2000)` before answering, since the isolate may freeze once the response is out.
- **Default integrations, no tracing.** A floating promise rejection is captured too. On bunny the
  script keeps serving after one (measured; under a local `deno run` the SDK re-throws and Deno exits).
- **Readable stacks.** A frame names the bundle (`/mod.ts:<line>:<col>`). `bundle-<sha>` holds
  `main.js.map`, and the `/bugsink` skill maps frames back to `api/src` through the map of the event's
  `release`.

### The backend boundary

**`api/openapi.yaml` is the ONE source of the wire format** (OpenAPI 3.1.2, hand-written, linted by Redocly in
`api-test`'s `OpenAPI lint`). Everything else is generated from it at build time and never committed, on both sides:

- **The backend's half** — `api/src/gen/wire.ts` (`deno task gen`, `src/scripts/generate-wire.ts`): every device
  operation mounted at the description's own method and path, its input validated by zod schemas generated from the
  description before its handler runs, and a `Handlers` type that makes a missing handler a type error. A route the
  description does not declare cannot exist, nor take a shape it does not declare.
- **The app's half** — `:domain:wire` (build-logic's `snapsync.wire`): Fabrikt's DTOs (`app.snapsync.wire.models`)
  and our own generator's routes, replies and limits (`app.snapsync.wire.api`). Each operation gets a `Routes.<Op>`
  object — its `ID`, `METHOD`, `TEMPLATE`, `path(…)`, and, for one the app sends itself, a typed `request(…)` and the
  `decide` that reads an answer — and one sealed `<Op>Reply`.

**Validation lives in the description**, through a closed `x-snapsync-*` vocabulary (`outcome`, `instant`, `after`,
`max-span`, `check`, `gate`, `export`, `transport`, `refusals`, `on-invalid`, `trim`), read by both generators; a name
outside it fails both builds. `x-snapsync-export` hands a constraint to the app as a constant
(`Limits.EVENT_NAME_MAX_LENGTH`), so the screen and the route bound a name by one value.

**Every declared response names its outcome** (`x-snapsync-outcome`), and an outcome is a type: a route's own is
nested in its reply (`CreateEventReply.InvalidName`), one defined once under `components/responses` is ONE type every reply
that references it implements (`EventNotFound`, `Unauthorized`, `AppTooOld`, …). Each outcome belongs to one group by
its status — `Success` (2xx–3xx), `ClientError` (4xx — a refused build is the client's), `ServerIssue` (5xx) — and
every reply also answers `NoAnswer`: `Unreachable` (nothing came back) or `Malformed` (an answer outside the
description: an undeclared status, a media type or text it does not name, a body that does not decode). One status
may name several outcomes, by media type or by the body's text (an annotated enumeration, `oneOf` + `const`).

**The layers, app side:**

| layer | owns | where |
|---|---|---|
| `HttpTransport` (port) | send one request, answer what came back — never throws | `ports/`; `KtorHttpTransport` in `:adapter:generic:app` |
| `:domain:wire` (generated) | routes, request shapes, replies — the description, in Kotlin | `domain/wire`, from `api/openapi.yaml` |
| `BackendExchange` / `BackendGateway` | the protocol: the build's base and version header on every request, the bearer on a `GatedRequest` only, `Unauthorized` → recover → retry once, `AppTooOld`/`Success` → the version gate | `services/backend/BackendGateway.kt` |
| need-shaped services | what a reply MEANS to the app (`EventNotFound` is "gone" to the directory, "nothing to leave" to the leave) | `services/backend/` |

**Two kinds of route.** An operation whose `security` admits no token is `OpenRequest` (the `/attest/…` issuers);
every other one is `GatedRequest`, which alone can carry the token — the two legacy optional reads (`getEvent`,
`eventFiles`) included: the app always sends its token there (`PENDING_CLEANUPS.md`, "Anonymous event reads").

**The byte routes** are performed by the OS transfer services (`x-snapsync-transport: os`): the app builds them —
`BackendUploadRequests` (the upload's address and headers) and the union source (each download's address) through
the same exchange, so they carry the version header too — and decodes no reply. The download redirect serves a
request without the header (builds that predate it) until a minimum app version retires them (`PENDING_CLEANUPS.md`,
"Header-less download redirects").

**Instants are one shape** — whole-second UTC (`2026-07-01T00:00:00Z`), compared as text on the backend, so the
description's `Timestamp` pattern is the contract (migration `0013` truncated the stored ones).

**Held by:** `BackendContract` — the production gateway against the real `api/` (`Live`) and against the backend
mock's HTTP face (`Fake`, which also plays a failing store and a maintenance window through its fault lever); its
cells are the replies' outcomes (`Backend.<operationId> → <Outcome>`, one `Backend.* → <Outcome>` for a shared one),
derived by the port grid from `:domain:wire`. `HttpTransportContract` holds each platform's engine.

### HTTP API (v2, current)

**`api/openapi.yaml` is the authority**; this table is the reading guide, not a second definition.

All device routes are under `/api/v2`. `<eventId>`/`<deviceId>` are UUIDs (`400` otherwise), and a
`<deviceId>` other than the one the token was minted for is `403` on every route that names one. Anything
not listed is `404` (no `405`) and makes no upstream request.

| method | path | does | answers |
|---|---|---|---|
| `GET` | `/attest/challenge` | stateless HMAC-signed, time-bounded nonce, writes nothing | `200 {challenge}` |
| `POST` | `/attest/token` `{deviceId, challenge, proof}`, `proof` = `{format:"apple-appattest", keyId, attestation}` or `{format:"android-key", chain:[DER b64, leaf first]}` | the format only CHOOSES the verifier. App Attest: chain to Apple root, nonce, app-id hash, counter, aaguid. Android key attestation (`src/android-attest.ts`): chain to a pinned Google root (by key), its provisioning shape, one extension in the leaf, validity (expiry ignored on factory chains), revocation (Google's public status list, cached per `max-age`), the challenge's SHA-256, the package and signing digest, TEE/StrongBox and a locked, verified boot — the last three per `androidAttestationTrust`. **Persists the device row with the platform that proved it, then mints** | `201 {token}` · `400` body (the retired flat v1 body too) · `401` failed check, its body `attestation rejected: <reason>` with the reason one of `device-modified` (software key, unlocked or unverified boot) · `app-not-genuine` (package, signing digest, App Attest app-id hash) · `device-unverifiable` (every other check — the default, which never accuses the user), suffixed ` (certificate)` when a certificate check failed (the chain's root, shape, signatures or validity, or a revoked certificate — diagnostics only, never told to the user) · `409` stale challenge · `502` write failed, or Android's status list unreachable |
| `POST` | `/attest/renew` `{deviceId, assertion, challenge}` | verifies by the STORED row's `attest_platform`: an App Attest assertion, or an Android ECDSA-P256 signature over the challenge's UTF-8 bytes, against the stored key (no vendor call); advances expiry, then mints | `201 {token}` · `401` no attestation / refused · `409` stale challenge · `502` read/write failure (never `401`: that would force a throttled re-attestation) |
| `POST` | `/events` `{name, startsAt, endsAt?, openUntil?, zone?, keyId?}` | name trimmed, non-empty, ≤100 chars; window rules; `openUntil` (absent → `endsAt`) at least `endsAt` and at most the window maximum after the later of `endsAt` and now (plus an hour for a creating phone whose clock runs ahead), else `400 invalid openUntil`; `zone` stored when usable, else NULL (never a `400`); `keyId` (16 lowercase hex — the encrypted file format's key id) makes the event **encrypted**, and a present invalid one is a `400`, never a plain event; backend mints the id | `201 {eventId, name, createdAt, startsAt, endsAt, openUntil, capacity, deletesAt, closedAt, completedAt, keyId?}` (`keyId` only on an encrypted event, in every event body; `capacity` a placeholder released apps require) · `400` · `502` |
| `GET`/`HEAD` | `/events/<eventId>` (ungated; an optional bearer token is verified, `changes/separate-event-page-from-device-api` D7) | metadata; `deletesAt` derived per response; `openUntil`; `closedAt` (the stored stamp, else derived for builds that predate open-until dates), `completedAt`, `members {active, final, waiting}` (`waiting`: present members not settled or with a declared role not landed), `keyId` | `200` (a completed event too, with `completedAt`) · `401` a sent token that does not verify · `404` sealed absence · `502` read failure |
| `PATCH` | `/events/<eventId>` `{name}` (`changeEvent`) | the only write to an existing event row; last-write-wins; no ownership check | `200` (metadata shape) · `400` · `404` · `410` an event closed before open-until dates, or completed · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>` | **join**: the only route that creates or reactivates a membership; one conditional insert, admitting any device; clears `manifest_version` and `final`; idempotent | `200` · `404` · `410` an event closed before open-until dates, or completed · `502` |
| `DELETE` | `/events/<eventId>/devices/<deviceId>` | **leave** (`?received=true\|false`): a present membership becomes `done` or `left`; closes nothing and wakes nobody; idempotent, a repeated leave keeps the first state (a completed event answers `200` too); assets retained; frees no slot | `200` · `404` · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>/manifest` | **contribution**: full-state replace of the membership's asset set in one transaction, ordered by `version`; `final` → `settled` (both required: absent is `400`), honoured only after `endsAt` and only for a present membership; closes nothing; writes no resource rows; enrols nobody | `200` (applied, or refused as older with nothing written, or the same set to an event closed before open-until dates) · `400` · `404` · `409` not a member · `409 {error:"closed"}` changed set to such an event · `410` completed · `502` |
| `PUT` | `/events/<eventId>/devices/<deviceId>/received` `{position}` | the union-log position up to which the device holds every photo of the others (`received_position`, monotonic: a lower one changes nothing); only a present member | `200` · `400 invalid position` · `404` · `409` not a member · `502` |
| `PUT` | `/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>?filename=<name>` (the download redirect's path; the PUT is gated) | accepted only from a **present** member (`sharing`/`settled` — an ended event still takes its members' bytes, a completed one has none); stores bytes at `files/<eventId>/<sha256 of event/device/asset/role>` — one write when under one part, S3 multipart parts otherwise ([Resumable uploads](#resumable-uploads)) — then records the `resources` row. With `x-snapsync-sha256` (the file's SHA-256) a dropped upload keeps what arrived, and a **partial PUT** (`Content-Range: bytes X-(N-1)/N`, RFC 9110 §14.5) continues it: a lower `X` discards the overlap, a higher one is `409` + `Upload-Offset`. **A failed record fails the request.** If this completed the asset, wakes the event's other members. **An encrypted event takes only encrypted files** (`routes/encrypted-upload.ts`, read in the same statement as the membership): a body whose 9-byte prefix names the event's key id passes untouched; with the iOS extension's `x-snapsync-file-key` + `x-snapsync-file-head` (ONE file's key and opening bytes, never the event key; never logged) the edge seals the plaintext itself, byte for byte as a device would; anything else is refused before a byte is stored. A plain event refuses those headers | `201` · `400` bad role / missing filename / file-key headers on a plain event or malformed / a malformed `x-snapsync-sha256` or `Content-Range`, a range without the sha256, or the body broke off mid-upload (`upload aborted`) · `403` not a present member, or a file head of another key · `409` + `Upload-Offset` a resume from past what is stored, or one a newer request took over · `422` an encrypted event's body that is not a file of its key · `502` (`OPTIONS` → `204`) |
| `GET` | `/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>/upload` with `x-snapsync-sha256` | **where an upload stands**, asked before a device retries one ([Resumable uploads](#resumable-uploads)); gated like the PUT; `Cache-Control: no-store, no-cache, max-age=0` | `204` these bytes landed · `200` + `Upload-Offset: X` — send from `X` · `404` nothing stored for these bytes · `400` · `403` not a present member · `502` |
| `PUT` | `/files/devices/<deviceId>/<assetId>/<role>?filename=<name>` | the **event-less** upload of builds before `per-event-storage-layout`: the same write, filed under the device's one present membership — and the same encrypted-event rule, so a build that predates encryption cannot put plaintext into one | as above, `409` with no present membership |
| `GET` | `/events/<eventId>/files/devices/<deviceId>` | what the backend holds for me **in this event**, from the DB — never another event's | `200 [{assetId, role, filename}]` · `502` |
| `GET` | `/files/devices/<deviceId>` | the event-less listing: the same answer for the device's present membership, `[]` with none | `200 [{assetId, role, filename}]` · `502` |
| `GET`/`HEAD` | `/events/<eventId>/files[?cursor=<n>][&urls=false]` (ungated; an optional bearer token is verified) | the event union: one query over every membership, present or gone; an asset is included only when **every declared role** has a resource (a set comparison, not a count). `cursor` serves only assets the union log says were gained after it (`changes/incremental-union`); `urls=false` omits `url`, which is otherwise the download redirect's absolute address. Every answer carries `SnapSync-Cursor: <n>`, the position it covers. Every answer also carries `SnapSync-Declared: <n>`, the event's declared total — every asset the whole union serves plus every not-yet-complete asset a present member declares, the same on a full and a delta read; a member's received total is it minus the member's own published declaration (`changes/count-declared-photos`). The `SnapSync-Trigger` header (`push`·`wake`·`foreground`·`join`·`grant`·`reconfigure`·`leave-check`) and the verified device, if any, go into the read's `fetch` log row, best-effort | `200 [{deviceId, assetId, creationDate, resources:[{role, contentType, key, filename, url?}]}]` · `400` bad cursor · `401` a sent token that does not verify · `404` · `502` |
| `GET`/`HEAD` | `/events/<eventId>/files/devices/<deviceId>/<assetId>/<role>` (ungated; behind the version gate, but a request with no version header is served — legacy builds) | the **download redirect**: the stored path of that role, when the event still declares the asset with it and its bytes are recorded; each segment percent-encoded | `302` to a 7-day presign, `Cache-Control: no-store, no-cache, max-age=0` · `404` · `502` |
| `PUT` | `/devices/<deviceId>` `{pushToken: {kind, token, env}}` (`kind` is the push adapter's — `apns` or `fcm`) or explicit absence | updates the push columns | `201` · `400` · **`401` when no row was affected** (device never attested; the client re-attests and re-sends) · `502` |

Served at the root under no version: `OPTIONS` on any path (`204`, no resumable upload advertised, so
the iOS uploader uses a plain `PUT`); `GET`/`HEAD` `/`, `/_astro/*` (the Astro build proxied
from the storage `site/` prefix), the event page and its read (below), the AASA (claiming `/join` and `/join/*`) and `/.well-known/assetlinks.json` (Android's: the package and the
`androidSigningCertDigests` the attestation policy accepts, `[]` while none is named or under trust `any`); `GET /health` (`200 {sha, maintenance?}` after
`SELECT 1` and a storage listing succeed, `503` otherwise; `maintenance` absent means closed).

**The event page is the one per-request page** (`src/routes/site.ts` + `src/routes/event-page.ts`, capability
`event-site`). The build emits ONE `site/join/index.html` carrying `%%TOKEN%%` placeholders (title, og tags,
view, status pill, heading, facts), and the api reads it as a template and fills it, HTML-escaping every
value. `/join` (a fragment invite: the server cannot see which) fills it generically, and the page's island
moves the browser to `/join/<eventId>`. `/join/<eventId>` reads the event row and its member counts and
renders its name, dates in the event's `zone` (UTC when NULL), phase and counts, so link previews and
script-less browsers see the event: `404` unknown or malformed, `410` completed (both the invalid view,
naming no event), a closed event renders as ended, a read failure `502`. Both answer `NO_CACHE` (a cached
copy would key the CDN cache on the event's identity) and `Referrer-Policy: no-referrer`. Per view: one
storage GET and one row read plus one count. The page also carries the event's key id (`%%KEY_ID%%`, empty
for a plain event), so an encrypted event's page checks the invite's `#k=` with no further request.

**The event page's read** is `GET`/`HEAD /web/events/<eventId>/photos` (`src/routes/web.ts`): the page's only
call, at the root and under no version, so no device-API version can break the page
(`changes/separate-event-page-from-device-api`). It lists the union's complete assets (the same
`completeAssets` the device union uses) in the page's own shape,
`200 [{deviceId, assetId, resources:[{role, filename, url}]}]`, each `url` a presign living **1 hour**. It
reads no token (an `Authorization` header is ignored, not checked), logs one anonymous `fetch` row, and
answers `NO_CACHE`; `404` for an absent or malformed event, `502` on a read failure. A zip that outlasts the
hour reads the list once more (the page matches files by device, asset and role).

The event's identity reaching `/join/<eventId>` is a decision
(`changes/server-rendered-event-page`), not a leak: it supersedes the fragment-only rule of
`migrate-to-universal-links` D1.

**Conventions that hold on every route:**

- **Faithful outcome.** A write returns `2xx` only when the store confirmed it. A read returns an array
  only when every query succeeded, otherwise `502`, never a partial list (a short list looks like a
  complete one, and a photo vanishes silently). A zero-row conditional write with two causes (closed
  or absent) is disambiguated by a follow-up read (`410` vs `404`).
- **Presigned URLs.** SigV4 query-signed S3 `GET`, path-style
  `https://<s3-host>/<zone>/<the resource's path, each segment percent-encoded>`, signed with the zone name as
  access key id and the storage password as secret. Minted by one builder: by the download redirect, one
  per download started, with a 7-day expiry; and by the event page's read, with a **1-hour** expiry. The 7 days stay on purpose:
  an iOS background session resumes from the redirect target and never revisits the redirect (measured,
  `changes/incremental-union` D2). A browser uses its links at once, so the page's hour bounds how long a
  photo withdrawn after the page loaded stays reachable (`changes/separate-event-page-from-device-api` D3).
- **The device-facing origin** of a redirect URL comes from the deployment's `domain` (`deviceOrigin`:
  `http` for a loopback literal, `https` otherwise), never from the request, which behind the pull zone
  promises nothing.
- **Wakes are best-effort and bounded.** The recipient set (present members with a push token) is one
  query, and the push happens **after** the transaction commits. A wake for a gain carries the union
  position (`seq`: top-level on APNs, a string `data` field on FCM) read after the commit, so a device
  whose cursor is already there reads nothing; one whose position read failed carries none. A failed, skipped or timed-out push
  never changes the response. The byte upload wakes when its resource was the last missing declared
  role. The manifest publish wakes only when it made an asset **newly** fetchable (a widening that
  re-admits stored bytes). Otherwise nobody is woken (iOS allows only a few background pushes an hour).
- **Manifest ordering.** `version` (optional, non-negative safe integer): at least the stored version,
  or none stored, applies and records it; strictly older changes nothing and answers `200`; absent
  applies unconditionally and clears the stored version. The comparison is inside the transaction, and
  every chunk of a chunked replace carries it.
- **Object writes are last-write-wins.** The response never distinguishes create from overwrite. The
  filename is a query parameter, so no caller bytes reach the storage key. It is metadata only, not
  identity.

### Database

- **Schema = ordered migrations** (`api/migrations/NNNN_*.sql`, checksummed, applied once, recorded in
  the store). **`api/schema.sql` is generated** (`deno task schema`) by replaying them, and CI fails if
  it is stale. Read a migration's effect in that file's diff, especially its deletions (a rebuild that
  forgets an index shows up only there).
- **Every `TEXT` primary key is explicitly `NOT NULL`** (SQLite only implies it for `INTEGER`). Tables
  are `STRICT`.
- **Foreign keys are trusted**: the platform defaults `PRAGMA foreign_keys = 1`, as measured. If the
  store is re-provisioned or its engine changes, re-measure.
- **One writer per table** on the current version: `events` by create (and rename, name only; the
  byte route stamps only `last_landed_at`, the sweep `closed_at` and `completed_at`; `open_until` is written
  once, by create), `memberships` by join/leave (the publish writes only `manifest_version` and `state`'s
  settled half, the received report only `received_position`, and join only clears the version),
  `event_assets` by the manifest publish, `resources` by the byte upload, `union_log` by the byte upload
  and the publish (`gained`/`removed`) and the union read (`fetch`), and `devices` by attestation
  and the config write, each naming only its own column group — except `app_version`, which the mint,
  the join, the manifest publish and a union read with a verified token all keep current from the
  version header (best-effort, a write only when it changed; never the byte route, whose fan-out has
  no subrequest to spare). The sweep otherwise only deletes. **review** (plus route tests).
- **Capacity is one conditional insert**, never read-then-write. Measured: 10 racing devices for 3
  slots gave 10 under read-then-write and exactly 3 here.
- **A manifest publish is one transaction**, chunked within it if needed, never across transactions.
  Out-of-DB work (push) happens after commit.
- Migration safety (carry data rather than dropping it, in-file SQL preconditions, no `SELECT *`, FK
  enforcement genuinely off while applying, one migration per deploy) is in `docs/deployment.md` and
  gated by `api/test/migrations.test.ts`.
- A one-time data cutover is never committed. It runs from a scratchpad.

**Accepted limitations** (decided 2026-09-25, spec diet follow-ups; no user promise either way):
- **A lost upload record is not repaired.** If a database failover loses a `resources` row after the
  bytes landed, the uploader believes the photo is shared, but it never appears in the event union for
  others. The photo stays in the uploader's own library. Rare infrastructure event, deliberately no
  device-side reconciliation.
- **A reinstall forgets what was received.** The download memory lives in the App-Group container,
  which a reinstall deletes. After reinstalling and rejoining mid-event, photos received earlier may be
  re-shared as the member's own (others see duplicates), and received photos the member deleted may
  arrive again. Inferred from the code, not measured. Accepted: reinstalling mid-event is rare and
  events are short.
- **The leave notice is sent once.** An offline leave never tells the backend, which only forgoes the
  opportunistic early deletion of an event everyone has left. The 30-day deletion is unaffected.

Decision records: `changes/archive/2026-08-25-record-uploads-in-database`,
`changes/archive/2026-09-07-adopt-bunny-cli-migrations`, `changes/archive/2026-09-22-manifest-versions`.

### Layout

```
src/app.ts         createApp({config, db, fetch}): the three gates and the composition of the routers
src/routes/        the routes as `(deps) => Hono` factories: site.ts (site proxy + AASA + assetlinks.json),
                   web.ts (the event page's read),
                   attest.ts, shared.ts, v2.ts, manifest.ts (manifest body parsing) and
                   support.ts (actsFor, presignDownloadUrl, the routes' shared refusals)
src/db.ts          the one narrow `Db` port and every statement (the enrollment, atomic publish, union,
                   sweep queries). No schema here
src/db-libsql.ts   the deployed `Db` (bunny Database)
src/storage.ts     byte-store and site-prefix key builders + LIST/GET/PUT/DELETE, shared with the sweep
src/attest.ts      App Attest verification, the stateless challenge, the device token (mint, verify, the
                   one expiry derivation)
src/android-attest.ts  Android Keystore key attestation (Google's verifier's rules, pinned roots, the
                   cached revocation list) and the renewal signature check
src/attest-proofs.ts   the mint/renew proofs the routes accept and which verifier each goes to
src/lifecycle.ts   deleteByMs / clockMs / sweepVerdict, shared with the sweep
src/push.ts        the silent-wake fan-out's one sender: each token through the service its kind names
src/push-send.ts   the push senders' shared POST-and-report tail and the PushToken type
src/apns.ts        ES256 provider JWT + silent push per token, per-token best-effort
src/fcm.ts         service-account RS256 assertion → OAuth access token + HIGH-priority data message
                   (`eventId` only, collapsed per event) per token; sends only under the configured
                   Firebase project; no key → every FCM token skipped, the backend still boots
src/validators.ts  UUID / filename / event name / instants (MAX_EVENT_NAME_LENGTH)
src/object-names.ts  a resource's wire object name (the union's `key`) and the role vocabulary
src/config.ts      readConfig over the resolved deployment (non-secrets) + env secrets; throws on a
                   missing secret (the optional FCM key excepted); minAppVersion
src/deployment.ts  GENERATED by scripts/resolve-deployment.py; never committed
src/version.ts     /api/vN splitting, version compare
src/main.ts        Edge entry: read config, serve createApp
src/lint/          the complexity deno-lint plugin
src/scripts/       out-of-edge programs, never bundled (sweep, schema generation, migration plan, probe)
src/dev/           dev-only local rig (fs storage shim, node:sqlite Db, serve.ts, replay.ts, tunnel);
                   unreachable from main.ts, so it cannot ship
migrations/        ordered schema history; schema.sql = its generated snapshot
test/              Deno tests over app.request() with fetch/db/config injected, --allow-net absent
```

Configuration, secrets, deploy, maintenance windows and Edge limits: `docs/deployment.md`. Tests and
the local rig: `docs/testing.md` section 9 and the `local-backend` skill.

---

## 9. Manifest and storage layout

None of these is a user contract. The manifest is re-derived from the ledger every cycle, and a
re-upload after an app update is acceptable. They are still the data real devices and the backend hold
today. Change them knowingly: a renamed on-device identifier strands state in the field
(`RuntimeIdentityTest` is there to make that loud).

### The device manifest (wire format)

The body of `PUT /api/v2/events/<eventId>/devices/<deviceId>/manifest`, one full-state document per
(event, device), recorded relationally (no manifest object in storage):

```json
{ "deviceId": "<uuid>", "version": 42,
  "assets": [ { "assetId": "<canonical AssetId>", "creationDate": "<ISO-8601>",
                "resources": [ { "role": "primary", "contentType": "image/heic",
                                 "key": "<assetId>-primary.heic", "filename": "IMG_0001.HEIC" } ] } ] }
```

- **`assetId`** is the device's canonical `AssetId` (section 2), sent and stored as it is. The iOS form is the
  PhotoKit identifier with `/` mapped to `_` — the same bytes every build since v1 has sent, so no stored row, object
  name or ledger key changed when the type was introduced.
- **Roles** are platform-neutral: `primary` (exactly one: the original still or video) and `live`
  (at most one: a Live Photo's paired video). Originals only, never edits. Image vs video is carried by
  `contentType`.
- **It declares intent, not completion.** It projects the upload ledger's rows **whatever their
  upload state** (a failed upload is still owed), filtered only by the membership's one selection
  policy. The union's role-set comparison is what hides an incomplete asset.
- The projection applies no filter of its own. Date bounds apply at projection, so a narrow or widen
  changes the manifest without re-uploading. Origin exclusions (screenshots, and so on) apply before a
  ledger row exists, so an excluded photo never reaches any manifest. A bare row (loaded at join from the
  file listing, no capture date yet) is excluded fail-closed by the policy until a walk backfills it.
  Every walk backfills every bare row it covers.
- **Deletion and de-selection** remove ledger rows on the next authoritative walk, so that cycle's
  manifest retracts the asset, even if its upload is in flight. Under a partial grant, a read selection
  snapshot counts as an authoritative walk.
- **An empty manifest is valid and published.** A manifest is **suppressed** (the previous one stays)
  whenever the cycle cannot trust the ledger. Suppression and "empty" are logged differently.
- **Writer**: the upload cycle, synchronously in-cycle, in whichever process runs it (app on every iOS
  version, the extension on iOS 26.1+). Two concurrent publishes are ordered by `version` (the ledger's
  manifest counter, which advances on any change that could alter the projection, including a
  reconfigure save and the join-time load). A cycle skips the publish only when event id, version and
  snapshot all match the last **successful** write (the shared database's `uploadedManifest`, which never
  moves to an older version of its event). A refused (older) publish counts as published.
- The job-creation limit never withholds the manifest.

### Byte store (bunny Storage zone)

```
files/<eventId>/<sha256>                          one object per resource of an event; the name is the hex
                                                  SHA-256 of "<eventId>/<deviceId>/<assetId>/<role>"
                                                  (`eventBytePath`), so a re-upload overwrites it and one
                                                  photo in two events has two unrelated names. Deleted as
                                                  one folder by the sweep
files/<eventId>/<sha256>.tail-<claim>             what a dropped resumable upload received past its last
                                                  stored part; deleted once used, or with the folder
files/devices/<deviceId>/<assetId>-<role>.<ext>   bytes written before migration 0010, still served
                                                  through rows that point at them; written no more
                                                  (`changes/per-event-storage-layout`, migration plan)
site/index.html · site/join/index.html · site/_astro/*   the Astro build (mirror-deployed, public)
```

Keys are percent-encoded per segment, so they stay flat. The storage `host` must be the zone's **main**
region (read-after-write). Replicas are asynchronous. There is exactly one zone (`snap-sync-dev`),
shared with real users. **Never add a whole-zone reset.** Clean up targeted only.

Besides objects, the zone holds **open S3 multipart uploads** — the parts of resources still uploading. They
are not objects: no listing shows them and a folder's recursive DELETE leaves them behind (measured), and
completing one afterwards puts its object back. So the sweep aborts the open uploads of every event that no
longer lives (`ListMultipartUploads` on `files/`) before it deletes folders.

### Resumable uploads

On a slow link the pull zone ends a request after 60 s without progress, and every retry used to start
again from byte 0 (Bugsink SNAPSYNC-54: one video retried seven times, 40 MB sent, nothing kept). The byte
route now keeps what arrives (`api/src/routes/resumable-upload.ts`, `api/src/s3.ts`):

- **Parts.** The body is cut into parts of `max(5 MiB, size ÷ 40)` (S3's floor; about 40 parts at most,
  since each part is an edge subrequest), and each full part is stored as an S3 multipart part as soon as it
  fills, one in flight while the next fills. A body under one part is one plain write. Complete joins them
  (~2.3 s, measured) and only then is the resource recorded.
- **The session** lives on the resource's row (migration 0015): `landed_at` (NULL while a first upload is
  under way — such a row is in no union, listing or completeness check, the `landed()` filter in `db.ts`), the
  declared SHA-256 and size, the S3 upload id, the part size, the tail, and the **claim** of the request
  writing it. Only a request that declares its file's SHA-256 (`x-snapsync-sha256`) has one; an older build's
  or the PhotoKit extension's upload is stored the same way but keeps nothing when it drops.
- **The tail.** When the client drops, the script keeps running (≥10 s, measured) and writes what it received
  past the last stored part as `<path>.tail-<claim>`. It counts only while it starts where the stored parts
  end, so the offset never overstates what is there.
- **The retry** asks `GET …/upload` first — the device cannot know: after a network cut its own count runs
  0.7–1.1 MB ahead of what arrived (its socket buffer, at every speed, measured) — and sends the rest as a
  partial PUT. A newer request for the same bytes claims the row; every later write of the older one is
  conditional on the claim, so it stops and lands nothing. Bytes whose row is gone when they complete (the
  member left, the event closed) are deleted again.
- **The app** (`services/upload`): a platform that uploads from a FILE (the iOS app's URLSession; Android for an
  encrypted event, sealed into a file) declares each staged file's SHA-256, hashed once as it is staged
  (`Crypto.sha256`) and kept beside it (`<staged>.sha256`). A FAILED upload keeps both, and the staging sweep
  spares them while the file's ledger row still needs it. The retry asks `Routes.UploadState`
  (`UploadStandings`, over the `BackendGateway`) and creates the transfer with `TransferSource.File(path, from)` and `Content-Range`; a fresh
  upload never asks. Bytes the backend already holds are finished by resending the last one. The platform
  sends the rest: iOS copies it into a file of its own in Caches (a background session uploads only whole
  files), Android skips to it. The PhotoKit extension and Android in a plain event send the library's own
  bytes and restart, as before. ⚠️ A photo edited in place between a failed attempt and its retry resumes the
  pre-edit bytes (Android, encrypted events only): the kept staged file is not checked against the library.
- **Why the wire looks like this:** URLSession's own resumable uploads (the IETF resumable-upload draft)
  need a `104` interim response and `HEAD`, and the pull zone forwards neither — it drops 1xx and sends a
  HEAD to the origin as a GET (measured, bunny ticket open). So the status read is a GET on its own path
  (GET on the upload's path is the download redirect), the resume a standard partial PUT, and the answer
  carries the draft's `Upload-Offset`. An early answer is impossible too: a reply sent before the body ends
  reaches the client as a `502`, so a `409` comes after the body was read.

### The encrypted file format

The stored bytes of an **encrypted** event's resource. An event is encrypted when it was created with a key; the
key travels only inside the invite link's fragment and the server holds nothing but its `key_id`. A shipped build
creates PLAIN events for now (every build from 0.5 reads encrypted ones), so every invite it shares opens on 0.4; a rig
build creates encrypted events by default and can still create a plain one. Encrypted creation returns to shipped
builds once the minimum app version is 0.5 (`PENDING_CLEANUPS.md`, "Re-enable encrypted creation"), and a later change
retires plain events.

```
prefix    version (1 byte, 0x01) ‖ key id (8 bytes)                       SnapSync's own
header    header length (1 byte, 40) ‖ salt (32 bytes) ‖ nonce prefix (7)   ┐ Google Tink's
segments  AES-256-GCM, 64 KiB ciphertext each (the first shorter by the     │ AesGcmHkdfStreaming
          header, the last shorter still); nonce = nonce prefix ‖           │ (HmacSha256, 32, 65536, 0),
          segment number (u32 BE) ‖ last (0|1)                              ┘ byte for byte
```

- **Segment key** = HKDF-SHA256(event key, salt, `snapsync/v1/<eventId>/<deviceId>/<assetId>/<role>`): a file
  moved to another event, device, asset or role fails to authenticate.
- **Key id** = HKDF-SHA256(event key, no salt, `snapsync/key-id/v1`), first 8 bytes: what the event row holds.
- **The last-segment flag** makes a truncation at a segment boundary fail, and a decryption writes nothing until
  every segment authenticated.
- **Reference vectors**: `test/vectors/encrypted-file.json`, regenerated by `scripts/encrypted-file-vectors.py`
  (an implementation independent of both halves). Plain events' bytes are raw, with no prefix.

**Where each step runs in the app** (all in `services/crypto`, over the `Crypto` port):

- **The key** is minted by the creating device (`EventKeys.mint`, unless `DevControls.createsPlainEvents` — inert
  `true` in production until encrypted creation is re-enabled; a rig build answers `true` only after
  `device/encrypt-new-events?on=false`), sent as its id alone (`POST /events {keyId}`), and
  kept on every member's device in the secure store's shared slot `SecureSlots.EVENT_KEY` — written by the join,
  removed by a leave, a reset or a plain join. `EventConfig` holds only `keyId`.
- **The link decides, never the backend** (`JoinEvent`): a link whose `#k=` does not name the event's `key_id`, or
  that lacks one for an encrypted event, never enrols — the gate shows `JoinPhase.WrongLink` at load, and the commit
  refuses it again. An invite for an encrypted event is the path form `/join/<id>#k=…`; a plain event's stays the
  fragment form every installed build reads.
- **A lost key** (`EventKeys.presenceFor` → `KeyPresence.Lost`: the event has a `keyId` and the slot holds no key of
  that id — an unencrypted backup restored onto a new phone, an invalidated Android Keystore key) stops both
  directions at their admission: the upload cycle's gate answers `UploadAdmission.Withheld` in the app and the
  extension alike (nothing walked, staged or published), and `DownloadController.reconcile` starts no download
  (`DownloadArm.keyHeld`, which unlike `DownloadArm.enabled` never reads as "nothing to receive"). A
  store that cannot be read is `Unknown`, never `Lost`, so a locked wake runs as before and withholds at the seal.
  `EventKeyReads.presence` (re-read on a membership change, every foreground and after a restore) is the joined screen's
  `SyncHealth.KeyLost`. Reopening the joined event's whole invite restores it in place (`StatusContainerHost`'s
  same-event rung → `UserCommands.restoreEventKey`, which keeps only the event's own key and only while it is lost).
  No invite is offered without its key: `Layer.Joined.inviteUrl` is `null` while an encrypted event's key cannot be
  read (lost or locked), which hides Share and QR; `EventKeyReads.inviteKey` is re-read with the presence.
- **Uploads** (`UploadSealing` inside `SessionTransfer` and `OsQueueTransfer`): the app's session (`TransferSession`:
  the iOS app's `URLSession`, Android's — which takes a file too) exports, seals into the staged file and deletes the
  export at once; the OS's queue (`OsUploadQueue`, PhotoKit), which sends the library's own bytes, carries `x-snapsync-file-key` + `x-snapsync-file-head` for that one file and
  the edge seals it. A key that is unreadable, absent or not the event's withholds the job — never plaintext.
- **Downloads** (`DownloadOpening` inside `DownloadJobs`): the finished file is adopted beside its staging path and
  opened into it off the delegate queue; only a fully authenticated file is staged. One that does not open is
  discarded and downloaded again by the next reconcile, and its fourth consecutive failure is reported once at
  `Error` (the crash channel).
- **No key reaches a log or a report**: `redactEventKeys` runs wherever the UUID scrub runs, on the diagnostic dump
  too, and on the logged link.
- **The event page** (`site/src/lib/event-key.ts`, over the edge's own `api/src/encrypted-file.ts`) reads the key
  from its address's `#k=`, checks it against the event's `keyId` before fetching anything, and opens each file of
  the zip in the browser; without the whole invite it says so and offers nothing. The page is served with a
  `Content-Security-Policy` (`eventPagePolicy`) that runs only the site's own scripts and talks only to this origin
  and the storage host. Google Play's install referrer carries the key too (`v=3&d=…&k=…`, appended only once the
  page checked the key against the event; `inviteLinkFromInstallReferrer` answers the keyed path form), so an
  Android install from the page opens the whole invite. A build before 0.6 reads such a referrer as an organic install.

### On-device layout (iOS)

App Group `group.app.snapsync`, shared by the app and the extension, at the default data-protection
class (readable after first unlock; see `DataProtectionEntitlementTest`):

| path / key | what | owner |
|---|---|---|
| `ledger.db` | the shared database (SQLDelight, `SharedDatabase`): the upload ledger — the membership's share set + manifest version; every row names its event and every read is scoped to the joined one, so another event's rows are inert until the next join purges them — and the records both processes share: the membership (`membership`; no row **is** "left the event"), the uploaded manifest (`uploadedManifest`: event id, version, snapshot), the pending leaves, the event albums (album + filled mark per event) and the event checks. Either process opens it read-write and migrates it; the app copies the records' legacy homes into it at its first open after the update, and the extension pauses until it has (`LegacyRecords`) | `LedgerService`, `ConfigService`, `DeviceManifestService`, `PendingLeaves`, `AlbumMapService`, `EventChecks` over `IosDatabases` |
| `downloads.db` | the download store: one record per received photo (its state and import marker, across events), which event's union each photo belongs to (`eventRef`, purged at the next join), which membership's received photos have been recognised (`recognised`, purged with `eventRef`), and each planned resource with the event whose bytes it fetches. The app writes and migrates it; the extension opens it read-only. It dies with the app; a join rebuilds its imported rows from the SnapSync mark each received photo's library name carries (`ReceivedPhotoName`, `ReceivedPhotoAdoption`), which is a second record, never the store's replacement | `DownloadService` / `SuppressionService` over `IosDatabases` |
| `upload-staging/` | bytes staged for the app's background `URLSession` uploads | `IosUrlSessionUploadPlatform` |
| `download-staging/` | downloaded bytes awaiting import, under `<eventId>/<sourceDeviceId>/` (a transfer an earlier build started stages without the event folder). The download store keeps each path **relative** to the container (schema 4 rewrote the absolute ones) | `StagingService` over `IosFiles` |
| `ext-debug.log` (+ `.1`) | the extension's verbatim log (the app's is `Documents/debug.log` in its own container) | log writers; read by `LogTailService` |
| defaults `app.snapsync.settings.mobileData` | the device's mobile-data choice | `MobileDataSetting` over `IosPreferences` (`AndroidPreferences` on Android) |
| defaults `app.snapsync.push.lastRegistered` | the last push registration the backend accepted (token, env, device id); dies with the install, so a reinstall publishes | `PushRegistrationRecord` over `IosPreferences` |
| legacy: `eventconfig.json`, `device-manifest/last-uploaded.json`, `membership/pending-leaves.txt`, `push-registration/last-registered.txt`, defaults `app.snapsync.album.map` / `.filled` / `app.snapsync.check.*` | where the shared records lived before they moved; an updated device's app copies each once and deletes it (`PENDING_CLEANUPS.md`) | `LegacyRecords`, `PushRegistrationRecord` |

Keychain (only in `:adapter:ios:ext-safe`, as `IosSecureStore`; every item is readable after first unlock). Every item is a `SecureSlot` in `model/SecureSlots`; `shared = true` names the access group, `false` searches unscoped:

| (service, account) | what | access group |
|---|---|---|
| (`app.snapsync.deviceid`, `deviceid`) | the per-install device id | shared group `<TEAM_ID>.<group>`, cross-checked against both signing entitlements |
| (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`) | device token, App Attest key id | unscoped (pinned set) |

`simulator.entitlements` carries the App Group only and **must not** declare `keychain-access-groups`,
because that makes an ad-hoc simulator build unlaunchable.

OS-registered identifiers: the heartbeat's two BGTasks — `app.snapsync.upload.heartbeat` (busy, a processing
task) and `app.snapsync.heartbeat.idle` (idle, an app refresh) — (Kotlin and `Info.plist` must agree:
`RuntimeIdentityTest` asserts `BGTaskSchedulerPermittedIdentifiers` lists **exactly** the pinned set, the wake adapter
registers exactly that set in its `inbound`, and the Swift shell registers none, so a retired id such as
`app.snapsync.download.backstop` left in the plist fails the build); background `URLSession`s `app.snapsync.upload.session` and
`app.snapsync.download.bg` (the OS reattaches transfers by these); framework base names `SnapSyncKit`
and `SnapSyncUploadKit`. The pinned inventory, including retired names kept for recognition, is
`DOCUMENTED_INVENTORY` in `RuntimeIdentityTest`.

---

## 10. Background execution: each wake's own work, then the wake's work

How the app process spends an OS wake. The user-visible promises this serves are in
`openspec/specs/delivery` and `receiving-photos`. The code of record is `entrypoints/AppEntryPoints.kt` (one entry
point per trigger, the shared `WakeWork`, and the sequences) over the runtime in `scheduling/` (`docs/patterns.md`).
Decision records: `changes/archive/2026-09-25-own-work-per-wake`, and the core-layers design's phase 3, which moved
the flows, the tail runner and the completion holder under entry points and a runtime without changing what each
wake runs.

**Own work per wake.** Every wake — an entry point — runs a shared prelude (`Prelude`: membership re-read,
attestation refresh), then only the work its event is about, and then the **wake's work** (`WakeWork.full`):

| wake (entry point) | own work | wake's work | heartbeat re-arm |
|---|---|---|---|
| silent push (`OnSilentPush`; active event only, `PushTailGuard`) | union read, plan, download enqueue | full, detached | the cadence rule |
| download-session relaunch (`OnDownloadSessionEvents`) | stage the delivered files (the drain, then the stagings recorded) | full, detached | the cadence rule |
| upload-session relaunch (`OnUploadSessionEvents`; iOS 18–26.0) | the delegate records terminals (the drain) | full, detached | the cadence rule |
| heartbeat (`OnScheduledWake`; on iOS the `BGTask` `app.snapsync.upload.heartbeat`, or `app.snapsync.heartbeat.idle` when idle) | none: the wake *is* the wake's work | full | the cadence rule |
| limited-grant selection change (`OnSelectionChanged`) | snapshot-fed discovery → manifest | full | the cadence rule |
| foreground (`OnForeground`) | download reconcile, stored-upload settle, staged-byte reclaim, status and membership refresh (`ForegroundWork`) | full | the cadence rule |
| the network's return, in the foreground (`OnNetworkReturned`) | the foreground's own work, unchanged | full | the cadence rule |
| membership transition's arm (`UploadEngine.arm`, labelled `OnArm`) | none (detached) | full | the cadence rule |
| upload completion (`OnUploadFinished`; admission `Admit` only) | the terminal write | `TopUp` only, detached | never |
| download staged in a running process (`OnDownloadStaged`) | record the staging | `ImportReceived` only, detached | never |

**The wake's work** (`WakeWork.full`) runs two single-flight sequences (`Sequence`, `docs/patterns.md`), then the end
of the wake: `ImportReceived` (import staged downloads — it **yields to a join**, so an import stalled on a
photo-library transaction never holds a new request back; the stalled import runs on, claimed, `outliving` its pass),
then `UploadOwn` (top-up — re-create retry-spent failures, enqueue known `DISCOVERED` rows — then the discovery walk →
manifest publish, full grant only, then a top-up again when the walk recorded rows). Under a partial grant the walk
is a selection change's own work, and the top-up resolves from the in-memory selection snapshot (withheld while it is
unread). A request while a pass runs **joins** it: the sequence makes exactly one more pass, however many joined, and
each joiner awaits it; a joiner whose time is up withdraws without cancelling a pass others wait for. Whatever the
passes ended as, `ensure` steps re-arm the heartbeat (a cut pass counts as work left) and record the memory footprint
before the run answers. Own work runs **outside** the sequences, so no wake waits behind another wake's walk. The
download reconcile never imports; `ImportReceived` is the only drain, plus the once-per-process interrupted-import
sweep at host assembly (same per-asset claims). There is no download backstop task any more.

**The heartbeat** is the `Heartbeat` service (`services/wake`) over the `Wake` port: a one-shot wake at a **cadence** —
**busy**, no sooner than 60 s, or **idle**, no sooner than an hour — needing the network, re-requested each time; a
request at one cadence replaces a pending one at the other. **The cadence rule** (`feature/upload/HeartbeatCadence`,
applied by `WakeRearm` at the end of every wake's work) keeps one pending **for as long as the device is
joined**: busy while work remains (uploads a wake left, or staged downloads not yet imported) or while a
full-grant sharer notices its own new photos only by looking — no library-change wake standing and the OS uploader
not confirmed registered — until the event's end; idle for every other joined device (receive-only, uploads held
back, a partial grant, caught up with a library wake, and everyone after the end); none once not joined. Its inputs
are the uploads' outcome and `CadenceFacts`, which the composition reads fresh (`cadenceFactsOf` in `compose/`: the membership,
its direction, the grant, and the OS's own registration answer). Decision record:
`changes/timely-background-receiving` (D1–D3).

**The library watch** is the service's other wake: `watchLibrary` requests a one-shot `WakeId.LibraryChanged`,
delivered within 60 s of a change to the photo library, and answers whether one now stands. `WakeRearm` renews it
after every wake whose uploads ran for a contributing membership (any trigger but an import-only one, any outcome
but `SKIPPED`), so a caught-up device still notices the next photo; where one stands (Android), a caught-up sharer
idles. iOS answers it `Unsupported` — its library-change wake is the upload extension, which counts only once the OS
confirms it registered. A disarm cancels both wakes. Decision record: `changes/archive/2026-09-29-android-sharing`
(D5).

**In-process requests hold time too.** Work started from inside the process — a transition's arm, an upload
completion's top-up, a staged download's import, a restored event key — is `detached`: it runs in the runtime's
scope under its own background-time hold, labelled with the trigger that started it, as a wake does. Free on iOS,
where background time is per app; on Android it is what keeps a worker's top-up from being frozen mid-pass.

**Handlers and time.** An OS completion handler crosses as a `Completion`, delivered with its event, and the run that
event starts answers it exactly once — nothing stores one in a field. A `Background` delivery's run (a push, a
relaunch, a foreground) holds the process's background time, the `BackgroundTime` port (`beginBackgroundTask` in
`:adapter:ios:app-only`), begun **on the delivering thread, before the callback returns** — the OS may suspend the
process the moment it does. A push is answered after its own work, and a `URLSession` relaunch after its drain report
(and, for downloads, once the stagings it caused are recorded — its adapter's `Completion` puts the call on the main
thread UIKit requires); the wake's work then runs detached under its own hold. A `BGTask` is a `Granted` delivery:
its run holds no time of its own, and `setTaskCompleted` waits until the wake's work ends. **No clock of ours**:
there is no receipt deadline, no manifest timeout and no `backgroundTimeRemaining` read. "Time is up" is only the
`BGTask`'s `expirationHandler` (the wake adapter expires the `Running` the delivery answered; nothing in Swift answers
it) or the background task's expiration handler (the hold expires the run it was begun for); a refused hold reports
as an immediate expiry. On expiry the run is **cancelled**: no new step starts, a sequence's pass is cancelled once
every waiter has gone, and the completion is answered once the cancellation has unwound — or after
`EXPIRY_GRACE` (2 s, measured on the SE2: see its KDoc) regardless, and the hold ended with it. Work that must
happen together is one non-cancellable step: a top-up checks cancellation between creations, and a job the platform
created is recorded `REQUESTED` before the pass can end; a cancelled walk records and publishes nothing; a
photo-library import runs on, `outliving` its pass. A cut pass counts as `PROCESSING` for the re-arm. The only
timeout left is the HTTP client's per-request one, which bounds a request and ends no wake.

**Cheaper work in `darwinbg`.** Background wakes run CPU-clamped (~9× after the first second, measured on an
SE2), so:
- **Walk memo** (app process only): the full-grant walk is reused while `PHPhotoLibrary.currentChangeToken`,
  the selection policy and the grant are unchanged (~2 ms against a 1.3–2.0 s walk). It reproduces a fresh
  walk exactly, so it stays authoritative for deletion. It lives in memory only, stores only completed
  full-grant walks, and reads the token before the walk. A build constant switches it to shadow (walk every
  time, log a disagreement at `Error`) as the revert.
- A walk's resources are handed to the top-up that follows it, so a row it just read is not resolved again.
  The engine's `isWork` answers "is this work" without minting a request.
- Download planning is batched (`settledAmong`, `planAll`, `markAllEnqueued`).
- Limited-grant selection changes queued behind a running enumeration are folded into one enumeration. Both
  the change and the baseline path drop a snapshot built under a grant that has since become full.
- The denylisted-album lookup is asked only under a full grant (it can only answer the empty set otherwise).
- Ledger counts refresh after a sequence's steps only while foregrounded (foreground entry re-reads them anyway).
- The network is watched only while foregrounded: `ForegroundWork` starts `feature/status`'s `NetworkWatch` beside
  the counts poller and `OnBackground` stops it, so the `NetworkMonitor` port's cold flow — and the platform's
  monitor behind it — runs only while a screen shows the result. Presentation reads it as the read-model
  `NetworkStatusSource`: the access to show (a missing network after the watch's grace, its return at once, `Online`
  whenever stopped) and `returned`, the moment a shown notice clears (capability `sync-status`; decision record
  `changes/tell-when-offline`, D1–D2). `Online` carries whether the network is restricted (mobile data, a hotspot,
  Low Data Mode / Data Saver), which the status line reads for "Waiting for Wi-Fi…" (capability `mobile-data`).
- **A photo transfer carries the device's network rule** (capability `mobile-data`; decision records
  `changes/archive/2026-10-04-mobile-data-for-photos` and `changes/archive/2026-10-07-mobile-data-per-device`): `TransferNetwork`, read
  from the device's choice (`MobileDataSetting`, one key in the shared `Preferences` both iOS processes read — an
  unreadable one holds to Wi-Fi) the moment a transfer is CREATED and
  handed over on `UploadTarget.network` / `Download.start` — so a change of the choice governs only later transfers,
  with no state of its own. Whoever owns "when may this run" honours it: iOS's request flags (`applyTransferNetwork`,
  one builder for both upload tiers and the download), `DownloadManager`'s metered flag, and on Android's in-process
  uploader the adapter's own wait — resumed after a process death by the busy heartbeat, whose `WakeNetwork` is
  `UNRESTRICTED` while photos are kept off mobile data. Every other request — the device API over `HttpTransport` — ignores it.

**On Android** there is one process and one uploader. The `Wake`, `BackgroundTime` and `TransferSession` adapters
(`:adapter:android`'s `work/`) are WorkManager's:
- **a wake** is one unique one-time work per `WakeId` — the heartbeat's delay and network constraint, or content-URI
  triggers on MediaStore's image and video collections for the library watch. A re-request replaces a pending wake,
  except from inside that wake's own running worker, where it appends (replacing would cancel the worker asking). The
  worker is an entry port on the `Application.onCreate` composition and builds no screen; its stop (`onStopped`) expires
  the wake's run.
- **a background-time hold** is an expedited work that runs until the hold ends; its stop is the hold's expiry. It is
  what keeps a process the member just left, or one a worker started, from being frozen mid-unit.
- **an upload** is an in-process PUT streamed from MediaStore (it `takesResources`: no staged copy), one at a
  time (parallel streams only divide a thin uplink), each end reported inline. The adapter holds background time while any is live; the hold's expiry
  cancels them, reported failed, to restart on a later run. A small journal names every job not yet reported, and a
  process that died with jobs in it has them reported failed when the next one listens — so a row never waits on a
  transfer that no longer exists. A connection per transfer: a streamed body is sent once, and a pooled connection
  the server closed would fail it outright.

A force-stop cancels every WorkManager request until the next opening, when the foreground's wake re-requests them;
before the first unlock the app (not direct-boot aware) never runs. Doze and App Standby defer work to maintenance
windows.

**Push registration is change-driven.** The composition asks the push service for the token at every launch (as
the graph is composed, from `onLaunch`) and in `OnForeground` at every foreground entry. `PushRegistration` publishes only when (token, env, deviceId) differs from the
`PushRegistrationRecord`, and unconditionally on join and on every fresh credential (mint, re-attestation,
renewal). The subscription is installed by `snapSyncHost` on every cold start, background included. Host
assembly is foreground-only. The token source carries a `deliveries` flow so an unchanged re-delivery still
re-sends a failed publish.

**Device-token copies.** Each process may serve the device token from memory. Its own writes drop the copy.
The app re-reads the Keychain at every attestation decision (every wake), the extension at every
`process()`, and both after a `401`. A `401` on a metadata call is recovered IN the call: the app's authenticated
backend waits for the re-attestation and retries once, so the call's caller is answered by the retry (this costs
the wake a challenge, an Apple attestation or a local assertion, and a mint — only when the backend has actually
rejected the token). The extension drops the rejected token and defers to the app's next wake. A retry's request is minted through `provideForRetry`, which reads the
store of record.

**The upload extension has no cooperative stop.** Measured (SE2, iOS 26.6): its only end is assetsd's 60.0 s
timer (SIGKILL), `notifyTermination` follows only normal returns, `performExpiringActivity` never fires, and its
memory limit is 32 MB (an overrun is a jetsam kill loop). A `process()` measured 0.6–1.4 s, and every unit is a
safe retry. It walks afresh every call and holds no memo.
