# event-link — delta for retire-legacy-config-fallback

## MODIFIED Requirements

### Requirement: An unreadable config is not an absent config

The config seam SHALL distinguish three outcomes: a **readable** config, a **definitely absent**
config, and an **unreadable** config. An unreadable config SHALL NOT be reported as an absent
config.

The distinction is grounded on the App-Group config file **alone**: **definitely absent** SHALL mean
exactly that the file read failed with the **not-found error class**
(`NSFileReadNoSuchFileError` 260 / `NSFileNoSuchFileError` 4 / POSIX `ENOENT`), and nothing else is
consulted. A read that fails for **any other reason** — notably the permission-class failure of a
protected-file read before first unlock, a missing App-Group container, or file content this build
cannot positively interpret (a foreign envelope version or an undecodable current-version payload)
— SHALL be **unreadable**. The absence class is
a closed whitelist, deliberately: an unrecognized error shape lands on the unreadable side, where
the cost is a deferred cycle, not a false leave.

**The error classifier is the only vote.** Until the read-only legacy-Keychain fallback was deleted,
a misclassified not-found was caught downstream — the fallback found the legacy item and the device
stayed joined — so absence required a *second* answer to agree. It no longer does: the classifier
that decides whether an `NSError` belongs to the not-found class is now solely load-bearing for the
leave decision, and a wrong verdict is an unrecoverable, silent logout (capability
`event-rejoin-reconciliation` states the consequence). Widening that whitelist SHALL therefore be
treated as changing the leave decision itself.

A reader that acts on the absence of a config — in particular the re-join reconciliation, for
which "no event configured" means *the device left the event* and triggers clearing the persisted
`joinedEventId` marker (capability `event-rejoin-reconciliation`) — SHALL act **only** on a
definitely absent config. On an unreadable config **the upload cycle** SHALL skip entirely: it
SHALL NOT reconcile, SHALL NOT clear the join marker, SHALL NOT reset the discovery cursor, and
SHALL NOT create upload jobs; the cycle SHALL complete cleanly and the next cycle SHALL retry.

This SHALL hold on **every upload tier and at every trigger**, not only where the OS is the
invoker. The tiers differ in who invokes a cycle — the OS on iOS ≥26.1, the app on iOS 18–26.0 —
and not in what an unreadable membership means. A tier SHALL NOT reach this decision through a
two-state read that cannot express "unreadable"; the three-state read is the only permitted path
(capability `upload-lifecycle`, which owns where the decision is made).

Conflating the two is what makes an ordinary locked-device wake perform a *false leave*: the
marker is cleared, and the next readable cycle sees a marker mismatch and pays for a full re-join
reconciliation (a device listing, an atomic ledger clear-and-seed, and a discovery-cursor reset
that forces a complete library re-enumeration) — repeatedly, without the marker ever settling.

#### Scenario: An unreadable config does not clear the join marker

- **WHEN** an upload cycle reads the config and the read fails because protected data is
  unavailable (the file read fails permission-class before first unlock)
- **THEN** the cycle is skipped, the reconciliation is not invoked, the persisted `joinedEventId`
  marker is left intact, the discovery cursor is not reset, and the cycle completes cleanly

#### Scenario: A definitely-absent config still drives the leave path

- **WHEN** an upload cycle reads the config and the file is missing by the not-found error class
- **THEN** the reconciliation runs for the no-config case and clears the `joinedEventId` marker,
  exactly as a leave requires — with no other store consulted

#### Scenario: An unrecognized read error stays unreadable

- **WHEN** the file read fails with an error outside the not-found whitelist
- **THEN** the read reports unreadable and the cycle skips — the classifier's `else` arm never
  admits an unknown error into the absence class, because it is now the only thing standing between
  a misclassified error and an unintended leave

#### Scenario: A joined device stays settled across locked wakes

- **WHEN** a joined device runs cycles repeatedly while locked and its config is unreadable
- **THEN** its join marker still matches its configured event on the next readable cycle, so no
  re-join reconciliation, ledger re-seed, or full re-enumeration is performed

#### Scenario: The app-driven tier skips rather than leaves

- **WHEN** the app-driven tier (iOS 18–26.0) runs a cycle from any trigger — foreground, background task,
  silent push, or session events — and the config read fails because protected data is unavailable
- **THEN** the cycle is skipped, the `joinedEventId` marker is left intact, and the membership survives —
  the same outcome the OS-invoked tier produces

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

#### Scenario: A reinstall reads as not joined even though a legacy item survives

- **WHEN** the app is deleted and reinstalled (the App-Group file is wiped) on a device whose
  pre-11a Keychain item survived the uninstall
- **THEN** the read reports no config — the surviving item is never consulted, so the device is
  not resurrected — and rejoining requires re-scanning the invite (capability
  `event-rejoin-reconciliation`)

#### Scenario: No config file reads as null

- **WHEN** the adapter is constructed with no file present
- **THEN** `config.value` is `null`
