## Why

`ports/Keychain.kt` is a port named for Apple technology, which the port law forbids ("named for the
need it serves — the name must remain correct if a second platform ships — never for the technology
satisfying it"). Its vocabulary leaks past the name: `migrateAccessibility()` and "accessibility
class" are `kSecAttrAccessible`, `KeychainRead.Unavailable(status: Int)` and
`KeychainUnavailable(status: Int)` carry an **`OSStatus`**, and the KDoc argues from
`errSecItemNotFound` / `errSecInteractionNotAllowed` and from "the Keychain survives app uninstall".

`enforce-port-boundary` found this, named it (D6), and deliberately did **not** fix it, for two
reasons: the port's consumers include `KeychainDeviceIdentity`, whose stored value is written once
and never rewritten (a wrong group, service or account is frozen permanently on a value whose loss is
unrecoverable — that shipped on 2026-07-20), and the simulator coverage that would make a reshape
verifiable did not exist. That coverage landed in `2b5eb54f`: `KeychainDeviceIdentityTest`,
`KeychainAttestStoreTest` and an extended `IosKeychainTest` now pin the item's real service, account
and access group, and the accessibility class every write carries. The blocker named in D6 is gone,
so its deferral expires.

## What Changes

- **The port is renamed for its need: `Keychain` → `SecureStore`** (`ports/SecureStore.kt`) — one
  addressed place to keep one small value so that it is confidential at rest, outlives the app
  install, and stays readable while the device is locked. `KeychainRead` → `SecureStoreRead`,
  `KeychainUnavailable` → `SecureStoreUnavailable`, `KeychainResolution` → `SecureStoreResolution`.
- **The `OSStatus` leaves the core.** `Unavailable(status: Int)` becomes `Unavailable(detail:
  String)` — an opaque diagnostic the adapter formats (`"OSStatus -25308"`) and that nothing in the
  core branches on. Making it a string is the point: a code invites a `when`, and the core has no
  business classifying another platform's error numbers.
- **The accessibility class leaves the core.** `SecureStoreRead.Found` carries a three-member
  `StoredProtection` (`BACKGROUND_READABLE` / `RESTRICTED` / `UNREPORTED`) instead of the raw class
  string, and `resolveOrMint` / `readExisting` / `needsMigration` lose their
  `requiredAccessibility: String` parameter — which class is required is the adapter's fact, not a
  value the core passes around. `Keychain.migrateAccessibility()` becomes
  `SecureStore.migrateProtection()`.
- **The three-state read survives unchanged in substance** (`Found` / `Absent` / `Unavailable`).
  "Absent" and "could not tell" have different consequences here — conflating them mints a second
  device identity — so the shape is the one thing this change must not simplify.
- **`needsMigration`'s justification is re-grounded.** It currently argues from "the Keychain
  survives app uninstall", an iOS-only property; the neutral form is that this store outlives the
  install by contract, so nothing in the device's remaining lifetime will rewrite an item written
  once.
- **Two deferred `PlatformIdentifierTest` pins are deleted** — `ports/Keychain.kt` and
  `feature/album/AlbumMapMigration.kt` — because the gate's pin list is exact in both directions and
  the token no longer appears in either file's code. The `deferred` baseline drops to one entry
  (`ports/OsReceipt.kt`).

**Not** in scope, deliberately: the stored item's identity. `kSecAttrAccessGroup`, the service, the
account and the accessibility class are **byte-identical** after this change. This is a rename and a
type reshape; no `SecItem*` query is re-addressed and no value is rewritten.

## Capabilities

### New Capabilities
<!-- none: this change adds no capability, it discharges a deferred violation of an existing one -->

### Modified Capabilities

- `module-architecture`: the "Absence is never silent" requirement cites `KeychainRead` and
  `Unavailable(status)` as one of its worked examples of a seam that already follows the law. The
  type is renamed and the status is gone, so the citation becomes false text in the contract of
  record.
- `architecture-guards`: the platform-identifier gate's **deferred** pin inventory is the spec's, and
  two of its three entries are discharged here. The requirement also carries D2's argument for
  keeping the `Keychain` token in the scanned vocabulary *so that this reshape could not land without
  deleting those pins* — that argument has now been paid off and should read as a completed one, not
  a pending one.

Not modified, checked: `device-identity` states its requirements in terms of the Keychain **item**
(its access group, service, account, accessibility class and resolution order) and the adapter class
`KeychainDeviceIdentity` — every one of which this change leaves exactly as it is. `event-album`
likewise never named the port type.

## Impact

- **`:domain`** — `ports/Keychain.kt` → `ports/SecureStore.kt` (renamed types, one enum added, the
  `requiredAccessibility` parameter dropped from three functions); `feature/album/AlbumMapMigration.kt`
  takes the neutral read type; `ports/AttestSeams.kt`'s absence contract stops naming an `OSStatus`;
  `compose/UploadCore.kt` logs the adapter's detail string instead of a status code.
- **`:adapter:ios:ext-safe`** — `IosKeychain` gains the two translations that left the core (status →
  detail string, accessibility class → `StoredProtection`) and keeps every query byte-identical;
  `KeychainDeviceIdentity`, `KeychainAttestStore` and `IosAlbumMapStore` follow the renames.
- **`:test:architecture`** — two deferred pins deleted from `PlatformIdentifierTest`.
- **Tests** — `KeychainResolveTest` (`commonTest`) and the three `iosTest` files follow the types.
  The assertions that pin the item's **address** and its **written attributes** are untouched, by
  construction: they are the safety net this change is checked against.
- **No behavior change of any kind.** No stored value, item address, query, accessibility class or
  wire format moves. One log line's wording changes (`status=-25308` → `OSStatus -25308`), and the
  adapter gains one diagnostic line naming a legacy item's real class — which the core used to carry
  inward only to print.
