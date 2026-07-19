# event-rejoin-reconciliation — delta for complete-architecture-migration

## ADDED Requirements

### Requirement: Reinstall semantics stay staged until a post-ship change deletes the read fallback

The reinstall semantics of the config-file migration SHALL remain **staged**, and this requirement
records the staging as contract. The decided end state is **reinstall = left the event** — an
App-Group file dies with the install — but the flip is gated on the read fallback's deletion,
which SHALL be a **designated post-ship change**, not part of the migration branch's ship.

**Stage 1 (in force at ship).** The migration finale ended the 11a Keychain **write-through**
(saves and clears are file-only; the revert direction is sacrificed, consistent with fix-forward),
but the config READ keeps the read-only legacy-Keychain fallback (capability `event-link`): a
definitively-missing file consults the legacy item, resurrects a found membership into the file,
and only file-missing **and** item-absent reads as a leave. **The ship model forces this**: the
migration branch reaches `main` — and therefore every production device — as ONE merge, so at
update time the entire joined installed base consists of pre-11a devices whose config file has
never existed. The per-step TestFlight soak the original 11a→13b staging assumed never happened
on this branch; shipping the fallback's deletion in the same merge that introduces the file would
read every joined device as left on update — a silent, fleet-wide logout. Consequently a
reinstall during Stage 1 (file wiped with the App Group; Keychain item surviving uninstall) still
resurrects the membership — indistinguishable from an update-in-place, by design — and the
pre-existing reinstall behavior (no marker, empty ledger, config present → clear-and-seed
reconciliation, nothing re-uploads) holds unchanged over the resurrected config.

**Stage 2 (a designated post-ship change).** After a production soak — every active joined device
has executed at least one read on a ≥13b build, so its membership has been migrated into the file
— a follow-up change SHALL delete the read-only fallback (`KeychainConfigReader`) and retire the
config pair's runtime-identity pin. Only then does a missing file read as **definitively not
joined** with nothing else consulted: the reinstalled device's first cycle runs the leave-side
reconciliation, uploads nothing, and rejoining requires re-scanning the invite. That change SHALL
carry its own delta to this requirement, collapsing the staging; until it lands, Stage 1 is the
behavior in force.

No stronger reinstall detector (e.g. an install-scoped marker distinguishing reinstall from
update) SHALL be introduced meanwhile: it would flip the semantics for fresh state while the
fallback still resurrects migrated state, buying divergence rather than the end-state truth
(decision record: `changes/archive/migrate-config-to-app-group-file` D5;
`changes/archive/2026-07-19-complete-architecture-migration` D4 records the ship-at-once
reasoning).

#### Scenario: A pre-11a device updates straight to this build and stays joined

- **WHEN** a device joined under a pre-11a (Keychain-only) build updates directly to this build —
  the whole installed base's update path — and the OS schedules the upload extension before the
  user opens the updated app
- **THEN** the first cycle reads the membership through the read-only fallback, migrates it into
  the file, runs no leave-side reconciliation, and leaves the `joinedEventId` marker intact

#### Scenario: A reinstall during Stage 1 still resurrects and reconciles

- **WHEN** the app is deleted and reinstalled (App-Group ledger and config file wiped; Keychain
  item surviving) while the read fallback is in force, and relaunched
- **THEN** the first read resurrects the membership from the legacy item, and the next upload
  cycle finds no `joinedEventId` marker and runs the pre-existing clear-and-seed reconciliation,
  so nothing already stored re-uploads

#### Scenario: The end state arrives only with the post-ship fallback deletion

- **WHEN** the designated post-ship change deletes the read-only fallback after the production
  soak, and the app is thereafter deleted and reinstalled
- **THEN** the first cycle reads definitively-not-joined (no file, no fallback), runs the
  leave-side reconciliation, uploads nothing, and the device rejoins only by scanning the invite
  again

## MODIFIED Requirements

### Requirement: Reconciliation gate before enabling uploads

The **upload tier** SHALL run a join reconciliation on **its own upload cycle**, before creating any
upload jobs, exactly when an event is configured and its `eventId` differs from a persisted
`joinedEventId` marker. The upload tier is whichever process holds the `LedgerWriter` — the extension
on iOS ≥26.1, the app on iOS 18–26.0. The `joinedEventId` marker — **not** ledger-emptiness — SHALL be the join
signal, persisted across the tier's processes. When the configured `eventId` equals the marker, the
tier SHALL NOT fetch, enumerate, or seed, and SHALL proceed to upload. When no event is configured,
the tier SHALL neither reconcile nor upload.

The reconciliation SHALL be driven from the **shared upload cycle** (`UploadCycle`, `:domain`
`feature/upload`), not from each tier's composition root, and the cycle SHALL require a reconciliation to
be supplied — a tier that supplies none SHALL NOT compile. Reconciliation is therefore reached on
**every** route to a divergent ledger: a fresh join, an event switch, a leave-then-rejoin, and a
delete-and-reinstall (which no provisioning path observes, because a cold relaunch of an
already-joined app performs no provision).

#### Scenario: Marker mismatch triggers a join

- **WHEN** the upload tier runs a cycle with an event configured whose `eventId` differs from the `joinedEventId` marker
- **THEN** a reconciliation runs before any upload job is created

#### Scenario: Marker match skips the join

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no fetch, enumeration, or seeding occurs and the producer uploads directly

#### Scenario: No event configured does nothing

- **WHEN** no event is configured
- **THEN** the tier neither reconciles nor uploads

#### Scenario: Both tiers reconcile

- **WHEN** a (re)join occurs on iOS 18–26.0 (the app-driven tier) or on iOS ≥26.1 (the OS-driven tier)
- **THEN** the same marker-gated reconciliation runs on that tier's cycle before any upload job is created

#### Scenario: A cycle reconciles without any provision having run in its process

- **WHEN** a membership exists (e.g. re-joined after a reinstall, or saved by the other process) and a cycle runs in a process where no provisioning path ever executed, with no `joinedEventId` marker and an empty ledger
- **THEN** that cycle reconciles against the per-device listing and seeds already-stored resources as `COMPLETED` so none re-upload — the reconciliation is cycle-resident, never provision-gated

## REMOVED Requirements

### Requirement: Reinstall semantics under the config-file migration are staged

**Reason**: Superseded by the ADDED staging requirement: the finale ends the write-through but KEEPS the read fallback — the branch ships to the installed base as one merge, so at ship time every joined device is pre-11a and a fallback-less flip would log the whole fleet out. Stage 2 (fallback deletion → true reinstall=left) becomes a designated post-ship change gated on production soak (design D4 records the ship-at-once reasoning).
