# Design

## Context

What exists today (verified 2026-10-01):

- **Listing text.** `metadata/app-info/en-US.json` and `metadata/version/current/en-US.json` are hand-written asc files
  with a `{{domain}}` placeholder. `scripts/resolve-deployment.py`'s `render_metadata` copies every committed
  `metadata/**/*.json` that contains the placeholder into `build/metadata/`, substituting it. `ci.yml`'s `metadata`
  gate runs `asc metadata validate --dir build/metadata`, which decodes `app-info/` and `version/` strictly and ignores
  everything else. `deploy.yml`'s `appstore-metadata-apply` pushes `build/metadata` to the editable App Store version
  on every main push.
- **Screenshots.** The six iPhone raws in `screenshots/` are captured by `screenshots.yml` (macOS, dispatch-only)
  through `CaptureShots.kt`, which is `simctl` throughout. `compose_screenshots.sh` composites the light set onto
  1320×2868 at promote time. The site imports the raws by explicit path, so a `screenshots/android/` subdirectory
  touches nothing.
- **Play.** `play_release.py deliver` opens ONE edit (upload bundle → internal release → commit). `android-deliver`
  runs on a push to main **and on a branch `workflow_dispatch`**.
- **Icon.** `scripts/appicon.py` is already a parametric master: two splayed rounded cards on a 0–100 grid, XOR'd, a
  sun punched out, on a corner-to-corner emerald gradient, rasterised with Pillow to `Icon-1024.png`.
