# CLAUDE.md

SnapSync — an iOS app for **sharing photos from an event** (Kotlin Multiplatform + Compose), shipped
via TestFlight. Join an event by scanning its QR, and your photos taken since a per-device
**capture-date cutoff** are shared to it while everyone else's arrive in your library. The JVM desktop
app is test equipment, not a product.

**Mission** (full form + decision record: `openspec/config.yaml` context /
`changes/archive/2026-07-21-align-specs-with-mission`): joined users easily share the photos they take during a
**short-lived event** (days/weeks — celebrations, holidays, trips), synced gallery-to-gallery; you never
care how photos arrive, you just look at your own gallery. No accounts; simple setup; the host picks the
event's **date range** at creation (at most **30 days** long), and that **end** is the capture-date ceiling
**only** — it bounds which photos may be uploaded and closes nothing, so a guest who scans days late still
joins and contributes their in-window photos. How long the event **lives** is a separate stamped lifetime
(30 days from `max(createdAt, startsAt)`); the nightly sweep deletes it then — or sooner, once every member
has left — and that IS how an event ends. Named futures (don't build for them; don't deepen assumptions
against them unnamed): Android · paid events (device count is the only lever) · concurrent multi-event
membership (single active membership is the *current* contract).

> It began as a *personal one-way photo backup*. Defaults inherited from that era are dangerous here:
> what was "back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's
> event". A membership's cutoff is therefore **required**, never absent.

