# event-rejoin-reconciliation — delta for retire-legacy-config-fallback

## RENAMED Requirements

- FROM: `### Requirement: Reinstall semantics stay staged until a post-ship change deletes the read fallback`
- TO: `### Requirement: Reinstall means the device left the event`

## MODIFIED Requirements

### Requirement: Reinstall means the device left the event

A reinstalled device SHALL read **definitively not joined** — a reinstall is a leave. The config
file lives in the App Group and an App-Group container dies with the install, so a reinstalled
device runs the leave-side reconciliation, uploads nothing, and rejoins only by scanning the invite
again. Nothing besides the file SHALL be consulted to reach that conclusion.

This requirement previously recorded a two-stage migration, and records it now as history rather
than contract. **Stage 1** (migration step 11a through the finale) kept a read-only legacy-Keychain
fallback behind the file read: a definitively-missing file consulted the pre-11a item, resurrected a
found membership into the file, and only file-missing **and** item-absent read as a leave. The ship
model forced it — the migration branch reached `main` as ONE merge, so at update time the entire
joined installed base consisted of pre-11a devices whose config file had never existed, and deleting
the fallback in that same merge would have read every joined device as left: a silent, fleet-wide
logout. **Stage 2** was the designated post-ship change that deleted the fallback and retired the
config pair's runtime-identity pin; it has landed, and this requirement is its collapse. The
per-device migration it performed is complete and is not repeated: a device that never ran a
post-11a build before uninstalling is simply not joined.

Stage 2's gate was *"a production soak — every active joined device has executed at least one read
on a ≥13b build"*. It was discharged by **distribution**, not telemetry (there is none — SnapSync has
no accounts): the fallback shipped in `74d2b848` (step 11a, 2026-07-18) and the finale in `94f0bfe5`
(step 13b, 2026-07-19), **both ancestors of the `v0.1` tag**, and `v0.1` (2026-07-21) is the first
App Store release — so every public install of SnapSync, ever, has been a ≥13b build. The migrating
read also sat in `FileBackedConfigStore`'s constructor, which both composition roots build, so any
process start of such a build migrated the membership without the user opening the app. The residual
population was internal TestFlight installs predating 2026-07-18 that had started no process since;
they read as not joined and re-scan. Decision record:
`changes/archive/…-retire-legacy-config-fallback` D1.

**The absence classifier is now solely load-bearing for this decision.** While the fallback existed,
a *wrong* `Missing` — a read error misclassified into the not-found class — was caught: the fallback
found the legacy item, answered joined, and the device stayed joined. With the fallback gone there is
no second opinion, so `isConfigFileAbsence` (the `NSError` domain/code classifier in
`:adapter:ios:ext-safe`) is the only thing standing between a misclassified read failure and an
**uncaught logout** — marker cleared, ledger clear-and-seeded, discovery cursor reset, screen back on
the setup gate, with no error raised anywhere and nothing to undo it. Its whitelist SHALL therefore
stay closed (`else` answers "not absent"), and widening it SHALL be treated as changing the leave
decision itself, not as an error-handling detail (capability `event-link` states the same rule at the
seam).

No stronger reinstall detector (e.g. an install-scoped marker distinguishing reinstall from update)
SHALL be introduced: the App-Group file's own lifetime **is** the detector, and a second one could
only disagree with it (decision record: `changes/archive/migrate-config-to-app-group-file` D5;
`changes/archive/2026-07-19-complete-architecture-migration` D4 records the ship-at-once reasoning
that produced the staging).

#### Scenario: A reinstall reads as not joined and uploads nothing

- **WHEN** the app is deleted and reinstalled (App-Group ledger and config file wiped) — even on a
  device whose pre-11a legacy Keychain item survived the uninstall — and relaunched
- **THEN** the first cycle reads definitively-not-joined with nothing else consulted, runs the
  leave-side reconciliation, uploads nothing, and the device rejoins only by scanning the invite
  again

#### Scenario: A surviving legacy Keychain item resurrects nothing

- **WHEN** a read finds no config file on a device that still holds the legacy
  `app.snapsync.config`/`eventconfig` item from a pre-11a build
- **THEN** the item is not read, no membership is resurrected, and the read reports no config

#### Scenario: An update in place keeps the membership

- **WHEN** a joined device updates to a build carrying this change (its App-Group config file
  present, as any post-11a process start left it)
- **THEN** the read answers from the file, the membership survives, no leave-side reconciliation
  runs, and the `joinedEventId` marker stays intact

#### Scenario: An unreadable config is still not a leave

- **WHEN** a cycle's config read fails for any reason outside the not-found error class — notably a
  protected-file read before first unlock
- **THEN** the read reports unreadable, the cycle skips, the `joinedEventId` marker is left intact,
  and the next cycle retries; the loss of the fallback narrows what may read as absent, never widens
  it
