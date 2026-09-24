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
  `upload-state-reconciliation`): its one seat was that fallback, and with it gone the pair appears
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
- **App-Group `NSUserDefaults` keys** `app.snapsync.album.map` and `rejoin.joinedEventId`. The discovery
  cursor's key `discovery.changeToken` was **retired from this inventory** when the cursor was removed: it
  appears in production Kotlin nowhere, which an exactly-once pin cannot express. A stale value an older
  build left in the App-Group defaults is inert. The upload tier's join-marker key `rejoin.joinedEventId`
  **stays pinned, as a removal target**: nothing reads or writes it since the marker was deleted, and its
  one production occurrence is the start-up removal that deletes the orphaned key from every existing
  device's App-Group defaults (capability `ios-app-shell`). That removal is what keeps a revert clean, and
  it fails *silently* if the literal drifts — `removeObjectForKey` on a misspelled key is a no-op that no
  test, log or device would notice. The exactly-once pin is what makes a re-valued literal fail the build.
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
- **BGTask identifier** `app.snapsync.upload.heartbeat` — once in Kotlin AND once in `Info.plist`'s
  `BGTaskSchedulerPermittedIdentifiers`; the guard SHALL assert the Kotlin value and the plist value
  agree, because drift between them silently kills that background tier (the OS rejects an unpermitted
  submit; nothing raises). The download backstop's identifier `app.snapsync.download.backstop` was
  **retired from this inventory** with its task (capability `ios-app-shell`; decision record
  `changes/own-work-per-wake`, D7): it appears in production Kotlin nowhere, which an exactly-once pin
  cannot express. The guard SHALL instead assert that `BGTaskSchedulerPermittedIdentifiers` lists
  **exactly** the pinned BGTask set — in the app's `Info.plist`, which SHALL declare the key, and in every other
  bundle's plist that declares it at all — with no duplicate, and with the plist's XML comments stripped first so
  an explanation that names a retired identifier is not a listing. A retired identifier left in a plist
  therefore fails the build: listing an identifier no registration serves is an operating-system error, and
  nothing else would notice it. The guard SHALL likewise assert that the Swift shell's
  `register(forTaskWithIdentifier:)` calls register **exactly** the pinned set, once each — a listed identifier
  nothing registers is the same silent error — and SHALL prove, in the same run, that both parsers still
  recognise a listing and a registration, so a parser that stops matching cannot turn either check into a
  pass.
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

#### Scenario: The retired join-marker key is pinned at its removal site

- **WHEN** the literal at the orphaned-key removal site no longer equals `rejoin.joinedEventId`, or a second
  production occurrence of it appears
- **THEN** the guard fails, because a drifted removal would silently leave the key in place on every device

#### Scenario: A retired BGTask id stays listed

- **WHEN** `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers` still lists `app.snapsync.download.backstop`,
  or any identifier outside the pinned set
- **THEN** the pin guard fails, naming the unpinned identifier

#### Scenario: A registration the plist does not pin

- **WHEN** the Swift shell registers a BGTask identifier outside the pinned set, or stops registering a
  pinned one
- **THEN** the pin guard fails, listing the pinned and the registered sets

#### Scenario: A BGTask id diverges between Kotlin and Info.plist

- **WHEN** the Kotlin constant and the `BGTaskSchedulerPermittedIdentifiers` entry for a BGTask
  id no longer agree
- **THEN** the pin guard fails, naming both values

### Requirement: OS completion handlers are held in one type

Holding an OS-supplied completion handler SHALL be confined to the single `:domain` `ports/` type
**`OsCompletions`**, which carries it across the wake's own work and releases every outstanding handler —
after that work, or at once on the operating system's expiry signal (capability `ios-app-shell`, "OS
completion handlers are released only after their work completes"). It replaces `OsReceipt` and
`BackgroundEventsReceipts`, which bounded the hold with a deadline of the app's own and are deleted with
`ReceiptDeadlines` (decision record `changes/own-work-per-wake`, D3); the confinement is unchanged, only its
home is re-aimed. The guard SHALL fail when its licensed owner no longer declares `OsCompletions` — an exemption
for a moved or renamed owner exempts nothing, and the handler's real home would then be judged by the rule it is
licensed to break. No other production source SHALL declare a **mutable** property whose type is a
nullary `Unit`-returning function — `var x: (() -> Unit)?`, `var x: () -> Unit`, or the `lateinit`
form — whether by import or by fully-qualified reference.

