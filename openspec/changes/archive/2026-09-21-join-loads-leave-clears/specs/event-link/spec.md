## MODIFIED Requirements

### Requirement: Switching events leaves the previous event first

The provisioning flow (`flow/Provision`) SHALL run a switch as a leave of the previous event followed
by a join of the new one — a switch being a valid event link that provisions an event whose `eventId`
**differs** from the currently provisioned one — in this order:

1. **stop uploads** (`uploadArm.onLeave()`), so no mechanism starts new work against a ledger about to
   be replaced;
2. send the **best-effort backend leave** of the previous event, awaited;
3. run the **join-time ledger load**, awaited (capability `join-event`, "A provision into a new
   membership clears, then loads the upload ledger"), so no cycle can see the new membership over the
   previous membership's ledger;
4. **persist** the new event's config;
5. then refresh status, arm the upload mechanism (`uploadArm.onProvision()`), ensure the album, and start
   downloads and push, as for any provision.

A first join (no current membership) takes steps 3–5. The backend leave issues
`DELETE /events/<previousEventId>/devices/<deviceId>` via the same `HttpLeaveNotifier` the explicit
Leave uses. The previous `eventId` SHALL be
read before it is replaced. Provisioning an event link for the **same** event that is already configured
SHALL tear nothing down: it SHALL NOT stop uploads, SHALL NOT fire a leave, and SHALL NOT load or reset
the upload ledger. The backend leave SHALL be best-effort — a failure SHALL NOT prevent the switch — so
the device always ends up provisioned to the new event. The
switch fires the leave **without** a confirmation dialog (the leave-confirm-on-switch dialog is a
separate change).

#### Scenario: Provisioning a different event leaves the previous one

- **WHEN** an event link provisions an `eventId` different from the currently configured event
- **THEN** the flow stops uploads, then issues `DELETE /events/<previousEventId>/devices/<deviceId>` best-effort, then runs the join-time ledger load, then persists the new event's config, all before the upload mechanism is armed

#### Scenario: Re-provisioning the same event fires no leave

- **WHEN** an event link provisions the `eventId` already configured
- **THEN** uploads are not stopped, no backend leave is issued, and the upload ledger is neither loaded
  nor reset

#### Scenario: A failed switch-leave still switches

- **WHEN** the previous-event `DELETE` fails during a switch
- **THEN** the failure is logged and the ledger is still loaded and the new event's config still persisted (the device is provisioned to the new event)

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
leave decision, and a wrong verdict silently reads a joined device as not joined (the consequence is
stated below). Widening that whitelist SHALL therefore be treated as changing the leave decision itself.

A reader that acts on the absence of a config — in particular the upload cycle, for which "no event
configured" means *not joined* and therefore *upload nothing* — SHALL act **only** on a definitely
absent config. On an unreadable config **the upload cycle** SHALL skip entirely: it SHALL NOT touch
the ledger (no write, no clear, no reset), SHALL NOT write the device manifest, and SHALL NOT create
upload jobs; the cycle SHALL complete cleanly and the next cycle SHALL retry. Neither outcome is a
leave: the cycle holds no leave-side action at all — ending a membership, and clearing the upload
ledger with it, is the explicit leave's (capability `leave-event`), never an inference from a read.

This SHALL hold on **every upload tier and at every trigger**, not only where the OS is the
invoker. The tiers differ in who invokes a cycle — the OS on iOS ≥26.1, the app on iOS 18–26.0 —
and not in what an unreadable membership means. A tier SHALL NOT reach this decision through a
two-state read that cannot express "unreadable"; the three-state read is the only permitted path
(capability `upload-lifecycle`, which owns where the decision is made).

Conflating the two is what makes an ordinary locked-device wake read as *not joined*. Because the
not-joined path writes nothing, the upload cycle's cost is bounded to that cycle: it uploads nothing,
and the next readable cycle proceeds as a joined one with its ledger intact. The remaining risk is the
app's: a false absence is a *conclusive* read, so the trigger-time reload replaces the held config with
it (`configAfterReload`, below) and the app treats the device as not joined — the screen regresses to
the setup gate until a later conclusive read restores the membership. A user who answers that gate by
re-scanning the invite reaches a join, whose clear-then-load re-baselines the ledger from the
per-device listing (capability `join-event`). That is why the absence class stays a closed whitelist.

#### Scenario: An unreadable config leaves the ledger and the membership untouched

- **WHEN** an upload cycle reads the config and the read fails because protected data is
  unavailable (the file read fails permission-class before first unlock)
- **THEN** the cycle is skipped, the ledger is not written, cleared or reset, no upload job is
  created, the config file is left intact, and the cycle completes cleanly

#### Scenario: A definitely-absent config reads as not joined

- **WHEN** an upload cycle reads the config and the file is missing by the not-found error class
- **THEN** the cycle takes the not-joined path — it uploads nothing, creates no upload job, and
  writes nothing to the ledger — with no other store consulted

#### Scenario: An unrecognized read error stays unreadable

- **WHEN** the file read fails with an error outside the not-found whitelist
- **THEN** the read reports unreadable and the cycle skips — the classifier's `else` arm never
  admits an unknown error into the absence class, because it is now the only thing standing between
  a misclassified error and an unintended leave

#### Scenario: A joined device stays settled across locked wakes

- **WHEN** a joined device runs cycles repeatedly while locked and its config is unreadable
- **THEN** its ledger is exactly as the last readable cycle left it, and the next readable cycle
  proceeds as a joined cycle — no listing is fetched and the ledger is not re-seeded

#### Scenario: The app-driven tier skips rather than leaves

- **WHEN** the app-driven tier (iOS 18–26.0) runs a cycle from any trigger — foreground, background task,
  silent push, or session events — and the config read fails because protected data is unavailable
- **THEN** the cycle is skipped, the ledger is untouched, and the membership survives — the same
  outcome the OS-invoked tier produces

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