- **Merged release manifest.** `FOREGROUND_SERVICE` (untyped, WorkManager's), no `FOREGROUND_SERVICE_*` type
  permission, no `AD_ID`, `POST_NOTIFICATIONS` (merged from Firebase, never requested), the four media permissions and
  `READ_EXTERNAL_STORAGE` ≤32; `minSdk` 30, `targetSdk` 36.
- **Privacy Policy** (`site/src/pages/index.astro`): describes the iPhone only, as the proposal says.

## Goals / Non-Goals

**Goals:**
- One committed source of listing copy per locale; the asc files and Play's fields are both generated from it.
- The Play listing changes only when its rendered text or images change, and only from `main`.
- Every Play image and icon derives from the one icon geometry.
- Every Console answer is in the repo with its justification.

**Non-Goals:**
- Automating the Console's App-content forms. They change rarely, and the Publisher API does not cover most of them.
- Play's release notes, promotion, tracks other than internal (5e, phase 6).
- Tablet, TV or Wear listings; any locale other than en-US.

## Decisions

### D1. The icon master stays code: extend `appicon.py`, do not hand-draw an SVG

`appicon.py` gains a vector emitter over the SAME constants. It writes the Android layers as VectorDrawables:
- **Background.** The gradient.
- **Foreground.** The mark scaled so its farthest point sits on the 66dp safe circle.
- **Monochrome.** The same path in one colour.

The card path is a rounded rectangle whose corner points are turned about its centre (a circular arc stays circular
under rotation). The even-odd XOR and the sun cut-out become one `fillType="evenOdd"` path, so VectorDrawable renders
it natively and no PNG density buckets are needed (`minSdk` 30 ≥ 26). `--check` flattens the same outlines,
rasterises them, and fails if they move away from the raster mark. A wrong turn direction measured 7% of pixels off;
the real path measured 0.

**No standalone SVG file.** The handoff asked for a "committed SVG master". The master already exists as code, and a
separate `.svg` would have no consumer: the VectorDrawables ARE the vector outputs, and the site derives its icons
from `Icon-1024.png`. A file nothing reads is dead weight.

The same module renders the 512×512 Play icon (opaque, full-bleed, the iPhone icon at 512) and the 1024×500 feature
graphic: the mark, "SnapSync Photos" and the App Store subtitle as a tagline, centred on the gradient. It is set in
Liberation Sans, the face of the screenshots' headlines. All raster outputs are committed, like `Icon-1024.png`, so CI
uploads exactly the bytes it hashes. The font therefore matters only to whoever regenerates them, and the script
refuses to run without it.

*Alternatives considered:*
- A hand-drawn SVG, with `appicon.py` reading it: two sources of the same geometry.
- Raster-only mipmaps: a themed icon needs a monochrome layer anyway, and vectors cover every density.

### D2. The listing source and its renderer

`metadata/listing/en-US.json`:

```json
{ "name": "SnapSync Photos",
  "description": "… right in your {{gallery}} …",
  "urls": { "marketing": "https://{{domain}}", "support": "…", "privacyPolicy": "https://{{domain}}/#privacy" },
  "words": { "gallery": { "apple": "Photos app", "play": "gallery" } },
  "apple": { "subtitle": "…", "keywords": "…", "promotionalText": "…" },
  "play":  { "shortDescription": "…" } }
```

- **Where the renderer lives.** It stays in `resolve-deployment.py`: `render_metadata` already owns `{{domain}}` and
  `build/metadata/`, and every job that needs the listing already runs the resolver.
- **Per-store rendering.** For each store, `{{domain}}` comes from the deployment and every other `{{token}}` from
  `words.<token>.<store>`. An unknown token, or a word missing for one store, fails the render.
- **Output layout.** It writes `build/metadata/app-info/en-US.json`, `build/metadata/version/current/en-US.json` (the
  paths `asc_metadata_apply.sh` and the validate step read today, unchanged) and `build/metadata/play/en-US.json`
  (`title`, `shortDescription`, `fullDescription`, `contactWebsite`).
- **What it stops copying.** The old template copy is narrowed so `listing/`, `play/` and `screenshots/` are no
  longer copied verbatim.
- **Deletions.** The hand-written `app-info/` and `version/current/` are deleted from the repo.
- **Play validation.** `scripts/validate_play_listing.py` checks title ≤30, short ≤80, full ≤4000 (Play counts
  characters) and that no `{{` survives. There is no offline Play validator, so this check is ours. It runs in the
  `metadata` gate beside `asc metadata validate`, and `resolve_deployment_test.py` covers the renderer.

*Alternatives considered:*
- A separate renderer script: a second entry point for the same placeholder.
- Jinja: a dependency the resolver deliberately does not have; it runs on a bare `python3`.

### D3. Play delivery: diff-gated, main only, inside the one edit

`play_release.py deliver` gains `--listing <dir>` and `--images <dir>`. `android-deliver` passes them only when
`github.ref == 'refs/heads/main'`. A branch dispatch keeps today's behaviour exactly, because Play's listing is not
per-track and a dispatched branch's draft copy must never reach it.

Inside the edit, after the bundle and the release:
- **Text.** `GET edits/{id}/listings/en-US` and `GET edits/{id}/details` are compared field by field with the
  rendered `title`, `shortDescription`, `fullDescription`, `contactWebsite` and `contactEmail` (the email comes from
  the `ASC_REVIEW_CONTACT_EMAIL` secret, the App Store review contact, passed as `PLAY_CONTACT_EMAIL`; empty means the field is left as Play has it). Only a difference is `PATCH`ed.
- **Images.** For each image type (`icon`, `featureGraphic`, `phoneScreenshots`), `GET …/images/{type}` gives each
  image's `sha256`. If the ordered list differs from our files' sha256s, the script runs `deleteall` and uploads them
  in order.
- **Logging.** One line per field and image type, `unchanged` or `updated`, so "most merges send nothing" is visible
  in the run.

A new read-only verb, `listing-diff <package> <listing> <images>`, runs the same comparison in an edit it then
deletes, for a local preview under `secrets-env`.

The privacy-policy URL is NOT in the Publisher API: it is a Console field, entered once in the guided steps. Play has
no support-URL field; the support link lives in the description and the website field.

*Alternatives considered:*
- A separate apply job: two edits race, since a commit invalidates the other open edit.
- Applying every merge: a Google review per merge.

### D4. Play screenshots: captured on the emulator, composited in CI

**Capture.** `CaptureShots` is split into the shot loop over a small `Screen` interface (launch, terminate, await
gone, appearance, settled screenshot, the adapter folder's state reset) with two implementations:
- **`Simulator`.** Today's, over `simctl`.
- **`AndroidEmulator`.** Over `adb`:
  - `am start -W` / `am force-stop` to launch and terminate;
  - `cmd uimode night yes|no` for the appearance;
  - `exec-out screencap -p` for the screenshot;
  - `run-as app.snapsync` for the rig folder's state files, which live in the app's private storage, not on the
    host;
  - SystemUI demo mode for a clean status bar (clock 9:41, full battery and signal, no notifications).

The adapter choice is the same `CHOICE`: Android has a mock for every system.

**Workflow.** `screenshots.yml` gains an `android` job on ubuntu with KVM, reusing ci.yml's emulator install
(`android_sdk_install.sh`) and its pre-created AVD on a 1080×2400 phone profile. Its raws are uploaded as
`screenshots-android-raw`. An operator eyeballs them and commits them to `screenshots/android/`.

**Compositing.** `compose_screenshots.sh` gains a `play` target: a 1080×1920 (9:16) brand canvas with the same
headline and the rounded shot scaled to fit. That passes Play's "long side ≤ 2× short side" rule, which a raw
1080×2400 breaks, and meets the ≥1080 px recommendation. It writes with `-strip` and PNG time chunks excluded, so an
unchanged input composites to unchanged bytes and the hash diff stays quiet. The composite runs in `android-deliver`
(ubuntu, ImageMagick 6), on main only.

*Alternatives considered:*
- Committing the composited Play images: every headline edit would need a local re-run, and the iPhone path
  deliberately composites in CI.

### D5. The Privacy Policy wording

Per the `privacy-security` delta:
- **Push token.** Issued by Apple on iPhone and by Google (Firebase Cloud Messaging) on Android, and used only for
  silent wake-ups.
- **App-integrity check.** Apple App Attest on iPhone. On Android, the phone's hardware-backed key attestation, which
  our server checks against Google's public certificate list; the app sends Google nothing for it.
- **Providers.** Google joins the list: push wake-ups on Android and distribution through Google Play. "We rely on
  three service providers" becomes four.

The always-rendered text no longer needs the `playStoreUrl`-conditional install-referrer paragraph to be the only
place Google appears.

### D6. Declarations are a document, entered by hand

`metadata/play/declarations.md` follows `metadata/review/notes.md`'s style. It has one section per Console form,
each with the answer and the reason, and covers:
- target audience;
- app access;
- ads;
- content rating;
- Data safety;
- photo & video permissions;
- foreground service;
- user-generated content;
- category and contact;
- countries;
- pre-launch report.

The user's two calls, minimal Data safety and location sharing "No", are recorded as theirs, with the
deferred-widening trigger. Countries are read from App Store Connect through `asc-portal` and listed.

## Risks / Trade-offs

- **Play re-encodes uploaded images**, so the listed `sha256` never matches ours and the screenshots re-upload on
  every merge. → Measured 2026-10-02, in edits that were deleted, never committed. Play lists the uploaded file's own
  sha256 and sha1 for both an icon and a screenshot. After a full write, a re-read in the same edit reports every
  text field, the details and every image set unchanged. Byte comparison holds.
- **An ImageMagick or font change on the runner image changes composite bytes**, giving one spurious screenshot
  update and one listing review. → Accepted; it is rare and harmless.
- **The Android capture is unproven.** `run-as` needs a debuggable build, and the rig build is one. The emulator's
  first frames may show the setup wizard or a notification. → Eyeball before committing, as for the iPhone, and
  re-dispatch if needed.
- **Minimal Data safety and location "No" may be judged under-declared**, now with a policy that names the device
  ID and push token. → Widen both when Play flags it; recorded in `declarations.md`.
- **User-generated content.** Play may demand in-app reporting or blocking. → Stop: that is a new phase and an
  OpenSpec change.
- **The pre-launch report's virtual devices fail attestation.** → Bugsink noise, accepted.

## Migration Plan

1. Merge. The first main run renders the listing. `appstore-metadata-apply` pushes identical App Store text, apart
   from the description's platform word, which is unchanged for iOS. `android-deliver` writes Play's text and images
   for the first time.
2. Guided Console steps, in this order:
   1. privacy-policy URL;
   2. app access;
   3. ads;
   4. content rating;
   5. target audience;
   6. Data safety;
   7. photo & video permissions;
   8. foreground-service check;
   9. category and contact;
   10. countries;
   11. pre-launch report.
3. Rollback: revert the PR. Play keeps the last listing it was given, and nothing else depends on it.
