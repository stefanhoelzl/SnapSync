# Google Play — App content declarations

Every answer SnapSync gives in Play Console's **Policy → App content** forms and its store settings, with the reason
for each. Like `metadata/review/notes.md` for App Review, this is the record. The Console is where the answers live,
and they are entered **by hand**: the Publisher API does not reach these forms, and they change rarely. Change an
answer here first, in a PR, then in the Console.

Facts this file leans on were read from the tree on 2026-10-02:
- **Permissions:** the merged release manifest of `:app:android` (`processReleaseMainManifest`).
- **App Store territories:** App Store Connect's app availability.
- **What leaves the device:** the Privacy Policy (`site/`, capability `privacy-security`).

Entered in the Console on 2026-10-02, with the first closed release (build 2118).

Two answers are the **operator's explicit choice against the recommendation**: the minimal Data safety form and
"No" to location sharing. Each is marked below, with what would reopen it.

## Store settings

| Field | Answer |
|---|---|
| App name | SnapSync Photos (from `metadata/listing/`; written by the delivery) |
| Category | **Events** (App → Events; chosen in the Console over Photography: the app is for events) |
| Tags | none (the operator's choice) |
| Contact email | the `ASC_REVIEW_CONTACT_EMAIL` secret, the App Store review contact (written by the delivery; never committed) |
| Website | the marketing URL (written by the delivery) |
| Phone | none |
| Form factors | **phones only**: no tablet, Chromebook, TV, Wear or XR screenshots or listing |
| Pre-launch report | **on** (see below) |

## Privacy policy

`https://snapsync.stho.net/#privacy`, entered by hand: the Publisher API has no field for it. It describes the
Android case too: the push token comes from Google (Firebase Cloud Messaging), the integrity check is the phone's key
attestation, and Google is among the providers.

## App access

**All functionality is available without special access.** There are no accounts, logins or demo credentials.

Instructions, the Android wording of `metadata/review/notes.md`:

> SnapSync shares photos among people at the same event. No account or sign-up is required.
>
> TESTING ON A SINGLE DEVICE
> 1. Open the app and create an event (any name).
> 2. Allow photo access when asked.
> 3. Take a photo with the phone's Camera app.
>
> The photo is shared to the event and the status screen counts up to "In sync". Only photos taken within the
> event's date range are shared, and a new event's range starts when you open the create screen, so older photos are
> never uploaded. On Android the app shares the photos in the phone's camera folders (DCIM), where the Camera app
> saves them.
>
> Photos from other people need a second device: show the event's QR code on the first device and scan it with the
> second. Photos then flow both ways.

## Ads

**No, the app does not contain ads.** There is no ad SDK, and the merged manifest declares no
`com.google.android.gms.permission.AD_ID`.

## Advertising ID

**No**, the app does not use an advertising ID, for the same reason.

## Content rating (IARC questionnaire)

- **Category, as entered:** **All other app types** ("Alle anderen App-Typen").
- **Ratings issued (2026-10-02):** USK 0 · PEGI 3 · ESRB Everyone · ClassInd L (0) · IARC Generic 3+ · Russia 3+ ·
  South Korea 3+, with **no content descriptors and no interactive elements** listed. The questionnaire WAS answered
  "users can share content": the issued rating shows no interactive element even so.
- **Users interact or exchange content:** **Yes**, photos, within a private, invite-only event.
- **Shares the user's current location with other users:** **No.** *(The operator's choice.)* The app shares no
  location of its own. A photo goes out as it was taken, so it carries the place in its metadata if the camera recorded
  it. That is treated as part of the photo, consistently with Data safety below.
  **Reopen** if Play's review flags photo location as location sharing: then answer Yes, and widen Data safety with it.
- **Digital purchases:** No. **Unrestricted web access:** No (the app shows no web content).
- Violence, sexual content, language, controlled substances, gambling: **None.** The app provides none of this
  content itself.

## Target audience and content

- **Age groups:** **13–15, 16–17, 18 and over.** Teens are real users: a class trip or a family holiday is the kind of
  event the app is for.
- **Excluded:** every group under 13, which keeps the app outside the Families policy and its certified-SDK and
  child-data obligations.
- **Appeals to children:** **No.** Nothing in the listing or the app is designed for children.

## Data safety  *(minimal, the operator's choice)*

**Data collected:**

| Data type | Collected | Shared | Optional | Purpose |
|---|---|---|---|---|
| Photos and videos → **Photos** | Yes | No | Yes: joining with sharing off sends none | App functionality |
| Photos and videos → **Videos** | Yes | No | Yes: joining with sharing off sends none | App functionality |
| App info and performance → **Crash logs** | Yes | No | No | Analytics (app stability) |

