## Why

The OS-callback entry points that carry every interesting behaviour on a device — foreground, silent
push, the `BGProcessingTask` backstop, the background-`URLSession` events wake — cannot be forced from
the sandbox. Today the only way to observe live app state on a phone is a screenshot, and the only way
to read the extension's log is a launch with `SNAPSYNC_EXPORT_LOGS=1` followed by a pull. A parallel
session measured the consequence: a fully joined simulator with photo access granted and the app-driven
tier armed ran **no upload cycle at all** — not one `enumeration` line — because the tier's kick is a
`BGProcessingTask` heartbeat or a background-`URLSession` relaunch, and a headless host fires neither.

So the device loop is: relaunch, screenshot, guess. This change adds a build-time-only HTTP control
channel that lets an agent force those entry points and read the real state back, driven with `curl`
over `pymobiledevice3 usbmux forward` — the same idiom `:test:harness-driver` already establishes for
the desktop harnesses.

It is the third of an eight-change plan. Later changes (a simulator host, an extension-shaped second
process, Gherkin scenarios, two-member runs, backend fault levers) build on it.

## What Changes

- **New module `:test:rig`** — a Ktor CIO HTTP server written against `:domain` types only, linked into
  the iOS app **exclusively** under `-Psnapsync.rig=true`. It is the one module permitted to depend on
  `ktor-server-*`.
- **Containment is compile-time, not runtime.** A production build contains no rig code at all — not an
  inert branch, not a stub. This is the first dev/test surface in the app contained by compilation
  rather than by a runtime check, and it is why no `ios-app-shell` requirement changes.
- **Zero lines in `:app:ios`.** Under the property, the build script adds one source directory from the
  rig module that compiles into `:app:ios` and self-starts. The only production diff in the shell is two
  fields' visibility, `private` → `internal` (`app`, so the hook can pass the core as a thunk; `mode`, so
  `/health` can report the resolved composition without a second resolution that could disagree with it).
  `internal` is module-wide and is not exported to the ObjC framework header — verified on device.
- **Four endpoints**, each a mechanical projection of a contract that already exists elsewhere:
  - `GET /health` — build facts.
  - `GET /state` — the real `UiState` (newly `@Serializable` where it is declared), plus membership
    readiness, ledger aggregates, the screen-level read-models, and the resolved composition facts.
  - `GET /logs` — a pass-through to the existing `DeviceLogSource.tail`, reaching **both** processes'
    logs; the extension's becomes one request instead of a launch cycle.
  - `POST /trigger/<entry>` — the real `@PlatformEntry` members, invoked exactly as the Swift shell
    invokes them.
- **A trigger returns what the platform returns.** The four entry points the OS hands a completion
  handler block until the app releases it — the rig supplies that handler, because it is playing the OS
  — and report how long it was held. The four the OS does not wait on return immediately. The rig
  classifies nothing.
- **Three new architecture guards**: rig trigger coverage derived from `@PlatformEntry`; the rig binds
  the loopback address and no other; and the `OsReceipt` expiry line's text is pinned, because scenarios
  read its presence as ground truth.
- **`@Serializable` on `UiState`** in `:ui:presentation` — annotations only; no requirement changes.

Not in this change, and named so the omission is deliberate: no scenario runner, no Cucumber, no
simulator host, no fault injection, no fakes inside the app, and no verb to stop the OS upload producer.

## Capabilities

### New Capabilities

None. The rig holds no behaviour of its own: `/state` is a compiler-generated encoder over `UiState`,
`/trigger` invokes entry points that `ios-app-shell` already specifies, `/logs` passes
`DeviceLogSource.tail` through verbatim, and a trigger's response is whatever `OsReceipt` decided. A rig
spec would be a second copy of contracts that already have homes — the drift this repo has been swept
for once already.

This matches how the repo already splits its test infrastructure: `:test:world` and both desktop
harnesses are spec'd because they hold behaviour that could be silently wrong; `:test:harness-driver`,
`ssh-mac.yml` and the local backend rig are not spec'd because they are lenses onto someone else's
behaviour. The rig is a lens.

### Modified Capabilities

- `module-architecture`: the module set gains `:test:rig`, with the withholding argument that makes it a
  module rather than a package — it is the only module that may depend on a server framework.
- `architecture-guards`: three new gating guards (rig trigger coverage, loopback-only bind, receipt
  expiry-line pin), and the shell gate's scanned roots extend to the rig's hook directory, which
  compiles into `:app:ios`.

## Impact

- **New**: `test/rig/` (`:test:rig`), plus its hook directory compiled into `:app:ios` under the property.
- **Modified**: `settings.gradle.kts` (module set, pinned by `ModuleSetTest`); `app/ios/build.gradle.kts`
  (the one property read); `SnapSyncRoot.kt` (two fields `private` → `internal`: `app`, `mode`);
  `ui/presentation/build.gradle.kts` and `UiState.kt` (`@Serializable`); the root build's
  `appShellSources`; `:test:architecture` (three guards, and `KotlinShellGuardTest`'s mirrored root list).
- **Dependencies**: `ktor-server-cio` and `ktor-server-core` at the ktor version already pinned in
  `gradle/libs.versions.toml`; the `kotlin-serialization` plugin and `kotlinx-serialization-core` on
  `:ui:presentation`.
- **Ships**: nothing. No TestFlight or App Store build links the rig.
- **Sequenced after this change**: `ComposedProducers` must distinguish selectable-from-stoppable so
  `UploadArm` deregisters the OS extension under the tier-force flag (`upload-lifecycle`,
  `ios-photokit-upload`). Until it lands, a rig session must not force the URLSession tier on an
  iOS ≥26.1 device — two `LedgerWriter`s would share one App-Group ledger.
- **Decided here, implemented later**: `SNAPSYNC_DEVICE_ID`, filling an absent device identity and never
  overwriting one, needed by the simulator host and the second-process change.
- **Two prerequisites, either of which invalidates the design**: `@EagerInitialization` must fire in a
  static Kotlin/Native framework, and `ktor-server-cio` must serve on a physical device. Only simulator
  execution has been measured.
