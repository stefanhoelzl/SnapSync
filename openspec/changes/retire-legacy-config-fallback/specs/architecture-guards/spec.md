# architecture-guards — delta for retire-legacy-config-fallback

> **Ordering:** the *platform-identifier gate* requirement below is introduced by the
> `enforce-port-boundary` change, which is implemented and committed but **not yet synced**. Sync
> that change before this one, or the `MODIFIED` will fail loudly ("MODIFIED failed … not found")
> rather than silently. The *Runtime identity is pinned* requirement is already in the main spec and
> has no such dependency.

## MODIFIED Requirements

### Requirement: The platform-identifier gate
The build SHALL fail when an Apple identifier appears in the **code** of `:domain`'s `model/`,
`ports/` or `feature/` zones. Comments and KDoc are **exempt**, and that exemption is what gives the
gate its signal: measured when the gate was introduced, scanning those zones including comments
flagged 48 files while scanning with comments stripped flagged 5 — and all 5 were genuine. One has
since been paid off (below), leaving a baseline of 4. Every remaining site SHALL be
pinned, exactly in both directions, and every pin SHALL state its reason.

The pinned baseline is **not zero**, and the pins SHALL be split into two kinds, because reading them
as one launders debt into design:

- **accepted** — a judgement the owner stands behind, with no expiry. `CompositionMode`'s tier
  members (`PHOTOKIT`, `URL_SESSION`) are the only entry: they name upload tiers the pure resolver
  selects, not platform APIs the core calls, and a second tier is a new member rather than a new
  coupling.
- **deferred** — a real violation of the port law, left standing deliberately, which SHALL carry an
  expiry trigger. Today: the `Keychain` token in `ports/Keychain.kt` and
  `feature/album/AlbumMapMigration.kt` (the family whose reshape is out of scope, expiring with it),
  and `ports/OsReceipt.kt`'s `ReceiptDeadlines.URL_SESSION_EVENTS` (a naming slip whose two sibling
  deadlines are already neutral; it expires with the iOS 18–26.0 app-driven tier).
  `ports/ConfigPorts.kt` carried the same `Keychain` pin and **no longer does** — not through the
  family's reshape, but because the Stage-2 change deleted `configReadFrom`, the file's only
  `KeychainRead`-typed function, with the legacy fallback it served (capability
  `event-rejoin-reconciliation`). A deferred pin may be discharged by whatever removes the code; the
  expiry trigger is a floor, not a schedule.

The scanned vocabulary SHALL keep the `Keychain` token rather than dropping it for a cleaner
baseline: because the pin list is exact in both directions, retaining it makes the deferred reshape
unable to land without deleting those pins, which is what attaches the debt to the work that removes
it. It is also what made the `ConfigPorts` discharge **visible**: the gate failed on a stale pin the
moment the code went, rather than letting the receipt outlive the debt.

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

#### Scenario: A deferred pin's code is deleted before its expiry trigger fires
- **WHEN** unrelated work removes the code a deferred pin describes — as the Stage-2 fallback
  deletion removed `ports/ConfigPorts.kt`'s `KeychainRead` use ahead of the port family's reshape
- **THEN** the gate fails on the stale pin, and the pin is deleted with that work rather than
  waiting for the trigger it was filed under

