> **Capability rename.** This capability was `photo-date-cutoff`; the base spec and this delta folder were
> renamed to `photo-selection-policy` together (the delta model cannot express a capability rename —
> precedent: `changes/archive/2026-07-04-add-url-session-upload`). Archived changes keep the old name.

## RENAMED Requirements

- FROM: `### Requirement: One cutoff gates both byte upload and manifest listing`
- TO: `### Requirement: One policy gates both byte upload and manifest listing`

- FROM: `### Requirement: The cutoff scopes the own-device status total`
- TO: `### Requirement: The policy scopes the own-device status total`

- FROM: `### Requirement: Cutoff byte-upload filter over the shared upload cycle`
- TO: `### Requirement: Selection filter over the shared upload cycle`

## MODIFIED Requirements

### Requirement: One policy gates both byte upload and manifest listing

A membership's **selection policy** — its capture-date cutoff together with the origin exclusions — SHALL
gate **both** which of the device's photo bytes are uploaded **and** which of its assets are listed in that
event's device manifest. The two SHALL use the **same** admitted set, so the set uploaded equals the set
listed. Because the event union exposes each device's manifest-listed assets to other members, the policy
thereby governs both this device's backup scope and what other members can download from it. A photo excluded
by the policy SHALL neither have its bytes uploaded nor appear in the manifest (and therefore SHALL NOT enter
the event union).

A photo excluded by the policy but listed in the manifest would be strictly worse than one merely uploaded:
it would enter the event union and every other member would attempt to download bytes that were never
uploaded.

#### Scenario: An in-scope photo is both uploaded and shared
- **WHEN** a photo's `creationDate` is at or after the membership's cutoff and no origin exclusion applies
- **THEN** its bytes are uploaded and it is listed in the device manifest (eligible for the event union)

#### Scenario: An out-of-scope photo is neither uploaded nor shared
- **WHEN** a photo's `creationDate` precedes the membership's cutoff
- **THEN** its bytes are not uploaded and it is not listed in the manifest, so no other member can download it

#### Scenario: An origin-excluded photo is neither uploaded nor shared
- **WHEN** a photo is at or after the cutoff but is excluded by an origin rule (for example a screenshot)
- **THEN** its bytes are not uploaded **and** it does not appear in the device manifest, so it never enters
  the event union and no other member downloads it

### Requirement: The policy scopes the own-device status total

