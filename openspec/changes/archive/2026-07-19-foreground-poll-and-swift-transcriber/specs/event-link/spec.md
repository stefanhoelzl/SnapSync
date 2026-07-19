# event-link — delta for foreground-poll-and-swift-transcriber

## MODIFIED Requirements

### Requirement: iOS file-backed config store with Keychain write-through

The capability SHALL provide an iOS adapter (`iosMain`, `:adapter:ios:ext-safe` — both processes
link it) implementing `ConfigSource`, `ConfigStore`, and the three-state `ConfigReader` against a
**single file in the App-Group container root** — filename `eventconfig.json`, a pinned
runtime-identity literal (capability `architecture-guards`) — holding a **versioned envelope**
`{"v": 1, "payload": <serialized EventConfig>}`. The payload carries the whole `EventConfig` (its
`eventId`, `name`, its **required, non-null** `minPhotoDate`, its `startsAt`, its `direction`, and
its `saveToAlbum`), so the background upload extension reads the `eventId`, the cutoff, and the
album flag from the same file the app writes. The envelope codec and the read algorithm SHALL be
pure `:domain` functions covered in `commonTest` (JVM **and** iOS simulator); the adapter SHALL
contain only file IO and error mapping.

Writes SHALL be **atomic** (temp file + rename) under
`NSFileProtectionCompleteUntilFirstUserAuthentication` — readable while the device is locked once
it has been unlocked since boot, because the OS invokes the upload extension while the device is
idle and therefore usually locked (the same class as the sibling App-Group stores).

**Write-through (copy, don't move).** `save` SHALL write the file and then write the identical
config through to the legacy Keychain item (the pre-11a store, unchanged accessibility contract);
`clear` SHALL delete the Keychain item **first** and the file second. The orderings are
load-bearing: a torn save leaves the file (which this build reads) authoritative; a torn clear
must never produce the missing-file + present-Keychain state, which the migration fallback below
would resurrect — silently undoing the leave. The write-through SHALL persist until a later change
deletes the Keychain entry (migration step 13b or after), so a **revert build** — which reads only
the Keychain — finds a live config for the whole soak window.

**Adapter-resident migration.** A read whose file is **definitively missing** (per capability
`event-link`, *An unreadable config is not an absent config* — the not-found error class only)
SHALL fall back to the Keychain copy: a found config is returned **and atomically written into the
file** (best-effort: a failed migration write returns the Keychain's answer and retries on the
next read; after the write the fallback is re-checked, and a value a concurrent save/clear
superseded is repaired to — and answered with — the fresh state); a definitively-absent Keychain
reads as no config; an unreadable Keychain reads as
unreadable. The migration SHALL live in the adapter — not in app startup — so it runs in
**whichever process reads first**: the OS can schedule the upload extension before the user ever
opens the updated app, and app + extension update atomically, so both carry the adapter.

**Version handling.** Decoding SHALL ignore unknown keys on both the envelope and the payload (a
same-version additive change needs no version bump, and the `EventConfig` legacy-field defaults
apply exactly as before — an item without `saveToAlbum`/`direction` decodes to `false`/`Both`, a
missing `name` to the empty string). A **current-version** payload lacking `minPhotoDate` SHALL
fail to decode and read as **unreadable** — no default cutoff substituted, the failure logged, no
upload until the user re-scans (a save overwrites the file). The Keychain legacy-item rule — an
undecodable item reads as no config — deliberately does NOT transfer to the file: the adapter's
own atomic writes make an unusable current-version file unreachable, so one is an unexplained
state, and an unexplained state defers rather than driving a leave; the rule stays in force on
the Keychain fallback side only. A file whose envelope
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

#### Scenario: An update-in-place migrates on first read, in whichever process runs first

- **WHEN** a device joined under the Keychain-era build updates in place (Keychain item present,
  no file) and either process — the app **or** the OS-scheduled upload extension — performs the
  first read
- **THEN** the read returns the Keychain config (never a false not-joined), writes it atomically
  into the App-Group file, and subsequent reads answer from the file alone

#### Scenario: A failed migration write does not fail the read

- **WHEN** the fallback finds a Keychain config but the file write fails
- **THEN** the read still returns that config, and the next read retries the migration

#### Scenario: Save writes through to the Keychain copy

- **WHEN** `save` persists a config
- **THEN** the App-Group file holds the new envelope **and** the legacy Keychain item holds the
  identical serialized config, so a reverted (pre-file) build reads the same membership

#### Scenario: Clear removes both copies, Keychain first

- **WHEN** `clear()` is invoked while a config is persisted
- **THEN** the Keychain item is deleted before the file, `config` emits `null`, and at no
  intermediate point does the store hold the missing-file + present-Keychain state that the
  migration fallback would resurrect

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

#### Scenario: A trigger-time reload retains the membership on a transient failure

- **WHEN** a trigger flow's `reload()` runs while the file read transiently fails (unreadable, not
  absent) and the `StateFlow` holds a joined config
- **THEN** the `StateFlow` retains the joined config — the screen does not regress to the setup
  gate — and a later conclusive read replaces it

#### Scenario: A reinstall during the write-through window resurrects from the Keychain copy

- **WHEN** the app is deleted and reinstalled (the App-Group file is wiped; the Keychain item
  survives uninstall) while the write-through is still in force
- **THEN** the first read falls back to the surviving Keychain config, migrates it into the file,
  and the device remains joined — indistinguishable from an update-in-place, by design; the
  "reinstall = left the event" end state takes effect only when the Keychain copy is deleted
  (capability `event-rejoin-reconciliation`)

#### Scenario: No config anywhere reads as null

- **WHEN** the adapter is constructed with no file and no Keychain item present
- **THEN** `config.value` is `null`
