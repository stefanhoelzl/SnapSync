## MODIFIED Requirements

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
contain only file IO and error mapping. The container's location SHALL be a constructor input
defaulting to the shared App-Group container, so a test can run the adapter's own file IO and error
mapping over a directory it owns (capability `port-contracts`); both composition roots SHALL pass
nothing.

An **unresolvable container** — the App-Group lookup answering nothing, which only a build without the
App-Group entitlement reaches — SHALL be treated as *unreadable* on every member: the read reports
unreadable (never no-config), and **both** `save` and `clear` SHALL fail rather than report success,
leaving `config` unchanged. A `clear` that could not reach its store and returned anyway would show
the setup gate while a file it never deleted resurrects the membership at the next launch — the
half-completed leave the failing `clear` exists to prevent (capability `leave-event`). Deleting a file
that is **definitively missing** remains success.

Writes SHALL be **atomic** (temp file + rename) under
`NSFileProtectionCompleteUntilFirstUserAuthentication` — readable while the device is locked once
it has been unlocked since boot, because the OS invokes the upload extension while the device is
idle and therefore usually locked (the same class as the sibling App-Group stores).

**The file is the only storage, on both the write and the read side.** The migration finale ended
the 11a Keychain **write-through** (`save` writes the file alone, so the revert direction is
sacrificed, consistent with fix-forward), and the Stage-2 change deleted the read-only
legacy-Keychain fallback with it. `save` SHALL write the file alone and `clear` SHALL delete the
file alone; neither SHALL touch the Keychain. The READ SHALL consult **no other store**: a file that
is **definitively missing** (the not-found error class only) SHALL read as no config — the sole road
to "this device left the event" — with nothing else consulted, no migration, and no
compare-and-repair. No adapter SHALL address the legacy `app.snapsync.config`/`eventconfig` Keychain
item, whose runtime-identity pin was retired with the fallback (capability `architecture-guards`).

Two constructs died with the fallback and SHALL NOT be reintroduced without reintroducing it:
`clear`'s Keychain-first ordering (whose only purpose was to stop the fallback resurrecting a
completed leave), and the accepted Stage-1 divergence in which a *switched* device's stale legacy
item resurrected the **previous** membership on reinstall.

On already-migrated devices the legacy Keychain item SHALL be left in place rather than purged. It
survives app deletion and nothing reads it; purging it would mean keeping the seat, its
runtime-identity pin, and a Keychain call on the leave path alive solely to delete data no code path
can observe. The orphan is knowingly abandoned, not overlooked (decision record:
`changes/archive/…-retire-legacy-config-fallback`, D3).

**Version handling.** Decoding SHALL ignore unknown keys on both the envelope and the payload (a
same-version additive change needs no version bump, and the `EventConfig` legacy-field defaults
apply exactly as before — an item without `saveToAlbum`/`direction` decodes to `false`/`Both`, one
without `endsAt`/`deletesAt` to `null`, one without `startsAt` to its `minPhotoDate`). A
**current-version** payload lacking `minPhotoDate`, `maxPhotoDate`, **or `name`** SHALL
fail to decode and read as **unreadable** — no default substituted, the failure logged, no
upload until the user re-joins (a save overwrites the file). The Keychain legacy-item rule — an
undecodable item reads as no config — never transferred to the file and now has no side left to
apply on: the adapter's own atomic writes make an unusable current-version file unreachable, so one
is an unexplained state, and an unexplained state defers rather than driving a leave. A file whose
envelope version is **not** this build's, or whose content is not an envelope at all, SHALL read as
**unreadable** — never as absent, never a crash — so a build that opens a successor's file defers
instead of reading a leave.

The adapter SHALL seed its `config` `StateFlow` synchronously at construction from the same read
(mapping both *absent* and *unreadable* to `null` — acceptable for the UI, never for the
upload cycle, which uses the three-state `ConfigReader`), and SHALL expose a `reload()` the trigger
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

#### Scenario: Clear removes the file alone

- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** only the App-Group file is deleted — no Keychain item is touched — and `config` emits
  `null`

#### Scenario: A missing file reads as no config without consulting anything

- **WHEN** a read finds no file (the not-found error class)
- **THEN** the read reports no config immediately — no Keychain item is read, no migration is
  attempted, and no compare-and-repair runs

#### Scenario: A future-version file reads as unreadable, never a leave

- **WHEN** a read finds a file whose envelope version is not this build's (e.g. a revert build
  opening a successor's file)
- **THEN** the read reports **unreadable** — the cycle skips, the ledger is untouched, no upload
  runs — and never reports no-config

#### Scenario: A current-version file without a cutoff reads as unreadable

- **WHEN** a read finds a current-version envelope whose payload lacks `minPhotoDate`
- **THEN** the decode fails, the failure is logged, the read reports **unreadable** (never
  no-config — the ledger is untouched), no default cutoff is substituted, and no upload occurs until
  the user re-joins

#### Scenario: A current-version file without a name reads as unreadable

- **WHEN** a read finds a current-version envelope whose payload lacks `name`
- **THEN** the decode fails, the failure is logged, the read reports **unreadable** (never
  no-config — the file is left intact, the ledger is untouched, and no backend leave is issued), and
  no empty name is substituted

#### Scenario: A trigger-time reload retains the membership on a transient failure

- **WHEN** a trigger flow's `reload()` runs while the file read transiently fails (unreadable, not
  absent) and the `StateFlow` holds a joined config
- **THEN** the `StateFlow` retains the joined config — the screen does not regress to the setup
  gate — and a later conclusive read replaces it

#### Scenario: A reinstall reads as not joined even though a legacy item survives

- **WHEN** the app is deleted and reinstalled (the App-Group file is wiped) on a device whose
  pre-11a Keychain item survived the uninstall
- **THEN** the read reports no config — the surviving item is never consulted, so the device is
  not resurrected — and rejoining requires re-scanning the invite (capability
  `upload-state-reconciliation`)

#### Scenario: No config file reads as null

- **WHEN** the adapter is constructed with no file present
- **THEN** `config.value` is `null`

#### Scenario: Clear without a container fails rather than leaving silently

- **WHEN** `clear()` is invoked and the App-Group container cannot be resolved
- **THEN** `clear()` fails, `config` keeps its value, and the leave treats the device as still joined
  rather than showing the setup gate over a membership it never removed

#### Scenario: Clear of a missing file succeeds

- **WHEN** `clear()` is invoked, the container resolves, and no config file exists
- **THEN** `clear()` succeeds, the read reports no config, and `config` is `null`
