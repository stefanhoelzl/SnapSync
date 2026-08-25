## MODIFIED Requirements

### Requirement: Runtime identity is pinned

`:test:architecture` SHALL pin every runtime-identity literal — a string the OS or the installed
base holds on its side, so that changing it strands or corrupts state on devices already in the
field. Each pin SHALL assert the literal appears **exactly once** in production Kotlin source
(main source sets; test sources and `build/` excluded) with its exact value, plus its pinned
occurrences in the non-Kotlin surfaces named below. Any delta — a disappearance, a value change,
or a new occurrence — SHALL fail the build.

The pinned inventory (this list is the contract of record; adding, removing, or re-valuing a pin
is a spec change to this requirement, deliberately):

- **App-Group id** `group.app.snapsync` — once in Kotlin, plus once in each of the **three**
  entitlements files (`iosApp.entitlements`, `BackgroundUploadExtension.entitlements`,
  `simulator.entitlements`). The third is not a signing surface for any shipped build: it is the
  App-Group-only plist an ad-hoc signature carries so a simulator build has a container at all. It
  is pinned for the same reason as the other two and one more — it is the surface most likely to be
  forgotten, because no shipped build fails when it is wrong. Re-value the App Group without it and
  the simulator host silently loses its container, which reads as the app being broken rather than
  as a rename being incomplete.
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
  declared in each of the **two signing** entitlements files together with `TEAM_ID` from
  `Config.xcconfig`: the guard SHALL assert the Kotlin literal equals `<TEAM_ID>.` followed by the
  entitlements' declared group, and that both signing entitlements files declare the same group.
  `simulator.entitlements` SHALL be excluded from this cross-check and SHALL declare **no**
  `keychain-access-groups` key at all — its omission is load-bearing rather than incidental: adding
  that entitlement to an ad-hoc-signed simulator build makes the app un-launchable, which is why
  `device-identity` carries a planted-identity path for hosts where the addressed group is
  unreachable. The guard SHALL assert the absence, so that "add the missing key" cannot be applied
  as a fix to a mystery it would cause. Drift in the signing files does not fail loudly — the item
  is written to a *different real group*, both processes still read successfully, and each simply
  reads a different item. That is the split-identity fault, which is invisible to every existing
  gate and unrecoverable once written.
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

- **WHEN** the Kotlin access-group literal, the group declared in the signing entitlements files, or
  `TEAM_ID` are edited so they no longer compose to the same string, or the two signing entitlements
  files declare different groups
- **THEN** the pin guard fails, naming the Kotlin value and the composed entitlements value

#### Scenario: The App Group is renamed without the simulator plist

- **WHEN** the App-Group id is re-valued in Kotlin and both signing entitlements files, and
  `simulator.entitlements` keeps the old value
- **THEN** the pin guard fails, naming the third file — rather than leaving a simulator host that
  launches and then reports its container as unavailable

#### Scenario: The simulator plist gains a keychain group

- **WHEN** `simulator.entitlements` declares a `keychain-access-groups` key
- **THEN** the guard fails, because that entitlement makes an ad-hoc-signed simulator build
  un-launchable and its absence is a deliberate decision rather than an omission to repair

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