- **"Shared: No".** Play's definition of sharing excludes a transfer the user initiates and expects. Photos reach
  the event's members because the user joined that event with sharing on.
- **Videos are declared in their own right:** the app shares videos as well as photos (capability `photo-sharing`).
- **Processed ephemerally: No** for every type: photos and videos are stored until the event's photos are deleted,
  crash reports in Bugsink.
- **What the minimal form treats as part of those types, rather than declaring separately:**
  - a photo's **location** (its metadata, when the camera recorded it), as part of the photo;
  - the random **install ID** and the **push token**: they exist only to move the event's photos (telling devices
    apart, waking a device for new photos), so they are part of providing the photos;
  - the **bug report** a user sends by hand, and the activity logs it carries: part of crash logs.
- **Security:** data is **encrypted in transit** (HTTPS only).
- **Deletion:** users **can request deletion** at the contact email, and an event's photos are deleted automatically
  once everyone has them, never later than 30 days after the event is created or starts.
- **Accounts:** none; the app has no account creation.

**Reopen** if Play's review says this is under-declared (location, the device or other IDs, or diagnostics). Then
declare the named type with its purpose and change the content rating's location answer in the same step. The Privacy
Policy already names all of them, so only the form would change.

## Photo and video permissions

The manifest requests `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED`
(Android 14+ partial access) and `ACCESS_MEDIA_LOCATION`, plus `READ_EXTERNAL_STORAGE` up to API 32.

**As entered** (the Console's form has one field per permission, at most 250 characters each; access answered as
frequent, not one-off):

- `READ_MEDIA_IMAGES`: *Core feature: every photo a member takes during an event is shared automatically and
  continuously, also in the background, with the event's other members. The photo picker only grants items picked
  once, so photos taken later would be missed.*
- `READ_MEDIA_VIDEO`: *Core feature: every video a member records during an event is shared automatically and
  continuously, also in the background, with the event's other members. The picker only grants items picked once,
  so videos recorded later would be missed.*

**Core use, the full argument:**

> SnapSync's core function is sharing, automatically and continuously, every photo a member takes during an event
> with the event's other members. That includes photos taken after joining, while the app is in the background. This
> needs ongoing read access to the photos the camera saves. The system photo picker cannot do it: it grants access only
> to items the user picks at that moment, and none to photos taken afterwards, so every new photo would need a manual
> pick and the shared album would silently miss photos. A user who prefers to choose can grant partial access
> (Android 14+); the app then shares only the photos they selected.

`ACCESS_MEDIA_LOCATION` keeps a photo's original metadata in the copy that is shared, so the other members receive the
photo exactly as it was taken. This is the same reason as the content-rating and Data safety answers above.

## Foreground service

**Expected not to apply.** The merged manifest declares only the untyped `android.permission.FOREGROUND_SERVICE` and no
`FOREGROUND_SERVICE_*` type permission. It comes from WorkManager, whose `SystemForegroundService` runs expedited work
on Android 11 only (the background-time hold). Confirm in the Console that no foreground-service form appears. If one
does, record its question and answer here.

## User-generated content

**The app does allow users to share content: photos, within a private, invite-only event.** The argument App Review
accepted for Guideline 1.2 (`metadata/review/notes.md`, "SHARED CONTENT"):

> Photos are shared only within a private, invite-only event, the same model as a private shared-album link.
> Joining requires the event's invite: its QR code, shown by a member, or the same link a member sends from the app's
> share button. Possession of the invite IS the invitation. There is no public feed, no discovery, no search and no
> browsing of content from strangers: people only ever see photos from an event whose invite a member chose to give
> them. Only photos taken within the event's dates are shared, and screenshots and pictures saved from chat apps are
> excluded. A member can leave the event at any time, which stops all sharing. Every event's photos are deleted once
> everyone has them, and never later than 30 days after the event is created or starts. Any concern can be reported to
> the published contact email on this listing.

**STOP condition.** If Play requires in-app reporting or blocking regardless of this argument, that is NOT part of
this release work. It is a new phase with its own OpenSpec change (an observable app behaviour). Do not answer the
form in a way that promises features the app does not have.

## News, government, financial, health and COVID-19 forms

- **News app:** No.
- **Government app:** No.
- **Financial features:** none.
- **Health:** no health features.
- **COVID-19 contact tracing or status:** No.

## Countries and regions

**Germany only**, the same as the App Store. App Store Connect, read 2026-10-02: available in 1 of 175 territories
(`DEU`), and not automatically in new territories. Set it on each track that is published (closed testing, then
production). Widen both stores together.

## Pre-launch report

**On**, for free coverage across OEM devices. Its virtual devices attest in software, so the app's attestation fails
there: those errors and the Bugsink reports they cause are expected noise, not regressions.
