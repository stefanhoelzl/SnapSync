# CLAUDE.md

SnapSync — an iOS app for **sharing photos from an event** (Kotlin Multiplatform + Compose), shipped
via TestFlight. Join an event by scanning its QR, and your photos taken since a per-device
**capture-date cutoff** are shared to it while everyone else's arrive in your library. The JVM desktop
app is test equipment, not a product.

> It began as a *personal one-way photo backup*. Defaults inherited from that era are dangerous here:
> what was "back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's
> event". A membership's cutoff is therefore **required**, never absent.

What a member contributes is decided by **one** policy at **one** place (capability
`photo-selection-policy`, enforced in `UploadCycle`'s resource selection): the cutoff bounds *when* a photo
was taken; the **origin exclusions** bound *what it is* — screenshots, screen recordings, GIFs,
sub-floor-resolution received media, and members of a denylisted album (WhatsApp, Telegram, …) never enter
an event. PhotoKit exposes **no** camera-origin flag on any iOS through 26, so the policy can only
*subtract* known non-captures and **admits on doubt**: a stray uploaded meme is harmless and visible, while
an event photo that silently fails to upload is invisible and unfixable. The same policy gates the byte
upload, the device manifest (or an excluded photo leaks into the event union), **and** the status total `N`
(or the screen pegs below 100% forever).

Stack: Kotlin 2.4.0 · Compose MP 1.11.1 · JDK 25 · min iOS 18.0 · Orbit MVI · SQLDelight · Ktor.
(Two upload tiers per OS version: OS-driven PhotoKit on iOS ≥26.1, app-driven background `URLSession`
on iOS 18–26.0 — see the `ios-photokit-upload` / `ios-url-session-upload` specs.)
(`gradle/libs.versions.toml` is the source of truth for versions.)

## Read first

There is no narrative design doc. Two OpenSpec trees carry the whole design:

- **`openspec/specs/<capability>/spec.md`** — the **contract of record**. One spec per capability, each
  a `## Purpose` (what it is for, and why) plus `## Requirements` (SHALL/WHEN/THEN). A spec is
  authoritative for its own contract; it never defers that to a doc outside `openspec/`.
- **`openspec/changes/archive/<id>/`** — the **decision record**. Each archived change holds
  `proposal.md` (what & why) and `design.md` (`## Context`, `## Goals / Non-Goals`, `## Decisions`) —
  this is where rationale, rejected alternatives, and trade-offs live. Specs cite theirs as
  `Decision record: changes/archive/<id>`.

Read the spec for the capability you are changing, then its decision record before altering behavior.

## Build & test

- `./gradlew build` — the canonical check (compiles all targets + runs JVM tests). **No display
  needed**: the Compose Desktop UI tests (`:domain:ui:jvmTest`) render offscreen under
  `-Djava.awt.headless=true` (set on that task in `domain/ui/build.gradle.kts`), so no X server /
  Xvfb is required. Only the two harness run tasks — `:app:desktop:ui:run` (forge) and
  `:app:desktop:run` (full-stack world, below) — open a real window and need a display.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source
  sets: it compiles `iosMain`/`commonMain` (and cinterop) without a Mac, so you can catch
  iOS-only breakage here. The actual iOS tests (`iosSimulatorArm64Test`, etc.) are **macOS-only**
  and run on GitHub Actions `macos-26`.

## Test UI (review/exercise every UI state)

`./gradlew :app:desktop:ui:run` launches the forge harness (module `:app:desktop:ui`): the real
`:domain:ui` status screen inside a phone-sized frame on the left, and a **control panel** on the right
(raw Material 3 — it is test equipment, never `App*`). The panel **forges any display state** — permission presets,
sync-state presets, and the engine console — so you can review and test all UI states without a
device. See the `desktop-test-harness` spec.

`./gradlew :app:desktop:run` launches the **full-stack world harness** (module `:app:desktop`): the same
real status screen on the left — but its counts **emerge** from `:test:world`'s real `LedgerBackedSyncStatusSource`
(never forged) — and a right-pane **world inspector** (raw M3) that drives the real stack: presets,
**Invoke extension** (one `process()`-shaped cycle + download reconcile), the gallery/backend, the
upload-job queue and downloads, failure levers, and an engine-console footer. The operator plays the OS
(nothing auto-runs). See the `full-stack-harness` spec.

### Driving either harness headlessly (agent runbook)

Both `run` tasks open a real window and need a display — useless to an agent, which can neither see nor
click one. `:test:harness-driver` serves **either harness over HTTP with no window at all**: it composes
the shipped root (`ForgeHarnessRoot()` / `WorldHarnessRoot()`) into an **offscreen** Compose scene — a
CPU raster Skia surface, no AWT peer, no `Robot` — so it needs **no X server** and never raises the
desktop's screen-capture consent prompt. **Never use `java.awt.Robot` or capture the real screen `:0`
instead**: it prompts the user for portal consent on every run, and blocks until they answer.

It is **dev infrastructure, non-gating, no spec** (same posture as `ssh-mac.yml`; rationale in
`Driver.kt`). Clicks go through the **real** buttons of the real panel, so there is no second
way-to-drive that can rot or lie.

```
./gradlew :test:harness-driver:driveForge   # forge harness (:app:desktop:ui), 800x950
./gradlew :test:harness-driver:driveWorld   # full-stack world (:app:desktop), 1240x950
# Run it backgrounded; it blocks serving until /quit or 30 min idle.
# `B=...` must be its OWN statement, and use -sS (not -s). `B=... curl "$B/x"` expands $B BEFORE the
# assignment applies, so curl gets a hostless URL — and plain `-s` silences the error, so it looks
# like the driver is dead when the command is simply wrong. Capital S keeps errors visible.
B="http://127.0.0.1:$(cat test/harness-driver/build/harness-driver.port)"
curl -sS "$B/health"                                    # harness=world scene=1240x950
curl -sS "$B/tree"                                      # phone-pane semantics (~700 tokens)
curl -sS "$B/tree?scope=all"                            # whole window (~9.7k tokens — mostly chrome)
curl -sS --get --data-urlencode "text=▶ Invoke extension" "$B/click"
curl -sS "$B/click?text=%E2%9C%93&index=0"              # per-row controls NEED index=
curl -sS -o shot.png "$B/phone.png"                     # the 390x844 pane; /shot.png = whole window
curl -sS "$B/quit"
```

- **The port is OS-assigned and written to `test/harness-driver/build/harness-driver.port`** — inside
  this worktree. That is deliberate: every CodeHydra workspace is its own worktree, so a fixed port
  would let two agents silently drive each other's world. Read the file; never hardcode a port.
- **Select a node** with `text=` (button label), `tag=`, or `desc=` (content description), plus
  `index=` and `substring=true`. `index=` is **required** for the world inspector's per-job `✓` `✕`
  `Net` `Http` `Cxl` `Unk` — one row per job, so those labels are ambiguous by construction.
- **`/click` settles before answering** (`waitForIdle()`), so a `200` means the state is stable. It also
  `performScrollTo()`s first, since both panels scroll and off-viewport controls are otherwise unclickable.
- **The operator plays the OS — including acknowledgement.** `✓` on a job does *not* complete it: it
  deposits the object store-direct and stages an ack that **the next `▶ Invoke extension` records as
  `COMPLETED`**. Completing every job and expecting "In sync" without a second invoke will look like a
  bug and isn't. A completed-but-unacked job stays listed, so `index=0` twice hits the *same* row.

## On-device iOS (agent-driveable over USB)

The iOS PhotoKit upload extension is **physical-device-only** (no simulator support; spec
`ios-photokit-upload`). It ships against the **deprecated iOS 26.1** `PHBackgroundResourceUploadExtension`
— the only protocol runnable on current GM devices — and
its *upload trigger* (`process()`) is OS-scheduled — it cannot be forced. But everything around
it — install, **launch**, **screenshot**, event-subscribe, logs — is **scriptable headless over
USB, no root and no Mac**. Reach a connected iPhone through the host's usbmuxd — **this is specific
to the codehydra sandbox** (the host socket is bridged at `/run/host/run/usbmuxd`).

Lockdown-level tools (no developer tunnel needed):

```
export USBMUXD_SOCKET_ADDRESS=UNIX:/run/host/run/usbmuxd
idevice_id -l          # list connected devices
ideviceinfo            # device details (UDID, model, iOS version)
idevicesyslog          # live device log — watch the app/extension at runtime
idevicecrashreport .   # pull crash reports
```

**Developer services — the `--userspace` unlock.** Launch, screenshot, and the rest of the DVT
surface need iOS 17+'s RemoteXPC tunnel + a mounted DeveloperDiskImage, which normally want root
and **hang over the usbmux bridge** (`idevicescreenshot`/`idevicedebug` fail here for this reason).
`pymobiledevice3 --userspace` builds the tunnel **in-process — no root** — and auto-mounts the DDI;
it needs **Python ≥3.14**, so pin it via `uvx --python 3.14`. Use the **bare** socket path (no
`UNIX:` prefix). Verified on the SE2 (iOS 26.5):

```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
P="uvx --python 3.14 pymobiledevice3"
$P developer dvt launch app.snapsync --userspace                 # launch (prints the pid)
$P developer dvt screenshot shot.png --userspace                 # real screen capture (auto-mounts the DDI)
$P developer dvt launch app.snapsync \                           # subscribe to an event headlessly:
  --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=<base64url({\"eventId\":\"<uuid>\"})>" --userspace
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log   # pull the file logger (re-provision line, etc.)
```

**Seeding a large photo library.** `SNAPSYNC_SEED_PHOTOS=<n>` is a second dev/test launch-env trigger
(`app/ios/.../DevPhotoSeeder.kt`): on launch the app creates `<n>` synthetic `PHAsset`s dated from
2001-01-01 forward, one minute apart, so the capture-date-bounded walk can be exercised against a large
library on device. ~85 s for 4000 assets on an SE2. Inert in production for the same reason as
`SNAPSYNC_EVENT_LINK` (a launch env var is only injectable via a developer launch).
**It writes to the real photo library** — deleting the assets again needs taps (`deleteAssets` always
raises a system confirmation), which is why they are parked in one year of the Photos timeline. Use it on
a dev device only.
```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_PHOTOS=4000 --userspace
```
Why it matters: the walk's cost is one synchronous PhotoKit XPC round-trip **per asset**
(`assetResourcesForAsset`, ~110 ms each on an SE2), so ~90 assets exhaust the 10 s scene-update watchdog.
A one-photo dev device cannot distinguish a bounded fetch from an unbounded one.

**These seeds never upload, by design** — they are dated 2001, i.e. before any plausible cutoff. (They are
also 64×64, three orders of magnitude below the selection policy's 3 MP image floor, so they are doubly out
of scope.) They exercise the *walk*, not the upload.

**Seeding for the selection policy.** `SNAPSYNC_SEED_POLICY=<n>` seeds `n` assets dated **an hour ahead**
— past any cutoff an event created today can carry (the cutoff is clamped to `max(chosen, startsAt)`) —
**alternating above and below the 3 MP floor**. It exists because a dev device may hold *no real photos at
all*, and without an asset the policy admits, a run cannot tell "the policy correctly excluded everything"
from "the fetch predicate silently returned nothing" — and the wrong predicate form returns **zero rows
without raising**, so that is precisely the confusion that matters. One launch answers everything: the walk
returns assets, exactly the below-floor half is `origin-excluded`, and only the rest uploads.
```
$P developer dvt launch app.snapsync --env SNAPSYNC_SEED_POLICY=20 \
  --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=<…fresh event…>" --userspace
```
Read the outcome from the two log lines the policy emits **before any HTTP call** — so an attestation `401`
can never be mistaken for an exclusion:
- app: `gallery: enumerated N resource(s) … (M origin-excluded) → N=…`
- extension: `origin policy dropped N resource(s)`

`SNAPSYNC_EVENT_LINK` is a **dev/test trigger** (capability `ios-app-shell`): on launch the app
forwards it through the same path as a scanned QR, (re)provisioning the event. It is read **once per
process** and is inert in production (a launch env var is only injectable via a developer launch).
**It bypasses AASA entirely** — it hands the URL straight to the decoder, so it exercises the
decode→gate→join path and proves **nothing** about whether the Universal Link actually resolves. To test
the *link*, tap one (see *Verifying the event link* below).
**Note:** re-provision no longer forces a fresh whole-library upload — it **reconciles against storage**
(`event-rejoin-reconciliation` seeds already-stored photos as `COMPLETED` before any upload job is
created), so a relaunch against an event that already has objects uploads **nothing new**. The reconcile
runs inside the shared `UploadCycle` and is a **required** constructor parameter, so it holds on **both**
tiers. (It did not always: it was wired per-composition-root and the iOS 18–26.0 tier shipped without one,
so a switch / leave-then-rejoin / reinstall re-uploaded the whole post-cutoff library. Fixed in
`changes/archive/…-fix-app-driven-upload-lifecycle`.) To observe real uploads in the dev loop, point at a
**fresh event id** (or clear the event's objects in the bunny zone) so the reconcile finds nothing to seed.

`SNAPSYNC_FORGE_STATE=<state>` is a third **dev/test trigger** (capability `ios-app-shell`), read **once
per process** and inert in production: it mounts the real `StatusScreen` over **forged sources** for a
recognized state (`create` · `joining` · `in_sync`) — no backend, attestation, or photo access — so a
marketing/App-Store screenshot can be captured of any state. The forge substitutes the container's
*inputs*, not a static `UiState`, so it can only render a frame the real reduction can reach (the
name→sources map is the tested `forgeStatusHost` factory in `:domain:presentation`). This is what the
non-gating, dispatch-only `.github/workflows/screenshots.yml` drives on a simulator (`macos-26`) —
`simctl launch … SNAPSYNC_FORGE_STATE=<state>` → `simctl io screenshot` → **6 raw captures**.

### Refreshing the marketing screenshots (operator runbook)

`screenshots/*.png` — 6 raws, 3 forge states × light/dark — are the **single source of truth for two
surfaces**: `appstore-screenshots.yml` composites the App Store listing images from them, and the backend
derives the landing page's WebP from them (`deno task shots`). So **refreshing them is a commit**, and
committing one is what ships both. Nothing regenerates automatically — the capture is dispatch-only.

Do this when the UI changes:

```
gh workflow run screenshots.yml --ref <branch>          # ~11-19 min (the Compose/Skiko link dominates)
RID=$(gh run list -w screenshots.yml -L1 --json databaseId -q '.[0].databaseId')
gh run download "$RID" -n screenshots-raw -D screenshots
# LOOK AT THEM (see below), then:
git add screenshots/ && git commit
```

- **Eyeball them before committing — this is the only check there is.** A system notification
  (*"Ready for Apple Intelligence"*, fired by fresh-device onboarding) can land in a capture; it hit **1 of
  2 runs** before the loop was tightened. Re-dispatch if one does. This is **not** automatable by asserting
  the top band is flat: `in_sync` legitimately renders "Anna's Birthday" there, so a colour check
  false-positives. A human glance is cheaper and catches whatever iOS pops up next.
- **Only `create` should re-diff on an unchanged UI** — and only in the timestamp, because it renders the
  wall clock. Verified across two runs of one commit: `joining` and `in_sync` come back **byte-identical**
  (`simctl` writes no timestamp) while `create` differs in a 90×32 px region and nowhere else. So a diff
  anywhere else means the UI really moved. Light and dark agree on the minute — both come from one launch,
  seconds apart.
- **A headline or size change needs NO dispatch** — both consumers composite from the committed raws.
  Edit `metadata/screenshots/en-US.json` (App Store copy) or `landing.html` and push.
- The App Store upload fires only on `main` and only when `screenshots/**` or `metadata/**` changes; it
  replaces the live set, behind the editable-version gate (never a version in review).

⚠️ **A "fresh event id" must be a real event you created — not an invented UUID.** `autoJoin` loads the
event's details first and **aborts on a miss**, leaving the *previous* membership untouched:

```
autoJoin aborted: details load did not succeed for <id> (NotFound)
```

The launch still succeeds and the app runs on happily — **with the old config** — so a run that assumes its
link applied is measuring the previous membership. (Observed: a `direction=download` link with an invented id
left a `Both` membership joined and uploading, which reads exactly like a broken direction gate. Always
confirm the id in `debug.log` — `reconcile(eventId=…)` and `config ok` — matches the one you passed.)

**One event per membership shape.** `direction`, the cutoff, and the album opt-in are **fixed at join**, and
re-scanning the *already-joined* event short-circuits as `AlreadyJoined` (capability `join-event`) — so
`SNAPSYNC_EVENT_LINK` can change **none** of them for the event you are already in. Exercising a different
direction needs a **different event that already exists**. There is no headless route to creating one
(`POST /events` is attest-gated, and create auto-routes into the pending-join gate, which wants a tap), so
pre-create a couple of events in the app — one per membership shape you test — and reuse their ids.

🚫 **Never point it at an event you do not own.** A `direction=download` join imports that event's photos into
this device's library and registers this device on its backend membership. Log-scraped ids are someone's real
event.

**Restarting the app (black-screen trap).** `dvt launch --kill-existing` — and `dvt kill`/`pkill` —
only send **SIGTERM**, which SnapSync ignores; a relaunch then layers a new instance on the
still-alive old one and the app sticks on a **black launch screen** (status bar visible, content
black). To truly restart: `dvt signal <pid> 9` (SIGKILL) **then** `dvt launch` (verified recovery).
Take the screenshot promptly after a single launch; avoid rapid relaunch cycles.

**The headless per-build loop:** CI builds the dev IPA → `apps install` → `dvt launch --env
SNAPSYNC_EVENT_LINK=…` (use a **fresh event id**, per the note above, or the reconcile will seed
already-stored photos and nothing uploads) → the OS invokes the upload extension on its own cadence →
confirm the objects landed in the backend's bunny storage zone (see *Verify real uploads* below; the
`dvt screenshot` status counts are informational, not the authoritative landing check). **Still
gated:** taps / UI gestures need a signed **WebDriverAgent** (`developer wda`), and `process()`
**timing** is OS-owned — a re-provision reliably triggers an invocation but you cannot force *when* it
runs.

### `main` is the public alpha channel

Every merge to `main` reaches **public** TestFlight testers, automatically and **silently** (capability
`ios-testflight-delivery`). `ios.yml` runs `ios-build` + `ios-test` (the merge gates) → `ios-deliver`
(export + upload) → **`ios-promote`**, which puts the build in the **`alpha` external group**, whose
public link — <https://testflight.apple.com/join/pvqgV7Uz> — anyone may tap. **Uploading is not
distributing:** without `ios-promote` a build reaches only the internal `development` group.

- **Testers are never notified.** `ios-promote` sets `autoNotifyEnabled=false` on every build; testers
  ride `main` via TestFlight auto-update. The suppression **must** precede the group assignment (the
  notification fires on group availability) — do not reorder those steps.
- **Every** `main` build is promoted, unfiltered. Docs-only and backend-only merges therefore ship a
  binary-identical build. Deliberate: any filter risks a real fix *silently* never reaching testers,
  which is worse than noise. (And a path filter on the trigger would freeze merges — `ios-build` /
  `ios-test` are required checks, and a skipped required check is never posted.)
- **Beta App Review is not a gate** — a build on an already-approved `MARKETING_VERSION` auto-approves
  instantly, and a build can join the group while still `WAITING_FOR_REVIEW`.
- ⚠️ **The `MARKETING_VERSION` trap — avoided by design; do not re-introduce it.** The fallback is
  pinned at `0.1.0` in **`Config.xcconfig`** (inherited by both targets; no target-level entry in
  `project.pbxproj`), and **`main` is never bumped** — real store versions are injected per release by
  the release channel below. *Were* you to bump the committed fallback, it would force a **real**
  first-of-version Beta App Review taking **hours to days**, during which each merge expires its
  predecessor's submission (`--expire-build-submitted-for-review`, newest wins), so **nothing reaches
  testers and nothing goes red** — a green pipeline delivering nothing. Don't bump it; dispatch a release.
- A promote cancelled by `concurrency: cancel-in-progress` (a second merge landing mid-poll) is also
  expected — the newer run promotes the newer build; nothing is lost.
- **APNs is production for every TestFlight/App Store build.** CI Release archives inject
  `APS_ENVIRONMENT=production` / `APNS_ENV=production` (in the `ios-archive` composite action); only
  dev-sideload builds (the `ios.yml` `workflow_dispatch` dev-IPA path and ssh-mac) stay
  `development`/`sandbox`. The `Config.xcconfig` values are the dev default, overridden for distribution.

### App Store releases are dispatch-driven (the tag is the RECEIPT, not the trigger)

```
gh workflow run ios-release.yml --ref main -f version=1.0             # build + attach, no submit
gh workflow run ios-release.yml --ref main -f version=1.0 -f submit=true
```

`.github/workflows/ios-release.yml` (capability `ios-appstore-release`) builds an `X.Y` archive (version
injected from the **`version` input** — committed source is never bumped, so the alpha channel is
untouched), uploads it, **finds-or-creates** the `X.Y` App Store version record, **attaches** the build,
applies the **App Review details** from the repo, optionally **submits**, and — last — **creates the
`vX.Y` tag**. ⚠️ **Don't push a `vX.Y` tag by hand**: tags no longer trigger anything, and a tag you push
yourself makes that version permanently un-releasable (the guard below refuses an existing tag).

- **Two jobs.** `build` (macOS: guards → archive → export → upload) then `finish` (ubuntu: attach →
  review details → submit → tag). `asc` is fetched as a **linux** binary verified with `sha256sum`, which
  macOS lacks — hence the split, which also keeps `contents: write` (for the tag push) off the job
  holding the signing certs.
- **Version scheme is two-part** (`-f version=1.0` → store version "1.0", tag `v1.0`); a hotfix is a minor
  bump (no `X.Y.Z`). A malformed version fails the run.
- **Guards**, in order: version matches `^\d+\.\d+$`; **the tag must not already exist** (checked first,
  so a doomed release fails in seconds rather than after a ~30 min build); the commit is an ancestor of
  `origin/main` (**load-bearing** — a dispatch can run from any ref); and every check-run on that commit
  is green (including the allowed-red `ios-deliver`/`ios-promote`). **Escape hatch:** a cancelled/red
  `ios-promote` on your target commit blocks the release — re-run that idempotent job to green, then
  re-dispatch.
- **The tag is created LAST, on success only** — so a failed run leaves no tag and retries cleanly. The
  green guard excludes this workflow's own check-suites (any run, any state), so a *failed* release does
  not poison its own retry.
- **Releasing an older commit**: dispatch picks a *ref*, not a SHA. Point a branch at the commit and
  dispatch from that.
- **Submit is opt-in and gated**: `-f submit=true` only submits if `asc review doctor` reports zero
  blocking checks; otherwise the run refuses and prints them.
- **Review details are repo-owned**: prose in `metadata/review/notes.md` (deliberately outside the
  metadata tool's canonical schema — an unknown key there fails a required check and freezes merges);
  contact from the `ASC_REVIEW_CONTACT_*` secrets (this repo is public, so they can be neither committed
  nor passed as inputs, which render publicly in the Actions UI). No demo account: the app has no sign-up.
- It posts **no** required status check (not in `main.json`); a failed release is red but blocks nothing.
  It introduces no new **ASC credential** (the contact secrets grant no ASC access). Build number = the
  release run's `run_number`.

**Unlisted App Distribution** (if you want the app link-only, not searchable) — ⚠️ **the sequencing is the
reverse of the intuition**, and ASC's "Private" is a trap:
- Apple **declines** an unlisted request for an app that has not been submitted to review. Submit v1.0
  **first** (the committed review notes already declare the intent, which Apple requires), get approved,
  *then* file the request at <https://developer.apple.com/contact/request/unlisted-app-distribution>.
  There is **no API** for it.
- 🚫 **Never select "Private" in Pricing and Availability.** That is **Custom Apps** (Apple Business
  Manager — org-only, no consumer can install), *not* unlisted. It is a **one-way door**: once approved,
  the distribution method can't be changed, and switching private↔public needs a **brand-new app record**
  — burning app id `6781692480` and everything configured on it. Public→unlisted *is* allowed, so staying
  Public keeps every option open.

### Sideload a dev IPA (skip TestFlight)

Dev IPAs are produced **on demand by the ssh-mac build loop** (see *Headless macOS build loop* below) —
build → dev-sign → `scp` back → install over usbmuxd. There is **no CI dev-IPA artifact**; CI delivers
only to TestFlight on `main`. This is an **operator runbook, not CI behavior**.

One-time setup (per device):
- Register the device UDID at developer.apple.com → Devices (SE2 is `00008030-0018703A1A7A402E`,
  obtainable via `ideviceinfo -k UniqueDeviceID`). The dev profile only includes registered UDIDs.
- Enable Developer Mode (dev-signed apps won't launch without it). Note `pymobiledevice3 amfi
  enable-developer-mode` **hangs over the usbmux bridge** and the Settings → Privacy & Security →
  **Developer Mode menu only appears after a dev-signed app is installed** — so install a dev IPA
  first, then toggle Developer Mode on in Settings (software restart, no hardware buttons).

Install a dev IPA you already have (run Python tools via `uvx`, never a global install — pymobiledevice3
wants the **bare** socket path, no `UNIX:` prefix):
```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
uvx pymobiledevice3 apps install <path>/SnapSync.ipa
```
(Install goes over `installation_proxy`/lockdownd — no developer tunnel needed. Launch, screenshot,
and other DVT services do need the tunnel + DDI, reached headless via `--userspace` above.)
**Reinstall hangs if the app is running** — `installation_proxy` stalls at "…% Complete" forever when
replacing a **running** app (the first install of a fresh session is fine because nothing is running
yet). SnapSync ignores SIGTERM (see the black-screen trap), so **SIGKILL it first**: `dvt signal <pid>
9 --userspace`, then install. Get `<pid>` from the last `dvt launch` (it prints it).

### Headless macOS build loop (ssh-mac)

For a fast **iterate** loop (not just install), `.github/workflows/ssh-mac.yml` opens a long-lived
`macos-26` job with an SSH server the sandbox connects to, so you can `rsync → build → test → dev-sign →
scp back → install` many times against one **warm** runner instead of one CI run per change. It is
**dispatch-only, non-gating** dev infrastructure (no spec; rationale in the workflow header). Public repo
⇒ the runner is **free**; the session self-closes after `stop_after` minutes (default 90) or when you
`touch /tmp/ssh-mac-stop`. This is an **operator/agent runbook, not CI behavior**.

The auth model: you pass your **public** key at dispatch (safe — a pubkey is public and the private half
never leaves the sandbox); the runner authorizes exactly that key on its own sshd, fronted by a
**cloudflared** quick tunnel (relays encrypted TCP only). **No ASC key ever touches the box** — the runner
holds only the dev cert plus a pre-generated dev provisioning profile baked in as the
`DEV_PROVISIONING_PROFILE_BASE64` secret (a profile carries no private keys, so it is safe as a secret).
`cloudflared` is fetched to the scratchpad, **not** globally installed.

```
export USBMUXD_SOCKET_ADDRESS=/run/host/run/usbmuxd
S=/tmp/.../scratchpad                                          # session scratchpad
# 1. Ephemeral keypair (public half goes to CI; private half stays here)
ssh-keygen -q -t ed25519 -N '' -f "$S/ssh-mac"
# 2. cloudflared client (the ProxyCommand transport)
curl -sSL -o "$S/cloudflared" \
  https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64
chmod +x "$S/cloudflared"
# 3. Dispatch and grab the run id
gh workflow run ssh-mac.yml -f ssh_pubkey="$(cat "$S/ssh-mac.pub")" -f stop_after=90
RID=$(gh run list -w ssh-mac.yml -L1 --json databaseId -q '.[0].databaseId')
# 4. Get the host from the ssh-mac-host ARTIFACT (logs are unreadable mid-run; v4 artifacts are)
until gh run download "$RID" -n ssh-mac-host -D "$S/host" 2>/dev/null; do sleep 5; done
HOST=$(cat "$S/host/ssh-mac-host.txt")                        # = <random>.trycloudflare.com
# 5. Connect (runner user is `runner`)
alias sshmac='ssh -i "$S/ssh-mac" -o StrictHostKeyChecking=no \
  -o ProxyCommand="'"$S"'/cloudflared access ssh --hostname %h" runner@<HOST>'
# 6. Iterate. NB: $RUNNER_TEMP is UNSET in an ssh shell (it is a GH-Actions-step var) — write outputs
#    under $HOME, not $RUNNER_TEMP, or paths resolve to read-only "/".
rsync -az --delete -e "..." --exclude .git --exclude build --exclude .gradle ./ runner@<HOST>:snapsync/
sshmac 'cd snapsync && ./gradlew iosSimulatorArm64Test'
# 6a. Build an UNSIGNED archive (compiles the Kotlin frameworks + assembles app+appex). The Xcode project
#     is CODE_SIGN_STYLE=Automatic, which needs -allowProvisioningUpdates + the Admin ASC key (absent
#     here) — so a *signed* archive is impossible on the box. Build unsigned, re-sign by hand (6b).
#     BUILD DEBUG, NOT RELEASE. `-configuration Debug` links `linkDebugFramework`, skipping the
#     Kotlin/Native LLVM optimizer that dominates a Release link — and it reruns FULLY on every relink,
#     so it costs you on every iterate, not just cold. Measured on the warm runner (macos-26, 3 cores,
#     Xcode 26.5, ~/.konan warm), archive of a ONE-FILE Kotlin change: Release 449s vs Debug 57s (~8×);
#     cold-from-empty-build/: Release 523s vs Debug 348s; no-op rebuild ~30s either way. The dev/sideload
#     IPA needs no optimization (ios.yml's on-demand dev path already builds Debug for exactly this), and
#     the Debug archive is a complete installable bundle (arm64 app binary + BackgroundUploadExtension.appex
#     in Extensions/) — the 6b re-sign is config-agnostic, so ONLY this -configuration line changes. Switch
#     to Release only when you need an optimization-representative build. Keep the cold cost paid once: never
#     wipe build/ or .gradle between iterates (the step-6 rsync already excludes them) and keep the Gradle
#     daemon alive (no --no-daemon) — an incremental Debug iterate is then ~1 min.
sshmac 'cd snapsync && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
          -destination "generic/platform=iOS" -archivePath "$HOME/artifacts/SnapSync.xcarchive" \
          CODE_SIGNING_ALLOWED=NO archive'
# 6b. Manually re-sign the archive INSIDE-OUT with the baked profiles, then repackage the IPA.
#     WHY not `xcodebuild -exportArchive`: automatic-signing export does NOT reuse manually-installed
#     profiles without an ASC key (fails "No profiles for 'app.snapsync…' were found"); and the
#     CODE_SIGNING_ALLOWED=NO archive has EMPTY entitlements, so any export ships an IPA that aborts at
#     launch on the App-Group container ("client is not entitled"). Re-signing with the entitlements
#     RESOLVED inside each profile (app-groups/keychain/aps/get-task-allow) is the working path.
#
#     ⚠️ RESOLVING OUT OF THE PROFILE SILENTLY DESTROYS `associated-domains`. A DEV profile grants the
#     WILDCARD `*` (permission to claim ANY domain) — so a straight resolve signs the app entitled to `*`
#     and claiming NOTHING, and every universal link then silently fails (verified 2026-07-16; it is also
#     why a pre-change build showed `associated-domains: *`). Narrow it to what the app actually declares
#     BEFORE signing — valid, because entitlements need only be a SUBSET of the profile's grant, and `*`
#     permits this. Use PlistBuddy: `plutil` CANNOT touch this key, it reads the dots as a key path and
#     fails "Key path not found".
sshmac 'bash -se' <<'SIGN'
set -e; cd "$HOME/artifacts"
PD="$HOME/Library/MobileDevice/Provisioning Profiles"
ID=$(security find-identity -v -p codesigning | awk '/Apple Development/{print $2; exit}')
APP="SnapSync.xcarchive/Products/Applications/SnapSync.app"
EXT="$APP/Extensions/BackgroundUploadExtension.appex"          # iOS 26 uses Extensions/, NOT PlugIns/
PB=/usr/libexec/PlistBuddy; K=":com.apple.developer.associated-domains"
for p in "$PD"/*.mobileprovision; do                          # match each profile by its bundle id
  aid=$(security cms -D -i "$p" | plutil -extract Entitlements.application-identifier raw -)
  case "$aid" in
    *.app.snapsync.BackgroundUpload) security cms -D -i "$p" | plutil -extract Entitlements xml1 -o ext.plist -; cp "$p" "$EXT/embedded.mobileprovision";;
    *.app.snapsync)                  security cms -D -i "$p" | plutil -extract Entitlements xml1 -o app.plist -; cp "$p" "$APP/embedded.mobileprovision";;
  esac
done
$PB -c "Delete $K" app.plist                                  # replace the profile's `*` wildcard…
$PB -c "Add $K array" app.plist
$PB -c "Add $K:0 string applinks:snapsync.stho.net" app.plist # …with the domain the app really claims
codesign -f -s "$ID" --entitlements ext.plist "$EXT"          # sign the extension first (inside-out)…
codesign -f -s "$ID" --entitlements app.plist "$APP"          # …then the app (statically-linked, no nested dylibs)
# Verify the claim survived — `*` here means universal links are dead and NOTHING will say so:
codesign -d --entitlements :- "$APP" 2>/dev/null | plutil -p - | grep -A2 associated-domains
codesign -v "$EXT" && codesign -v "$APP"
rm -rf Payload && mkdir Payload && cp -R "$APP" Payload/ && zip -qry SnapSync.ipa Payload
SIGN
scp -o ProxyCommand=... runner@<HOST>:artifacts/SnapSync.ipa "$S/"
uvx pymobiledevice3 apps install "$S/SnapSync.ipa"                          # over usbmuxd, as above
sshmac 'touch /tmp/ssh-mac-stop'                                            # end the session
```
Same one-time device prerequisites as *Sideload a dev IPA* (registered UDID + Developer Mode). The
`DEV_PROVISIONING_PROFILE_BASE64` secret is a **tar of both** the app (`app.snapsync`, profile *SnapSync
Dev Push*) and extension (`app.snapsync.BackgroundUpload`, *SnapSync Ext Dev Push*) dev profiles — the
re-sign step above signs both targets. Refresh it when they expire (~yearly), when you register a new
device, **or when you enable a bundle-id capability** — that last one silently *invalidates* the affected
profile (verified 2026-07-16: enabling Associated Domains flipped *SnapSync Dev Push* to `INVALID` while
the extension's profile, whose bundle id gained nothing, stayed `ACTIVE`). A stale profile is the worst
kind of failure here: the re-sign resolves entitlements **out of the profile**, so the IPA installs and
launches fine and merely lacks the capability — no error, no log line.

Refreshing needs **no Mac and no build** — mint and download both profiles over the ASC API from Linux
(`$A` from the *App Store Connect via API* section below), then tar them **flat** (the workflow globs
`$WORK/*.mobileprovision` and installs each by its embedded UUID, so filenames are free but nesting
breaks it):
```
P="proton-env -- uvx --from codemagic-cli-tools app-store-connect"
$P profiles list $A --json                        # find the INVALID one + note cert/device ids
$P profiles delete <INVALID_PROFILE_ID> $A        # Apple rejects a duplicate name; delete first
$P profiles create <BUNDLE_RESOURCE_ID> $A --certificate-ids <CERT> --device-ids <DEVICE> \
     --type IOS_APP_DEVELOPMENT --name "SnapSync Dev Push" --save
$P profiles get <EXT_PROFILE_ID> $A --save        # the extension's, still ACTIVE — grab it as-is
# both land in ~/Library/Developer/Xcode/UserData/Provisioning Profiles/
tar -cf p.tar -C <dir> app.mobileprovision ext.mobileprovision   # FLAT
base64 -w0 p.tar | gh secret set DEV_PROVISIONING_PROFILE_BASE64
```
Verify before shipping — decode each and confirm the app's carries what you added and the extension's
does not: `openssl smime -inform DER -verify -noverify -in <p>.mobileprovision` (works on Linux; no
`security cms` needed). The non-root sshd + `cloudflared access ssh` handshake were
proven on 2026-07-01; the **unsigned-archive + manual re-sign** path (replacing the earlier
`-exportArchive` claim, which does not reuse installed profiles without an ASC key) was proven on
2026-07-05 — a dev IPA built this way installs and launches on the SE2.

### Verifying the event link

An invite is an HTTPS **Universal Link** — `https://snapsync.stho.net/join#v=3&d=<base64url>` (capability
`event-link`). The payload rides in the **fragment** on purpose: a browser never sends it, so the
`eventId` (which *is* the upload capability) never reaches the backend or its CDN even when someone
without the app opens the link and gets redirected to the App Store.

Two checks run from Linux with **no device**:

```
# 1. our origin, THROUGH the pull zone — must be JSON with no redirect
curl -sSI https://snapsync.stho.net/.well-known/apple-app-site-association
# 2. what Apple actually hands a device (it caches, and parse errors show up as a miss)
curl -sS https://app-site-association.cdn-apple.com/a/v1/snapsync.stho.net
```

That second endpoint is the cheap oracle: it 404s until Apple has fetched and **accepted** our AASA, and
200s once it has. It is also why we ship plain `applinks:` with no `?mode=developer` — CDN staleness is
one curl away from being diagnosed rather than an invisible wait.

⚠️ **Apple's own apps are not AASA-wired** — `apps.apple.com` serves an empty file, `maps.apple.com`
404s, `music.apple.com` serves HTML; they are special-cased inside the OS. So an `apps.apple.com` QR is a
**worthless** test target that appears to pass. Test with a real third-party universal link (verify the
domain against the CDN endpoint above first).

Verified on device: the stock **Camera app honors AASA** on a scanned QR, and iOS **delivers the fragment**
to the app. Opening a real link and landing on the event proves the entitlement, the AASA, and fragment
delivery in one observation; a stripped fragment would surface visibly as the invalid-link error, never
silently.

⚠️ **A green AASA proves nothing about delivery.** Both curls above can pass while every link is dead:
iOS matches the AASA, foregrounds the app, and the app drops the URL — indistinguishable from success,
and on an unjoined device the create screen it lands on is the correct resting state. That shipped
(2026-07-16). The link is delivered as an `NSUserActivity` to the **scene** delegate — a SwiftUI
`WindowGroup` is a scene — so `scene(_:willConnectTo:options:)` (app NOT running) and `scene(_:continue:)`
(running) are the only hooks that work. `.onOpenURL` never fires for a universal link;
`.onContinueUserActivity` is warm-only; `application(_:continue:)` is never called in a SwiftUI app. A
`:test:architecture` guard now pins this (`EventLinkDeliveryTest`).

**The authoritative on-device check is `debug.log`, not the screen** (spec `ios-app-shell`):
```
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log     # then read the [onOpenUrl] lines
```
A **cold** delivery is an `onOpenUrl` sharing a timestamp with `=== app process start ===`; a **warm** one
has no preceding process start. A multi-second gap after a launch means a *second* scan delivered warm —
misreading that gap is how "cold works" was concluded wrongly the first time. Both cases must appear,
exactly once each. **`swcd` is NOT visible in `idevicesyslog`** (measured: 23,525 lines across an install,
zero AASA activity) — don't retry that. Apple's TN3155 instead exposes approval state via `swcutil` inside
a **sysdiagnose** (`swcutil_show.txt` → `Site/Fmwk Approval: approved`), fetchable headlessly with
`$P developer core-device sysdiagnose` — untried here, but the documented route.

⚠️ **Changing the AASA needs an app REINSTALL.** Devices download it from Apple's CDN at install and
re-check roughly weekly; there is **no invalidation** (TN3155). A changed path/appID does not reach
installed apps on its own.

### Verify real uploads

On-device uploads go to the **deployed HTTPS backend** (the device-facing host baked from
`Config.xcconfig`); there is no local upload rig. Confirm an upload landed by checking the backend's
bunny **storage zone** (see `backend/README.md` / `openspec/specs/backend-deployment`), not the app
status screen. Connections are HTTPS-only — default ATS, no `NSAllowsLocalNetworking` exception.

## App Store Connect via API (agent-driven portal chores)

Apple Developer portal tasks that are otherwise GUI-only — code-signing certs, devices,
provisioning profiles, bundle-id capabilities, and App Store / TestFlight text metadata — are
driven through the **App Store Connect API** via the `codemagic-cli-tools` `app-store-connect`
command, run with **uvx** (no install). Credentials are injected as env vars by **`proton-env`**,
which requires **user sign-off on each run** — that approval is the only mutation guardrail (no
bespoke protection on the CI certs), so prefer read-only subcommands and keep mutations
deliberate.

```
# proton-env injects these three (the same values as the CI secrets of the same names):
#   ASC_ISSUER_ID
#   ASC_KEY_ID
#   ASC_AUTH_KEY     # full .p8 PEM content (not a path)
#
# ⚠️ Those names are NOT what the codemagic CLI looks for (it wants APP_STORE_CONNECT_ISSUER_ID /
# _KEY_IDENTIFIER / _PRIVATE_KEY), so a bare `proton-env -- app-store-connect …` fails with
# "Missing value ISSUER_ID". Bridge them with the CLI's own `@env:` prefix — no shell remap needed:
A="--issuer-id @env:ASC_ISSUER_ID --key-id @env:ASC_KEY_ID --private-key @env:ASC_AUTH_KEY"

proton-env -- uvx --from codemagic-cli-tools app-store-connect certificates list $A --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect devices list $A --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect profiles list $A --json
proton-env -- uvx --from codemagic-cli-tools app-store-connect bundle-ids list $A --json
# The CLI prints JSON to STDOUT and its own logs to STDERR — capture them separately, and note that
# `--capability` takes N values, so the bundle-id positional must come BEFORE it or it gets eaten:
#   … bundle-ids enable-capabilities <BUNDLE_RESOURCE_ID> $A --capability "Associated Domains"
# metadata: app-store-version-localizations (descriptions/keywords),
#           beta-build-localizations (TestFlight "what to test")
```

Covers certs (list/create/revoke), devices (register/enable/disable — Apple has **no delete**),
profiles, bundle-ids + capabilities, and App Store / TestFlight **text** metadata. **Gap:**
screenshot upload (reserve→chunk→commit) has no subcommand — drop to raw REST when a real App
Store listing needs it. The CI key is **Admin** (needed for cloud signing); if an agent should not
reach app metadata / user management, mint a narrower **App Manager** or **Developer** key for
agent use and inject that one instead.

## Modules

```
:domain:engine         sync core + SQL ledger (the only state); no platform deps
:domain:keychain       cross-cutting Keychain access: the ONLY module that may touch SecItem* (a :test:architecture guard enforces it) — three-state read (found/absent/UNREADABLE), mint-only-on-absent, AfterFirstUnlock + in-place migration, ProtectedDataGate (capability architecture-guards; decision record: fix-locked-device-keychain-access)
:domain:logging        cross-cutting diagnostics: Logger.invocation helper + LogContext ambient prefix + consolidated iOS device-log writers (Kermit-only leaf) (capability diagnostic-logging)
:domain:status         ledger → SyncStatus projection (read-only)
:domain:permission     permission seam (3-state)
:domain:gallery        library resource-enumeration seam + upload-key/role layout (uploadKey, resourceRole, assetIdFromUploadKey, normalizeAssetId) + device manifest + the origin-exclusion rules of the selection policy (SelectionPolicy.excludedAssetIds — screenshots/screen-recordings by mediaSubtype, GIFs by MIME, resolution floors; capability photo-selection-policy). The policy lives HERE because :capability:upload and :domain:status must apply the identical rules and it is the only module both can see
:domain:download-store  app-written download store + read-only SuppressionSource projection (echo-suppression)
:domain:presentation   Orbit MVI container + UiState (Compose-free, no engine dep)
:domain:ui             Compose screens (written against App* only)
:domain:ui:components   App* design system + the Material 3 skin
:capability:upload     upload orchestration: UploadCycle + the UploadJobPlatform seam + DiscoveryStore + UploadConfig + the app-driven BackgroundUploadPump/BackgroundScheduler (jvm()+ios, JVM/harness-covered; deps :domain:engine + :domain:gallery)
:capability:upload-url local edge-URL builder (no network/crypto) — the UploadRequestProvider
:capability:config     event-link provisioning: the HTTPS Universal Link codec + eventId config (event-link)
:capability:device-id  stable per-install device identity (shared Keychain)
:capability:attest     App Attest device token: the tested DeviceAttestation policy (attest → token → renew, 401 → clear-and-retry) over an AttestKey seam; the token is the ONLY way past the backend, which gates every route on it. The extension cannot attest (`isSupported` is false in an app extension, true in the app — measured, not assumed), so it is strictly a READER of the token the app leaves in the shared Keychain (device-attestation)
:capability:download   foreign-photo download → stage → import controller (photo-download)
:capability:join       join use-case + DeviceEnroller (writes the per-event device manifest = the physical fact of membership) + EventDetailsSource (join-event)
:capability:album      tested commonMain album orchestration: resolve-or-create the per-event album, dispatch-or-skip an add; PhotoKit behind seams (event-album). Also the album DENYLIST (DENYLISTED_ALBUM_TITLES — WhatsApp, Telegram, …) + the decision-free AlbumManager.assetIdsInAlbums membership lookup it feeds (capability photo-selection-policy)
:capability:push       APNs token registration + PushReceiver seam + EventNotifier (POST /events/<id>/notify) (push-registration, upload-completion-notify)
:capability:membership event-membership lifecycle: extension-side re-join reconciliation + leave use-case + LeaveNotifier (DELETE /events/<id>/devices/<id>) + device-file listing seam
:capability:event-creation-ui  create-event screen seams: EventCreator/CreationStatusSource + HTTP creator
:app:desktop           shared harness library (PhoneFrame + StatusPane, StatusContainerHost wiring both desktop harnesses reuse) AND the full-stack world harness app (:app:desktop:run): real StatusScreen whose counts EMERGE from :test:world's real LedgerBackedSyncStatusSource + a right-pane world inspector driving the world (capability full-stack-harness)
:app:desktop:ui        forge harness (:app:desktop:ui:run): phone frame + control panel that forges any UI state; depends on :app:desktop
:app:ios               iOS app wiring + framework export (thin, untested)
:app:ios:photokit-extension  iOS ≥26.1 background-upload extension: PhotoKit adapter (IosPhotoKitUploadPlatform) + composition root, composing :capability:upload + :app:ios:photokit-discovery — thin, untested (orchestration + tests live in :capability:upload)
:app:ios:photokit-discovery  shared iOS PhotoKit discovery (IosDiscovery: change-token walk + PUT request builder + token archive; IosDiscoveryStore) — consumed by BOTH upload tiers; iosMain-only, no jvm/framework (keeps PhotoKit out of the platform-free :capability:upload)
:app:ios:url-session-upload  app-driven iOS 18–26.0 upload adapters: IosUrlSessionUploadPlatform (background URLSession impl of UploadJobPlatform) + IosBackgroundScheduler (BGTaskScheduler) — runs in the MAIN APP process, composed into SnapSyncKit (no separate target); thin, untested
:test:world            test-only shared infra: a controllable in-memory "world" (backend object store + read-models, MockEngine mini-edge, operator-driven UploadJobPlatform/download fakes) the REAL stack runs against + composition helpers mirroring the extension root; jvm()+iosSimulatorArm64. Consumed by :app:desktop AND :test:integration (capability harness-world-model)
:test:architecture     test-only JVM guards for invariants the compiler cannot express (capability architecture-guards): Konsist — no SecItem* outside :domain:keychain (catches fully-qualified calls, which no linter can see on iosMain); plus the entitlements never raise default-data-protection to NSFileProtectionComplete (which would make every App-Group file unreadable while locked, killing the background tier)
:test:integration      test-only: seam → UI-state integration over :test:world — asserts UiState AND world outcomes (objects landed, ledger COMPLETED, foreign photos imported)
:test:harness-driver   test-only dev infra (non-gating, no spec): serves EITHER desktop harness over HTTP with no window — composes the shipped ForgeHarnessRoot/WorldHarnessRoot into an offscreen Compose scene (CPU raster Skia; no X server, no screen-capture portal) so an agent can click the real buttons and read back the real pixels + semantics tree. Runbook above; rationale in Driver.kt
iosApp/                Xcode project (app + upload-extension targets) — not Gradle
```

Dependency flow: `engine ← status ← presentation ← ui`. Boundaries are compiler-enforced; the
platform backend is selected structurally in the app modules.

## Hard rules

- **Design-system containment.** Only `:domain:ui:components` may import Material 3. **No M3 type
  may appear in any `App*` signature.** `App*` components are semantic, not customizable — params
  carry data/meaning, never appearance; **no `Modifier`/color/shape/textStyle params**. Screens
  use `App*` exclusively (spec `design-system`).
- **DI, not `expect`/`actual`.** Implementations are chosen by manual dependency injection in the
  app modules (composition root). The JVM target needs multiple impls per seam (in-memory fake +
  the controllable harness fake), which `expect`/`actual` cannot express.
- **iOS constrains `commonMain`.** Because iOS targets are present, `commonMain` is limited to the
  common stdlib — JVM-only APIs there break the iOS compile (verify with the proxy task above).
- **`:app:ios` is wiring-only.** `:app:ios` and the `iosApp/` Swift host are a thin, **untestable**
  platform layer. All logic — shared *or* iOS-specific — must live in a `domain`/`capability`
  module under test; nothing testable is parked in the app shell.

## Logging & errors

- Log via **Kermit** (multiplatform). Cross-cutting logging infra lives in **`:domain:logging`**: the
  `Logger.invocation` enter/exit helper (params + result + duration), the process-global `LogContext`
  ambient prefix, and the consolidated iOS device-log writers (`FileLogWriter`/`PublicNSLogWriter`).
- **Device diagnostics** (capability `diagnostic-logging`): the app and extension are separate
  processes, each writing its **own** verbatim, un-redacted `Documents/debug.log` (the App Group
  container is not pullable — verified). Pull both:
  `pymobiledevice3 apps pull app.snapsync Documents/debug.log` and
  `… app.snapsync.BackgroundUpload Documents/debug.log`. `debug.log` is the **canonical un-redacted
  channel** (os_log redacts `<private>`); it rolls to `debug.log.1` past 10 MB.
  ⚠️ **Do not reach for `NSLog` when debugging — not even "just this once", not even from Swift.** An
  interpolated `NSLog("x \(y)")` is a *dynamic format string*, which os_log redacts wholesale: your line
  never appears in `idevicesyslog` and the capture looks like "the code never ran". This is written
  above; it was ignored anyway on 2026-07-16 and burned a full build/install/scan cycle to re-learn. From
  Swift, route diagnostics through Kotlin (`SnapSyncRoot`) so they land in `debug.log`. Every line carries
  a
  `[<entryPoint>]` prefix (e.g. `[onSilentPush]`, `[process]`) tracing it to what triggered it; wrap
  new platform invocations / entry points with `Logger.invocation` and, for `scope.launch` work, wrap
  *inside* the launch so the context spans the async body.
- Errors are **reduced into state**: sealed domain errors → `UiState`, converted at capability
  boundaries — not thrown to the UI. This is also what lets the harness force any failure state.

## Testing strategy

Three standing rules:

1. **Every unit test runs on the iOS simulator too.** Put logic tests in `commonTest` so they run
   on **both** JVM and `iosSimulatorArm64` — JVM is the fast loop, not the only coverage.
   `jvmTest`/`iosTest` hold only driver/cinterop wiring behind a shared contract (e.g.
   `LedgerBackendContract` over the JVM-sqlite vs native driver).
2. **`:app:ios` is wiring-only and untested** (see Hard rules). All logic, shared or iOS-specific,
   lives in tested `domain`/`capability` modules.
3. **Seam ↔ UI-state integration tests** assemble the real `engine → status → presentation` stack
   and assert `UiState` from injected `SyncEvent`s, faking only the execution edge (in-memory
   `LedgerBackend`, fake `UploadRequestProvider`). They live in the test-only **`:test:integration`**
   module (`commonTest` → runs on JVM and simulator), which exists so the test may cross the
   `engine → presentation` boundary production forbids.

The edge-URL builder (`:capability:upload-url`) is pinned by `commonMain` tests on URL composition,
filename percent-encoding (deterministic + injective), and the Content-Type-only header set — pure
string-building, no network or crypto.

## Workflow

- **All changes** go through a branch → PR → **`/ship`** (branch protection forbids direct pushes
  to `main`).
- For changes that **add, alter, or remove behavior**, drive it through the **OpenSpec** flow
  (propose → apply → archive) so `openspec/specs/` stays the contract of record. Purely mechanical
  work — build/CI, dependency bumps, behavior-preserving refactors, docs — can skip OpenSpec and
  just branch → PR → `/ship`. Use judgment on the line between the two.
- **The `openspec` CLI is not installed** — there is no global binary and no `package.json`. Invoke
  it via npx, pinned to the version CI uses: `npx --yes @fission-ai/openspec@1.5.0 <cmd>` (e.g.
  `… validate --specs --strict`, matching `.github/workflows/build.yml`). Do not run a bare
  `openspec …`; it will fail with "command not found". The generated skills below say bare
  `openspec …` — translate each call to the pinned npx form.
- **The `.claude/opsx` skills and commands are generated**, not hand-written. They assume the
  machine-global profile in `~/.config/openspec/config.json` is `core` (workflows propose ·
  explore · apply · sync · archive) with `delivery: both`. Regenerate with
  `npx --yes @fission-ai/openspec@1.5.0 config profile core` then `… update`, and commit the
  output verbatim — hand-edits are overwritten on the next update. On a default profile, `update`
  emits only four workflows and **deletes** the `sync` skill/command.
  **Regenerating is a no-op today, and must stay one**: `.claude/` is byte-identical to generated
  output, so `update --force` changes nothing. It was not always — the archive's placeholder gate was
  hand-patched into `SKILL.md` (and only there, which is why `/opsx:archive` never had it), and
  `update --force` silently deleted it, with a green run. Anything an archive must *enforce* belongs
  in **`openspec/config.yaml`**'s `context:` block — hand-authored, injected into every agent in this
  root, and the one surface `update` does not rewrite (capability `openspec-archive-command`). Never
  patch a rule into `.claude/`; the tool will take it away and tell no one.
- **`openspec validate --specs --strict` checks structure, not truth.** It asks whether a spec has a
  Purpose, Requirements, and SHALL/WHEN/THEN with a scenario — it has never opened a `.kt` file. It
  passed 50/50 on a tree carrying 28 audited drifts (four specs contradicting themselves *within one
  file*), and it passes 50/50 now that they are swept: the same answer with the lies in and with them
  out. Green means well-formed. It does not mean true, and nothing in CI does.
