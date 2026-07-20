# Partial (limited) photo access — device probe findings

**Date:** 2026-07-20 · **Device:** iPhone SE2 (`00008030-0018703A1A7A402E`), **iOS 26.5** ·
**Build:** dev IPA off branch `partial-access` (commits `8f7e980`, `2da2c6e`)

Probe for the question: *can `.limited` photo access be accepted as a first-class way for a member
to hand-pick which photos are contributed?* Today `permission-gate` maps `.limited → DENIED`.

The probe build maps `.limited → GRANTED` (crude on purpose — the real change adds a fourth
`LIMITED` state) so the upload path actually arms, and adds census logging on both sides of the
app/appex process split. **This branch is not shippable.**

All log lines below are verbatim from `Documents/debug.log` of the two processes.

---

## 1. PhotoKit narrows the walk silently — confirmed

Library held 7 assets; 2 were selected.

```
limited probe: app authorizationStatus=limited (PARTIAL) raw=4
limited probe: raw library count=2          ← 7 under full access
```

`PHAsset.fetchAssetsWithOptions` returns only the selection, with **no error and no signal**. This
is what makes the "selection is invisible to the domain" design viable: the enumerator
(`PhotoLibraryResourceEnumerator`) is already authorization-blind by construction, so every
downstream filter (cutoff, origin exclusions, echo suppression) applies unchanged over a smaller
input set.

It is also why the *current* `.limited → DENIED` mapping is load-bearing rather than cosmetic:
without it, the pre-existing code would silently report "In sync" over a truncated library.

## 2. ⛔ The iOS ≥26.1 OS-driven tier does NOT run under `.limited`

The decisive result, from a differential test in which the **app-side action was identical** in both
conditions (the probe hack reported `GRANTED` under limited too, and the extension was re-registered
in both) — only the real OS authorization differed.

```
09:01:36  FULL     extension fires, library count=2, "2 seen / 0 new"
09:09:05  LIMITED  background-upload extension re-registered (provisionEvent)
09:09:19  LIMITED  background-upload extension re-registered (arm.onPermissionGranted)
          LIMITED  ── 22 minutes, real pending work, ZERO invocations ──
09:31:21  FULL     extension fires WITHIN SECONDS of restoring full access
                   library count=7, discovered 14 resources, "12 seen / 12 new" → PROCESSING
```

The OS simply declines to invoke `process()` on `PHBackgroundResourceUploadExtension` while the
containing app holds a partial grant. **Registration succeeds and lies** — no error, no callback,
nothing to observe. Shipped naively, this would present as "uploads mysteriously never happen on the
newest OS".

**Consequence:** unknown (a) — *does the appex inherit the app's limited selection?* — is **moot and
unanswerable on this tier**, because the extension never runs at all.

## 3. ✅ The app-driven URLSession tier DOES upload under `.limited`

Forced via `SNAPSYNC_FORCE_URLSESSION_UPLOAD` with 2 photos selected (1 own, 1 previously-downloaded
and therefore echo-suppressed → `N=1`):

```
enumeration: 2 seen, 2 new, 0 already-uploaded
[SyncEngine] completed key=51A5CB2C-…_L0_001-live.mov     attempt=0
[SyncEngine] completed key=51A5CB2C-…_L0_001-primary.heic attempt=0
PUT  /events/6df5dcaf…/devices/4A2A03CF… → 201     (device manifest)
POST /events/6df5dcaf…/notify            → 202
```

Both resources uploaded on the **first attempt**. The whole cycle — discovery, echo suppression,
cutoff, origin exclusions, manifest, notify — behaved normally over the narrowed input.

## 4. ✅ Album creation works under `.limited`; ⛔ denylist membership lookup is a no-op

```
limited probe: ensureCreated -> 9F2E284D-9AEA-467E-86FB-A5C16E656FAF/L0/040
limited probe: exists(albumId) -> true
```

Album *creation* verified repeatedly under both full and limited access, so `event-album`'s
`saveToAlbum` opt-in and the download half are not blocked by a partial grant.

**Denylist membership lookup — RESOLVED, negative.** Set up the exact real-world case: took a photo,
added it to a **"WhatsApp"** album, and added that same photo to SnapSync's limited selection. The
selection then held 3 assets **including the WhatsApp photo** (`3CC1885F…`, visible in
`raw library count=3`), yet:

```
limited probe: assetIdsInAlbums(denylist) -> 0 asset(s)
```

with **no** `denylisted album '…'` match line — so `PHAssetCollection.fetchAssetCollectionsWithType`
did not surface the WhatsApp album even though a selected asset is its member.

**Under `.limited` the denylist is a silent no-op.** This is expected PhotoKit semantics: limited
access grants access to specific *assets*, not the *album structure*, so the app cannot enumerate user
albums or test membership. A user who hand-picks a WhatsApp/Telegram photo under limited access **will
upload it** — the denylist cannot catch it.

