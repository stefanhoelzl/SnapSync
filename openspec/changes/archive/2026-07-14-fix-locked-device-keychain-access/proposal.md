## Why

Every Keychain item the app writes uses the iOS default accessibility class,
`kSecAttrAccessibleWhenUnlocked` — no `kSecAttrAccessible` is set anywhere in the repo. So on a
**locked device** the app and the extension cannot read the device id, the event config, or the album
map. This is not an edge case: background work (a `BGProcessingTask`, a silent push, a background
`URLSession` completion, and the OS-scheduled PhotoKit upload extension) runs precisely when the
device is idle — which usually means locked. Two failures follow, one loud and one silent:

- **The app aborts.** `KeychainDeviceIdentity.readValue()` maps *any* non-success status to `null`
  ("no id stored"), so a locked read mints a **new** UUID, tries to `SecItemAdd` it, fails for the
  same reason, and `check(status == errSecSuccess)` throws inside an unguarded `scope.launch` on
  `Dispatchers.Main`. Kotlin/Native terminates the process. This is the confirmed root cause of the
  TestFlight crash in build 297 (`SIGABRT`, symbolicated: grant collector → lazy chain →
  `KeychainDeviceIdentity` → `writeValue`), on a device the report marks `"isLocked": 1`.
- **The extension churns.** A locked config read returns `null`, which `UploadExtensionRoot.process()`
  reads as *"not joined / left"* and passes to `reconciler.reconcile(null)` — the leave-side path that
  **clears the `joinedEventId` marker**. The next unlocked cycle then sees a marker mismatch and
  performs a full re-join reconcile: a device LIST, an atomic ledger clear-and-seed, and a
  **discovery-cursor clear that forces a full PhotoKit re-enumeration**. Nothing re-uploads (the seed
  preserves dedup), but the marker never settles and every cycle pays for a full re-walk.

The read contract is also a latent data-integrity hazard independent of the lock: because any error
reads as "absent", a transient failure whose subsequent write *succeeds* would mint a **new device
id** — orphaning this device's `/files/devices/<deviceId>/` partition and its ledger, and re-uploading
the entire post-cutoff library. Today the crash is the only thing preventing that.

## What Changes

- **New `:domain:keychain` leaf** (cross-cutting infrastructure, mirroring `:domain:logging`): the
  single `SecItem*` implementation in the repo. `commonMain` holds the pure read/mint/migrate decision
  logic (tested in `commonTest`, so it runs on JVM **and** `iosSimulatorArm64`); `iosMain` holds the
  one Keychain adapter.
- **Keychain items become background-readable**: every item is written with
  `kSecAttrAccessibleAfterFirstUnlock`. Deliberately **backup-restorable** — see `design.md`.
- **In-place migration, same id**: on a successful read of an item whose accessibility class is wrong,
  the class is updated (`SecItemUpdate`) **without changing the value**. Existing devices heal on their
  next unlocked read. No re-mint, no identity churn, no re-upload.
- **Three-state read**: only `errSecItemNotFound` means *absent → mint*. Every other status is
  `Unavailable`. **Minting on error becomes impossible**, closing the identity-churn hazard.
- **`Unavailable` is not `None`.** The config seam distinguishes *joined* / *not joined* /
  *unreadable*. On `Unavailable` the extension **skips the whole cycle** — no reconcile, no marker
  clear, no upload — so an unreadable config can never be mistaken for a leave.
- **The app defers instead of failing**: the app process consults
  `UIApplication.isProtectedDataAvailable` and, when protected data is unavailable, defers background
  work and resumes it on `protectedDataDidBecomeAvailable` (which fires the moment the user unlocks)
  rather than waiting for the OS's next wake. The extension has no `UIApplication` and keeps the
  typed-exception / skip-cycle path.
- **The album map leaves the Keychain** for the App-Group `NSUserDefaults` suite (mirroring
  `IosDiscoveryStore`), which inherits `NSFileProtectionCompleteUntilFirstUserAuthentication` and is
  therefore background-readable by construction. Its legacy Keychain item is migrated once, then
  deleted. No `event-album` requirement changes: that spec pins a *shared store that survives leave*,
  never a Keychain.
- **New `:test:architecture` module** with two guards that make this bug class unwritable rather than
  merely fixed (see `design.md` for why both are needed and neither suffices alone):
  - Konsist: `SecItem*` may not appear outside `:domain:keychain`.
  - A plist assertion: neither `.entitlements` file may set `default-data-protection` to
    `NSFileProtectionComplete` (which would make **every** App-Group file — ledger, download store,
    discovery cursor, album map — unreadable while locked, killing the entire background tier).
- **Diagnostics**: every background entry point (`runDownloadBackstop`, `onSilentPush`,
  `handleBackgroundUrlSession`, the extension's `process()`) logs protected-data availability and the
  Keychain status code to `debug.log`, so a real locked wake is *observable* on any device with one
  `apps pull` — the end-to-end locked wake cannot be exercised in any test.

## Capabilities

### New Capabilities

- `architecture-guards`: mechanically-enforced structural invariants that a compiler cannot express —
  Keychain access confined to one module, and the data-protection entitlement never raised to
  `NSFileProtectionComplete`. Lives in the test-only `:test:architecture` module and runs under
  `./gradlew build`.

### Modified Capabilities

- `device-identity`: the id SHALL be readable by background work on a locked device (after first
  unlock); a read error SHALL NOT be read as "absent" and SHALL NOT mint; an item written under a
  weaker accessibility class SHALL be migrated in place without changing its value.
- `deeplink-config`: the config store SHALL be readable on a locked device (after first unlock), and
  an **unreadable** config SHALL be distinguishable from an **absent** one — an unreadable config
  SHALL NOT be treated as "not joined" and SHALL NOT drive the leave-side reconciliation.
- `ios-app-shell`: when protected data is unavailable, the app SHALL defer background work and resume
  it when protected data becomes available, rather than failing or dropping it.

## Impact

- **New modules**: `:domain:keychain` (jvm + ios), `:test:architecture` (jvm, test-only).
- **Modified**: `:capability:device-id` (`KeychainDeviceIdentity` → the shared adapter, typed
  `KeychainUnavailable`), `:capability:config` (`KeychainConfigStore` → three-state read),
  `:capability:album` (`IosAlbumMapStore` → App-Group `NSUserDefaults` + one-shot migration),
  `:app:ios` (`SnapSyncRoot`: protected-data deferral, entry-point diagnostics),
  `:app:ios:photokit-extension` (`UploadExtensionRoot`: skip the cycle on `Unavailable`).
- **Dependencies**: Konsist (test-only, `:test:architecture`).
- **Seam contracts**: `DeviceIdentity.deviceId(): String` is **unchanged** (the typed exception is
  caught at the two composition roots, so the five consumers — upload, download, join, push,
  membership — are untouched). `ConfigSource` gains a three-state read.
- **Not covered here**: the uncaught-coroutine-exception class (an exception in any `scope.launch` on
  `Dispatchers.Main` terminates a Kotlin/Native process) is a separate change. That one makes a
  residual throw non-fatal; this one makes the throw unreachable. They are independent and may ship in
  either order.
