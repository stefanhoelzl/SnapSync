## ADDED Requirements

### Requirement: The composition seam gate
The build SHALL fail when the function-typed field inventory of `AppPorts` or `UploadPorts` differs
from a pinned list held in `:test:architecture`, exact in **both** directions. A new function-typed
field fails until it is pinned with a stated reason it is not a port; a removed one fails until the
pin is deleted, so the inventory cannot outlive what it describes.

The gate pins an inventory rather than inspecting call targets, deliberately: whether a lambda
reaches out of the process is not decidable from its declared type — `downloadStagingRoot: () ->
String` and `deviceId: () -> String` are type-identical while one resolves a platform container and
the other returns a value the composition already holds. The pin records a human judgement once,
which is the same mechanism the shell gates use for complexity suppressions.

**What it does not cover, stated so a green run is not over-read:** it constrains what the
composition hands the core. It says nothing about what the OS hands the shell — registering an
`NSNotificationCenter` observer or submitting a `BGProcessingTaskRequest` is the shell being called
by the platform, not accessing it, and is out of scope.

#### Scenario: A function-typed field is added to a composition bundle
- **WHEN** `AppPorts` or `UploadPorts` gains a function-typed field that is not in the pinned
  inventory
- **THEN** the gate fails, naming the field, until it is given a port type or pinned with its reason

#### Scenario: A pinned seam is converted to a port
- **WHEN** a pinned function-typed field is replaced by a port type
- **THEN** the gate fails until its pin is removed, so the inventory shrinks with the code

#### Scenario: The gate is pointed at a bundle that no longer exists
- **WHEN** the scanned declarations resolve to zero fields
- **THEN** the gate fails as vacuous rather than passing, per "Gates fail closed on novelty"

#### Scenario: A third composition bundle appears
- **WHEN** a new `*Ports` bundle is declared in `compose/`
- **THEN** the gate fails until that bundle is listed with its file, because a bundle it does not
  know about is a third place the composition can hand the core a lambda unseen

### Requirement: The platform-identifier gate
The build SHALL fail when an Apple identifier appears in the **code** of `:domain`'s `model/`,
`ports/` or `feature/` zones. Comments and KDoc are **exempt**, and that exemption is what gives the
gate its signal: measured across those zones, scanning source including comments flags 48 files,
while scanning with comments stripped flags 5 — and all 5 are genuine. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. `CompositionMode`'s tier
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload tiers the pure resolver
  selects, not platform APIs the core calls, and a second tier is a new member rather than a new
  coupling.
- **deferred** — a real violation of the port law, left standing deliberately, which SHALL carry an
  expiry trigger. Today: the `Keychain` token in `ports/Keychain.kt`, `ports/ConfigPorts.kt` and
  `feature/album/AlbumMapMigration.kt` (the family whose reshape is out of scope, expiring with it),
  and `ports/OsReceipt.kt`'s `ReceiptDeadlines.URL_SESSION_EVENTS` (a naming slip whose two sibling
  deadlines are already neutral; it expires with the iOS 18–26.0 app-driven tier).

The scanned vocabulary SHALL keep the `Keychain` token rather than dropping it for a cleaner
baseline: because the pin list is exact in both directions, retaining it makes the deferred reshape
unable to land without deleting those pins, which is what attaches the debt to the work that removes
it.

**What it does not cover, stated so a green run is not over-read:** the gate is lexical. A decoder
over another system's values written in bare integers — a `when` over `0L`, `1L`, `2L` that is in
fact a `UIApplicationState` table — is indistinguishable from arithmetic and SHALL NOT be assumed
caught. The gate's hits are therefore not ranked by risk: it fires on named constants, which are the
safer kind, and is silent on unnamespaced integer tables, which are the kind that can return a wrong
answer to a second platform rather than a safe default.

#### Scenario: An Apple constant is introduced into a platform-free zone
- **WHEN** an `NS*`, `PH*`, `kSec*`, `UI*`, `AV*` identifier or an Apple product name appears
  outside a comment in `model/`, `ports/` or `feature/`
- **THEN** the gate fails, naming the file and the token

#### Scenario: A documented binding note is written
- **WHEN** a KDoc records how a neutral type is bound on iOS (for example, that an opaque payload is
  a `PHAssetResource` there)
- **THEN** the gate does not fire, because comments are exempt by design

#### Scenario: A pinned exception is removed from the code
- **WHEN** a pinned Apple identifier is deleted or moved into an adapter
- **THEN** the gate fails until its pin is removed, so the pin list cannot describe absent code

#### Scenario: Deferred debt is filed as accepted
- **WHEN** a pin is added for an identifier the owner intends to remove later
- **THEN** it belongs in the deferred list with an expiry trigger, not in the accepted list, so the
  pin inventory never reads as if the law had no outstanding violations

## MODIFIED Requirements

### Requirement: The migration's laws are permanent gates
Every law the migration beacon measured SHALL be enforced permanently in `:test:architecture`
under `./gradlew build`. (The module-architecture migration is complete; its beacon — the
detached burn-down module and the non-required `verify` job — measured zero on every law at the
finale and was deleted, per its own contract.) The promoted gates:

- **Module-set equality**: the `settings.gradle.kts` include set SHALL equal the
  `module-architecture` target module list exactly (a loud-when-stale list — the one the "Gates
  fail closed on novelty" requirement permits); adding or deleting a module fails until the list
  is consciously amended with the withholding argument in a `module-architecture` spec delta.
- **Mixed port/impl files**: no file under `adapter/`, `domain/`, or `ui/` SHALL declare an
  `interface` beside a Ktor or SQLDelight import — a port and its technology impl cohabiting is
  the seed of the pre-migration shape.
- **Deletion ledger**: the migration's retired dead weight SHALL stay dead — the zxing and
  kotlincrypto catalog entries, the `capability/` tree, `LedgerReader`, `LoggingPushReceiver`,
  `EventMetadataSource`, the Arrow/ArrowLevel duplicate
  enum, and any second `*Enrollment` uploader. Resurrection is not forbidden forever; it is
  forbidden **silently** — bringing an item back means deleting its guard row in the same commit,
  with the argument in the PR. The guard SHALL assemble its patterns so its own source never
  matches them (the beacon's self-match lesson).
  The `LeaveNotifier` interface, retired as single-implementation ceremony ("the class is the
  seam"), has been brought back under exactly that clause and its row deleted: a **port** is not an
  interface justified by a second implementation, it is the declared boundary where the core stops
  and an external system begins, and with the interface gone the composition carried the crossing as
  an opaque closure instead — invisible to every gate that reads types.
- **Shells** and **zones** are gated by their own standing requirements (the shell gates; the zone
  gates), now all armed and gating.

The flow-transcriber generation failure (capability `architecture-diagrams`) SHALL likewise be a
hard gate: an untranscribable flow fails `architectureDiagrams` and the freshness test under the
canonical build.

#### Scenario: A module is added without amending the target list
- **WHEN** a new `include(...)` lands in `settings.gradle.kts` with no matching edit to the
  module-set gate's target list
- **THEN** the gate fails, naming the drift and the withholding bar a new module must clear

#### Scenario: Retired dead weight grows back
- **WHEN** a retired declaration (or catalog entry, or the `capability/` tree) reappears anywhere
  in the scanned roots
- **THEN** the deletion-ledger gate fails, naming the resurrected item and its rationale

#### Scenario: A retired name comes back as a port
- **WHEN** a declaration the ledger retired is reintroduced because the judgement that retired it is
  overturned
- **THEN** its ledger row is deleted in the same commit and the reversal is argued in the change's
  decision record, so the resurrection is loud rather than silent
