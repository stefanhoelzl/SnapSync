# event-link — delta for complete-architecture-migration

## ADDED Requirements

### Requirement: iOS file-backed config store

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
apply exactly as before — an item without `saveToAlbum`/`direction` decodes to `false`/`Both`, a
missing `name` to the empty string). A **current-version** payload lacking `minPhotoDate` SHALL
fail to decode and read as **unreadable** — no default cutoff substituted, the failure logged, no
upload until the user re-scans (a save overwrites the file). The Keychain legacy-item rule — an
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

## MODIFIED Requirements

### Requirement: An unreadable config is not an absent config

The config seam SHALL distinguish three outcomes: a **readable** config, a **definitely absent**
config, and an **unreadable** config. An unreadable config SHALL NOT be reported as an absent
config.

The distinction is grounded on the App-Group config file: **definitely absent** SHALL mean
exactly that the file read failed with the **not-found error class**
(`NSFileReadNoSuchFileError` 260 / `NSFileNoSuchFileError` 4 / POSIX `ENOENT`) **and** the
read-only legacy-Keychain fallback reported item-not-found (while the fallback lasts — it is the
installed base's update path, and its deletion is a designated post-ship change; capability
`event-rejoin-reconciliation` records the staging). A read that fails for **any other reason** —
notably the permission-class failure of a protected-file read before first unlock, an unreadable
legacy-Keychain fallback, a missing App-Group container, or file content this build cannot
positively interpret (a foreign envelope version or an undecodable current-version payload) —
SHALL be **unreadable**. The absence class is
a closed whitelist, deliberately: an unrecognized error shape lands on the unreadable side, where
the cost is a deferred cycle, not a false leave.

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

- **WHEN** an upload cycle reads the config, the file is missing by the not-found error class, and
  the read-only legacy-Keychain fallback reports no such item
- **THEN** the reconciliation runs for the no-config case and clears the `joinedEventId` marker,
  exactly as a leave requires

#### Scenario: A missing file with an unreadable legacy Keychain stays unreadable

- **WHEN** an upload cycle reads the config, the file is definitively missing, and the fallback
  read fails (protected data unavailable)
- **THEN** the read reports unreadable — a pre-11a joined install on a locked device is
  indistinguishable from a left device here, so absence is unproven — and the cycle skips

#### Scenario: A joined device stays settled across locked wakes

- **WHEN** a joined device runs cycles repeatedly while locked and its config is unreadable
- **THEN** its join marker still matches its configured event on the next readable cycle, so no
  re-join reconciliation, ledger re-seed, or full re-enumeration is performed

#### Scenario: The app-driven tier skips rather than leaves

- **WHEN** the app-driven tier (iOS 18–26.0) runs a cycle from any trigger — foreground, background task,
  silent push, or session events — and the config read fails because protected data is unavailable
- **THEN** the cycle is skipped, the `joinedEventId` marker is left intact, and the membership survives —
  the same outcome the OS-invoked tier produces

## REMOVED Requirements

### Requirement: iOS file-backed config store with Keychain write-through

**Reason**: The write-through proper ends (saves are file-only; no config value is ever written to the Keychain again; the revert direction is sacrificed, consistent with fix-forward). Replaced by the ADDED "iOS file-backed config store", which keeps the READ-ONLY legacy-item fallback and the leave-path legacy-item deletion — the ship-at-once model makes the fallback the entire installed base's update path; its deletion is the designated post-ship Stage-2 change (capability event-rejoin-reconciliation).
