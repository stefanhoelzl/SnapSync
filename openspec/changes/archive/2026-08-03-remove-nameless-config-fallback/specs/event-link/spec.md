## MODIFIED Requirements

### Requirement: Config source and store seams

The capability SHALL define a persisted, joined-event state type **`EventConfig { eventId: String,
name: String, minPhotoDate: CaptureCutoff, startsAt: EventStart, endsAt: EventEnd?, maxPhotoDate:
CaptureCeiling, deletesAt: DeletesAt?, direction: Direction, saveToAlbum: Boolean }`** (distinct from the
event-link wire type `EventLinkPayload`). Each field's behavior when **absent from decoded state** is
part of this contract, and the two categories are deliberate: a field whose absence is survivable
defaults, and a field whose absence would silently move a bound or a scope does not.

- `eventId` is the joined event — required, no default.
- `name` is the human-readable event name — **required, with no default**. A persisted payload lacking
  the key SHALL fail to decode. It SHALL NOT default to the empty string: the join gate only provisions
  from a loaded phase that carries a name (capability `join-event`) and the backend enforces
  name-required on create (capability `event-creation`), so a nameless membership is not a representable
  state, and a default that can never fire is an invitation for each reader to decide separately what an
  empty name means. Requiring it also makes `name` a required **constructor** parameter, so every present
  and future construction site must supply one under compiler enforcement. Note that this requires the
  key to be **present**, not its value to be **non-blank**: a blank name is guarded at the details-fetch
  boundary and nowhere else (capability `join-event`).
- `minPhotoDate` is this device's chosen capture-date cutoff for the event (capability
  `photo-selection-policy`) — **required and non-null, with no default**. A membership with no cutoff is
  not a representable state; an absent cutoff once meant whole-library scope, which under event photo
  sharing uploads a guest's entire camera roll to another person's event.
- `startsAt` is the event's start date and the floor of this membership's cutoff — it **SHALL default to
  `minPhotoDate`** when absent, the only value guaranteed consistent with the floor invariant
  `minPhotoDate >= startsAt`.
- `endsAt` is the event's declared end date — **nullable, defaulting to `null`**, backfilled by the
  membership refresh when absent (capability `event-rejoin-reconciliation`).
- `maxPhotoDate` is this membership's capture-date ceiling — **required and non-null, with no default**,
  like `minPhotoDate`.
- `deletesAt` is when the backend deletes the event's shared data — **nullable, defaulting to `null`**,
  where `null` means *deadline not yet learned*, backfilled by the membership refresh. The default fails
  toward keeping the membership: the self-leave cannot fire on a membership that has not learned its
  deadline (capability `leave-event`).
- `direction` is this device's chosen participation direction — a `Direction` enum with values `Both`,
  `UploadOnly`, `DownloadOnly` — that **SHALL default to `Both`** when absent from persisted or decoded
  state.
