# architecture-guards — delta for extract-adapter-modules

## MODIFIED Requirements

### Requirement: Keychain access is confined to one module

All Keychain access SHALL be confined to a single module (`:adapter:ios:ext-safe` — the
extension-safe adapter module, where the migration seated the Keychain impls; before migration
step 4 the owning module was `:domain:keychain`, which retains only the platform-free
`ProtectedData` seam). No other module SHALL reference the Keychain API (`SecItem*`, `kSecClass*`,
`kSecAttr*`), whether by import **or** by fully-qualified reference.

This containment is what makes the *"every Keychain item is readable by background work on a locked
device"* invariant provable rather than merely intended: it is the containment plus that module's own
test — that every query it builds carries the required accessibility class — which together establish
the property for every item in the app. Neither half is sufficient alone. Containment cannot be enforced
by the dependency graph the way Material 3 containment is (capability `design-system`), because the
Kotlin/Native platform libraries are ambient in every `iosMain` source set and there is no dependency to
withhold.

#### Scenario: A Keychain call outside the owning module fails the build
- **WHEN** any module other than `:adapter:ios:ext-safe` references `SecItemAdd`, `SecItemCopyMatching`,
  `SecItemUpdate`, `SecItemDelete`, or a `kSecClass`/`kSecAttr` constant
- **THEN** the guard fails the build

#### Scenario: A fully-qualified call is caught
- **WHEN** a module calls the Keychain API by fully-qualified reference and therefore imports nothing
- **THEN** the guard still fails the build

#### Scenario: The owning module is exempt
- **WHEN** `:adapter:ios:ext-safe` itself uses the Keychain API
- **THEN** the guard passes
