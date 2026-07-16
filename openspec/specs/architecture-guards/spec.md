# architecture-guards Specification

## Purpose

Executable enforcement for the structural invariants the Kotlin compiler cannot express and the module
graph cannot withhold — the rules that hold across modules, source sets, and build files, and that no
type signature can state.

It exists because such an invariant, held only by convention and review, silently shipped a crash. Two
properties govern whether SnapSync's background tiers work at all on a **locked** device — the state
they run against is normally locked, since the OS wakes them while the phone is idle:

- **Every Keychain item the app persists must be readable by background work on a locked device**
  (capability `device-identity`, capability `event-link`). Keychain accessibility is chosen
  per-item at the call site, so this is a property of *every* call site — provable only if there is
  exactly one module that may contain them.
- **The App-Group default data-protection class must never be raised.** The background tier's SQL
  ledger, download store, discovery cursor, and event-album map are readable while locked only because
  they inherit the iOS default. Raising it in an entitlements file is a one-line edit that reads as a
  security improvement and disables background sync entirely — silently, and only on locked devices.

Neither is expressible in a type, and neither failed loudly: the first shipped as a lock-time crash on
a real device, the second is a latent trap. This capability turns their structural half into ordinary
tests in a test-only module (`:test:architecture`) that gate `./gradlew build`, so a violation fails the
build for everyone instead of resting on a reviewer noticing. The guards are the containment half of
each invariant; the behavioral half (that the confined module's every query carries the required
accessibility class) is pinned by that module's own tests. Both halves together are what make the
property true of the whole app.

Decision record: `changes/archive/2026-07-14-fix-locked-device-keychain-access`.

## Requirements

### Requirement: Architecture guards are executable and gate the build

The project SHALL enforce, mechanically, structural invariants that the compiler cannot express. The
guards SHALL live in a test-only module (`:test:architecture`) and SHALL run as part of the canonical
check (`./gradlew build`), so a violation fails the build locally and in CI rather than relying on
review.

A guard SHALL fail loudly rather than vacuously: a guard that analyses source SHALL assert that the
source it scanned is non-empty, so that a parser or configuration regression cannot cause the guard to
pass by inspecting nothing.

#### Scenario: A violation fails the build
- **WHEN** the canonical check runs against a tree that violates a guarded invariant
- **THEN** the build fails and names the violating file

#### Scenario: A guard that scans nothing fails
- **WHEN** a source-scanning guard resolves an empty scope (e.g. its parser rejected the sources)
- **THEN** the guard fails rather than reporting success

### Requirement: Keychain access is confined to one module

All Keychain access SHALL be confined to a single module (`:domain:keychain`). No other module SHALL
reference the Keychain API (`SecItem*`, `kSecClass*`, `kSecAttr*`), whether by import **or** by
fully-qualified reference.

This containment is what makes the *"every Keychain item is readable by background work on a locked
device"* invariant provable rather than merely intended: it is the containment plus that module's own
test — that every query it builds carries the required accessibility class — which together establish
the property for every item in the app. Neither half is sufficient alone. Containment cannot be enforced
by the dependency graph the way Material 3 containment is (capability `design-system`), because the
Kotlin/Native platform libraries are ambient in every `iosMain` source set and there is no dependency to
withhold.

#### Scenario: A Keychain call outside the owning module fails the build
- **WHEN** any module other than `:domain:keychain` references `SecItemAdd`, `SecItemCopyMatching`,
  `SecItemUpdate`, `SecItemDelete`, or a `kSecClass`/`kSecAttr` constant
- **THEN** the guard fails the build

#### Scenario: A fully-qualified call is caught
- **WHEN** a module calls the Keychain API by fully-qualified reference and therefore imports nothing
- **THEN** the guard still fails the build

#### Scenario: The owning module is exempt
- **WHEN** `:domain:keychain` itself uses the Keychain API
- **THEN** the guard passes

### Requirement: The data-protection entitlement never raises the default protection class

Neither target's entitlements (`iosApp.entitlements`, `BackgroundUploadExtension.entitlements`) SHALL
set `com.apple.developer.default-data-protection` to `NSFileProtectionComplete`.

The background tier depends on the iOS default protection class for App-Group container files,
`NSFileProtectionCompleteUntilFirstUserAuthentication` — the guarantee that makes the SQL ledger, the
download store, the discovery cursor, and the event-album map readable while the device is locked.
Raising the default to `NSFileProtectionComplete` would make **every** file in both containers unreadable
while locked, disabling background upload and download entirely, silently, and only on locked devices —
while presenting as a security improvement.

#### Scenario: Raising the default protection class fails the build
- **WHEN** either entitlements file sets `com.apple.developer.default-data-protection` to
  `NSFileProtectionComplete`
- **THEN** the guard fails the build

#### Scenario: The current entitlements pass
- **WHEN** neither entitlements file sets the key at all, so both containers inherit the iOS default
- **THEN** the guard passes



### Requirement: The event-link domain agrees across the app and the backend

A test-only JVM guard SHALL assert that the event link's domain agrees across every place it appears:
the app's `applinks:` associated-domains entitlement, the app's `LINK_ORIGIN` constant, the Apple App
Site Association document the backend serves, and the backend's own domain constant (capability
`event-link`). No compiler and no module boundary can hold those four together.

Two of the four SHALL be **single-sourced** rather than merely guarded: `LINK_ORIGIN` SHALL be generated
from one Gradle property, and the entitlement's value SHALL be supplied from `Config.xcconfig`. The
backend's copy **cannot** be: `backend/` is a Deno tree deployed by a separate, path-scoped workflow that
ships code only and never config (capability `backend-deployment`), so nothing in the Gradle build can
reach it, and generating it would couple two deliberately independent pipelines. The guard therefore
exists to hold exactly the seam that single-sourcing cannot close.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

The guard SHALL fail loudly rather than vacuously: if a file it inspects has moved or been renamed, it
SHALL fail rather than silently scanning nothing.

#### Scenario: A drifted domain fails the build

- **WHEN** any one of the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's
  domain, or the backend's domain constant names a different host than the others
- **THEN** the guard test fails, naming the disagreeing values

#### Scenario: The guard is not vacuous

- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing domains pass

- **WHEN** all four locations name the same host
- **THEN** the guard passes