#### Scenario: Deferred debt is filed as accepted
- **WHEN** a pin is added for an identifier the owner intends to remove later
- **THEN** it belongs in the deferred list with an expiry trigger, not in the accepted list, so the
  pin inventory never reads as if the law had no outstanding violations

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
  string survives: (`app.snapsync.deviceid`, `deviceid`),
  (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`),
  (`app.snapsync.album`, `albummap`). Each pair SHALL match exactly once in production Kotlin.
  The config pair (`app.snapsync.config`, `eventconfig`) was **retired from this inventory** by the
  Stage-2 change that deleted the read-only legacy-Keychain fallback (capability
  `event-rejoin-reconciliation`): its one seat was that fallback, and with it gone the pair appears
  in production Kotlin **nowhere**, which an exactly-once pin cannot express. The config's runtime
  identity is now carried entirely by the `eventconfig.json` pin below.
- **Keychain access group** — the shared group the device-id item is addressed with (capability
  `device-identity`). Pinned once in production Kotlin, and cross-checked against the **suffix**
  declared in each of the two entitlements files together with `TEAM_ID` from `Config.xcconfig`:
  the guard SHALL assert the Kotlin literal equals `<TEAM_ID>.` followed by the entitlements'
  declared group, and that both entitlements files declare the same group. Drift here does not
  fail loudly — the item is written to a *different real group*, both processes still read
  successfully, and each simply reads a different item. That is the split-identity fault, which
  is invisible to every existing gate and unrecoverable once written.
- **The unscoped-Keychain inventory** — the guard SHALL pin, as an exact set, which Keychain seats
  search **without** naming an access group. That set SHALL be
  (`app.snapsync.attest`, `token`), (`app.snapsync.attest`, `keyid`),
  (`app.snapsync.album`, `albummap`); and the device-id seat
  SHALL NOT be in it. Unscoped search is bounded, not forbidden: the attest pair and album map
  remain unscoped deliberately — the attest token is demonstrably read cross-process today, and the
  album map is a self-healing cache. What the pin forbids is a **new** unscoped seat appearing by
  default, which is how implicit placement spread. Adding, removing, or re-scoping an entry is a
  spec delta to this requirement. The config seat left this set with the Stage-2 fallback deletion,
  and because the set is exact in both directions, a *reconstructed* unscoped config seat SHALL fail
  the build. **Stated blind spot:** a config seat reconstructed **scoped** would not — scoped sites
  are checked only for the device-id seat's presence, not pinned as a set. That gap is narrow by
  construction (a scoped read cannot find the unscoped items pre-11a builds wrote, which is the only
  thing such a seat could be after) and is named here rather than left to be discovered.
- **App-Group `NSUserDefaults` keys** `discovery.changeToken`, `rejoin.joinedEventId`,
  `app.snapsync.album.map`.
- **Database filenames** `ledger.db`, `downloads.db`.
- **Config filename** `eventconfig.json` — the App-Group config file of record (capability
  `event-link`; the only config storage). Re-valuing it reads every joined device's file as
  absent: a **false leave on every joined device**.
- **Device-manifest App-Group layout**: directory `device-manifest`, file `last-uploaded.json` —
  the skip-if-unchanged record of the manifest this device last published successfully. The manifest
  itself is a projection of the upload ledger's `COMPLETED` rows (capability `sync-ledger`), so this
  is the layout's only file: re-valuing either name reads the previously-written state as absent and
  abandons it in the container. A device-global accumulator file is deliberately **not** in this
  inventory — there is none; pinning one would fail the exactly-once assertion forever, and a stale
  `accumulator.json` an older build left in the container is inert.
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

#### Scenario: The access group disagrees with the entitlements

- **WHEN** the Kotlin access-group literal, the group declared in the entitlements files, or
  `TEAM_ID` are edited so they no longer compose to the same string, or the two entitlements
  files declare different groups
- **THEN** the pin guard fails, naming the Kotlin value and the composed entitlements value

#### Scenario: A new unscoped Keychain seat appears

- **WHEN** a production Keychain seat outside the pinned inventory is constructed without naming an
  access group, or a pinned unscoped seat is silently re-scoped
- **THEN** the guard fails, listing the expected and found inventories — implicit placement may only
  change deliberately

#### Scenario: The retired config seat is reconstructed unscoped

- **WHEN** production Kotlin again constructs an unscoped Keychain seat for
  (`app.snapsync.config`, `eventconfig`)
- **THEN** the unscoped-inventory pin fails, because the expected set no longer contains it — the
  retired legacy fallback cannot return silently

#### Scenario: The device id names its group

- **WHEN** the guard classifies the device-id seat
- **THEN** it is found among the seats that name an access group, never among the unscoped ones

#### Scenario: A BGTask id diverges between Kotlin and Info.plist

- **WHEN** the Kotlin constant and the `BGTaskSchedulerPermittedIdentifiers` entry for a BGTask
  id no longer agree
- **THEN** the pin guard fails, naming both values
