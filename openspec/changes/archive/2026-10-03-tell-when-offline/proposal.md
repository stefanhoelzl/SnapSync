# Proposal

## Why

When SnapSync cannot use the network, nothing on screen says so. A joined member sees an ordinary
status line while nothing uploads or arrives, and a user creating or joining an event finds out only by tapping
and failing. The worst case is the one the member can fix themselves: iOS's per-app mobile-data switch turned off
for SnapSync (or an Android network restriction), which stops every transfer away from Wi-Fi without a single
error anywhere. The port that tells these cases apart now exists, held to a contract measured on the Android
emulator and the SE2 (`NetworkMonitor`, committed ahead of this change).

## What Changes

- The app tells the user, while it is open, when it has no usable network, naming which of two causes applies:
  **blocked** (the system withholds the network from SnapSync, a setting the user can change, offered as a way to
  open SnapSync's Settings page) or **offline** (the device has no network at all). A server that does not
  answer is not part of this; the app cannot see it.
- A drop of a few seconds shows nothing; the notice appears only once the network has been missing for a moment,
  and clears as soon as it returns.
- **Not joined**: the create screen and the join screen keep their normal content and show the notice in place
  of their hint or failure line, with Create / Join unavailable until the network returns. An invite opened
  without a network names the cause instead of the generic "could not load", has no Retry while there is no
  network, and loads the event by itself once it returns. A create or join already under way is never
  interrupted.
- **Joined**: a new status line, ranked below missing photo access and above everything else, says the network
  is missing. When it is blocked, tapping it opens SnapSync's Settings page; it is then the second tappable
  status line beside missing access.
- When the network returns while the app is open, the app clears the notice and resumes its work at once —
  the same work opening the app does — rather than waiting for the next trigger.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `sync-status`: a new app-wide requirement — the app tells the user when it cannot reach the network, by cause,
  debounced, foreground only, resuming its work when the network returns; the status line's priority gains the
  no-network line below missing access; the "only tappable status line" requirement becomes two lines (missing
  access, blocked network).
- `create-event`: Create is unavailable while there is no network, with the notice in place of the hint or
  failure line; the offline-create scenario becomes the server-unreachable case it actually is.
- `join-event`: Join is unavailable while there is no network; an invite opened without a network names the
  cause and loads by itself when the network returns, replacing the manual-Retry offline scenario.

## Impact

- **Composition**: `NetworkMonitor` joins `AppPorts`/`DevicePorts` (app process only; the extension does not take
  it), the Android manifest declares `ACCESS_NETWORK_STATE`, and the JVM root, the rig's launch-time adapters and
  `MockDevice` gain the network mock as a mocked system with an operator lever.
- **Core**: a service watches the port only while the app is in the foreground, debounces it and holds the
  current access; presentation folds it into `UiState` (a `SyncHealth` rung, the create and join layers); the
  return to a network triggers the foreground work and the join reload.
- **UI**: `:ui:screens` renders the notice on the create, join and joined screens, with the Settings action when
  blocked.
- **Tests and tools**: presentation and screen tests, integration tests over the rig's new lever, the world
  harness's lever.
- No backend change, no new dependency.
