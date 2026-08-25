## Why

The control channel (`:test:rig`) runs on exactly one host: the physical SE2. Three things that host
structurally cannot do are now the things most worth testing.

- **Two members of one event at once.** SnapSync is a photo-*sharing* app, and every multi-member
  behaviour is today exercised only in `:test:world` against fakes. One phone cannot be two members.
- **Wipe and seed a photo library headlessly.** `SNAPSYNC_WIPE_GALLERY` needs a physical tap on the
  platform's own delete confirmation.
- **Set permission state headlessly.**

A simulator can do all three, and is disposable and parallel besides. It is **not** a device
replacement — several properties stay device-only and this change states which.

It is also the gate for the three changes that follow it in the same plan: an extension-shaped second
process, Gherkin scenarios, and two-member runs each inherit decisions made here.

Standing in the way is one blocker with a long tail. An **unsigned** simulator build has no App-Group
container — measured: `IllegalStateException: App Group container 'group.app.snapsync' unavailable`
— which is why `screenshots.yml` builds with `CODE_SIGNING_ALLOWED=NO` and forge boots no live stack.
An ad-hoc signature carrying an app-group entitlement fixes it, but the **Keychain group cannot come
along**: `keychain-access-groups` makes the app un-launchable in every signing form measured. Device
identity lives in that group, so without it the app resolves no id, enrolls nowhere, and joins
nothing.

## What Changes

- **A signed simulator build.** Same `iosApp` scheme plus a post-build ad-hoc `codesign`, carrying a
  committed App-Group-only entitlements plist. The embedded extension is signed before the app.
  Nothing is added to `project.pbxproj`.
- **Device identity resolves on a host with no reachable Keychain**, by binding the identity store
  per **compilation target** rather than by deciding anything at runtime. `iosArm64` keeps the
  addressed-Keychain binding, unchanged. `iosSimulatorArm64` — a target whose output only ever runs on
  a simulator — binds an App-Group-file store instead, which mints, persists and reads back normally,
  so the `device-identity` contract is satisfied over a different port implementation rather than
  altered. A device binary contains no route to the simulator binding.
- **A local backend a simulator can reach.** `deno task dev:local` runs in the sandbox, reverse-forwarded
  over the ssh-mac tunnel; the build is pointed at `http://127.0.0.1:8080/api/v1` by an `xcodebuild`
  override. `api/src/dev` mints presigned download URLs from its actual origin instead of a hardcoded
  `https`, without which every simulator download fails on TLS.
- **The channel becomes addressable per instance.** `18099` stays the device default; the rig also
  publishes its actually-bound port into its own container, so two simulators are reachable
  independently and a port collision surfaces as a missing file rather than a plausible answer from
  the wrong instance.
- **Two open measurements settled** — whether the OS relaunches a terminated app to deliver
  `handleEventsForBackgroundURLSession`, and whether downloads are inert on a simulator. Both are
  taken from one in-flight download. `fix-download-session-lifecycle` D5's closing limitation is
  superseded rather than edited.
- **An `ios-simulator` skill**, whose headline is that it needs **no device lease**.

No launch trigger is added. No `SNAPSYNC_*` literal enters production Kotlin.

## Capabilities

### New Capabilities

None. The simulator host is dev infrastructure that is a **lens**, which this repo deliberately leaves
unspec'd — the posture of `:test:harness-driver`, `ssh-mac.yml`, `api/src/dev`, and `:test:rig` itself.

### Modified Capabilities

- `architecture-guards`: the pinned runtime-identity inventory extends to a third entitlements
  surface, so the App-Group id cannot be re-valued while a simulator build keeps the old one.

`device-identity` is deliberately **not** modified. Its requirements describe behaviour over the
`SecureStore` port, and substituting a port implementation for a test-only compilation target is not a
change to that behaviour — the same reasoning under which `:test:world` substitutes every port and no
spec records it. The simulator binding mints, persists and reads back exactly as the contract requires;
it simply does so somewhere the Keychain is reachable. See design D7, which states the argument
explicitly so a reviewer can disagree with it rather than discover it.

## Impact

- **`:adapter:ios:ext-safe`** — an `expect` identity-store selector in `iosMain`, with the
  Keychain `actual` in `iosArm64Main` and an App-Group-file `actual` in `iosSimulatorArm64Main`. The
  device target's compiled output is unchanged.
- **`:test:rig`** — the bound-port publication.
- **`iosApp/`** — a committed `simulator.entitlements`; `Config.xcconfig`'s ATS comment corrected.
- **`:test:architecture`** — `RuntimeIdentityTest`'s entitlements-file list.
- **`api/src/dev`** — download-URL origin; `fs-storage`'s guard prefix moves with it.
- **`scripts/`, `.claude/skills/`, `CLAUDE.md`** — the signing step, the new skill and its runbook
  pointer, and the simulator port line in `rig-channel`.
- **No shipped behaviour changes** on any device: every new path is unreachable without a file no
  production build writes.
