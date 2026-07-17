# architecture-guards — delta for pin-runtime-identity-and-zone-gates

## ADDED Requirements

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
- **App-Group `NSUserDefaults` keys** `discovery.changeToken`, `rejoin.joinedEventId`,
  `app.snapsync.album.map`.
- **Database filenames** `ledger.db`, `downloads.db`.
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

### Requirement: The zone gates exist before their zones, pending and self-arming

The five zone gates SHALL exist in `:test:architecture` **before** the zones they guard exist —
the gates of requirement "The zone gates": model-purity, ports→model, feature-blindness,
flow-no-ports, presentation-imports — following the fake-honesty gate's self-arming pattern:

- While a gate's scope directory does not exist, the gate SHALL report itself pending — visibly
  (a printed PENDING line naming the scope), never vacuously green-by-silence.
- Once the scope directory exists, the gate SHALL fail if it scans zero sources (the non-vacuity
  twin), and SHALL enforce its import law with **zero gate edits** — migration steps arm gates by
  creating code, never by writing gates mid-move.
- The scan scopes are pinned now, as named assumptions in each gate:
  `domain/src/*/kotlin/**/model/`, `…/ports/`, `…/feature/`, `…/flow/`, `…/compose/` (the
  `:domain` module roots at `domain/`, its `src/` beside the legacy submodule directories until
  they empty), and `ui/presentation/src/**` for the presentation gate.
- The presentation gate SHALL enforce the import-level approximation of its law:
  `ui/presentation` sources never reference the `ports/` or `flow/` packages (imported or
  fully-qualified); the finer no-feature-command-invocation rule remains a review concern until
  it has a mechanical form.

#### Scenario: A gate whose zone does not exist yet

- **WHEN** the guards run while `domain/src` (or `ui/presentation/src`) does not exist
- **THEN** the gate prints a PENDING line naming its absent scope and passes, rather than
  failing or passing silently

#### Scenario: A zone is born and the gate arms itself

- **WHEN** a migration step creates the first file under a gate's pinned scope
- **THEN** the gate enforces its import law on that file with zero edits to the gate

#### Scenario: A scope exists but the scan is empty

- **WHEN** a gate's scope directory exists but the gate's file walk matches nothing
- **THEN** the gate fails — a layout drift must surface as red, not as a gate that passes
  forever