What a member contributes is decided by **one** policy at **one** place (capability
`photo-selection-policy`, enforced in `UploadCycle`'s resource selection): the capture-date **range**
`[from, until]` bounds *when* a photo was taken (lower bound clamped to the event start, upper to the event
end); the **origin exclusions** bound *what it is* — screenshots, screen recordings, GIFs,
sub-floor-resolution received media, and members of a denylisted album (WhatsApp, Telegram, …) never enter
an event. PhotoKit exposes **no** camera-origin flag on any iOS through 26, so the policy can only
*subtract* known non-captures and **admits on doubt**: a stray uploaded meme is harmless and visible, while
an event photo that silently fails to upload is invisible and unfixable. The same policy gates the byte
upload, the device manifest (or an excluded photo leaks into the event union), **and** the status total `N`
(or the screen pegs below 100% forever).

**Limited (partial) photo access is a first-class grant** (capability `limited-photo-access`;
`PermissionStatus.LIMITED`): the user's hand-picked selection IS the membership's own-photo scope — the
policy then filters the selection exactly as it would a library. Three measured platform facts shape the
implementation, and violating any of them reads as "mysteriously broken" with no error anywhere:
① **never add an autonomous `PHAsset` read under `LIMITED`** — off-flow reads queue iOS's limited-access
alert into an app-killing storm that survives process death; reads happen ONLY on the cold-launch baseline
and the `PhotoSelectionChangeSource` observer emissions, and every upload cycle's discovery is fed the
in-memory snapshot (`SelectionScopedTransfer` in `uploadCore`), never a walk; ② **the ≥26.1 PhotoKit
extension is never invoked by the OS under `.limited`** (registration succeeds and lies) — both producers
are composed there and the permission-aware `UploadArm` starts exactly one (guarded by
`ProducerExclusivityTest`); ③ asset/album **creation is unrestricted** under `.limited`, so downloads and
the event album need no special handling (the album **denylist**, though, is inert — album structure is
unreadable; the resolution floors still apply). Decision record:
`openspec/changes/accept-limited-photo-access/` (`PROBE-FINDINGS.md` + `LIMITED-ACCESS-DESIGN.md`).

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
  needed**: the Compose Desktop UI tests (`:ui:screens:jvmTest`) render offscreen under
  `-Djava.awt.headless=true` (set on that task in `ui/screens/build.gradle.kts`), so no X server /
  Xvfb is required. Only the two harness run tasks — `:app:desktop:runForge` (forge) and
  `:app:desktop:run` (full-stack world, below) — open a real window and need a display.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source
  sets: it compiles `iosMain`/`commonMain` (and cinterop) without a Mac, so you can catch
  iOS-only breakage here. The actual iOS tests (`iosSimulatorArm64Test`, etc.) are **macOS-only**
  and run on GitHub Actions `macos-26`.

## Test UI (review/exercise every UI state)

`./gradlew :app:desktop:runForge` launches the forge harness (module `:app:desktop`, which hosts BOTH
desktop harnesses): the real
`:ui:screens` status screen inside a phone-sized frame on the left, and a **control panel** on the right
(raw Material 3 — it is test equipment, never `App*`). The panel **forges any display state** — permission presets,
sync-state presets, and the engine console — so you can review and test all UI states without a
device. See the `desktop-test-harness` spec.

`./gradlew :app:desktop:run` launches the **full-stack world harness** (same module): the same
real status screen on the left — but its counts **emerge** from the real `LedgerBackedSyncStatusSource`
composed by `snapSyncApp` over `:test:world` (never forged) — and a right-pane **world inspector** (raw M3) that drives the real stack: presets,
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
./gradlew :test:harness-driver:driveForge   # forge harness (:app:desktop:runForge), 800x950
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
curl -sS "$B/doubletap?text=SNAPSYNC"                   # the hidden bug-report gesture (no click semantics)
curl -sS --get --data-urlencode "text=What went wrong, and what were you doing?" \
     --data-urlencode "value=…" "$B/input"              # type into a field
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
- ⚠️ **`/tree` prints `onRoot()` — ONE root — so a popup is INVISIBLE in it.** A `ModalBottomSheet` or
  dialog renders into its own root: the bug-report sheet is fully open and driveable (`/input`, `/click`
  reach its field and buttons, which search every root) while `/tree`, even `?scope=all`, shows no trace
  of it. An empty tree after opening a sheet is not evidence the sheet failed to open — address its
  contents by label instead, and read `/phone.png` to see it.
- **The operator plays the OS — including acknowledgement.** `✓` on a job does *not* complete it: it
  deposits the object store-direct and stages an ack that **the next `▶ Invoke extension` records as
  `COMPLETED`**. Completing every job and expecting "In sync" without a second invoke will look like a
  bug and isn't. A completed-but-unacked job stays listed, so `index=0` twice hits the *same* row.

## On-device iOS (agent-driveable over USB)

The iOS PhotoKit upload extension is **physical-device-only** (no simulator support; spec
`ios-photokit-upload`). It ships against the **deprecated iOS 26.1** `PHBackgroundResourceUploadExtension`
— the only protocol runnable on current GM devices (⏰ **re-evaluate at iOS 27 GM, ~Sept 2026**:
the async `PHBackgroundResourceUploadJobExtension` exists in the 27 SDK) — and
its *upload trigger* (`process()`) is OS-scheduled — it cannot be forced.

**Platform checks** (forcing proofs recorded in
`changes/archive/2026-07-17-establish-target-architecture/design.md`; settled by the migration's
device Session A, and consumed): ① `PHBackgroundResourceUploadProcessingResult` is **Swift-only**
(swiftinterface, no ObjC header) but RawRepresentable over Int — so step 12 moved the decision to
Kotlin (`processingResultRawValue`, raw values pinned in commonTest; Session D re-verifies them
against the SDK) while the construction stays the one pinned Swift `??`; ② the 26.1 extension
*protocol* carries **no** deprecation (only its creation method is deprecated at 26.4) — do not
write prose calling the protocol deprecated, and do not adopt the 26.4 APIs before the ~Sept 2026
re-eval; ④ zero `deferring`/`running deferred` lines across all production logs — the
ProtectedData defer-queue was dead code and step 12 deleted the port (`:domain:keychain` died with
it); ⑥ settled at 11a (closed absence-whitelist). Still open: ③ `BackgroundUploadURLBase`
runtime-destination rules; ⑤ `backup2` App-Group extraction. But everything around
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
  --env SNAPSYNC_EVENT_LINK="https://snapsync.stho.net/join#v=3&d=<base64url({\"eventId\":\"<uuid>\",\"autoJoin\":true})>" --userspace
# ⚠️ `"autoJoin":true` is REQUIRED for a headless join: without it the link opens the interactive
# join gate — a confirmation dialog awaiting a tap no headless run can give (spec `ios-app-shell`).
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
⚠️ **There is deliberately NO whole-zone reset tool.** The single `snap-sync-dev` zone is the *only* zone
(`api/src/config.ts` — the deployed backend uses it too), so it is **shared with real TestFlight /
App-Store users' photos**; a blind zone wipe would destroy them. Clean up **targeted only** — a fresh
event id, `SNAPSYNC_LEAVE`, or deleting the specific event's/device's objects via bunny (dashboard or
native Storage API). Do not re-introduce a `reset-storage`-style whole-zone delete.

**Creating an event headlessly.** `SNAPSYNC_CREATE_EVENT=<base64url(JSON)>` is a **dev/test trigger**
(capability `ios-app-shell`), read **once per process** and inert in production. The JSON carries a
**required** `name` plus optional `startsAt` (canonical `…Z`; default **now** — which is also the cutoff
floor, so a create-today event accepts `SNAPSYNC_SEED_POLICY`'s +1h assets), `autoJoin`, `minPhotoDate`,
`direction`, `saveToAlbum`. It mints via the attest-gated `POST /events`, then:
- **without `autoJoin`** — mint-only: it joins nothing and logs the greppable oracle
  `created eventId=<uuid>` (in `debug.log`), the id to reuse in a later `SNAPSYNC_EVENT_LINK` join;
- **with `autoJoin`** — it forwards a synthesized `autoJoin` link through the **same** join gate a QR
  uses, landing a live membership in one launch (cutoff/direction/album honoured, cutoff clamped to the
  floor like every join).
```
d=$(python3 -c "import json,base64;print(base64.urlsafe_b64encode(json.dumps(
  {'name':'Test Party','autoJoin':True,'direction':'both'}).encode()).decode().rstrip('='))")
$P developer dvt launch app.snapsync --env SNAPSYNC_CREATE_EVENT="$d" --userspace
uvx pymobiledevice3 apps pull app.snapsync Documents/debug.log   # read `created eventId=…` (mint-only)
```
⚠️ **`SNAPSYNC_CREATE_EVENT` is NON-idempotent — every cold launch mints a NEW backend event** (the
backend mints a fresh UUID per `POST`; there is no create-if-not-exists). This is the **opposite** of
`SNAPSYNC_EVENT_LINK`, which is safe to leave set for the per-build loop (a re-join reconciles). **Unset
`SNAPSYNC_CREATE_EVENT` after the mint**, or each relaunch orphans another event (and an `autoJoin`
re-launch leaves the previous one to join the new). Use mint-only to pre-seed the several distinct events
a multi-shape test needs (one relaunch per event), then join them with `SNAPSYNC_EVENT_LINK`.

**Leaving headlessly.** `SNAPSYNC_LEAVE=1` (presence-triggered, like `SNAPSYNC_FORCE_URLSESSION_UPLOAD`)
is a **dev/test trigger** (capability `ios-app-shell`), read **once per process** and inert in production:
it leaves the current membership (cancel downloads, stop the producer, clear config, notify the backend)
and returns the device to the unjoined resting state. A no-op when unjoined. It is the only headless route
to the unjoined state (a *switch* to a different event id leaves-then-joins via the join gate; standalone
leave does not rejoin).

**Resetting durable state headlessly.** `SNAPSYNC_RESET_STATE=1` (presence-triggered, like
`SNAPSYNC_LEAVE`) is a **dev/test trigger** (capability `ios-app-shell`), read **once per process** and
inert in production: it voids this device's durable sync state — the upload ledger, the discovery
cursor, the membership config (**locally**, notifying no backend), and non-terminal download rows —
while **keeping** imported download rows (their `createdLocalId` is what stops a downloaded photo being
re-uploaded). It exists because **crossing backends otherwise fails silently in both directions**; see
*The local backend rig* below, which is the only reason to reach for it. Not needed for ordinary
event-to-event work — a *leave* keeps the ledger deliberately, and correctly, against one backend.

**Ordering.** When more than one membership trigger is set in a launch, they apply in the fixed order
`reset → leave → create → event-link`, sequentially (each awaited), so e.g. `SNAPSYNC_LEAVE` +
`SNAPSYNC_CREATE_EVENT` drops the current membership before minting the new one, and a
`SNAPSYNC_RESET_STATE` + `SNAPSYNC_CREATE_EVENT` launch mints against a clean slate (after a reset the
device is unjoined, so a paired `SNAPSYNC_LEAVE` is a no-op rather than a `DELETE` aimed at the backend
that is no longer baked in). A `SNAPSYNC_FORGE_STATE` launch ignores all four (forge wins, structurally).

`SNAPSYNC_FORGE_STATE=<state>` is a **dev/test trigger** (capability `ios-app-shell`), read **once
per process** and inert in production: it mounts the real `StatusScreen` over **forged sources** for a
recognized state (`create` · `joining` · `in_sync`) — no backend, attestation, or photo access — so a
marketing/App-Store screenshot can be captured of any state. The forge substitutes the container's
*inputs*, not a static `UiState`, so it can only render a frame the real reduction can reach (the
name→sources map is the tested `forgeStatusHost` factory in `:ui:presentation`). This is what the
non-gating, dispatch-only `.github/workflows/screenshots.yml` drives on a simulator (`macos-26`) —
`simctl launch … SNAPSYNC_FORGE_STATE=<state>` → `simctl io screenshot` → **6 raw captures**.

### Refreshing the marketing screenshots (operator runbook)

`screenshots/*.png` — 6 raws, 3 forge states × light/dark — are the **single source of truth for two
surfaces**, which consume them on **different schedules**: `ios-appstore-promote.yml` composites the App
Store listing images from them **at release time** (only the three `-light` raws reach the store; see
`compose_screenshots.sh`), and the `site/` Astro build derives the landing page's WebP from them
(`astro:assets`, shipped by `site-deploy.yml`) **on merge**. So **refreshing them is a commit** — that
commit ships the landing page immediately and is picked up by the next release. Nothing regenerates
automatically — the capture is dispatch-only.

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
- **A headline or size change needs NO re-capture** — both consumers derive from the committed raws.
  Edit `metadata/screenshots/en-US.json` (App Store copy) or the `site/` landing page and push. The
  landing page rebuilds on merge; the App Store copy waits for the next release, like the raws.
- **The App Store upload happens ONLY inside a release** (`ios-appstore-promote.yml`, after the build is
  attached and before the submit gate), replacing the set on the version being released, behind the
  editable-version gate (never a version in review). ⚠️ **A merge to `main` uploads nothing to the store**
  — pushing a raw or a headline does not change the listing on its own. There is deliberately **no**
  push-triggered screenshot upload: a promote is single-shot per version (the `vX.Y` tag guard refuses a
  re-run), so **correcting an already-promoted version's screenshots is a MANUAL console upload**. Decision
  record: `changes/archive/…-upload-screenshots-on-promote`.

⚠️ **A "fresh event id" must be a real event you created — not an invented UUID.** The join gate loads
the event's details first and **aborts on a miss**, leaving the *previous* membership untouched. The
abort's only headless signal is one `debug.log` line — with `autoJoin=true` (the headless path):

```
autoJoin aborted: details load did not succeed for <id> (NotFound)
```

and on a link **without** `autoJoin` (which parks on the gate's dialog instead — see the launch
example above), the same oracle reads:

```
join gate: details load did not succeed for <id> (NotFound)
```

(`(Failed)` in either shape means a transient load failure, not a missing event.) Both are emitted by
the gate itself, **after** the HTTP `GET … → 404` line — a `404` alone is the raw fetch, not the
abort decision.

The launch still succeeds and the app runs on happily — **with the old config** — so a run that assumes its
link applied is measuring the previous membership. (Observed: a `direction=download` link with an invented id
left a `Both` membership joined and uploading, which reads exactly like a broken direction gate. Always
confirm the id in `debug.log` — `reconcile(eventId=…)` and `config ok` — matches the one you passed.)

**One event per membership shape.** `direction`, the cutoff, and the album opt-in are **fixed at join**, and
re-scanning the *already-joined* event short-circuits as `AlreadyJoined` (capability `join-event`) — so
`SNAPSYNC_EVENT_LINK` can change **none** of them for the event you are already in. Exercising a different
direction needs a **different event that already exists**. Create those events headlessly with
`SNAPSYNC_CREATE_EVENT` in mint-only mode (above) — one relaunch per event, each logging its
`created eventId=<uuid>` — then join each with `SNAPSYNC_EVENT_LINK` carrying the shape you want.

🚫 **Never point it at an event you do not own.** A `direction=download` join imports that event's photos into
this device's library and registers this device on its backend membership. Log-scraped ids are someone's real
event.

**Restarting the app (black-screen trap).** `dvt launch --kill-existing` — and `dvt kill`/`pkill` —
only send **SIGTERM**, which SnapSync ignores; a relaunch then layers a new instance on the
still-alive old one and the app sticks on a **black launch screen** (status bar visible, content
black). To truly restart: `dvt signal <pid> 9` (SIGKILL) **then** `dvt launch` (verified recovery).
Take the screenshot promptly after a single launch; avoid rapid relaunch cycles.

**The headless per-build loop:** the ssh-mac loop builds the dev IPA (below) → `apps install` → `dvt launch --env
SNAPSYNC_EVENT_LINK=…` (use a **fresh event id**, per the note above, or the reconcile will seed
already-stored photos and nothing uploads) → the OS invokes the upload extension on its own cadence →
confirm the objects landed in the backend's bunny storage zone (see *Verify real uploads* below; the
`dvt screenshot` status counts are informational, not the authoritative landing check). **Still
gated:** taps / UI gestures need a signed **WebDriverAgent** (`developer wda`), and `process()`
**timing** is OS-owned — a re-provision reliably triggers an invocation but you cannot force *when* it
runs.

### `main` uploads to internal TestFlight — there is no public channel

Every merge to `main` uploads a signed build to TestFlight automatically (capability
`ios-testflight-delivery`), but it reaches **no external tester**. `ios.yml` runs `ios-build` +
`ios-test` (the merge gates) → `ios-deliver` (export + upload). The uploaded build lands in the
**internal `development` group** (`hasAccessToAllBuilds`) only. **There is no `ios-promote` job and no
`alpha` external group fed by CI** — the public alpha channel was **removed** (App-Store-only; decision
record `changes/archive/2026-07-19-remove-alpha-testflight-promotion`). **Distribution to real users is
the dispatch-driven App Store release below (`ios-appstore-promote.yml`) — the only path to external users.**

- **Uploads are unfiltered.** Docs-only and backend-only merges upload a binary-identical build too. A
  path filter on the trigger would freeze merges — `ios-build` / `ios-test` are required checks, and a
  skipped required check is never posted. These internal builds accumulate unseen; that is harmless
  because nothing external consumes them.
- **Internal testers may be notified per build.** The `autoNotifyEnabled=false` suppression lived in the
  removed `ios-promote` job, so nothing suppresses it now — accepted, since the internal group is
  effectively just the developer.
- **Every delivered build names its change.** `ios-deliver` sets the TestFlight "What to Test" note to
  `<PR title> (#<num>, <short sha>)` — PR title because merges are rebase-only, so the head-commit
  subject can be a trailing docs/test commit (that subject + SHA is the no-PR fallback). Upload and
  note are one `app-store-connect publish` invocation (codemagic-cli-tools; no `--testflight`/submit
  flag), which owns the wait for the build to become discoverable in ASC.
- **The marketing version each build carries is COMPUTED** — `ios.yml` bakes
  `MARKETING_VERSION = max(floor, latest vX.Y tag with its minor +1)`. The **floor** is `Config.xcconfig`'s
  `MARKETING_VERSION` (seed `0.1`, two-part; no target-level entry in `project.pbxproj`). The minor bump is
  **integer** (`v0.9 → 0.10`, never a decimal carry to `1.0`) and `max` compares `(major,minor)` **tuples**,
  so after `v0.1` ships every build carries `0.2`, and so on. A **major jump** (`→ 1.0`) is a **manual floor
  bump** in `Config.xcconfig` via a PR — the only reason to touch that value. The App Store release below
  **promotes** one of these builds and derives its store version from it (there's no version input).
- **APNs is production for every TestFlight/App Store build.** CI Release archives inject
  `APS_ENVIRONMENT=production` / `APNS_ENV=production` (in the `ios-archive` composite action); only
  never-distributed Debug builds — the branch-gate archive (non-`main` pushes build Debug; the gate
  skips the LLVM optimization pass, capability `ios-ci`) and ssh-mac — stay `development`/`sandbox`.
  The `Config.xcconfig` values are the dev default, overridden for distribution. **`ios.yml` has no
  `workflow_dispatch`**: it once carried an `upload_host` input for a dev IPA, but `ios-build` uploads
  its archive on `main` only, so a dispatched run built a Debug archive and discarded it — there was no
  way to get the IPA out. Point a dev build at another backend on the ssh-mac `xcodebuild` line instead
  (see *Pointing a build at a local backend*). Removing it also removed the plain-dispatch escape hatch
  for exercising the Release path pre-merge, so a Release-only link failure now surfaces only on the
  post-merge `main` run.

### App Store releases PROMOTE a tested build (the tag is the RECEIPT, not the trigger)

```
gh workflow run ios-appstore-promote.yml -f build_number=512             # attach, no submit
gh workflow run ios-appstore-promote.yml -f build_number=512 -f submit=true
```

`.github/workflows/ios-appstore-promote.yml` (capability `ios-appstore-release`) **promotes an existing App
Store Connect build** — one `ios-deliver` already uploaded — instead of building a new one. Pick it by
**`build_number`** (its `CFBundleVersion`); the store version is **derived from that build's own marketing
version** (so the version record and the build always match — there's no `version` input). It
**finds-or-creates** the `X.Y` App Store version record, **attaches** the build, applies the **App Review
details** from the repo, optionally **submits**, and — last — **creates the `vX.Y` tag** on the build's
origin commit. ⚠️ **Don't push a `vX.Y` tag by hand**: tags trigger nothing, and a tag you push yourself
makes that version permanently un-releasable (the guard below refuses an existing tag).

- **One `ubuntu` job, no build.** It compiles and signs nothing — no Xcode, no keychain, no certs. `asc`
  is fetched as a linux binary; the job holds `contents: write` for the tag push.
- **To find the build number**: it's the `CFBundleVersion` = the `ios.yml` `run_number` that produced the
  build you tested on TestFlight. Promote the exact build you validated.
- **Version scheme is two-part** (`build → 0.2`, tag `v0.2`); a hotfix is a minor bump (no `X.Y.Z`). The
  derived version must match `^\d+\.\d+$` or the run fails fast (a pre-change `0.1.0` build can't be
  promoted). To ship a **new** version, merge builds until one carries it (versions auto-advance off the
  last tag; a major is a `Config.xcconfig` floor bump — see the version section above), then promote it.
- **Guards** (both before any ASC mutation): the derived version matches `^\d+\.\d+$`, and its `vX.Y` tag
  must not already exist (an already-shipped version is never re-released). **No green/ancestor re-check** —
  provenance is guaranteed at upload time (`ios-deliver` runs only on `main`, only when both gates pass), so
  every promotable build is from a merged, gated commit.
- **The tag points at the build's ORIGIN commit**, resolved `build_number → ios.yml run(branch=main) →
  head_sha`. If that can't be resolved (run deleted), the run **fails loud** rather than tag a guess —
  hand-tag `vX.Y` at the right commit. A wrong resolution would be a wrong *tag*, never wrong bits (the
  attach identifies the build by number). Created **LAST, on success only**, so a failed run leaves none.
- **Submit is opt-in and gated**: `-f submit=true` only submits if `asc review doctor` reports zero
  blocking checks; otherwise the run refuses and prints them. ⚠️ `doctor` is **not** the whole
  preflight — it reported zero blockers on a version `asc review submit` then refused for a missing
  `en-US: whatsNew` (run 30632785849). A green gate is not a promise the submit will pass.
- **The release notes write themselves** (capability `changelog-labels`): the promote derives
  `whatsNew` from the **labelled PRs** merged between the nearest ancestor `vX.Y` tag of the build's
  origin commit and that commit — `/ship`'s `enhancement`/`bug`/`internal` label is what decides
  whether a change is customer-visible, and the table at the top of `.github/scripts/release_notes.py`
  is the one place a label maps to a heading (`New`/`Fixed`; `internal` excluded; no catch-all).
  Derived **before** any ASC mutation (an unresolvable range or an over-4000-char result costs a red
  run and nothing else), applied after the attach on **every** promote, `en-US` only. A PR **must**
  carry one of the three labels: `check-label` is a required check, and it is the only thing that
  *prevents* an uncategorized change — the derivation reports one but ships anyway. An all-`internal`
  release gets a committed fallback sentence.
  **The run summary is the thing to read**, not just ASC: the script's stdout *is* that summary —
  rendered notes, the range's reconciliation counts (`N pull request(s) in range — P published,
  I internal, U uncategorized`), the `internal` roster, and a ⚠️ block for anything it could not
  categorize (unlabelled PRs, commits with no PR merged to the default branch). The ⚠️ block is absent
  when there is nothing wrong. Preview any range locally before dispatching — a promote is
  single-shot per version, so notes you dislike are a manual console fix:
  ```
  GH_TOKEN=$(gh auth token) python3 .github/scripts/release_notes.py \
    --repo stefanhoelzl/SnapSync --target <origin-sha> --previous vX.Y   # omit --changelog to preview
  ```
  ⚠️ **Do NOT reintroduce GitHub's `releases/generate-notes`.** It reads `.github/release.yml` from the
  **`target_commitish`**, so the changelog's shape became a property of the *released bits*: a build
  whose commit predated that file rendered as one ungrouped section listing every `internal` PR, and
  no edit to `main` could ever change it — build 542 was permanently un-promotable that way. The
  derivation now resolves PRs itself (GraphQL `associatedPullRequests`, which handles rebased commits)
  and reads **nothing** out of the commits it describes, so **any** build ASC holds is promotable.
  Nothing publishes a **GitHub Release**, and there is no committed `CHANGELOG.md`: the tags plus the
  labelled PRs are the history, and the store listing is the only rendering.
- **Review details are repo-owned**: prose in `metadata/review/notes.md` (deliberately outside the
  metadata tool's canonical schema — an unknown key there fails a required check and freezes merges);
  contact from the `ASC_REVIEW_CONTACT_*` secrets (this repo is public, so they can be neither committed
  nor passed as inputs, which render publicly in the Actions UI). No demo account: the app has no sign-up.
- It posts **no** required status check (not in `main.json`); a failed release is red but blocks nothing.
  It introduces no new **ASC credential** (the contact secrets grant no ASC access).

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
#     IPA needs no optimization, and
#     the Debug archive is a complete installable bundle (arm64 app binary + BackgroundUploadExtension.appex
#     in Extensions/) — the 6b re-sign is config-agnostic, so ONLY this -configuration line changes. Switch
#     to Release only when you need an optimization-representative build. Keep the cold cost paid once: never
#     wipe build/ or .gradle between iterates (the step-6 rsync already excludes them) and keep the Gradle
#     daemon alive (no --no-daemon) — an incremental Debug iterate is then ~1 min.
sshmac 'cd snapsync && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
          -destination "generic/platform=iOS" -archivePath "$HOME/artifacts/SnapSync.xcarchive" \
          CODE_SIGNING_ALLOWED=NO archive'
# 6b. Manually re-sign the archive INSIDE-OUT, then repackage the IPA. The entitlements come from the
#     REPO's own `.entitlements` files, with the build variables expanded — NOT from the profile.
#     WHY not `xcodebuild -exportArchive`: automatic-signing export does NOT reuse manually-installed
#     profiles without an ASC key (fails "No profiles for 'app.snapsync…' were found"); and the
#     CODE_SIGNING_ALLOWED=NO archive has EMPTY entitlements, so any export ships an IPA that aborts at
#     launch on the App-Group container ("client is not entitled").
#
#     ⚠️ A PROFILE IS A GRANT; ENTITLEMENTS ARE A CLAIM. The profile says "you MAY use anything in
#     `<TEAM>.*`"; entitlements say "I AM this". Copying one into the other is a category error, and it
#     is silently wrong for every WILDCARD key an Apple DEV profile carries — of which there are two:
#       • `associated-domains: *`      → the app claims every domain, therefore NONE. Every universal
#                                        link dies silently (verified 2026-07-16).
#       • `keychain-access-groups: <TEAM>.*` → not a writable group name, so each process falls back to
#                                        its OWN `application-identifier` group. The app and the upload
#                                        extension then hold DIFFERENT device ids, both reads succeed,
#                                        and the app re-imports every photo it uploaded (2026-07-20).
#                                        SINCE device-identity started naming the group EXPLICITLY
#                                        (`kSecAttrAccessGroup = <TEAM>.app.snapsync.shared`), the wildcard
#                                        is WORSE than silent: an explicit-group query is not satisfied by a
#                                        `<TEAM>.*` entitlement, so the read throws `errSecMissingEntitlement`
#                                        (-34018) and the launch coroutine — see the app-scope error boundary
#                                        in app/ios/CLAUDE.md — logs it rather than aborting, but the app is
#                                        dead in the water (no device id). A hand-narrowed re-sign that kept
#                                        the keychain wildcard did exactly this on 2026-07-21. USE `build_ent`
#                                        BELOW; never re-sign by narrowing the profile grant key-by-key.
#     The keychain one is the worse of the two: it writes a real item to a real group, and the device id
#     is written once and never rewritten — so the mistake is frozen permanently, on a value whose loss
#     is unrecoverable. This is why we now GENERATE the claim instead of narrowing the grant key by key:
#     narrowing only ever fixes the wildcard you already know about (`associated-domains` was narrowed in
#     July; `keychain-access-groups` sat there unnarrowed the whole time and nobody connected the two).
#
#     The profile-resolve supplied THREE things for free that the repo `.entitlements` do NOT carry —
#     `application-identifier`, `com.apple.developer.team-identifier`, and `get-task-allow`. The first is
#     MANDATORY: without it the install is refused ("Application is missing the application-identifier
#     entitlement", verified 2026-07-20). Add all three back. The two id keys are CONCRETE in the profile
#     (never wildcards), so extracting exactly them from the matched profile is safe — it is only the
#     wildcard keys that a grant must never donate to a claim.
sshmac 'bash -se' <<'SIGN'
set -e; cd "$HOME/artifacts"
SRC="$HOME/snapsync/iosApp"
PD="$HOME/Library/MobileDevice/Provisioning Profiles"
ID=$(security find-identity -v -p codesigning | awk '/Apple Development/{print $2; exit}')
APP="SnapSync.xcarchive/Products/Applications/SnapSync.app"
EXT="$APP/Extensions/BackgroundUploadExtension.appex"          # iOS 26 uses Extensions/, NOT PlugIns/
PB=/usr/libexec/PlistBuddy
CFG="$SRC/Configuration/Config.xcconfig"
TEAM=$(awk -F= '/^TEAM_ID/{gsub(/[ \t]/,"",$2);print $2}' "$CFG")
DOMAIN=$(awk -F= '/^ASSOCIATED_DOMAIN/{gsub(/[ \t]/,"",$2);print $2}' "$CFG")
build_ent() {                                                  # $1 = repo .entitlements, $2 = out, $3 = matched profile
  sed -e 's|\$(AppIdentifierPrefix)|'"$TEAM"'.|g' \
      -e 's|\$(ASSOCIATED_DOMAIN)|'"$DOMAIN"'|g' \
      -e 's|\$(APS_ENVIRONMENT)|development|g' "$1" > "$2"
  # The identity keys the profile-resolve used to supply. Concrete, never wildcards — safe to lift.
  local appid teamid
  appid=$(security cms -D -i "$3" | plutil -extract Entitlements.application-identifier raw -)
  teamid=$(security cms -D -i "$3" | plutil -extract Entitlements.com\\.apple\\.developer\\.team-identifier raw -)
  $PB -c "Add :application-identifier string $appid" "$2"      # MANDATORY — install fails without it
  $PB -c "Add :com.apple.developer.team-identifier string $teamid" "$2"
  $PB -c "Add :get-task-allow bool true" "$2"                  # dev-only; required to launch/debug
}
for p in "$PD"/*.mobileprovision; do                           # embed each profile + remember which target
  aid=$(security cms -D -i "$p" | plutil -extract Entitlements.application-identifier raw -)
  case "$aid" in
    *.app.snapsync.BackgroundUpload) EXTP="$p"; cp "$p" "$EXT/embedded.mobileprovision";;
    *.app.snapsync)                  APPP="$p"; cp "$p" "$APP/embedded.mobileprovision";;
  esac
done
build_ent "$SRC/iosApp/iosApp.entitlements" app.plist "$APPP"
build_ent "$SRC/BackgroundUploadExtension/BackgroundUploadExtension.entitlements" ext.plist "$EXTP"
# Nested frameworks first (deepest inside-out). The SPM `Sentry` product links STATICALLY into both
# binaries (nm-verified: classes defined in the app image, no load command) — but Xcode still embeds
# the binaryTarget's dynamic Sentry.framework in Frameworks/, unreferenced dead weight that must
# nonetheless be signed or the install is refused (measured 2026-07-21).
for fw in "$APP"/Frameworks/*.framework; do
  [ -d "$fw" ] && codesign -f -s "$ID" "$fw"
done
codesign -f -s "$ID" --entitlements ext.plist "$EXT"           # …then the extension (inside-out)…
codesign -f -s "$ID" --entitlements app.plist "$APP"           # …then the app
# THE GUARD: no wildcard may reach a signed binary. Key-agnostic ON PURPOSE — it catches whichever
# wildcard key Apple adds next, which per-key narrowing by construction cannot.
for b in "$EXT" "$APP"; do
  if codesign -d --entitlements :- "$b" 2>/dev/null | grep -q '[*]'; then
    echo "WILDCARD LEAKED into $b — do not install this build:"
    codesign -d --entitlements :- "$b" 2>/dev/null | plutil -p -; exit 1
  fi
done
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

By default on-device uploads go to the **deployed HTTPS backend** (the device-facing host baked from
`Config.xcconfig`). Confirm one landed by checking the backend's bunny **storage zone** (see
`api/README.md` / `openspec/specs/backend-deployment`), not the app status screen. Connections are
HTTPS-only — default ATS, no `NSAllowsLocalNetworking` exception, on any host.

To test a **backend change** without deploying it, point the device at a local rig instead (below);
there the oracle is `find api/.localstore -type f`.

### The local backend rig (test backend changes without deploying)

Runs the **real** `api/` app — same routes, same gates, same source constants — against a filesystem
store, so nothing touches the shared `snap-sync-dev` zone (which holds real users' photos). Dev
infrastructure: non-gating, no spec, same posture as `ssh-mac.yml` and `:test:harness-driver`.
`main.ts` never imports `src/dev/`, and `deno bundle` roots the deployed bundle at `main.ts`, so none
of it can ship.

```bash
cd api
deno task dev:local     # 127.0.0.1:8080, no tunnel — the curl loop
deno task dev:tunnel    # + a cloudflared quick tunnel, for a real device
```

Both print the origin, the store path, and a ready-to-paste `BACKGROUND_UPLOAD_URL_BASE=…` line, and
write the origin to `api/.localdev/host`. **curl needs no `authorization` header** — the gate stays
fully on and a request carrying a *bad* token still `401`s; the rig only fills in a token when one is
absent (the same trick `test/app.test.ts` uses). `/attest/*` is untouched, so a device's real
attestation runs for real against the rig.

- **Reset is `rm -rf api/.localstore`.** This is the deliberate **inverse** of the production rule
  above: no whole-zone reset tool exists for bunny because that one zone holds real users' photos;
  the local store holds nothing.
- **`dev:local` mints download URLs as `https://127.0.0.1:8080/…`** because the production presigned
  URL shape is fixed. Swap the scheme to follow one by hand: `… | sed 's|^https://|http://|'`.
- **No APNs**, so `/events/<id>/notify` returns `202` with every token skipped — faithful to the
  route's best-effort contract. A receiving device therefore reconciles on foreground/relaunch rather
  than on a silent push.

#### Pointing a build at a local backend

The upload host is **compile-time** (PhotoKit forces it), so this needs a rebuild. One xcconfig
setting feeds **both** targets' `Info.plist`, so one override covers the app and the extension:

```bash
H=$(cat api/.localdev/host)
sshmac "cd snapsync && xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
          -configuration Debug -destination 'generic/platform=iOS' \
          -archivePath \"\$HOME/artifacts/SnapSync.xcarchive\" \
          BACKGROUND_UPLOAD_URL_BASE=$H/api/v1 CODE_SIGNING_ALLOWED=NO archive"
# then the unchanged 6b re-sign + install steps from the ssh-mac loop above
```

A quick tunnel's hostname is **random per session**, so the IPA is rebuilt per session (~1 min
incremental Debug). There is no CI path for this: `ios.yml` has no `workflow_dispatch` (see above).

#### ⚠️ Crossing backends REQUIRES `SNAPSYNC_RESET_STATE` — or nothing uploads, silently

Launch the swapped build with `SNAPSYNC_RESET_STATE=1` **every time you change which backend is
baked in — in both directions**, including going back to production:

```bash
$P developer dvt launch app.snapsync --env SNAPSYNC_RESET_STATE=1 \
   --env SNAPSYNC_CREATE_EVENT="$d" --userspace
```

Why it is not optional: the upload ledger's key is the **bare filename**, event-independent, and a
*leave* deliberately keeps it (a `COMPLETED` row stays true across a leave — `sync-ledger`). Point the
build at a different backend and the bytes are on the one you left while the ledger still says
`COMPLETED`, so the device uploads **nothing** — no error, no failed request, no log line. Clearing
the ledger alone is **not enough** either: the discovery cursor is a `PHPersistentChangeToken`, and
with it retained the next cycle sees no changes and enumerates nothing. The trigger clears both, plus
the membership config (**locally**, notifying no backend) and non-terminal download rows; it **keeps**
imported download rows, whose `createdLocalId` suppresses re-uploading photos this device downloaded.

**The oracle when you forget:** each process logs `[boot] upload base = …` in `debug.log`. A tunnel
host there beside a cycle reporting `enumeration: 0 seen` (or `N seen, 0 new, N already-uploaded`)
means the reset did not run. Ordering is `reset → leave → create → event-link`, so a reset in the
same launch as a create lands clean; after a reset the device is unjoined, so a paired
`SNAPSYNC_LEAVE` is a no-op rather than a `DELETE` aimed at the wrong backend.

Going **back to production** is the direction with no automatic protection and it needs the same
flag; `event-rejoin-reconciliation` then re-seeds already-stored photos as `COMPLETED`, so the cost is
one reconcile, not a re-upload of the library.

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
:domain                the platform-free core (spec module-architecture): five zone packages under app.snapsync — model/ (vocabulary + pure codecs: the config surface incl. the EventLink codec + generated LINK_ORIGIN, sync vocabulary, selection policy + denylist + upload keys + device manifest + RawAsset mapping, edge-URL builder, SyncStatus/SyncProgress, PermissionStatus, LaunchDirectives + the sealed CompositionMode resolver, the UserCommands bundle type, and the Logger.invocation enter/exit helper over the injected ports/LogScope seam), ports/ (every port seam, named for the need: config, keychain, gallery candidate-read/status/manifest, permission, photo-selection-change, download + download-store, backend-need clients, upload transfer/scheduler/discovery, push, attest, join marker, LedgerStore, album, crash reporting — CrashReporting.start() is the shared compositions' first act, idempotent, no-op without a baked DSN), feature/ (mutually blind, feature-blindness gate armed): upload (UploadCycle + the port-pure cycle gate, UploadArm/UploadProducer, BackgroundUploadPump, ExtensionReconciler, SyncEngine + LedgerWriter — the single ledger-writer feature — UploadPushReceiver), membership (JoinEvent + DeviceEnroller, LeaveEvent, DeviceManifestProducer, MembershipRefresh + TitleNeed, ReconfigureEvent, ResetDeviceState, switchDecision), status (SyncStatusSource + LedgerBackedSyncStatusSource + LedgerCountsSource/Poller + OwnDeviceGalleryStatusSource), trust (DeviceAttestation incl. refreshOutcome), download (DownloadController + QueuedPhotoDownloadJobs + DownloadPushReceiver + the DownloadStatusSource read-model), album (AlbumCoordinator — ensureAlbum owns the granted/opt-in gate, albumIdFor the import-time lookup), creation (CreateEvent + the CreationStatus seams), push (PushRegistration + EventNotifier over the PushHttpClient/PushTokenSource ports), flow/ (the OS-callback trigger flows Foreground · Background · SilentPush [the cross-arm push fan-out] · DownloadBackstop · Provision, each importing model/+feature/ ONLY — flow-no-ports gate armed; every port/platform touch injected as a compose/-built effect lambda; each flow transcribes into architecture/flows/ under the closed grammar, and an untranscribable flow FAILS generation), and compose/ (the SHARED composition, law "One shared composition": uploadCore(scope, UploadPorts) → UploadCycle — the ONE cycle assembly both device tiers' roots AND the world call; snapSyncApp(scope, AppPorts) → AppCore — the app feature graph, the flow instances, the live UserCommands bundle, and the permission-grant subscriptions as an explicit installPermissionSubscriptions() the app shell invokes from host assembly ONLY). Zero project() deps; jvm+iosArm64+iosSimulatorArm64; no iosMain source dir; model-purity + ports→model + feature-blindness + flow-no-ports gates armed in :test:architecture
:adapter:generic:app   platform-free technology impls of the :domain ports: the Ktor clients (HttpAttestClient, HttpEnrollment, HttpEventDirectory, HttpDeviceFilesSource, HttpLeaveNotifier, HttpEventUnionSource, HttpEventCreation, KtorPushHttpClient) + the SQLDelight stores (SqlDelightLedgerStore, SqlDelightDownloadStore) with both db schemas + the SystemClock/SystemTimeZone port impls; jvm+ios. Its jvmTest/iosSimulatorArm64Test extend the storage contracts from :test:world
:adapter:ios:ext-safe  every iOS adapter the EXTENSION process links (placed by linkage): the discovery walk + cursor store (IosDiscovery/IosDiscoveryStore), the PhotoKit upload-job platform (IosPhotoKitUploadPlatform — the BackgroundTransfer impl for the OS-driven tier), ledger/download-store native drivers (iosLedgerStore/iosDownloadStore + the App-Group consts), manifest store + PhotoKit enumerator, device-log writers (FileLogWriter/PublicNSLogWriter) + IosLogScope, the crash-reporting seat (SentryCrashReporting + SentryLogWriter over the Sentry KMP SDK, capability crash-reporting: Error/Assert→events, lower→breadcrumbs, every outgoing UUID scrubbed; DSN read from the process bundle — absent in every dev build, injected only by CI Release archives), darwinHttpClient, IosJoinedEventMarker, IosAlbumManager/IosAlbumMapStore, IosAttestKey, FileBackedConfigStore (the App-Group config file of record; save/clear are file-only since the finale ended 11a's Keychain write-through) + KeychainConfigReader (the legacy-item fallback seat: read + the leave-path delete, never a value write — the installed base's update path under the ship-at-once model; dies with the designated post-ship Stage-2 change, capability event-rejoin-reconciliation) — and the Keychain impls: this is the ONLY module that may touch SecItem* (IosKeychain, KeychainDeviceIdentity, KeychainAttestStore, KeychainConfigReader; the :test:architecture guard enforces it, and the extension-safety gate forbids platform.UIKit/BackgroundTasks anywhere in it)
:adapter:ios:app-only  iOS adapters only the MAIN APP process links: IosUrlSessionUploadPlatform + IosBackgroundScheduler (the 18–26.0 tier), IosDownloadTransport, IosPhotoLibraryImporter, PhotoLibraryPermission, presentShareSheet (the UIKit share-sheet presenter) — the two URLSession adapters own OS-reattached app-process session ids, so an extension-side link must stay structurally impossible
:adapter:generic:fake  the honest in-memory port impls (package app.snapsync.fake): InMemoryLedgerStore/DownloadStore/AttestStore/CandidateSource/GalleryStatusSource/CrashReporting + the discovery/marker/manifest/album-map stores — what the world harness and every integration test stand on. Honesty is MECHANICAL (FakeHonestyTest, armed here): a fake's public surface is its port contract plus a constructor taking initial state (the gallery fakes take a MutableStateFlow cell); operator rigging (levers, inspection) lives in :test:world wrappers (WorldGallery, RecordingDownloadStore), never here. jvm+iosSimulatorArm64 only (never links into a shipped framework); commonTest hosts the re-homed fake-driven feature tests (RawAssetMapping, status sources, download trio, DeviceAttestation)
:ui:presentation       Orbit MVI container + UiState (Compose-free, no engine dep) — the presentation-imports gate (scope ui/presentation/src) enforces: it references only model/, feature read-model types, and its own vocabulary — never ports/ or flow/. StatusContainerHost's inputs are exactly read-models (bare StateFlows + feature sources), the model/ UserCommands bundle (user taps incl. requestAccess/openSettings; live instance built only in compose/), the loadJoinDetails query, and the pure CutoffFormatter (now/zone injected; production binds the Clock/TimeZoneSource ports via :adapter:generic:app's SystemClock/SystemTimeZone in the shells). Also hosts the forgeStatusHost forge factory (formatter passed in by the shell)
:ui:screens            Compose screens (written against App* only); both sides speak model/'s one Arrow enum; CutoffFormatter is a required param (no system-reading default)
:ui:components         App* design system + the Material 3 skin; api(:domain) for the model/ Arrow in AppStatusLine's signature (the one enum presentation and the skin both render from)
:app:desktop           the ONE desktop module: the shared pane library (PhoneFrame + StatusPane, StatusContainerHost wiring) + BOTH harness apps — the full-stack world harness (:app:desktop:run; real StatusScreen whose counts EMERGE from the AppCore the world composes via snapSyncApp, + a right-pane world inspector; capability full-stack-harness) and the forge harness (:app:desktop:runForge, a JavaExec — the Compose plugin models one application main class; phone frame + control panel forging any UI state; capability desktop-test-harness)
:app:ios               iOS app wiring + framework export (thin, untested); SnapSyncRoot parses LaunchDirectives, resolves the sealed CompositionMode ONCE (model/'s resolveComposition — forge excludes the live-stack boot structurally: the one when(mode) picks a ForgeShell/LiveShell delegate and ForgeShell holds no route to app/host), builds the platform adapters and calls :domain compose/'s snapSyncApp
:app:ios:extension     iOS ≥26.1 background-upload extension: the composition root (UploadExtensionRoot) calling :domain compose/'s uploadCore over :adapter:ios:ext-safe (which holds the PhotoKit platform adapter) + :adapter:generic:app — the SnapSyncUploadKit framework; thin, untested (orchestration + tests live in :domain feature/upload)
:test:world            test-only shared infra: the controllable in-memory "world" — BackendStore + MockEngine mini-edge + operator levers/wrappers rigging :adapter:generic:fake's honest doubles — whose World.core IS the real AppCore from the SAME snapSyncApp the iOS shell calls (features, flows, and the UserCommands bundle are production instances), with the extension-tier cycle from the same uploadCore. commonMain also hosts the LedgerStore/DownloadStore CONTRACTS (a test source set cannot be exported; :adapter:generic:app's driver tests and the fake-backed runs extend them from their own test source sets). jvm()+iosSimulatorArm64. Consumed by :app:desktop AND :test:integration (capability harness-world-model)
:test:architecture     test-only JVM guards for invariants the compiler cannot express (capability architecture-guards), all gating ./gradlew build: the zone gates (model-purity, ports→model, feature-blindness, flow-no-ports, presentation-imports), KeychainContainmentTest (no SecItem* outside :adapter:ios:ext-safe — catches fully-qualified calls, which no linter can see on iosMain), the extension-safety gate (no platform.UIKit/BackgroundTasks in extension-linked source), RuntimeIdentityTest (every OS-held literal exactly once), the entitlements guard (never raise default-data-protection to NSFileProtectionComplete — it would make every App-Group file unreadable while locked, killing the background tier), SwiftShellGuardTest + KotlinShellGuardTest (the shell decision pins, exact in both directions; detektAppShell itself gates inside check), ModuleSetTest (settings == the target module set), MixedPortImplTest (no port interface beside a technology impl), DeletionLedgerTest (the migration's retired dead weight stays dead), FakeHonestyTest, LawsDigestTest, EventLink guards
:test:integration      test-only: seam → UI-state integration over :test:world — asserts UiState AND world outcomes (objects landed, ledger COMPLETED, foreign photos imported)
:test:harness-driver   test-only dev infra (non-gating, no spec): serves EITHER desktop harness over HTTP with no window — composes the shipped ForgeHarnessRoot/WorldHarnessRoot into an offscreen Compose scene (CPU raster Skia; no X server, no screen-capture portal) so an agent can click the real buttons and read back the real pixels + semantics tree. Runbook above; rationale in Driver.kt
iosApp/                Xcode project (app + upload-extension targets) — not Gradle
```

**The migration that produced this graph is complete.** The graph and its laws are the
**`module-architecture`** spec — the contract of record; read it (and `architecture-guards` /
`architecture-diagrams`) before moving any code. Every law is now a permanent, gating check under
`./gradlew build` (`:test:architecture` + `detektAppShell` + the flow-transcriber generation
failure) — there is no non-gating grace period any more, and no migration beacon: the `verify`
job measured zero at the finale and was deleted with its module, per its own contract. The
**`diagrams`** check IS required: stale `architecture/` blocks the PR — run
`./gradlew architectureDiagrams` and commit. Rationale for any placement lives in the decision
records under `openspec/changes/archive/` (the migration's steps are archived changes like any
other).

## The laws (digest)

Authority: `openspec/specs/module-architecture/spec.md` — this digest is the in-context copy, one
line per law; a `:test:architecture` guard keeps the two in sync. Every law is mechanically
gated in `./gradlew build`; a violation is a red build, not a review note.

- **The module set withholds; packages organize** — a module exists only to withhold a
  third-party/platform dep by compile error (platform-free `:domain`, M3 in `:ui:components`,
  extension-safety adapter split); everything finer is a package with a derived text gate.
- **Zones inside the core** — `:domain` is `model/` ← `ports/` ← `feature/` ← `flow/` ←
  `compose/`; features never reference a sibling feature; `flow/` never references `ports/`.
- **Ports are the I/O boundary named for the need** — anything touching an external system (time,
  files, network, env included) goes through a port interface in `ports/`, named for the need
  (must survive a second platform); adapters implement, named for technology, placed by linkage.
- **State and authority** — no global mutable state in `:domain`, ever; instance state only as
  derived caches or coordination primitives; authority behind ports (kill-test: after
  kill+relaunch, every fact recoverable via ports, keyed by identifiers the external system
  persisted); sync-I/O port impls own their dispatcher hop.
- **Rules in features, order in flows** — flows coordinate, never decide; features are mutually
  blind and coordinate via one-writer durable state behind shared ports, written whole; no field
  encodes a request to another feature.
- **Commands cross one door** — user taps, OS callbacks, and port-state transitions all enter
  through `flow/` commands (built/decorated only in `compose/`, injected into presentation);
  reads do NOT cross flow — presentation observes feature read-model StateFlows directly.
- **One shared composition** — every live-core binary and the world harness call
  `snapSyncApp`/`uploadCore`; selection is a pure, tested sealed `CompositionMode` resolver; the
  wiring graph itself is smoke-tested, never unit-tested; DI is manual (decision D6).
- **Shells are wiring only** — zero conditionals in `:app:*` Kotlin (detekt-gated); Swift is a
  transcriber (forwards raw ObjC-visible inputs whole, decides nothing; pinned exceptions only).
- **Necessity claims carry forcing proofs** — "the platform forces X" cites an API contract, a
  measurement, or a vendor doc — never the current code — and names its expiry trigger.

Still true and not a law: because iOS targets are present, `commonMain` is limited to the common
stdlib + each zone's allowlisted libraries — JVM-only APIs there break the iOS compile (verify
with the proxy task above).

## Logging & errors

- Log via **Kermit** (multiplatform). Cross-cutting logging infra lives in **`:domain`'s `model/`**:
  the `Logger.invocation` enter/exit helper (params + result + duration), driving the injected
  `ports/LogScope` seam. The iOS ambient prefix global (`IosLogScope`) and the consolidated
  device-log writers (`FileLogWriter`/`PublicNSLogWriter`) live in `:adapter:ios:ext-safe`.
- **Device diagnostics** (capability `diagnostic-logging`): the app and extension are separate
  processes, each writing its **own** verbatim, un-redacted log. The **app** writes
  `Documents/debug.log` — pull it unchanged with `pymobiledevice3 apps pull app.snapsync
  Documents/debug.log`. The **extension** writes `ext-debug.log` into the **shared App Group**, so the
  app process can read it for a diagnostic dump; an App Group container is **not** pullable, so
  getting it over USB takes one extra launch:
  ```
  $P developer dvt launch app.snapsync --env SNAPSYNC_EXPORT_LOGS=1 --userspace
  uvx pymobiledevice3 apps pull app.snapsync Documents/ext-debug.log
  ```
  ⚠️ `apps pull app.snapsync.BackgroundUpload Documents/debug.log` is **dead** — the extension deletes
  that stale file on first launch of a build carrying this change, so the pull fails honestly instead
  of returning months-old content. Each log is the **canonical un-redacted channel** (os_log redacts
  `<private>`) and rolls to a `.1` sibling past 10 MB.
- **Sending the logs off-device** (capability `diagnostic-logging`): **double-tap the "SnapSync" label**
  at the top of any screen → a confirm dialog → one diagnostic dump reaches Bugsink (state + counts +
  the tail of BOTH logs, ~700 KB total, sent **verbatim** — ids intact, unlike automatic crash
  events). It is deliberately invisible: no button, no semantics, and on a build with no baked
  `SENTRY_DSN` (every dev/sideload build) **no dialog opens at all**. To exercise it on device, inject
  a DSN into the ssh-mac `xcodebuild` line. Measured against the hosted instance (2026-07-29):
  Bugsink **drops attachments entirely**, caps events at `MAX_EVENT_SIZE` = 1 MiB (a `413` the SDK
  swallows — an over-budget dump is silently lost), and stores 340 KB context strings **byte-identical**.
  Dumps group as one issue (`diagnostic dump`); read them with `/bugsink`.
  ⚠️ **Do not reach for `NSLog` when debugging — not even "just this once", not even from Swift.** An
  interpolated `NSLog("x \(y)")` is a *dynamic format string*, which os_log redacts wholesale: your line
  never appears in `idevicesyslog` and the capture looks like "the code never ran". This is written
  above; it was ignored anyway on 2026-07-16 and burned a full build/install/scan cycle to re-learn. From
  Swift, route diagnostics through Kotlin (`SnapSyncRoot`) so they land in `debug.log`. Every line carries
  a
  `[<entryPoint>]` prefix (e.g. `[onSilentPush]`, `[process]`) tracing it to what triggered it; wrap
  new platform invocations / entry points with `Logger.invocation` and, for `scope.launch` work, wrap
  *inside* the launch so the context spans the async body.
- **Crash reporting** (capability `crash-reporting`): production builds report crashes and
  `Error`/`Assert`-severity Kermit lines (lower severities ride as breadcrumbs) to the operator's
  Bugsink instance, in **both** processes, via the `CrashReporting` port both shared compositions
  start first. Every UUID-shaped token is scrubbed before send (an eventId IS the upload
  capability); the SDK's random per-install `user.id` is the one deliberate exception — do not
  "fix" it into the scrub. The DSN exists only as the `SENTRY_DSN` CI secret, baked into Release
  archives (`ios-archive`) — dev/sideload builds carry none, so the SDK never starts there. Bugsink
  ingests no dSYMs: `ios-deliver` parks each main build's dSYMs as a `dsyms-<build>` artifact for
  offline `atos` symbolication (90-day cap; park longer-lived versions' dSYMs elsewhere at promote
  time). **Triage these crashes with the `/bugsink` skill** (`.claude/skills/bugsink/`, read-only,
  non-gating dev infra): it lists unresolved issues from `steho.bugsink.com` (project 1, API
  `/api/canonical/0/`, `BUGSINK_TOKEN` via proton-env) and drills into one for the symbolicated
  stacktrace — symbolication runs **on Linux** via the `symbolic` lib against the `dsyms-<data.dist>`
  artifact (no Mac/atos needed), and fails loud when that artifact has expired.
- Errors are **reduced into state**: sealed domain errors → `UiState`, converted at capability
  boundaries — not thrown to the UI. This is also what lets the harness force any failure state.

## Testing strategy

Three standing rules:

1. **Every unit test runs on the iOS simulator too.** Put logic tests in `commonTest` so they run
   on **both** JVM and `iosSimulatorArm64` — JVM is the fast loop, not the only coverage.
   `jvmTest`/`iosTest` hold only driver/cinterop wiring behind a shared contract (e.g.
   `LedgerStoreContract` over the JVM-sqlite vs native driver).
2. **`:app:*` Kotlin is wiring-only and untested** (the shell gates enforce zero unpinned
   decisions). All logic, shared or iOS-specific, lives in the tested `:domain` zones or the
   adapter modules.
3. **Seam ↔ UI-state integration tests** compose the real core — the same `snapSyncApp`/
   `uploadCore` the device shells call — over `:test:world`'s rigged `:adapter:generic:fake` ports, drive
   the flows and commands, and assert `UiState` AND world outcomes (objects landed, ledger
   `COMPLETED`, foreign photos imported). They live in the test-only **`:test:integration`**
   module (`commonTest` → runs on JVM and simulator).

The edge-URL builder (`:domain` `model/`) is pinned by `commonMain` tests on URL composition,
filename percent-encoding (deterministic + injective), and the Content-Type-only header set — pure
string-building, no network or crypto.

## Workflow

- **All changes** go through a branch → PR → **`/ship`** (branch protection forbids direct pushes
  to `main`). Every PR carries exactly one changelog label — `enhancement` · `bug` · `internal` —
  which `/ship` applies and the required `check-label` gate enforces; the App Store release notes are
  derived from it (capability `changelog-labels`), so `internal` means "no customer sees this".
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
