## Why

The OS-driven upload tier (`ios-photokit-upload`, iOS ≥26.1) is the tier that ships to current devices,
and it is the one thing the simulator host cannot exercise: the OS never invokes an appex there, so every
upload scenario on that host pins the app-driven mechanism instead. That leaves the shipping tier's cycle
— its adjudication of job states, its registration ritual, its `stop()` repair — testable only by hand on
one physical phone.

Measurement makes it worse than "the OS won't schedule it" (`PROBE-FINDINGS.md`, 2026-08-26, iOS 26.5).
Registration is **refused** on a simulator (`setUploadJobExtensionEnabled(true)` → `false`,
`PHPhotosErrorDomain:-1`), and with no registration record `creationRequestForJobWithDestination` raises
`NSInvalidArgumentException` **inside PhotoKit** and terminates the process — not a returnable error.
Everything else in the tier ran: the shared `uploadCore`, the entry gate, the re-join reconcile, real
PhotoKit discovery, the real selection policy, real HTTP to a real backend, and cross-root device identity.
Nine seams cleared before the wall; one subsystem is the wall.

## What Changes

- **The rig substitutes the OS's upload-job subsystem on the simulator target**, and only that subsystem —
  `setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled`, `fetchJobsWithAction`, and job
  creation/retry/acknowledge. Every other PhotoKit surface (assets, resources, change tokens, albums)
  stays real. The substitution is bound by **compilation target**, so a device binary contains no route to
  it — load-bearing here, because reaching `createJob` on a simulator kills the process.
- **The control channel invokes the real extension root.** `:app:ios` links `:app:ios:extension` under
  `-Psnapsync.rig=true` only, and a trigger calls `UploadExtensionRoot.processRawValue()` verbatim — not a
  copy of its wiring — on its own serial thread, never the main lane. No second process and no second
  composition.
- **The job queue lives in the caller, not the app.** The trigger takes the finished jobs as input and
  returns the newly created ones; a separate verb performs one upload for real. This mirrors how PhotoKit
  presents itself to `process()` — the durability is the system's, outside the process — and keeps the
  substitute free of state it could get wrong.
- **The `/os` trigger namespace is prefixed by root** — `/os/app/<member>` and
  `/os/photokit-ext/<member>` — because it now spans two composition roots.
- **Registration moves behind a port.** `PhotoKitUploadProducer` currently makes raw `PHPhotoLibrary`
  calls from `:app:ios`, which is wiring-only. A port in `:domain` `ports/` with an adapter in
  `:adapter:ios:app-only` puts the I/O where the ports law says it goes, lets the rig answer it, and makes
  the disable→enable ritual and its `stop()` repair testable — including on JVM.
- **A success claim that was never checked is deleted.** `start()` logs "background-upload extension
  re-registered" unconditionally, two milliseconds after `RegistrationOutcome` may have classified the
  enable as failed. `Applied(enabling = true)` already logs "extension enable succeeded"; the extra line is
  a second, unearned claim.
- **No change to device behaviour.** `PHPhotosErrorDomain:-1` on a device stays a loud `Error`; the closed
  and measured expected-code enumeration is not widened.

## Capabilities

### New Capabilities

None. The simulator host and the control channel are dev infrastructure that is a **lens** — the posture
`add-simulator-rig-host` took, and the one `:test:rig`, `:test:harness-driver`, `api/src/dev` and
`ssh-mac.yml` already hold. The stateless design keeps the substitute a projection of contracts specified
elsewhere rather than a second implementation with behaviour of its own.

### Modified Capabilities

- `ios-photokit-upload`: the registration change becomes a **port call** rather than a direct platform
  call, relocating where that decision lives; the extension root's port bundle becomes a named seam that
  takes its `BackgroundTransfer`; the terminal-job adjudication moves into the tested pure mapping file
  its own contract already claims holds every per-job decision; and the unearned post-registration success
  line is removed from the reporting requirement's neighbourhood.
- `architecture-guards`: `RigControlChannelTest` derives its platform-entry population from **two**
  composition roots grouped by root, not from `SnapSyncRoot` alone. Its current scoping reason — "the rig
  runs in the app process, so the extension root's entry points are not reachable from it" — is falsified
  by this change and must be replaced rather than reworded.

## Impact

- **Code**: `:app:ios` (`PhotoKitUploadProducer`, the rig hook, the build script's rig-gated deps);
  `:app:ios:extension` (`UploadExtensionRoot` gains an extracted `uploadPorts`); `:adapter:ios:ext-safe`
  (`PhotoKitJobMapping` gains the adjudication; `IosPhotoKitUploadPlatform` loses it);
  `:adapter:ios:app-only` (new registration adapter); `:domain` `ports/` (new registration port);
  `:test:rig` (the substituted subsystem, the new triggers, the perform verb, the root-prefixed routes);
  `:test:architecture` (`RigControlChannelTest`).
- **No new module**, so `ModuleSetTest` is unaffected. **No new Xcode target** and no second bundle.
- **Route migration**: every existing `/os/<name>` becomes `/os/app/<name>`. The `rig-channel` and
  `ios-simulator` skills follow, both gated by `RunbookSkillsTest`.
- **Unchanged and still device-only**: OS scheduling of `process()`, the appex Swift shell and its
  `processingResultRawValue` handoff, the appex memory cap, cross-process ledger locking, and the shipped
  Keychain identity binding.
- **Changelog label**: `internal`.
