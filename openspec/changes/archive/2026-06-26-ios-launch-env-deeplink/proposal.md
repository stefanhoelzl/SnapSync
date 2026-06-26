## Why

On-device testing is now driveable headless over USB — a developer launch can install,
**launch**, and **screenshot** the app with no root and no Mac (pmd3 `--userspace`, verified on
device). The one gap blocking a fully-headless per-build loop is **joining an event**: today the
only way to provision a config event is to tap a Camera-scanned `snapsync://` QR. An agent has no
way to subscribe to an event without a human tap.

## What Changes

- Add a **dev/test launch trigger**: on iOS app launch, if a `SNAPSYNC_DEEPLINK` process-environment
  variable is present and carries a valid `snapsync://config?…` URL, the app provisions **identically
  to a scanned deeplink** (decode → re-provision: clear ledger + discovery cursor, re-register the
  background-upload extension), reusing the existing `SnapSyncRoot.onOpenUrl` path verbatim.
- The variable is read **once per process launch** (a fresh cold launch with it set re-provisions
  again — the intended per-build re-trigger); it is **not** re-applied on view recreation within the
  same process.
- The path is **self-guarding**: a process environment variable is only injectable via a developer
  launch (`pymobiledevice3 developer dvt launch --env …`). App launches from SpringBoard / TestFlight
  carry no such variable, so the trigger is inert in production with no compile-time guard.
- Reframe the root `CLAUDE.md` *"On-device iOS"* section from "manual testing" to **agent-driveable
  over USB** — the `--userspace` (Python ≥3.14) unlock, the proven command set (install · launch ·
  screenshot · `SNAPSYNC_DEEPLINK` event-subscribe · pull `debug.log`/crashes · syslog), the headless
  loop, and what remains gated (taps need a signed WebDriverAgent; `processJobs()` timing is OS-owned).

Not in scope: taps/UI gestures (WDA), any change to the `snapsync://` URL contract or decoder, any CI
or build-flag change, any production-user-visible behavior.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ios-app-shell`: add a requirement that the app honors a `SNAPSYNC_DEEPLINK` launch-environment
  variable carrying a config deeplink, provisioning once per process launch identically to a scanned
  deeplink, inert when absent (dev/test trigger). The existing scanned-QR forwarding requirement is
  unchanged; this adds a second, parallel trigger source.

## Impact

- **Code**: `:app:ios` only — a small `SnapSyncRoot.applyLaunchEnvDeeplink()` (reads
  `NSProcessInfo.processInfo.environment`, forwards to `onOpenUrl`, guarded once-per-process via
  `by lazy`) plus a one-line `LaunchedEffect(Unit)` hook in `MainViewController`. **No Swift change.**
- **`deeplink-config`**: untouched — the decoder, contract, seams, and Keychain store are reused as-is.
- **Tests**: none added — the `onOpenUrl` provisioning path is already covered
  (`StatusContainerHostTest`, `ConfigDeeplinkTest`); the env wrapper is untestable iOS-shell wiring.
- **CI**: none — pure runtime env, no compile-time flag; the existing dev-IPA build simply rebuilds.
- **Docs**: root `CLAUDE.md` "On-device iOS" section reframed. `docs/design.md` untouched.