Severity is low, and it aligns the runtime with the spec's own stance: `photo-selection-policy` R13
already declares the denylist poor-recall and **not** the primary mechanism for excluding received
media — the resolution floors are, and those read pixel dimensions off the asset itself, which **does**
work under `.limited`. So a limited membership simply falls back to the mechanism the spec already
relies on. The spec should state the denylist is inert under `.limited`.

(One residual ambiguity, non-blocking: the probe logs the net result, not the album-walk's total count,
so "0 albums returned" vs "albums returned but none matching" is not separated. Given limited-access
semantics and that a member-selected album still wasn't found, "no user albums enumerable under
limited" is the overwhelmingly likely reading.)

## 4b. ✅ Creating photos is NOT restricted under `.limited` — the download half is safe

`.limited` scopes what the app can **read**, not what it can **add**. Measured by seeding 3 synthetic
assets (`SNAPSYNC_SEED_PHOTOS=3`, which uses `PHAssetCreationRequest`) while holding a partial grant:

```
seeded 3/3 asset(s)
limited probe: raw library count=5        ← was 2 (the two hand-picked)
  asset[0] 08D0C4F9… created=2001-01-01 00:02:00  64x64      ← newly created
  asset[3] 8B2EF6B7… created=2001-01-01 00:00:00  64x64      ← newly created
  asset[4] 8F55D500… created=2001-01-01 00:01:00  64x64      ← newly created
```

Creation succeeded 3/3 with no error, and the count went 2 → 5: **app-created assets are auto-added
to the limited selection at creation time**. Album creation also works (finding 4).

**✅ RESOLVED — creating assets does NOT trigger the alert.** Measured cleanly on device (iOS 26.5):
with the app **unjoined** (so it performs **zero** `PHAsset` fetches — no membership → no status
refresh, no upload walk) and the alert queue drained empty, launched `SNAPSYNC_SEED_PHOTOS=5` (pure
`PHAssetCreationRequest`). Result, confirmed across two runs — one where the only prior fetch had queued
exactly **1** alert (so the 5 creates added **0**), and a second from a drained queue that produced a
**clean screen, no dialog**:

```
15:54:21  seeding 5 synthetic asset(s)   ← PHAssetCreationRequest ×5
15:54:21  seeded 5/5 asset(s)            ← NO "gallery: enumerated" line (fetch-free)
→ screen: "Start an event", no alert
```

The measured asymmetry:

| operation | API | triggers the limited-access alert? |
|---|---|---|
| create an **album** | `PHAssetCollectionChangeRequest` | no |
| create an **asset** | `PHAssetCreationRequest` | **no** (2 clean runs) |
| **fetch** assets | `PHAsset.fetchAssets…` | **yes** |

rahul.bio's claim that asset creation triggers it is **wrong on iOS 26.5**. The scope-based hypothesis
holds: the alert concerns read *scope*, and a created asset auto-joins the selection with no scope
ambiguity, so iOS does not prompt.

**Consequence — the download half is clean under `.limited`.** A `DownloadOnly` limited membership
performs **no `PHAsset` fetch** (upload walk `SKIPPED` on `Contribution.None`;
`OwnDeviceGalleryStatusSource.refresh(Contribution.None)` returns early with `N=0`, no enumeration;
import reconcile is HTTP, not a library fetch) and its only `PHAsset` op — the import
(`PHAssetCreationRequest`) — does **not** trigger the alert. So a download-only limited membership is
**storm-free and works today**. `photo-download` and `event-album` are genuinely unaffected.

**This narrows finding 5's blocker to the upload/status *read* path only.** Only memberships that
*upload* (and therefore walk the library) hit the alert storm. The redesign (make reads user-initiated
+ `PHPhotoLibraryChangeObserver`-driven) only has to touch the **read** path — the download path needs
no change.

Two nuances:

- **Auto-add is creation-time only and does NOT survive a later downgrade.** When the device was
  switched from full to limited, the 13 previously-imported assets did *not* reappear in the
  selection — the count was exactly 2, the user's picks. So a member who imports an event's photos
  under full access and later switches to limited will find those photos no longer visible to
  SnapSync. Benign (they are already downloaded, and echo suppression means they were never upload
  candidates), but it should be stated rather than discovered.
- **Imported photos joining the selection are not upload candidates**, because the download store's
  echo suppression already excludes them — this is the "13 suppressed" visible in every walk above.
  No new mechanism is required.

## 5. ⚠️ The automatic limited-access alert must be suppressed — and the app must own the picker

Under a partial grant iOS auto-presents its *"Select More Photos / Keep Current Selection"* alert on
the first library touch **per app session**. SnapSync re-fetches on every foreground and every
reconcile, so the alert fired repeatedly and made the app unusable on device.

Fixed on this branch, and **both halves are mandatory**:

- `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` in `iosApp/iosApp/Info.plist`
- `presentLimitedLibraryPicker()` in `:adapter:ios:app-only` — verified working on device

Suppressing the alert *without* offering the picker would strand a limited user with no route to
widen their selection from inside the app. This is what the designed **"Choose more photos"** status
row exists to drive; it is not an optional affordance.

**Measured three ways** (key verified present in the *installed* bundle via `pymobiledevice3 apps
list` → `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = True`; the last two rows are from a **clean
install** carrying the key from first launch):

| condition | behavior |
|---|---|
| **without** the key | alert on essentially every library touch — re-appeared immediately after each answer; app unusable |
| **with** key — steady state (limited already granted) | ✅ **clean.** A cold launch with 2 photos already selected reached the normal status screen with **no** alert; the gallery walk (`enumerated 4 … → N=2`) touched the library and nothing fired. This is the everyday case. |
| **with** key — during the *initial* limited grant | ⚠️ **still storms.** While the user was in the first-grant picker selecting photos, the automatic alert fired repeatedly *on top of the picker* — the app's foreground/reconcile re-fetch loop hits the library during the grant transition, and the key does not suppress it in that window. It settled to "In sync" once the grant completed. |

**⚠️ Corrected conclusion — the plist key does NOT reliably suppress the alert on iOS 26.5.** This
supersedes TWO earlier drafts (one calling the key "ineffective", one calling it "fully working" with a
clean steady state). Both were wrong; the truth is worse than the second draft.

The decisive observation: after taking a new photo (a library change) and letting SnapSync re-fetch,
the alert **stormed** — and when SnapSync was then **killed (confirmed: no process in `proclist`), the
alert kept appearing on the bare home screen.** These are **queued** alerts: iOS accumulated one per
library touch while the app hammered the library, and they drain one-by-one afterward, surviving the
app's death. Each had to be dismissed by hand with "Keep Current Selection".

This means:

- The earlier "steady state is clean at 15:08" was a **single-screenshot timing artifact** — one frame
  captured before a queued alert surfaced. Not a reliable clean state.
- Every `PHAsset` fetch under `.limited` appears to queue an automatic alert **despite**
  `PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` (verified present in the installed bundle).
  The app fetches on every foreground and every reconcile, so the queue grows fast.
- A **library change** (user takes a photo) while the app is running re-triggers the storm — it is not
  confined to the initial grant.

**This is now a first-order design problem, not an implementation detail.** Partial access as probed
is **not usable** on iOS 26.5 unless the alert can be genuinely suppressed.

### 5a. Root cause — from research, not the device (why the key doesn't save us)

Documentation and an Apple-engineer forum response explain the storm, and the cause is **SnapSync's
architecture, not a broken plist key**:

- **The automatic alert is triggered by calling the `PHAsset` API (fetch/create) under a limited
  grant.** The community guidance is explicit: *"calls to the PHAsset API should be made carefully…
  you cannot trigger it in the background or when the user is not expecting it. This code should only
  be executed when the user is trying to access the gallery"* (Swift Senpai; echoed by rahul.bio:
  *"whenever you want to use the PHAsset API to create or fetch the assets… user will see the system
  alert which is annoying if it is not in the right flow"*).
- **`PHPhotoLibraryPreventAutomaticLimitedAccessAlert` is documented-unreliable.** An Apple engineer
  confirmed on the developer forums that even with the key set to `true`, the picker/alert **still
  appears when the selection changes** (Settings-side or otherwise) — the key suppresses the *routine
  first-access* prompt, not every path. Our two storm triggers (initial grant, and taking a photo)
  are both selection-change events, i.e. exactly the case the key does **not** cover.
- **PhotosUI.framework must be linked** for `presentLimitedLibraryPicker` (forum thread 737847) — we
  do import it, so that box is checked.

**The collision:** SnapSync is an *autonomous background sync* app. It fetches `PHAsset`s on **every
foreground, every reconcile, every silent-push wake, and every upload cycle** — none of which is "the
user is trying to access the gallery". Under a limited grant, each such off-flow fetch can surface the
automatic alert, and because SnapSync fetches constantly (and rapidly, while foreground/background
cycling around the picker), the alerts **queue** — which is why they kept draining onto the home screen
after the app was killed. This is a structural conflict between limited access (PHAsset calls must be
user-initiated) and SnapSync's whole model (PHAsset calls are OS/timer-driven and invisible).

### 5b. What this means for the design

Partial access is **not a small additive feature** — it collides with the core sync model. Two ways
forward, both real work:

1. **Make library reads user-initiated + observer-driven.** Fetch the selection **once** on an explicit
   user action, cache the result, and refresh **only** via a `PHPhotoLibraryChangeObserver` callback —
   never on foreground/reconcile/push/cycle. This is the Apple-sanctioned pattern and the only thing
   likely to stop the storm. It is a non-trivial change to `OwnDeviceGalleryStatusSource`, the
   `UploadCycle` walk, and the flow triggers — all of which currently fetch autonomously.
2. **Confirm residual alerts are acceptable.** Even done perfectly, the key won't suppress the alert on
   a Settings-side selection change (per Apple). That one is unavoidable and one-time; acceptable.

Until (1) is prototyped and shown to tame the storm, the "Choose more photos" UX and the whole
partial-access feature are **blocked**. The upload-tier findings (§2/§3) are necessary but not
sufficient — this is the harder problem.

**Sources:** [Swift Senpai — Photo Library Permission](https://swiftsenpai.com/development/photo-library-permission/) ·
[Apple Developer Forums thread 650911 (engineer response on the key)](https://developer.apple.com/forums/thread/650911) ·
[Apple Developer Forums thread 737847 (PhotosUI linkage)](https://developer.apple.com/forums/thread/737847) ·
[rahul.bio — Hidden quirks in iOS PhotoKit limit access](https://www.rahul.bio/blog/limit-access-ios-api) ·
[WWDC20 — Handle the Limited Photos Library](https://developer.apple.com/videos/play/wwdc2020/10641/)

Placement note: the API is `presentLimitedLibraryPickerFromViewController:`, a **PhotosUI** category
on `PHPhotoLibrary` (not Photos). PhotosUI + UIKit are both banned in extension-linked source by the
extension-safety gate, so it can only ever live in `:adapter:ios:app-only`.

## 6. The app notices a selection change without a relaunch

After the in-app picker was dismissed, the own-device walk went `0 → 4 resources` with no relaunch
(14:13:04 → 14:13:17), and a subsequent cycle uploaded. The refresh rode the existing
foreground/reconcile path. Whether that is reliable enough on its own — versus registering a
`PHPhotoLibraryChangeObserver` — was **not** isolated by this probe and remains open.

---

## What this forces in the design

The interview settled on "add a `LIMITED` state and let the selection stay invisible to the domain".
Finding 2 breaks the "and everything else falls out" half of that: on iOS ≥26.1 — which is where the
SE2 and every current GM device sits — a limited membership **cannot** use the shipping upload tier.

The options, all now grounded rather than speculative:

1. **Permission state becomes an input to tier selection.** `resolveComposition` today keys purely on
   OS version; a `LIMITED` membership on ≥26.1 would fall back to the app-driven URLSession tier,
   which finding 3 proves works. Cost: the tier split stops being a pure OS-version function, and the
   app-driven tier's background behavior (its whole reason for being replaced on ≥26.1) applies to
   these members.
2. **Offer partial access only on iOS 18–26.0**, keeping `.limited → DENIED` on ≥26.1. Cheapest, but
   the newest OS gets the worst behavior, and the difference is invisible to the user.
3. **Don't ship it.** Finding 2 is a real platform constraint, not an implementation gap.

Option 1 is the only one that delivers the feature as asked on current devices.

## Open questions this probe did not settle

- Denylisted-album membership under `.limited` (finding 4) — needs a device with such an album.
- Whether a `PHPhotoLibraryChangeObserver` is needed, or the foreground/reconcile refresh suffices
  (finding 6).
- **The alert queue (finding 5) — now the #1 blocker.** Confirm whether
  `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` is honored on iOS 26.5 at all, and whether
  fetching once-per-grant + `PHPhotoLibraryChangeObserver` (instead of every foreground/reconcile)
  stops the queue building. The feature is not shippable until this is resolved.
- Whether the OS-driven tier's refusal is permanent or a schedulability heuristic that might relent
  over hours. 22 minutes with pending work and two registrations is strong but not infinite evidence.
  ⏰ Re-evaluate at iOS 27 GM (~Sept 2026), alongside the existing
  `PHBackgroundResourceUploadJobExtension` re-eval trigger.

## Probe hygiene

- The branch maps `.limited → GRANTED`. **Never promote it.**
- `ensureCreated` runs on every probe launch and PhotoKit's creation request does not dedupe, so the
  device accumulated several albums named **"SnapSync limited probe"** — delete by hand.
- Finding 4b seeded **3 synthetic 64×64 assets dated 2001-01-01** into the real library. They never
  upload (three orders of magnitude below the 3 MP floor, and dated before any plausible cutoff), but
  they need hand-deletion like any seed — `deleteAssets` always raises a system confirmation.
- The reinstall lost the Keychain-held device identity (`FD82A0DB…` → `4A2A03CF…`), so the device
  re-registered as a new member. Unrelated to partial access, but it perturbed this event's
  membership.
