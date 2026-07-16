## MODIFIED Requirements

### Requirement: Event switch versus re-join

The upload tier SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When
they **match** (a relaunch or re-provision of the already-joined event) the switch is a no-op: no
seed, no cursor clear, no re-projection, no marker write. When they **differ** — an event switch, a
reinstall with no marker, or a fresh provision — the tier SHALL **`resetTo`** (atomic clear-and-seed)
the ledger from the per-device listing; **clear the discovery cursor** to force a full re-enumeration;
**keep** the device-global accumulator intact and **re-project** the device manifest (`device.json`) to
the **new** event's storage path; and set the `joinedEventId` marker to the configured `eventId`. The
clear-and-seed makes the ledger exactly the device's stored files — dropping stale/phantom rows —
while the device-global listing re-seeds the same files `COMPLETED`, so nothing already stored
re-uploads; the cursor clear re-enumerates to find genuinely-unstored work (the App-Group cursor
survives an app upgrade, so without it a re-join would scan incrementally and find nothing).

After a **leave** (config absent), **no** lifecycle path SHALL clear the ledger or the accumulator
(`upload-lifecycle`, "Upload producer seam has no destructive verb"). The tier SHALL clear
the `joinedEventId` marker **only**, on its next cycle, while **keeping** the ledger and the
accumulator intact (the ledger is device-global and valid across events), so a subsequent provision of
any event runs a fresh reconciliation without losing dedup.

The property this defends is **dedup**: the ledger's `COMPLETED` rows are device-global and stay true
across a leave, a switch, and a re-join, so clearing them would re-upload every already-stored resource on
the next join (`sync-ledger`). The **discovery cursor is not dedup state** — a tier's `stop()` may clear it
as a repair for its own mechanism (`upload-lifecycle`), and this reconciliation clears it itself whenever
it re-baselines. Either way the cost is one full re-enumeration that finds nothing new, because the ledger
it did not touch still knows what is stored.

#### Scenario: Re-provision of an already-joined event is a no-op

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no seed, no cursor clear, no re-projection, and no marker write occur; the ledger, cursor, and accumulator are unchanged

#### Scenario: A different event resets-and-seeds and clears the cursor

- **WHEN** the configured `eventId` differs from the marker
- **THEN** the ledger is `resetTo` (clear-and-seed) from the per-device listing, the discovery cursor is cleared, the accumulator is kept and `device.json` is re-projected to the new event path, and the marker is set — with the global listing re-seeding the same files `COMPLETED` so nothing already stored re-uploads

#### Scenario: A reinstall restores via the same clear-and-seed

- **WHEN** the marker is absent and the ledger is empty (a reinstall) for a configured event
- **THEN** the `resetTo` from the per-device listing restores the `COMPLETED` rows, the cursor is cleared, and the marker is set

#### Scenario: Leaving clears the marker but keeps dedup

- **WHEN** the user has left an event (config absent) and the upload tier next runs
- **THEN** the tier clears the `joinedEventId` marker **only** and keeps the ledger and accumulator intact, so provisioning any event afterward runs a fresh reconciliation and re-uploads nothing already stored

#### Scenario: No lifecycle transition wipes the ledger

- **WHEN** a leave, an event switch, a re-provision, a permission change, or a direction change occurs on either tier
- **THEN** the ledger is never cleared by that transition; only a triggered reconciliation's `resetTo` ever re-baselines it

#### Scenario: A cleared cursor costs a re-enumeration, not a re-upload

- **WHEN** a transition leaves the discovery cursor cleared — by a tier's own `stop()` repair, or by this reconciliation's re-baseline
- **THEN** the next cycle enumerates the whole in-scope library and creates **no** upload job for anything already `COMPLETED`, because dedup lives in the ledger, not in the cursor
