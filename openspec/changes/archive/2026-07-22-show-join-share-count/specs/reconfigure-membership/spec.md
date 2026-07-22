## ADDED Requirements

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

Raising the cutoff (narrowing) SHALL remain non-retractive per the existing narrowing rule — already-
shared photos are not un-shared — and SHALL NOT require a cursor invalidation.

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

#### Scenario: Raising the cutoff needs no re-enumeration and un-shares nothing
- **WHEN** a member raises the cutoff (narrowing scope)
- **THEN** no discovery cursor is invalidated, no re-enumeration is forced, and photos already shared
  remain shared

### Requirement: The reconfigure surface shows a live count of the photos that will be shared

The reconfigure surface SHALL render the same live shareable-count row the join surface renders
(capability `join-event`, `join-share-count`): beneath the Share switch, `XX photos from your gallery will
be shared`, recomputed as the cutoff choice changes, with the zero-state gloss, the `counting…` state,
hidden when Share is off, and omitted when the photo grant does not permit a count. Because a cutoff-
lowering reconfigure now back-shares the newly-in-scope older photos on every tier (above), the count on
this surface is truthful: the number shown is the number that will be shared.

#### Scenario: The reconfigure surface shows the count for the pending cutoff
- **WHEN** the reconfigure surface is open with Share on and photo access permits a count
- **THEN** a row beneath the Share switch reads `XX photos from your gallery will be shared` for the
  currently-selected cutoff, updating as the member changes it

#### Scenario: Lowering the cutoff on reconfigure raises the count truthfully
- **WHEN** the member drags the cutoff earlier so more of their gallery comes into scope
- **THEN** the count rises to the new in-scope total, and confirming actually shares that many (the older
  photos are re-enumerated and uploaded on both tiers)