The rule **confines rather than forbids**, because storing the handler is the platform's own documented
recipe (*"You should then store that completion handler before creating a background configuration
object"*). What is unsafe is not the storing but the shape a bare field forces: a single slot is released by
nothing when the work never finishes — no expiry reaches it — and a second handover silently overwrites
the first, which costs the app its future background wakes. Naming one home makes both properties provable in one tested place, in the same shape Keychain
access is confined to one module. Any exempt declaration SHALL state, at the exemption, why it is
exempt.

The guard SHALL fail when it reads no files, so a moved directory or a regex that stops matching fails
loudly instead of passing empty. A guard of this kind has already failed silently once: a prior version
matched on field **names** containing `ompletion`/`nComplete`, which passed a field of the exact
forbidden type — `IosUrlSessionUploadPlatform.onBackgroundEventsFinished` — in a directory it was
scanning. Matching the **type** is what makes the rule mean anything, and it is why that adapter's
callback slot becomes a constructor `val`: an allowlist for a field that is not an OS handler at all
would invite the next one.

The rule SHALL cover **both languages of the shell**. Apple's recipe stores the handler on the
`UIApplicationDelegate`, so a Swift property holding a `(() -> Void)?` is the likeliest reintroduction, and
a Kotlin-only rule would never see it.

**The rule's residue SHALL be stated where the rule is written**, not implied away. It catches *storing*,
not *releasing early*: an entry point that invokes its raw handler inline stores nothing and passes. It
does not match non-nullary or non-`Unit` handler shapes, a handler held inside a collection, or one behind
a type alias. It reads raw source text, so it also matches the shape inside a comment — prose must
describe such a declaration rather than quote it. Where a missed case appears, the rule SHALL be widened
rather than an exception added.

#### Scenario: A stored handler outside the owning type fails the build

- **WHEN** any production source other than the owning `ports/` type declares
  `var handler: (() -> Unit)? = null` or an equivalent mutable nullary-`Unit` function property
- **THEN** the guard fails the build

#### Scenario: A non-null or lateinit store is caught too

- **WHEN** the declaration avoids nullability — `lateinit var handler: () -> Unit` — to hold the same
  value
- **THEN** the guard still fails the build

#### Scenario: The owning type is exempt, with its reason recorded

- **WHEN** the owning `ports/` type holds handlers itself
- **THEN** the guard passes, and its allowlist entry states why that type is the one permitted holder

#### Scenario: A constructor parameter is not a stored handler

- **WHEN** a type takes its release action as a `val` constructor parameter, as the handler-carrying type does
- **THEN** the guard passes, because an immutable parameter can be neither overwritten nor left unreleased

#### Scenario: A handler stored in the Swift shell is caught

- **WHEN** a Swift shell property holds the completion handler, as Apple's own sample does
- **THEN** the guard fails the build, in that language

#### Scenario: The guard fails when it scans nothing

- **WHEN** the scanned roots match no files
- **THEN** the guard fails rather than reporting no violations

## ADDED Requirements

### Requirement: The walk memo is composed in the app process only

The walk memo (capability `sync-ledger`, "An unchanged library is answered from the walk memo") SHALL be
reachable from the app process's composition only, and a `:test:architecture` guard (`WalkMemoContainmentTest`)
SHALL pin the two steps that keep it there, exact in both directions: a `WalkMemo` SHALL be constructed in
production source in **one** place — the app composition's `appUploadDiscovery`, in `:domain` `compose/` — and
`appUploadDiscovery` SHALL be called in production source from **one** file, the app's uploader
(`UrlSessionUploadController`), never from the extension's root or from the shared `uploadCore` the extension
also calls. Declarations and comments do not count as uses.

The module graph cannot say this: both processes link `:domain`, where the memo type lives. And the extension
must not hold one: its memory limit is 32 MB, whose overrun is a jetsam kill and a relaunch loop rather than an
error, and it holds nothing across `process()` calls, so it walks afresh every time (capability
`ios-photokit-upload`, "In-extension discovery by full enumeration"). The iOS change-token read is kept out of
the extension by linkage besides — it lives in `:adapter:ios:app-only`. Decision record:
`changes/own-work-per-wake` (D9).

#### Scenario: A memo built in a shared composition

- **WHEN** production source constructs a `WalkMemo` anywhere but `appUploadDiscovery`
- **THEN** the guard fails, because that composition may be one the upload extension runs

#### Scenario: The extension binds the memoised discovery

- **WHEN** `appUploadDiscovery` is called from any production file other than the app's uploader — the
  extension's root or `uploadCore` included
- **THEN** the guard fails