- `saveToAlbum` is whether this membership gathers its synced photos into an event album (capability
  `event-album`) and **SHALL default to `false`** when absent (so an `EventConfig` persisted before this
  field existed reads as `false`, today's no-album behavior).

The capability SHALL define a `ConfigSource`
state port exposing `config: StateFlow<EventConfig?>` — a level-triggered holder whose current value (the
active config, or `null` when none) is always available synchronously — and a `ConfigStore` command port
with `suspend fun save(config: EventConfig)` that persists the config and updates the source, and
`suspend fun clear()` that removes it and updates the source to `null`. `save` of a config equal
**field-for-field** to the
current one SHALL be an
idempotent no-op; a `save` differing in any field SHALL replace it and emit (a name-only change updates
the title without any ledger effect; the switch-reset on an `eventId` change is orchestrated by the
provision path, not this seam). `clear` SHALL remove the persisted config and set the source to `null`,
and SHALL NOT touch the ledger, **and SHALL NOT clear the event-album map** (capability `event-album`,
which persists `eventId → albumLocalId` in a separate store that survives leave); `clear` when absent
SHALL be an idempotent no-op. Consumers SHALL depend on each port separately.

#### Scenario: Source seeds the current config synchronously
- **WHEN** a `ConfigSource` implementation is constructed while a config is already persisted
- **THEN** `config.value` immediately reflects the persisted `EventConfig`, field for field, without waiting for an emission

#### Scenario: A pre-existing config without saveToAlbum reads as false
- **WHEN** a `ConfigSource` is constructed over a persisted `EventConfig` serialized before the `saveToAlbum` field existed
- **THEN** `config.value.saveToAlbum` is `false` (the default), preserving today's no-album behavior

#### Scenario: A config without a name does not decode
- **WHEN** a persisted `EventConfig` payload carries no `name` key
- **THEN** the decode fails, and the read reports the outcome its store defines for an undecodable payload — never a config with a substituted empty name

#### Scenario: A config whose name is the empty string decodes
- **WHEN** a persisted `EventConfig` payload carries `"name": ""`
- **THEN** it decodes successfully with that value, because the type requires the key to be present and not its value to be non-blank — the blank-name guard lives at the details-fetch boundary (capability `join-event`)

#### Scenario: Saving a name-only update emits without a switch
- **WHEN** `save` is invoked with every other field unchanged and a newly-fetched `name`
- **THEN** the persisted config's name is updated and `config` emits, with no ledger reset

#### Scenario: Saving a different event hot-swaps the source
- **WHEN** `save` is invoked with a different `eventId`
- **THEN** the persisted config is replaced and `config` emits the new `EventConfig`

#### Scenario: Saving an identical config is a no-op
- **WHEN** `save` is invoked with a config equal field-for-field to the current value
- **THEN** no change and no redundant emission occur

#### Scenario: Clearing removes the config but keeps the album map
- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the persisted config is removed and `config` emits `null`, with no change to the ledger and no change to the event-album map

### Requirement: iOS file-backed config store

The capability SHALL provide an iOS adapter (`iosMain`, `:adapter:ios:ext-safe` — both processes
link it) implementing `ConfigSource`, `ConfigStore`, and the three-state `ConfigReader` against a
**single file in the App-Group container root** — filename `eventconfig.json`, a pinned
runtime-identity literal (capability `architecture-guards`) — holding a **versioned envelope**
`{"v": 1, "payload": <serialized EventConfig>}`. The payload carries the whole `EventConfig` (its
`eventId`, its **required** `name`, its **required, non-null** `minPhotoDate`, its `startsAt`, its
`endsAt`, its **required, non-null** `maxPhotoDate`, its `deletesAt`, its `direction`, and
its `saveToAlbum`), so the background upload extension reads the `eventId`, the cutoff, and the
album flag from the same file the app writes. The envelope codec and the read algorithm SHALL be
pure `:domain` functions covered in `commonTest` (JVM **and** iOS simulator); the adapter SHALL
contain only file IO and error mapping.

Writes SHALL be **atomic** (temp file + rename) under
`NSFileProtectionCompleteUntilFirstUserAuthentication` — readable while the device is locked once
it has been unlocked since boot, because the OS invokes the upload extension while the device is
idle and therefore usually locked (the same class as the sibling App-Group stores).

**No config value is ever written to the Keychain again; the read keeps the legacy fallback.**
The migration finale ended the 11a Keychain **write-through**: `save` SHALL write the file alone,
so the revert direction is sacrificed, consistent with fix-forward. `clear` SHALL delete the
legacy Keychain item **first** and the file second (both idempotent) — the 11a clear contract's
surviving half, load-bearing while the fallback lasts: a file-only clear would leave exactly the
missing-file + item-present state the fallback resurrects, silently undoing the leave on every
migrated device; a crash between the two leaves the file present, so this build stays joined and
the leave retries. The READ SHALL keep the adapter-resident migration fallback through the
legacy-Keychain seat (`KeychainConfigReader` — read + the leave-path delete only, no save; it may
repair a legacy item's accessibility class in place, value untouched): a
read whose file is **definitively missing** (the not-found error class only) SHALL consult it,
and a found config is returned **and atomically written into the file** (best-effort: a failed
migration write returns the fallback's answer and retries on the next read; after the write the
fallback is re-checked — compare-and-repair — and a value a concurrent save/clear superseded is
repaired to, and answered with, the fresh state); a definitively-absent item reads as no config;
an unreadable item reads as unreadable. The fallback SHALL live in the adapter — not in app
startup — so it runs in **whichever process reads first** (the OS can schedule the upload
extension before the user ever opens the updated app; app + extension update atomically).

The fallback outlives the write-through **because of the ship model**: this branch reaches the
installed base as one merge, so at ship (update) time every joined production device is a
pre-11a device whose file has never existed — without the fallback, the update itself would read
every joined device as left. Deleting the fallback (and only then retiring the pair's
runtime-identity pin) is the designated post-ship Stage-2 change, gated on production soak
(capability `event-rejoin-reconciliation`).

One accepted Stage-1 divergence, on record: because `save` no longer maintains the legacy item, a
migrated device that **switches** events leaves a stale legacy item behind (holding the previous
membership), and a reinstall before Stage 2 then resurrects that *previous* membership rather
than the current one — bounded (the device was genuinely a member of it; the switch already
issued its best-effort backend leave; re-scanning converges) and it dies with the Stage-2
fallback deletion. Maintaining the item on save would be the write-through this change ends.

**Version handling.** Decoding SHALL ignore unknown keys on both the envelope and the payload (a
same-version additive change needs no version bump, and the `EventConfig` legacy-field defaults
apply exactly as before — an item without `saveToAlbum`/`direction` decodes to `false`/`Both`, one
without `endsAt`/`deletesAt` to `null`, one without `startsAt` to its `minPhotoDate`). A
**current-version** payload lacking `minPhotoDate`, `maxPhotoDate`, **or `name`** SHALL
fail to decode and read as **unreadable** — no default substituted, the failure logged, no
upload until the user re-joins (a save overwrites the file). The Keychain legacy-item rule — an
undecodable item reads as no config — deliberately does NOT transfer to the file: the adapter's
own atomic writes make an unusable current-version file unreachable, so one is an unexplained
state, and an unexplained state defers rather than driving a leave; the rule stays in force on
the read-only fallback side. A file whose envelope
version is **not** this build's, or whose content is not an envelope at all, SHALL read as
**unreadable** — never as absent, never a crash — so a build that opens a successor's file defers
instead of reading a leave.

The adapter SHALL seed its `config` `StateFlow` synchronously at construction from the same read
(mapping both *absent* and *unreadable* to `null` — acceptable for the UI, never for the
reconciler, which uses the three-state `ConfigReader`), and SHALL expose a `reload()` the trigger
flows call before acting (migration step 12 — the trigger-time membership re-read replaced the
protected-data unlock hook; see `ios-app-shell`): a background construction before first unlock
seeds `null` (the protected read fails permission-class → unreadable) and is repaired at the next
trigger. `reload()` SHALL apply the pure, tested merge rule (`configAfterReload`): a conclusive
read (joined / definitively absent) replaces the `StateFlow` value; an **unreadable** read
**retains** the last good one — at trigger cadence a transient read failure must not clear a good
membership and flip the screen to the setup gate. The persisted file
SHALL survive app updates and process death. It is **not excluded from device backups** — the
membership's backup/restore continuity is deliberate, matching the Keychain item's non-ThisDeviceOnly
posture (decision record: `changes/archive/migrate-config-to-app-group-file`, D6).

#### Scenario: Persisted config survives relaunch from the file

- **WHEN** a config is saved, the app terminates, and the adapter is reconstructed on next launch
- **THEN** `config.value` immediately reflects the previously-saved `EventConfig`, read from the
  App-Group file without consulting the Keychain

#### Scenario: The extension reads the config file on a locked device

- **WHEN** the OS invokes the upload extension while the device is locked, and the device has been
  unlocked at least once since boot
- **THEN** the file is read successfully and the cycle proceeds with the persisted config

#### Scenario: Save writes the file alone

- **WHEN** `save` persists a config
- **THEN** only the App-Group file is written — no Keychain item is touched (the write-through is
  ended) — and `config` emits the new value

#### Scenario: Clear removes the legacy item first, then the file

- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the legacy Keychain item is (best-effort, idempotently) deleted before the file,
  `config` emits `null`, and at no crash point does the store rest in the missing-file +
  present-item state the read fallback would resurrect

#### Scenario: A pre-file joined device migrates on first read, in whichever process runs first

- **WHEN** a device joined under a Keychain-era build updates to this build (legacy item present,
  no file) and either process — the app **or** the OS-scheduled upload extension — performs the
  first read
- **THEN** the read returns the legacy config (never a false not-joined), writes it atomically
  into the App-Group file, and subsequent reads answer from the file alone

#### Scenario: A failed migration write does not fail the read

- **WHEN** the fallback finds a legacy config but the file write fails
- **THEN** the read still returns that config, and the next read retries the migration

#### Scenario: A future-version file reads as unreadable, never a leave

- **WHEN** a read finds a file whose envelope version is not this build's (e.g. a revert build
  opening a successor's file)
- **THEN** the read reports **unreadable** — the cycle skips, no marker is cleared, no upload runs
  — and never reports no-config

#### Scenario: A current-version file without a cutoff reads as unreadable

- **WHEN** a read finds a current-version envelope whose payload lacks `minPhotoDate`
- **THEN** the decode fails, the failure is logged, the read reports **unreadable** (never
  no-config — no marker is cleared), no default cutoff is substituted, and no upload occurs until
  the user re-joins

#### Scenario: A current-version file without a name reads as unreadable

- **WHEN** a read finds a current-version envelope whose payload lacks `name`
- **THEN** the decode fails, the failure is logged, the read reports **unreadable** (never
  no-config — the file is left intact, no marker is cleared, and no backend leave is issued), and
  no empty name is substituted

#### Scenario: A trigger-time reload retains the membership on a transient failure

- **WHEN** a trigger flow's `reload()` runs while the file read transiently fails (unreadable, not
  absent) and the `StateFlow` holds a joined config
- **THEN** the `StateFlow` retains the joined config — the screen does not regress to the setup
  gate — and a later conclusive read replaces it

#### Scenario: A reinstall while the read fallback lasts resurrects from the legacy item

- **WHEN** the app is deleted and reinstalled (the App-Group file is wiped; the legacy Keychain
  item survives uninstall) while the read-only fallback is still in force
- **THEN** the first read falls back to the surviving legacy config, migrates it into the file,
  and the device remains joined — indistinguishable from an update-in-place, by design; the
  "reinstall = left the event" end state takes effect only when the post-ship Stage-2 change
  deletes the fallback (capability `event-rejoin-reconciliation`)

#### Scenario: No config anywhere reads as null

- **WHEN** the adapter is constructed with no file and no legacy Keychain item present
- **THEN** `config.value` is `null`
