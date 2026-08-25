## MODIFIED Requirements

### Requirement: Consequences are surfaced as inline helper text, never a blocking dialog

The reconfigure surface SHALL communicate the consequences of a change with **inline helper text** and
SHALL NOT gate Save behind a confirmation dialog (Save itself is the confirmation). The helper text SHALL
make clear that turning the album **on** adds only photos synced **from now on** (no backfill).

The helper text SHALL further make clear that a **narrowing** change — raising the cutoff, or turning the
share direction off — **stops the affected photos being listed to the event**, and that this is
**partial**: members who have already received a photo keep it, because a received photo lives in that
member's own library and nothing on this device reaches it. The text SHALL NOT state or imply that a
narrowing change deletes, recalls, or removes photos other members already hold.

This replaces the prior formulation, under which the helper text stated that a narrowing change does **not**
retract photos already shared. That is no longer true of the listing: a narrowing change now re-projects the
device manifest against the new policy (see *A narrowing change retracts the member's listings; leaving does
not*).

Receipt is unaffected: photos this device has already **received** from the event are untouched by any
narrowing change, exactly as before.

#### Scenario: Album-on carries forward-only helper text
- **WHEN** the album toggle is turned on on the surface
- **THEN** helper text states that only photos synced from now on are added to the album

#### Scenario: Narrowing carries partial-retraction helper text
- **WHEN** the member raises the cutoff or turns the share direction off on the surface
- **THEN** helper text states that the affected photos stop being listed to the event, and that members who
  already received them keep them

#### Scenario: The helper text does not overpromise removal
- **WHEN** any narrowing change is offered on the surface
- **THEN** no helper text states or implies that photos are deleted, recalled, or removed from members who
  already hold them

#### Scenario: Save is not gated by a confirmation dialog
- **WHEN** the member taps Save after any combination of changes
- **THEN** the change is applied without an intervening confirmation dialog

### Requirement: Lowering the cutoff re-shares newly-in-scope older photos, on every tier

A reconfigure that **lowers** the cutoff SHALL share the newly-in-scope older photos to the event — uploaded and listed — on the next upload cycle, **on both upload tiers** (the OS-driven PhotoKit tier and the app-driven `URLSession` tier alike). Lowering the cutoff moves `minPhotoDate` earlier, widening the membership's scope above the immutable `startsAt` floor. Because the platform discovery walk is bounded by a persisted,
forward-only change cursor that would otherwise never re-visit unchanged older assets, a cutoff-lowering
reconfigure SHALL **invalidate that discovery cursor** so the next cycle performs a **full re-enumeration
at the new cutoff**; the ledger's `COMPLETED` rows still suppress re-upload of already-shared photos, so
only the genuinely newly-in-scope assets are uploaded. This invalidation SHALL be driven by the shared
domain reconfigure path (`ReconfigureEvent`), so it is **tier-agnostic** and does not depend on any one
producer's start/stop behaviour.

This makes real the widening the capability's purpose already promises ("the worst a member can do is
widen their own contribution above the event's start, visibly and on purpose") and removes the prior
silent divergence where lowering the cutoff back-shared older photos on the PhotoKit tier but not on the
`URLSession` tier.

Raising the cutoff (narrowing) SHALL NOT require a cursor invalidation, because nothing new comes into
scope. It SHALL, however, retract the affected **listings** on the next cycle, per *A narrowing change
retracts the member's listings; leaving does not* — and it SHALL NOT prune the ledger rows for the
now-out-of-scope photos, so a later widening restores their listings without re-uploading a byte.

#### Scenario: Lowering the cutoff shares the newly-in-scope older photos on the PhotoKit tier
- **WHEN** a member on the iOS ≥26.1 PhotoKit tier reconfigures the cutoff from a later instant to an
  earlier one, bringing older in-scope photos into range
- **THEN** the next upload cycle enumerates those older photos and uploads and lists them, none having
  been shared before

#### Scenario: Lowering the cutoff shares the newly-in-scope older photos on the URLSession tier
- **WHEN** a member on the iOS 18–26.0 `URLSession` tier makes the same cutoff-lowering reconfigure
- **THEN** the discovery cursor is invalidated and the next cycle re-enumerates at the new cutoff, so the
  older in-scope photos are uploaded and listed — the same outcome as the PhotoKit tier

#### Scenario: Already-shared photos are not re-uploaded on re-enumeration
- **WHEN** the full re-enumeration after a cutoff-lowering reconfigure re-encounters photos already shared
  under the previous cutoff
- **THEN** their `COMPLETED` ledger rows suppress re-upload, so only the newly-in-scope photos upload

#### Scenario: Raising the cutoff needs no re-enumeration
- **WHEN** a member raises the cutoff (narrowing scope)
- **THEN** no discovery cursor is invalidated and no re-enumeration is forced

#### Scenario: Raising then lowering the cutoff re-lists without re-uploading
- **WHEN** a member raises the cutoff, a cycle runs, and the member then lowers it back
- **THEN** the previously-listed photos are listed again and **no byte is re-uploaded**, because their
  ledger rows were never pruned

## ADDED Requirements

### Requirement: A narrowing change retracts the member's listings; leaving does not

A narrowing reconfigure SHALL cause the device manifest to be re-projected against the new policy on the
next upload cycle, so the photos now outside the membership's scope are **no longer listed** to the event.
A narrowing reconfigure is one that raises the capture cutoff, or turns the share direction off.

The retraction SHALL be confined to the **listing**. It SHALL NOT delete any uploaded byte, and it SHALL NOT
prune any ledger row (capability `sync-ledger`), so a later widening restores the listings without
re-uploading.

The retraction is **partial by nature and SHALL be described as such**: because photos reach members by
being imported into their own libraries, a member who has already received a photo keeps it, and no manifest
change reaches it. A narrowing change stops future receipt and removes the listing; it does not un-share
from a member who already holds the photo.

**Leaving the event SHALL NOT retract.** A departing member's manifest is preserved as the departed
manifest (capability `event-leave-endpoint`), so their contributions remain available to the remaining
members. Narrowing says "this is what I share now"; leaving says "I am done", and the second is not a
retraction request. An option to remove a manifest on leave is a possible future addition and is not part of
this behaviour.

Turning the share direction off SHALL therefore result in an **empty** manifest being published for that
membership, not in the previous manifest being left in place.

#### Scenario: Raising the cutoff removes the out-of-scope listings
- **WHEN** a member raises the capture cutoff above the capture date of a photo they previously shared, and
  the next upload cycle runs
- **THEN** the re-projected device manifest no longer lists that photo, so a member who has not yet synced
  does not receive it

#### Scenario: Turning share off publishes an empty manifest
- **WHEN** a contributing member turns the share direction off and the next upload cycle runs
- **THEN** an empty device manifest is published for that membership, replacing the previous listing

#### Scenario: A narrowing change deletes no bytes and prunes no rows
- **WHEN** any narrowing reconfigure takes effect
- **THEN** no uploaded object is deleted and no ledger row is pruned

#### Scenario: A member who already received the photo keeps it
- **WHEN** another member has already downloaded a photo, and the contributing member then narrows their
  scope to exclude it
- **THEN** the photo remains in the receiving member's library, unaffected

#### Scenario: Leaving preserves the member's listings
- **WHEN** a member leaves the event
- **THEN** their manifest is preserved as the departed manifest and their contributions remain available to
  the remaining members — leaving retracts nothing
