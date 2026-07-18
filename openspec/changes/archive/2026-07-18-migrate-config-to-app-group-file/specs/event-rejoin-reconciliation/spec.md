# event-rejoin-reconciliation — delta for migrate-config-to-app-group-file

## ADDED Requirements

### Requirement: Reinstall semantics under the config-file migration are staged

The reinstall semantics of the config-file migration SHALL land in **two stages**, and this
requirement records the staging as contract. Migration step 11a moves the config's storage of
record from the shared Keychain to an App-Group file (capability `event-link`), toward the decided
end state **reinstall = left the event** — an App-Group file dies with the install, so a
deleted-and-reinstalled app finds no membership.

**Stage 1 (this change, the write-through window).** The config read SHALL consult the file first
and, on a definitively-missing file, SHALL fall back to the written-through Keychain copy —
resurrecting a found membership into the file. Consequently a reinstall (file wiped with the App
Group; Keychain item surviving uninstall) SHALL still resurrect the membership, exactly as before
this change: **the missing-file state cannot distinguish a reinstall from an update-in-place**,
and the fallback must resurrect the update-in-place case or the rollout itself would read a false
leave on every joined device whose OS-scheduled extension cycle runs before the user first opens
the updated app (the migration is adapter-resident for the same reason). The pre-existing
reinstall behavior of this capability — no marker, empty ledger, config present → clear-and-seed
reconciliation, nothing re-uploads — SHALL continue to hold unchanged over the resurrected config.

**Stage 2 (a later change, migration step 13b or after).** When the Keychain copy is deleted and
the write-through ends, a missing file SHALL have no fallback to consult and SHALL read as
**definitively not joined**: the reinstalled device's first cycle runs the leave-side
reconciliation (clearing the `joinedEventId` marker), no upload occurs, and rejoining requires
re-scanning the invite. That change SHALL carry its own delta to this requirement, collapsing the
staging; until it lands, Stage 1 is the behavior in force.

No stronger reinstall detector (e.g. an install-scoped marker distinguishing reinstall from
update) SHALL be introduced meanwhile: it would flip the semantics early for this build while a
**revert build** — which reads only the Keychain — still resurrects the membership, so the early
flip would buy build-dependent divergence, not the end-state truth (decision record:
`changes/archive/migrate-config-to-app-group-file`, D5).

#### Scenario: An update-in-place is never read as a leave, whichever process reads first

- **WHEN** a device joined under the Keychain-era build updates in place and the OS schedules the
  upload extension before the user opens the updated app
- **THEN** the extension's first cycle reads the membership through the Keychain fallback,
  migrates it into the file, runs no leave-side reconciliation, and leaves the `joinedEventId`
  marker intact

#### Scenario: A reinstall during the write-through window still resurrects and reconciles

- **WHEN** the app is deleted and reinstalled (App-Group ledger and config file wiped; Keychain
  item surviving) during the write-through window and relaunched
- **THEN** the first read resurrects the membership from the Keychain copy, and the next upload
  cycle finds no `joinedEventId` marker and runs the pre-existing clear-and-seed reconciliation,
  so nothing already stored re-uploads — reinstall behaves exactly as it did before this change

#### Scenario: The end state is reached only by deleting the Keychain copy

- **WHEN** a later change ends the write-through and deletes the Keychain entry, and the app is
  thereafter deleted and reinstalled
- **THEN** the first cycle reads definitively-not-joined (no file, no fallback), runs the
  leave-side reconciliation, uploads nothing, and the device rejoins only by scanning the invite
  again
