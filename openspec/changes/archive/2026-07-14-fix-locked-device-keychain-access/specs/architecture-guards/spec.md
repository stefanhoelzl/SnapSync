## ADDED Requirements

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