The own-device upload **total** `N` SHALL count only the device's own assets that the **selection policy
admits** — at or after the cutoff **and** not origin-excluded — the same set the upload cycle admits (`N` is
the count driving the joined screen's sync health, capability `sync-status`). An asset the policy excludes
SHALL NOT count toward `N`, because it is never uploaded; counting it would peg completeness permanently below
100% and hold the screen at "pending" forever. Because a membership always carries a cutoff, the total is
always a scoped count.

The status total and the upload cycle SHALL apply the **same** policy. They are computed by different
components (`OwnDeviceGalleryStatusSource` in the app process, `UploadCycle` in the upload path), so this
identity is a requirement, not an implementation coincidence.

#### Scenario: A pre-cutoff asset does not inflate the total
- **WHEN** the library holds a pre-cutoff asset and an in-scope asset, and the in-scope asset is uploaded
- **THEN** the total counts only the in-scope asset, so the joined screen reaches "in sync" (not a
  perpetual "pending")

#### Scenario: An origin-excluded asset does not inflate the total
- **WHEN** the library holds a screenshot taken after the cutoff, and every admitted asset is uploaded
- **THEN** the screenshot does not count toward `N`, and the joined screen reaches "in sync" rather than
  holding permanently below 100%

#### Scenario: The total is always scoped by a cutoff
- **WHEN** the own-device total is computed for a joined membership
- **THEN** it is computed against that membership's non-null cutoff, with no unscoped whole-library branch

### Requirement: Selection filter over the shared upload cycle

The shared upload cycle SHALL drop from byte upload every discovered resource that the selection policy does
not admit — whose owning asset's `creationDate` precedes the applicable cutoff, **or** which any origin
exclusion rejects — **before the resource reaches the ledger/engine**. The filter SHALL be applied to **both**
the full enumeration and the incremental change-token walk, and SHALL be **tier-agnostic** (it governs the
OS-driven PhotoKit extension tier and the app-driven `URLSession` tier alike, since both funnel through the
shared cycle). The applicable cutoff SHALL be expressed as the **minimum** cutoff across the device's current
memberships — so a photo is uploaded when it is in scope for **at least one** joined event — which in v1
(single membership) reduces to that membership's single cutoff. The applicable cutoff is always non-null. The
engine and ledger SHALL remain policy-blind; the exclusion happens entirely in the cycle's resource selection.

The filter in the cycle's resource selection SHALL remain the **authoritative** exclusion, and SHALL live in
the **platform-free upload-cycle core**, not in untested platform wiring, so it is exercised in `commonTest`. A
platform enumeration MAY additionally narrow its fetch (by capture date, media subtype, or pixel dimensions)
as an optimization, but the cycle's filter SHALL still run over whatever that fetch returns, so **a platform
fetch can never widen or narrow the admitted set**.

The origin exclusions SHALL be applied **before** the device-manifest hook, whereas the capture-date cutoff
SHALL NOT be. The origin exclusions are **event-independent** (a screenshot is a screenshot in every event),
while the cutoff is **per-membership** — so pre-filtering by origin costs the device-global accumulator no
per-event flexibility, while pre-filtering by date would (capability `device-manifest`).

#### Scenario: Pre-cutoff resources never reach the engine
- **WHEN** the cycle discovers a resource whose asset `creationDate` precedes the cutoff
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: Origin-excluded resources never reach the engine
- **WHEN** the cycle discovers a resource whose owning asset an origin rule rejects
- **THEN** the resource is dropped before the engine and before `retainAssets`, so no upload job is created
  and the ledger gains no entry for it

#### Scenario: The filter covers the incremental walk
- **WHEN** the incremental change-token walk surfaces a changed asset the policy does not admit
- **THEN** that asset is excluded, exactly as in the full enumeration

#### Scenario: The admitted set is the minimum across memberships
- **WHEN** the device has memberships with cutoffs `C1` and `C2`
- **THEN** a resource is admitted for upload when its `creationDate >= min(C1, C2)` (in v1 this is the single membership's cutoff)

#### Scenario: A platform fetch narrowed by date or origin does not change the admitted set
- **WHEN** the platform enumeration returns a superset of the admitted assets (for example because its
  predicate was deliberately widened, or because it cannot express an exclusion the policy makes)
- **THEN** the cycle's filter still excludes every non-admitted resource, so the admitted set is identical to
  that of an unnarrowed fetch

#### Scenario: The manifest sees the origin-filtered set but not the cutoff-filtered set
- **WHEN** a cycle discovers a screenshot and a pre-cutoff camera photo
- **THEN** the device-manifest hook is fed neither the screenshot (origin-excluded before the hook) nor, in
  the event's manifest, the pre-cutoff photo (excluded by the per-event date projection) — while the
  pre-cutoff photo remains in the device-global accumulator and the screenshot does not

## ADDED Requirements

### Requirement: Origin exclusions admit on doubt

The selection policy's origin rules SHALL exclude an asset only on a **certain** signal, and SHALL admit it
otherwise. Where a rule cannot distinguish a received or generated asset from a captured one, the asset SHALL
be **admitted**.

This posture is asymmetric on purpose. An event photo that fails to upload is a silent failure of the
product's core promise, with no surface on which the user could even notice it, let alone correct it; a stray
uploaded meme is visible, harmless, and recoverable. The policy therefore SHALL NOT adopt any rule whose
failure mode is dropping a genuine capture — in particular, it SHALL NOT infer capture-origin from an
allowlist of camera pixel dimensions (which excludes every cropped photo and every panorama) nor from an
allowlist of `originalFilename` shapes (which excludes third-party camera apps).

#### Scenario: An unrecognized asset is admitted
- **WHEN** an asset matches no origin exclusion rule, and the policy cannot establish that it was received or
  generated rather than captured
- **THEN** the asset is admitted for upload

#### Scenario: A full-resolution received photo is admitted
- **WHEN** a full-resolution photo is received via AirDrop or saved from Messages, carrying no album
  membership and no excluded media subtype
- **THEN** the asset is admitted — the policy makes no attempt to exclude it, and this is a known, accepted gap

### Requirement: Screenshots, screen recordings and animated images are excluded

The policy SHALL exclude every asset whose `mediaSubtypes` carries the **screenshot** bit (`1 << 2`) or the
**screen-recording** bit (`1 << 19`), and every asset whose **primary** resource MIME content type is
`image/gif`.

None of these three is a camera capture under any reading. Screenshots are the highest-frequency non-captured
asset in a typical library, and both bits are exact — this is the one rule with perfect recall.

#### Scenario: A screenshot is excluded
- **WHEN** a discovered asset's `mediaSubtypes` has the screenshot bit set
- **THEN** it is excluded from upload and from the manifest, whatever its capture date

#### Scenario: A screen recording is excluded
- **WHEN** a discovered asset's `mediaSubtypes` has the screen-recording bit set
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A GIF is excluded
- **WHEN** a discovered asset's primary resource MIME content type is `image/gif`
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A camera photo carrying other subtypes is admitted
- **WHEN** a discovered asset carries only non-excluded subtype bits (for example panorama, HDR, live photo,
  or depth effect)
- **THEN** it is admitted — those subtypes are all camera captures

### Requirement: Resolution floors exclude compressed received media

The policy SHALL exclude an **image** whose pixel area is below **3 megapixels**, and a **video** whose pixel
area is below **1280 × 720**, **unless** the asset reports adjustments (`hasAdjustments`), in which case the
floor SHALL NOT be applied to it.

The floors SHALL be fixed `commonMain` constants and SHALL NOT be derived from the device's actual camera
dimensions at runtime. A device-derived floor is *tighter* on a better camera, and tighter means more false
drops — the opposite of the admit-on-doubt posture. The chosen floor sits more than 2× below the weakest
camera on the oldest supported device, while still sitting above the output of every common messaging app.

The **separate video floor is load-bearing, not a refinement**: 1080p video is 1920 × 1080 = 2.07 MP, which is
*below* the 3 MP image floor. A single shared floor would silently drop every 1080p recording — and 1080p is
the iOS capture default.

The **`hasAdjustments` guard is likewise load-bearing**: a photo cropped in Photos renders at its cropped size
and may fall under the floor despite being a genuine capture.

#### Scenario: A compressed received image is excluded
- **WHEN** a discovered image asset is 1600 × 1200 (1.9 MP) and reports no adjustments
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A camera photo is admitted
- **WHEN** a discovered image asset is 4032 × 3024 (12.2 MP)
- **THEN** it is admitted

#### Scenario: A cropped camera photo below the floor is admitted
- **WHEN** a discovered image asset is below 3 MP **and** reports `hasAdjustments`
- **THEN** it is admitted — the floor is not applied to an edited asset

#### Scenario: A 1080p recording is admitted
- **WHEN** a discovered video asset is 1920 × 1080 (2.07 MP, below the image floor)
- **THEN** it is admitted — the video floor is 1280 × 720, not the image floor

#### Scenario: A compressed received video is excluded
- **WHEN** a discovered video asset is 848 × 480 and reports no adjustments
- **THEN** it is excluded from upload and from the manifest

### Requirement: Denylisted album membership excludes an asset

The policy SHALL exclude every asset that is a member of an album whose title matches, **case-insensitively
and exactly**, an entry in a denylist of messaging- and social-application album titles. The denylist SHALL be
a `commonMain` constant.

The denylist SHALL match **user albums by title only**. Smart albums SHALL be matched by **subtype**, never by
title, because a smart album's title is system-localized.

Recall here is **known to be poor and is accepted**: on current iOS most messaging applications save directly
to the camera roll and create no album at all, and only WhatsApp is confirmed to create one (and only when its
"Save to Camera Roll" setting is enabled). The rule is retained because it is cheap — its cost is proportional
to the number of albums, not the number of assets — and strictly additive. It SHALL NOT be relied upon as the
primary mechanism for excluding received media; the resolution floors are.

#### Scenario: An asset in a denylisted album is excluded
- **WHEN** a discovered asset is a member of an album titled `WhatsApp`
- **THEN** it is excluded from upload and from the manifest

#### Scenario: Title matching is case-insensitive and exact
- **WHEN** an album is titled `whatsapp`
- **THEN** it matches the denylist entry `WhatsApp`; an album titled `WhatsApp Backup` does not match

#### Scenario: An asset in a non-denylisted album is admitted
- **WHEN** a discovered asset is a member of a user album titled `Holiday 2026`
- **THEN** it is admitted — album membership excludes only against the denylist

### Requirement: Album membership is read through a decision-free platform seam

The album-membership lookup SHALL be exposed as a **decision-free** platform verb: it takes a set of album
titles and returns the member asset identifiers, bounded by the capture-date cutoff. The platform SHALL supply
**facts only**; the **policy** — which titles are denied — SHALL live in tested `commonMain`, per the rule
(capability `event-album`) that no album decision may live in the untestable app or extension shell.

The lookup's cost SHALL be proportional to the number of denylisted albums, not to the number of assets in the
library: it SHALL NOT be implemented as a per-asset membership test.

#### Scenario: The platform seam makes no policy decision
- **WHEN** the album-membership seam is invoked
- **THEN** it receives the titles to look up as a parameter and returns the matching member asset identifiers,
  applying no denylist of its own

#### Scenario: The lookup is bounded by the cutoff
- **WHEN** the album-membership seam is invoked for a membership whose cutoff is `C`
- **THEN** it returns only member assets whose capture date is at or after `C` — the seam never enumerates the
  whole album
