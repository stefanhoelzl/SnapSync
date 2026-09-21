## MODIFIED Requirements

### Requirement: Lowering the cutoff re-shares newly-in-scope older photos, on every tier

A reconfigure that **lowers** the cutoff SHALL share the newly-in-scope older photos to the event — uploaded and listed — on the next upload cycle, **on both upload tiers** (the OS-driven PhotoKit tier and the app-driven `URLSession` tier alike). Lowering the cutoff moves `minPhotoDate` earlier, widening the membership's scope above the immutable `startsAt` floor. Every upload walk is a **full enumeration** narrowed at the platform fetch by
the membership's **current** policy (capability `ios-photokit-upload`, "In-extension discovery by full
enumeration"), and the next cycle derives that policy from the reconfigured membership, so its walk
**already** covers the newly-in-scope older assets. The reconfigure SHALL therefore need no discovery-side
action of its own. The ledger's `COMPLETED` rows still suppress re-upload of already-shared photos, so only
the genuinely newly-in-scope assets are uploaded. Because both tiers bind the same walk, the outcome is
**tier-agnostic** and does not depend on any one producer's start/stop behaviour.

This replaces the earlier mechanism, in which the walk resumed from a persisted, forward-only change cursor
that never re-visited unchanged older assets, and a cutoff-lowering reconfigure had to invalidate that
cursor. There is no cursor any more, so there is nothing to invalidate.

This makes real the widening the capability's purpose already promises ("the worst a member can do is
widen their own contribution above the event's start, visibly and on purpose") and removes the prior
silent divergence where lowering the cutoff back-shared older photos on the PhotoKit tier but not on the
`URLSession` tier.

Raising the cutoff (narrowing) brings nothing new into scope. It SHALL, however, retract the affected **listings** on the next cycle, per *A narrowing change
retracts the member's listings; leaving does not* — and it SHALL NOT prune the ledger rows for the
now-out-of-scope photos, so a later widening restores their listings without re-uploading a byte.

#### Scenario: Lowering the cutoff shares the newly-in-scope older photos on the PhotoKit tier
- **WHEN** a member on the iOS ≥26.1 PhotoKit tier reconfigures the cutoff from a later instant to an
  earlier one, bringing older in-scope photos into range
- **THEN** the next upload cycle enumerates those older photos and uploads and lists them, none having
  been shared before

#### Scenario: Lowering the cutoff shares the newly-in-scope older photos on the URLSession tier
- **WHEN** a member on the iOS 18–26.0 `URLSession` tier makes the same cutoff-lowering reconfigure
- **THEN** the next cycle's walk enumerates at the new cutoff, so the older in-scope photos are uploaded
  and listed — the same outcome as the PhotoKit tier

#### Scenario: Already-shared photos are not re-uploaded on re-enumeration
- **WHEN** the walk after a cutoff-lowering reconfigure re-encounters photos already shared
  under the previous cutoff
- **THEN** their `COMPLETED` ledger rows suppress re-upload, so only the newly-in-scope photos upload

#### Scenario: Raising the cutoff deletes no row
- **WHEN** a member raises the cutoff (narrowing scope) and the next cycle's walk no longer returns the
  photos now before it
- **THEN** their ledger rows are kept, because they are outside the walk's window and so are not evidence
  of absence (capability `sync-ledger`, "Deletion is a presence diff over an authoritative walk")

#### Scenario: Raising then lowering the cutoff re-lists without re-uploading
- **WHEN** a member raises the cutoff, a cycle runs, and the member then lowers it back
- **THEN** the previously-listed photos are listed again and **no byte is re-uploaded**, because their
  ledger rows were never pruned
