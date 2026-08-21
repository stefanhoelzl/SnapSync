## Why

Eleven `SNAPSYNC_*` launch-environment variables are read by **production Kotlin**. They ship in every
TestFlight and App Store binary, inert only because a process-environment variable cannot be injected by a
SpringBoard launch. That inertness is a property of how the app is *started*, not of what it *contains* —
so the shipped app carries a complete remote-control surface for joining events, minting events, leaving,
voiding durable sync state, seeding the photo library, and deleting it.

The cost is not hypothetical, and it is not only philosophical:

- **Six of the repo's eight pinned `detektAppShell` suppressions are dev equipment** sitting in a
  production, wiring-only module — `DevPhotoSeeder` ×3, `DevGalleryWiper` ×2, and the policy probe in
  `SnapSyncRoot`.
- **An env trigger that does not fire is silent.** `simctl launch <dev> <bundle> KEY=VAL` passes ARGV, not
  environment, so every trigger invoked that way does nothing at all and says nothing about it. Finding out
  costs a log hunt; a channel call returns a status.
- **The channel has already made two of them dead** and nobody noticed. `SNAPSYNC_EXPORT_LOGS` is
  superseded by `/logs?process=extension`, and `SNAPSYNC_EVENT_LINK` reaches the same `shell.onOpenUrl`
  destination as the already-wired `onSceneContinueActivity` trigger, with more fidelity.

`:test:rig` shipped in `2026-08-09-add-rig-control-channel` and established the mechanism that makes this
possible: a build-time-only module contained by compilation, contributing its own call site, absent from
production builds entirely. This change finishes the job it started.

## What Changes

- **BREAKING (dev workflow): production Kotlin ends with zero `SNAPSYNC_*` literals.** Driving a device
  now requires `-Psnapsync.rig=true`. A shipped build becomes undriveable, which is the point.
- **The control channel is re-carved by actor** — `/os/*` (what the OS calls), `/user/*` (what a user taps),
  `/device/*` (the device under test). `/trigger` and `/triggers` are gone; the inventory routes are dropped
  in favour of the 404 body, which already carries each exclusion's reason.
- **Four triggers are deleted outright, needing no replacement command**: `SNAPSYNC_EXPORT_LOGS`
  (→ `/device/logs`), `SNAPSYNC_EVENT_LINK` (→ `/os/onSceneContinueActivity`), `SNAPSYNC_LEAVE`
  (→ `/user/leave`), `SNAPSYNC_CREATE_EVENT` (→ `/user/create` → `/user/confirmJoin`). Creating an event
  through the real user path deletes `HeadlessCreate`, `LaunchEnvMembership`, `CreateEventPayload` and
  `decodeCreateDirective` with it.
- **Four move into `/device`**: `reset`, `gallery/seed`, `gallery/wipe`, and the policy probe — which stops
  being a trigger that logs and becomes `GET /device/gallery`, returning the census, every asset, and the
  policy's verdict per asset.
- **`SNAPSYNC_FORCE_URLSESSION_UPLOAD` is deleted with no replacement here.** Restoring it as a runtime
  input belongs to the producer-resolution work; see Impact.
- **`SNAPSYNC_FORGE_STATE` stops being a mode of the shipped app.** Forge becomes its own Xcode target with
  its own entry point and framework, linking neither `:app:ios` nor the live graph — so `CompositionMode.Forge`,
  `ForgeShell` and the shell's outer mode switch are deleted, and forge inertness stops being fifteen no-op
  members and becomes something the binary cannot express.
- **The device identity becomes injectable** on hosts where the Keychain is unavailable, through a
  read-only App-Group fallback that production never writes.
- **Extension-registration failure stops being silent.** `setUploadJobExtensionEnabled` returns a `Boolean`
  and takes an `NSError**`; both are discarded today, so a failed registration leaves the screen at
  "Synchronization pending…" forever with no error anywhere.
- **The trigger-index guard inverts.** It asserts an exact inventory of `SNAPSYNC_*` literals in production
  Kotlin, and that inventory is empty.

## Capabilities

### New Capabilities
- `device-state-reset`: voiding this device's durable sync state — which four stores are cleared, which
  three download-row shapes are retained and why, that the download half runs under the download feature's
  own lock, and that the attestation credential is untouched. Currently stated only as a side effect of a
  launch variable in `ios-app-shell`.

### Modified Capabilities
- `ios-app-shell`: the six launch-environment trigger requirements and the forge-state requirement are
  removed; the composition-mode resolver reduces to the upload tier; the scene's memoization and the
  background-wake guarantees are unchanged.
- `architecture-guards`: the launch-trigger index requirement inverts from "the skill's index equals the
  literals in production Kotlin" to "production Kotlin declares none"; the shell gate's scanned roots gain
  the forge target.
- `module-architecture`: the build-time-containment law currently contrasts a contained module with "a
  dev/test launch trigger, which ships" — that contrast no longer holds.
- `diagnostic-logging`: the extension log reaches an operator through the channel rather than through a
  copy-into-`Documents` launch trigger; the rolled `.1` sibling is no longer reachable, stated.
- `ios-url-session-upload`: the development tier-force flag requirement is removed.
- `ios-photokit-upload`: a registration change that fails is reported rather than discarded, with the
  fresh-install `PHPhotosError 3201` on the leading disable named as expected.
- `ios-appstore-metadata`: gains the provenance the forge requirement used to carry — every committed raw
  is a capture of the real `StatusScreen` in a state the real reduction can reach.
- `device-identity`: an identity may be supplied to a host whose Keychain cannot serve one, filling an
  absence at the supplier and never overwriting a resolved id.

## Impact

**Modules.** `:app:ios` loses `DevPhotoSeeder`, `DevGalleryWiper`, both `applyLaunchEnv*` entry points,
the photo-library ordering gate and the policy probe. `:domain` loses `LaunchDirectives`, `WipeGallery.kt`,
`CreateDirective.kt`, `HeadlessCreate` and `LaunchEnvMembership`. `:test:rig` gains the three namespaces and
the gallery commands. A new `:app:ios:forge` module and `SnapSyncForge` Xcode target are added.

**Guards.** `KotlinShellGuardTest`'s pin table drops from eight entries to two. `RunbookSkillsTest`'s
trigger-index half inverts. `RigControlChannelTest` gains a second derived population from
`StatusContainerHost` and loses two exclusions whose entry points are deleted.

**Workflows.** `screenshots.yml` builds the forge target. It must not change what `-scheme iosApp` produces
for the simulator SDK. The six committed raws have no automated check — the capture run is its own gate.

**Runbooks.** The `ios-device` and `rig-channel` skills are re-cut at the boundary of the running app.

**Other workspaces.** `os-producer-deregistration` restores the upload tier as a runtime-readable `forced`
input and requires this change to land first; that input must survive an OS-initiated cold relaunch, or
`rig-simulator-host` — which depends on forcing the app-driven tier on a simulator — stays blocked after it
lands. Neither dependency is carried here.
