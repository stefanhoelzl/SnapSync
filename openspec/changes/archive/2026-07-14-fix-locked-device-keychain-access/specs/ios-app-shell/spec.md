## ADDED Requirements

### Requirement: Background work defers while protected data is unavailable

The app process SHALL consult `UIApplication.isProtectedDataAvailable` before performing background
work that reads protected state (the Keychain-backed device id and event config). When protected data
is **unavailable** — the device has not been unlocked since boot — the app SHALL **defer** that work
rather than failing it or dropping it, and SHALL resume it when the system posts
`UIApplicationProtectedDataDidBecomeAvailable`, which fires as soon as the user unlocks.

Deferring SHALL NOT mint a device id, SHALL NOT write any Keychain item, and SHALL NOT clear or reset
any persisted state.

The upload extension has no `UIApplication` (the API is unavailable to app extensions). In the
extension process an unavailable protected-data read SHALL instead surface as an unavailability error
and the cycle SHALL be skipped cleanly, per capability `deeplink-config` (*An unreadable config is not
an absent config*).

#### Scenario: A background wake before first unlock defers rather than failing
- **WHEN** the app is woken in the background (a `BGProcessingTask`, a silent push, or a background
  `URLSession` completion) while protected data is unavailable
- **THEN** the work is deferred, no Keychain write or mint occurs, no persisted state is cleared, and
  the process does not terminate

#### Scenario: Deferred work resumes at unlock
- **WHEN** protected data becomes available after such a deferral
- **THEN** the deferred background work runs, rather than waiting for the operating system's next wake

#### Scenario: Work proceeds normally once protected data is available
- **WHEN** the app is woken in the background while the device is locked but has been unlocked at least
  once since boot
- **THEN** protected data is available, the device id and config are read, and the work proceeds without
  deferral

### Requirement: Background entry points record protected-data state

Every background entry point of both processes SHALL log, to the device diagnostic log (capability
`diagnostic-logging`), the protected-data state it observed. The entry points are the app's download
import-tail backstop, its silent-push handler, its background-`URLSession` handler, and the extension's
`process()`.

The **app** SHALL log protected-data availability directly (it can ask `UIApplication`). The
**extension** cannot: `UIApplication` is unavailable to app extensions and the platform offers no
equivalent, so it SHALL instead log the status returned by each Keychain read it performed — the only
observable proxy available to it, and the one that distinguishes *unreadable* from *absent*.

An end-to-end background wake on a **locked** device cannot be exercised by any test: the simulator has
no lock state, and a background task's scheduling is owned by the operating system and cannot be forced.
These diagnostics are therefore the only means of confirming, from a real device, that background work
reached its protected state — and of diagnosing it when it does not.

#### Scenario: A locked background wake is observable after the fact
- **WHEN** background work runs on a locked device and the device log is subsequently pulled
- **THEN** the log states, for that invocation, whether protected data was available (in the app) or what
  status each Keychain read returned (in the extension)

#### Scenario: A failed protected read is attributable to its trigger
- **WHEN** a Keychain read fails during background work
- **THEN** the logged line carries the entry-point prefix of the trigger that started it, so the failure
  is traceable to the backstop, the silent push, the URL-session handler, or the extension cycle
