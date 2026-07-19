# architecture-guards — delta for complete-architecture-migration

## ADDED Requirements

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
  `EventMetadataSource`, the `LeaveNotifier` interface ceremony, the Arrow/ArrowLevel duplicate
  enum, and any second `*Enrollment` uploader. Resurrection is not forbidden forever; it is
  forbidden **silently** — bringing an item back means deleting its guard row in the same commit,
  with the argument in the PR. The guard SHALL assemble its patterns so its own source never
  matches them (the beacon's self-match lesson).
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

## MODIFIED Requirements

### Requirement: The shell gates
The build SHALL enforce zero conditionals in `:app:*` Kotlin via a detekt complexity gate
(threshold: no function above cyclomatic complexity 1 beyond pinned wiring forms), **gating**
(`ignoreFailures = false`, wired into `check`) over all production `:app:*` source sets including
`iosMain`, asserted by a test with a non-vacuity floor (`KotlinShellGuardTest`: the scanned source
roots exist and are non-empty — a stale source list after a module rename must fail, never pass
vacuously). Because detekt honors `@Suppress`, the suppression IS the Kotlin pin mechanism, and
the same guard SHALL pin the suppression inventory exactly, in both directions (per file, by
count): a new `@Suppress("CyclomaticComplexMethod")` fails until it is argued into the table with
a forcing proof at the suppression site, and a removed one fails until the table shrinks. The
Swift shells SHALL be guarded by a pinned-structure text check: decision keywords (`if`, `guard`,
`switch`, `??`) may appear only at the explicitly pinned occurrences, each pin carrying its
forcing proof in the failure message.

#### Scenario: A decision creeps into a shell
- **WHEN** a branch is added to `:app:*` Kotlin or an unpinned decision keyword to a Swift shell
- **THEN** the canonical build fails (the detekt gate or the Swift pin check) and the message
  names the tested zone the decision belongs in

#### Scenario: A suppression sidesteps the Kotlin gate
- **WHEN** a new `@Suppress("CyclomaticComplexMethod")` appears in the shells without a pin row
- **THEN** the pin-inventory guard fails — a suppression is exactly as loud as a branch

### Requirement: Runtime identity is pinned

`:test:architecture` SHALL pin every runtime-identity literal — a string the OS or the installed
base holds on its side, so that changing it strands or corrupts state on devices already in the
field. Each pin SHALL assert the literal appears **exactly once** in production Kotlin source
(main source sets; test sources and `build/` excluded) with its exact value, plus its pinned
occurrences in the non-Kotlin surfaces named below. Any delta — a disappearance, a value change,
or a new occurrence — SHALL fail the build.

The pinned inventory (this list is the contract of record; adding, removing, or re-valuing a pin
is a spec change to this requirement, deliberately):

- **App-Group id** `group.app.snapsync` — once in Kotlin, plus once in each of the two
  entitlements files (`iosApp.entitlements`, `BackgroundUploadExtension.entitlements`).
- **Keychain entries**, pinned as (service, account) **pairs** — the pair is the unit of
  identity, so a cross-swap of accounts between services fails even though every individual
  string survives: (`app.snapsync.deviceid`, `deviceid`), (`app.snapsync.config`, `eventconfig`),
  (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`),
  (`app.snapsync.album`, `albummap`). Each pair SHALL match exactly once in production Kotlin.
  The config pair's one seat is the legacy fallback seat (`KeychainConfigReader` — read + the
  leave-path delete only; the migration finale ended the 11a write-through, so no config value is
  ever written to the Keychain again): the fallback is the installed base's update path under the
  ship-at-once model, and the pair's pin dies with the designated post-ship Stage-2 change that
  deletes the fallback (capability `event-rejoin-reconciliation`).
- **App-Group `NSUserDefaults` keys** `discovery.changeToken`, `rejoin.joinedEventId`,
  `app.snapsync.album.map`.
- **Database filenames** `ledger.db`, `downloads.db`.
- **Config filename** `eventconfig.json` — the App-Group config file of record (capability
  `event-link`; the only config storage). Re-valuing it reads every joined device's file as
  absent: a **false leave on every joined device**.
- **Device-manifest App-Group layout**: directory `device-manifest`, files `accumulator.json`,
  `last-uploaded.json` — the manifest is the physical fact of membership; losing the accumulator
  shrinks the event union.
- **BGTask identifiers** `app.snapsync.upload.heartbeat`, `app.snapsync.download.backstop` —
  once in Kotlin AND once in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`; the guard
  SHALL assert the Kotlin value and the plist value agree, because drift between them silently
  kills that background tier (the OS rejects an unpermitted submit; nothing raises).
- **Background `URLSession` identifiers** `app.snapsync.upload.session`,
  `app.snapsync.download.bg` — the OS reattaches in-flight transfers by these across relaunch.
- **Framework `baseName`s** `SnapSyncKit`, `SnapSyncUploadKit` — once each, in
  `build.gradle.kts` files (the scan surface for this pin is build files, not Kotlin).

The guard SHALL match source text (a fully-qualified or string-template occurrence counts), and
per the existing non-vacuity requirement SHALL fail if any scanned surface resolves to zero
files.

#### Scenario: A moved literal drifts

- **WHEN** a migration step moves a file and the App-Group id (or any pinned literal) arrives
  with a changed value, or does not arrive at all
- **THEN** the pin guard fails the build, naming the literal and the expected count

#### Scenario: A literal is duplicated

- **WHEN** a second production Kotlin occurrence of a pinned literal appears (e.g. a private
  copy instead of an import of the consolidated const)
- **THEN** the pin guard fails — exactly-once is the invariant that keeps future drift
  single-sited

#### Scenario: A keychain cross-swap preserves every string

- **WHEN** an edit moves account `token` from service `app.snapsync.attest` to another service,
  leaving every individual string present somewhere
- **THEN** the pair pin fails, because the (service, account) pair no longer matches

#### Scenario: A BGTask id diverges between Kotlin and Info.plist

- **WHEN** the Kotlin constant and the `BGTaskSchedulerPermittedIdentifiers` entry for a BGTask
  id no longer agree
- **THEN** the pin guard fails, naming both values

### Requirement: Gates fail closed on novelty
Every architecture gate SHALL derive its scope from the repository's structure at test runtime —
directory listings for feature enumeration, package patterns for zones, "everything not
allowlisted" for purity — never from a hand-maintained inclusion list. The only permitted lists
are loud-when-stale: the end-state module list (compared against the build's own include set)
and the per-zone library allowlists. Every gate SHALL keep a non-vacuity twin proving it
scanned a non-empty scope. Zone gates SHALL match source text (fully-qualified references import
nothing), not import lists.

#### Scenario: New code is born in scope
- **WHEN** a new feature package, flow file, port, or adapter is added
- **THEN** every applicable gate covers it with zero gate edits

#### Scenario: A gate's scope silently empties
- **WHEN** a rename or restructure removes everything a gate scans
- **THEN** the gate's non-vacuity twin fails rather than the gate passing forever

## REMOVED Requirements

### Requirement: The migration beacon is red until the migration completes

**Reason**: The beacon measured zero on every law at the finale and was deleted with its module and the verify job, per its own completion contract; each law moved into :test:architecture as a permanent gate (the ADDED requirement).
