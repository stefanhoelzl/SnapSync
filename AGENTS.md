# AGENTS.md

SnapSync — an iOS and Android app for **sharing photos from an event** (Kotlin Multiplatform + Compose), shipped
via TestFlight and Google Play. Join an event by scanning its QR, and your photos taken since a per-device
**capture-date cutoff** are shared to it while everyone else's arrive in your library. The JVM desktop app is test
equipment, not a product.

This file loads into every session, so it holds only what a session would do damage or waste a cycle without.
Everything else is on demand: skills (below), nested `AGENTS.md` files (`app/ios/`, `adapter/ios/`, `iosApp/`) and
`docs/`. What belongs where: `docs/agents.md`.

**Mission** (full form + decision record: `openspec/config.yaml` context /
`changes/archive/2026-07-21-align-specs-with-mission`): **family and friends** — small groups who know each other —
easily share the photos they take during a **short-lived event** (an afternoon to a few weeks), synced
gallery-to-gallery; you never care how photos arrive, you just look at your own gallery. No accounts and no name
asked; creating or joining takes seconds. Marketing copy follows `metadata/messaging.md`. An event's date range
(at most 30 days) bounds which photos may be uploaded and closes nothing; joining, sharing and receiving keep working
until the nightly sweep deletes the photos (`openspec/specs/`, `changes/back-dated-events`). Named future (don't
build for it, don't deepen assumptions against it unnamed): concurrent multi-event membership — single active
membership is the *current* contract.

> It began as a *personal one-way photo backup*. Defaults inherited from that era are dangerous here: what was
> "back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's event". A membership's
> cutoff is therefore **required**, never absent.

What a member contributes is decided by **one** policy at **one** place: capability `photo-sharing`, the domain's
`SelectionPolicy`, derived, read and admitted only by the data layer's `OwnPhotos` — and that one answer gates the
byte upload, the device manifest, the status total, the join preview and the album gather. Limited (partial) photo
access is a first-class grant whose selection decides; its measured platform traps are in `adapter/ios/AGENTS.md`.

Stack: Kotlin · Compose MP · JDK 25 · min iOS 18.0 · Orbit MVI · SQLDelight · Ktor (`gradle/libs.versions.toml` is
the source of truth for versions).

## Repo layout

```
api/            the Deno backend (bunny Edge Scripting) - explained in docs/ (architecture · testing · deployment)
site/           the Astro landing page
iosApp/         the Xcode project (app + upload-extension targets) - NOT a Gradle project
domain/ adapter/ ui/ app/ test/   the Gradle modules - graph in architecture/modules.md, groups + laws in docs/architecture.md §1-2
openspec/       specs/ (contract of record: user-observable outcomes) + changes/archive/ (decision records)
docs/           architecture · testing · deployment · patterns · agents - engineering explanation, NOT contract
architecture/   GENERATED diagrams - `./gradlew architectureDiagrams` and commit; stale blocks the PR
metadata/       the listing copy both stores render from, App Review notes, Play declarations + art
screenshots/    the committed raw captures: ios/ and android/
scripts/        build and dev tooling
.ship/          this repo's half of the global `/ship` skill (docs/agents.md)
build-logic/    the included build holding the convention plugins
tools/ config/ gradle/            more build tooling
```

**There is no `backend/`** - it split into `api/` + `site/`. Capability names are whatever `ls openspec/specs/`
prints; never guess a spec path. Where a class lives: grep.

## Runbooks (load the skill before you start)

- **Touching a physical iPhone** (the SE2, or the XS on iOS 18) - install, launch, screenshot, device logs
  -> load **`snapsync-device`**. It first has you load the machine-global `device` skill, which owns the
  **per-phone locks** (`SE2`, `XS`, `A40` — each shared by every project on this machine; its guard hook
  refuses a device command that does not name its phone, or whose phone this workspace does not hold).
  It stops at the running app: to *drive* one, see `rig-channel` below.
- **Seeing or clicking the app's UI without a device** -> load **`ui-harness`**. 🚫 **Never**
  `java.awt.Robot`, and never capture the real screen `:0` - it raises a portal consent prompt and
  **blocks until someone answers**.
- **Anything needing a Mac** - an Xcode build, an `.xcarchive`, an IPA, code signing, or the
  `iosPlatformTest` -> load **`ssh-mac-build`**. First check whether the question is what an Apple API
  *declares*: that is readable from Linux (`adapter/ios/AGENTS.md`, "Reading the Apple SDK from Linux").
- **Testing a backend change against a real device** -> load **`local-backend`** first; it owns the
  three-hop chain and the one step whose omission is silent.
- **Driving the app on device** — joining, creating, leaving, resetting, seeding, wiping, reading live state —
  over the build-time-only control channel (`-Psnapsync.rig=true`, the `rig` CLI) -> load **`rig-channel`**. It
  needs the same phone lock as `snapsync-device`. There are no `SNAPSYNC_*` launch triggers any more, and a guard
  fails the build if one returns.
- **Running the app on a SIMULATOR** — two members of one event at once, a headlessly seeded/wiped
  photo library, headless permission state -> load **`ios-simulator`**. No phone lock.
- **Running the ANDROID app** — on an emulator on this Linux box (headless, no lock) or on the A40 (under its
  lock) -> load **`snapsync-android`**. Every `adb` names its device (`-s`).
- **Apple portal chores** - certificates, device UDIDs, provisioning profiles, bundle-id
  capabilities, App Store / TestFlight text metadata -> load **`asc-portal`**.
- **Crash reports** -> load **`bugsink`**. **Releases, promotes and screenshots** -> `docs/deployment.md` §5–6.

## Traps no skill gates

- 🚫 **Never join an event you did not create.** A `direction=download` join imports that event's
  photos into this device's library and registers this device on its backend membership. Log-scraped
  ids are someone's real event.
- ⚠️ **There is deliberately NO whole-zone storage reset.** The single `snap-sync-dev` zone is the
  *only* zone (`api/src/config.ts` - the deployed backend uses it too), so it is shared with real
  TestFlight / App-Store users' photos; a blind wipe would destroy them. Clean up **targeted only** -
  a fresh event id, a leave, or deleting that event's/device's objects via bunny. Never
  re-introduce a `reset-storage`-style whole-zone delete.
- ⚠️ **Never push a `vX.Y` tag by hand.** A tag that already exists makes that version permanently
  unreleasable (`docs/deployment.md` §6).

## Read first

- **`openspec/specs/<capability>/spec.md`** — the **contract of record** for what a **user can observe**. What,
  never how. The rule and the swap test are `WHAT EARNS A SPEC` in `openspec/config.yaml`.
- **`openspec/changes/archive/<id>/`** — the **decision record**: `proposal.md` (what & why) and `design.md`
  (rationale, rejected alternatives, trade-offs).
- **`docs/architecture.md` · `docs/testing.md` · `docs/deployment.md` · `docs/patterns.md`** — engineering
  explanation for the app and the api. Not contract: the gates in `./gradlew build` are the authority.

Changing an observable outcome: read its spec, then its decision record. Changing anything else: read the relevant
`docs/` file and the code. Before moving code or changing a boundary, read `docs/architecture.md`; before adding a
test file, `docs/testing.md`.

## Agent harness limits

- ⏱️ **The Bash tool's `timeout` is capped at 600 000 ms (10 min) and larger values are clamped SILENTLY** — you
  get `Command timed out after 10m 0s`, never a warning. Raising the number is never the fix. Sum inner timeouts
  to stay under it, or use `run_in_background: true`, which has no cap: the harness re-invokes you when it exits.
  Have such a command print one greppable result line as its last act.
- 🏃 **`ch bg <cmd…>`** marks a long-lived process that is *not* the work (dev servers, watchers, `tail -f`) so
  CodeHydra does not count it as keeping the workspace busy. Do not wrap work the workspace is genuinely doing.
- **Don't prefix commands with `cd <workspace root>`**: every Bash call starts there, and shell state does not
  persist between calls. `cd` into a **sub**directory is still correct: `cd api && deno task dev:local`.

History behind these: `docs/agents.md` §4.

## Build & test

- `./gradlew build` — the canonical check (compiles all targets, runs the JVM tests and every gate). No display
  needed. **`deno` on PATH is required**: the backend contract runs against the real `api/`, and without Deno those
  tests fail naming it.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source sets. A compile, not
  a test.
- Formatting findings are fixed by the tool: `./gradlew <tier task> -Psnapsync.detektAutoCorrect`.
- **Every number in a `config/detekt/<tier>.yml` is a ceiling that may only fall.** Lowering one is ordinary work;
  raising one needs a stated forcing proof in the PR. Never fold `detektAppShell` into a tier
  (`docs/architecture.md` §5).
- Platform tests, CI gates and where each test runs: `docs/testing.md` §1, `docs/deployment.md` §4.

## Code rules no gate states for you

- `commonMain` is limited to the common stdlib + each zone's allowlisted libraries; JVM-only APIs there break the
  iOS compile (check with the proxy task).
- **Never write a word a person reads as a string literal** in `:ui:*`: use the module's
  `composeResources/values/strings.xml` and `stringResource`. Dates go through `DateFormats`. OS-shown text
  regenerates with `./gradlew nativeStrings` (`docs/architecture.md`, "Localization").
- **New code logs through `Log`** (`docs/patterns.md` §6), and work an entry point does not await is a `detached`
  step, never a `scope.launch`, so its lines keep their trigger label. Errors are **reduced into state**: sealed
  domain errors → `UiState`, never thrown to the UI.
- Device logs and the diagnostic dump: `snapsync-device`, `bugsink`, `docs/deployment.md` §5.

## Workflow

- **All changes** go branch → PR → **`/ship`** (direct pushes to `main` are forbidden). Every PR carries exactly
  one changelog label — `enhancement` · `bug` · `internal` — and `internal` means "no customer sees this"
  (`docs/deployment.md` §7). Every merge to `main` ships to internal TestFlight and Play's internal track.
- **Every backwards-compatibility or legacy shim gets an entry in `PENDING_CLEANUPS.md` in the same PR** — code that
  tolerates an older app build, stored or on-device format, api, or an OS transfer left by a previous build: what,
  where (file anchors), why, its unlock condition, and the removal steps. The PR that removes the shim deletes its
  entry. Raising `MIN_APP_VERSION` starts by reading its section. A shim that reads what an older build left **on the
  device** also gets, in the same PR, the layer that writes that state (`older-install.installedAt`,
  `test/rig/.../OlderInstalls.kt`), so `AppUpdateIntegrationTest` updates through it; its entry names both. A new
  database migration needs no test of its own.
- **The OpenSpec flow is user-driven — never entered on the agent's initiative.** Changes that **add, alter, or
  remove a user-observable outcome** go through it (propose → apply → sync/archive), but *entering* the flow is the
  user's call, and so is **every phase boundary** inside it. On a behaviour-touching request: name the affected
  capability, say it needs a change, and **stop** — no code, no `openspec/` file — until the user picks the route
  (OpenSpec proposal or direct change). Then run **only the phase asked for**. Never invoke an `opsx` skill or the
  `openspec` CLI unbidden. **Reading is the opposite, and is mandatory**: read the capability's spec and its decision
  record before changing anything.
- Work no user could observe — build/CI, dependency bumps, refactors, a new mechanism, a platform workaround,
  docs — touches no spec and skips OpenSpec (the swap test in `openspec/config.yaml`). Classify honestly and **ask
  when unsure**.
- **The `openspec` CLI is not installed**: run `npx --yes @fission-ai/openspec@1.13.2 <cmd>`, never a bare
  `openspec …`. **Never hand-edit `.claude/opsx` or the `openspec-*` skills** — they are generated, and
  `openspec update` silently deletes edits. A rule an archive must enforce goes in `openspec/config.yaml`'s
  `context:` (`docs/agents.md` §3).
