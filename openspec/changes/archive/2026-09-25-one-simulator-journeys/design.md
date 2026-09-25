## Context

`ios-contracts` runs the simulator app's port contracts, then the all-real journeys. Decision D11 of
`changes/archive/2026-09-24-integration-over-control` added the journeys with a **second freshly created
simulator** as member B. It estimated the cost at +5–6 min and put the branch's 31–42 min down to a cold
Kotlin/Native compile.

The job's evidence tells a different story. Sources: the job metadata of 150 `ios.yml` runs, the
`ios-contracts-evidence` artifacts of 17 runs, and the job logs' own timestamps.

| | before the journeys (28 runs) | since (10 attempts) |
|---|---|---|
| job, median | 12.8 min | ~35 min |
| failures | 1 | 4: 1 real bug (a production-baked build, fixed by `6ff1be15`) + 3 flakes |
| xcodebuild | 3.8–6.8 min | 10–23 min (of which Gradle only 2.6–8.7) |
| first PhotoKit write (`CandidateSource`) | usually < 15 s | 126–769 s |

- **The cost is fresh-simulator first-boot work, and two cost far more than twice one.** With one simulator, the
  stretch from build end to first contract took about 6 min. With two booted after the build, it took 20–22 min.
  Since `ebda82c5` the boots overlap the build, so the same load now slows xcodebuild instead. The job got no
  faster.
- **The contracts stage is one hidden wait.** Every contract finishes in seconds except the first to write to
  the photo library. That one blocks in its first `performChangesAndWait` until the fresh library is ready, and
  logs nothing while it waits.
- **The flakes are stalls on the runner.** Two simulators, the journeys' Gradle and test JVMs (`-Xmx4g`), deno
  and the fixture all share 3 vCPUs and 7 GB. The stalls show up as:
  - a loopback `POST /events` missing the app's 5 s timeout;
  - the rig's 200 ms state polls showing gaps of up to 6 s;
  - `/device/reset` taking 10 s where it normally takes 31 ms;
  - app A going silent mid-request.

  Run `36130696610` failed twice on `02af29c1`, which differs from the passing `92ff6446` only in `openspec/`
  markdown. Nothing samples memory, so whether the stalls come from memory pressure or CPU starvation is
  inferred, not measured.

**Why B existed.** Journey 3 needs a *foreign* member:
- `DownloadController` selects only assets whose `deviceId` differs from the device's own;
- `/device/reset` keeps the Keychain identity;
- the world's foreign bytes (4 bytes labelled HEIC) cannot survive a real PhotoKit import, and world levers are
  refused on the app host anyway.

## Goals / Non-Goals

**Goals:**
- Bring `ios-contracts` back near its pre-journeys ~13 min, with no journey flake across the change's runs.
- Keep the journeys all-real where it matters:
  - the app's create, upload, download and import run against the real backend and the real photo library;
  - the foreign member is indistinguishable from a device to the backend.
- Make the job's time and failures attributable from its own evidence.

**Non-Goals:**
- Making the Gradle build cache hit in this job (it hit 1–11 of 141 tasks in every run, before and after).
  This is a separate investigation.
- A larger runner (billed per minute). Revisit only if the new resource samples show pressure with one simulator.
- Retrying transient errors inside a journey.
- Any change to production code or to the deployed backend.

## Decisions

### D1. One simulator; the journey itself plays the foreign member

The journey test joins the app's event through the backend's public HTTP surface and uploads and publishes photos
the way a device's uploader addresses them, with real JPEG bytes. To the backend this member is a device, and to
the app it is a foreign member whose photos go through the real download and import path.

Alternatives considered:
- **Switch identity on one simulator** (`simctl keychain reset` plus a reinstall between the two roles). Both
  roles stay the real app. But it rests on two unmeasured simulator behaviors: whether the App Group config
  survives a reinstall, and whether a reset identity registers as a second device. It also adds a cold launch.
- **Keep two simulators in their own job.** This brings ios-contracts back to ~12 min. But the journeys job keeps
  the load that causes the flakes, and it adds a runner and a required check per push.
- **Stagger the two boots.** This lowers the peak but keeps the total work, serially.
- **A larger runner.** This pays per minute to keep the load instead of removing it.

### D2. The member is a small client in the journeys source set, not `EdgeSetup`

`:test:contracts`' `EdgeSetup` already joins, uploads and publishes over the edge. Two things rule it out:
- It uploads a 4-byte fake JPEG with a fixed 2030 creation date. That is right for a backend contract and wrong
  for an import.
- `:test:contracts` is not on the journeys' compile path. `:test:integration` compiles against `:test:control`,
  whose rig dependencies are `implementation`, and that compile boundary is the read-model rule.

So the journeys source set gets a client of about four calls: `PUT events/<id>/devices/<id>`,
`PUT files/devices/<id>/<asset>/<role>?filename=`, `PUT events/<id>/devices/<id>/manifest`, and the union read
it already makes. It builds its payloads from `model/`'s wire types (`DeviceManifest`, `uploadKey`,
`ResourceRole`), which the client's compile path already carries. That way a wire change breaks the compile
instead of drifting silently.

The client sends no `authorization` header. The dev backend's fallback bearer then supplies both the credential
and the enrolment (`api/src/dev/serve.ts`, "FALLBACK BEARER"), exactly as it does for the simulator app, which
cannot attest.

The JPEG is a committed test resource, and it must import through PhotoKit. Its creation date lies inside the
event window. The download path does not filter by date, but a real device's photo would sit in the window.

### D3. The journey, on one app

1. **Create and join.** `/device/reset`, create an event (today −1 through +3 days), confirm the join `both`, and
   reach `Joined`. This is unchanged.
2. **Own photos land.** Seed `policy` photos and fire foreground; the admitted assets appear in the event's union.
   This is unchanged.
3. **A member's photos arrive.**
   - Decode the event id from A's `inviteUrl` with `model/`'s `decodeEventUrl`, so the link the app hands out is
     what the member uses.
   - The member joins, uploads its photos and publishes its manifest.
   - Read the whole-library census, fire foreground, and await the download to settle.
   - The census must grow by the member's count.

**Coverage dropped:** a second *app* opening the invite link through `onSceneContinueActivity`, and the
download-only join. The mocked integration surface still covers both through the rig's JVM host.

### D4. Readiness is a stage: the app's first write, on a quiet machine

The first `ios-contracts` run of this change measured the plan this decision first held, and corrected it:
- **A `simctl addmedia` warm-up does not warm the path the app waits on.** `assetsd` migrates each photo library
  from the runtime's baked schema on first boot. The main library's foreground migration finished at 15:29, long
  before the app ran. The app's first write still took 304 s, and it returned at 15:48:06.8, the instant the
  **Syndication** library's background migration finished (15:47:20–15:48:06). `addmedia` touches only the main
  library.
- **Booting during the build is a net loss even with one simulator.** xcodebuild took 13.6 min, against 3.8 when
  the simulator booted after it. Load average sat between 100 and 450, and swapping began during the framework
  link and continued through the contracts. The migrations run at utility priority, which the system defers while
  the machine is busy.

So nothing overlaps the build any more (D5), and readiness is measured where it is spent: once the app is up, the
job issues one `BULK` seed through the rig (`POST /device/gallery/seed?n=1&kind=bulk`, one tiny asset dated 2001,
outside every contract's capture window and the journey's event window) and marks `photo library: ready` when it
returns. That is the same kind of `performChangesAndWait` the first contract would block in.

### D5. Build first, then stop every daemon, then boot

Order:
1. xcodebuild.
2. `./gradlew :test:integration:journeysClasses`, on the daemon the build's Gradle left running, so the journeys'
   run at the end compiles nothing.
3. `./gradlew --stop`, and the Kotlin compile daemon killed, because each holds gigabytes the simulator would swap
   against.
4. Boot the simulator, install, grant, launch, then the readiness stage.

The first version compiled the journeys in the background while the simulator settled. That kept a second
daemon's memory alive during the most memory-sensitive stretch of the run.

### D6. Evidence that answers the next failure

The job keeps the following with its evidence:
- a sampler writing load average, `memory_pressure` and swap figures every 10 s to `resources.log`;
- `~/Library/Logs/DiagnosticReports` and the simulator's crash logs, copied at the end;
- on a journey failure, the failing assertion's message, printed from the JUnit XML. Today the job prints only
  Gradle's summary;
- a request log in `dev:local` (method, path, status, duration), so a timeout can be placed on one side of the
  wire.

The request log lives in `api/src/dev/serve.ts`, the dev-only entry point. The deployed app is untouched.

### D7. No retry for transient errors

A journey failure is read first as a missing contract clause. A retry would absorb exactly the slowness this
change sets out to remove, and it would hide a repeat. If flakes remain, the new evidence decides the next step.

### D8. The rig client really has no request timeout

A re-run of the D4/D5 shape failed in journey 2: `/device/gallery/seed?n=4&kind=policy` raised
`HttpRequestTimeoutException` after 15 s, while the app was still seeding four 2048×1536 images on a freshly
booted simulator. `RigClient` documents that it has no request timeout, and it installs `HttpTimeout` unset to
say so. But Ktor's CIO engine carries its own `requestTimeout`, 15 000 ms by default (read from
`CIOEngineConfig` in Ktor 3.2.0), which the plugin left unset does not lift. The client now sets
`engine { requestTimeout = 0 }`, so every bound is the caller's, as its comment always claimed. This had been
latent since the client was written: every rig verb slower than 15 s would have failed the same way.

## Measured

`ios-contracts` on this change's final shape (D4, D5 and D8), stage timestamps from `build/sim-contracts/stages`:

| run | job | xcodebuild | boot | install + grant | launch | photo library ready | contracts | journeys |
|---|---|---|---|---|---|---|---|---|
| 36160981548 #1 | 20.5 min | 4:31 | 2:16 | 2:16 | 0:58 | 7:41 | 0:14 | 0:40 |
| 36160981548 #2 | 19.9 min | 5:20 | 1:59 | 1:24 | 1:37 | 5:33 | 0:12 | 1:30 |
| 36160981548 #3 | 13.3 min | 4:26 | 2:19 | 1:39 | 0:16 | 0:02 | 0:14 | 2:32 |

All three passed. Against the baseline in Context, the median fell from ~35 to ~20 min. There was no flake in the
final shape; the one failure on the way was D8.

The earlier shapes, for the record:
- First shape (boot overlapped the build, `addmedia` warm-up, background journey compile): 23.1 min, green.
  xcodebuild 13.6 min; `CandidateSource` still waited 304 s.
- D4/D5 shape without D8: 19.3 min green, then one journey failure (D8).

What remains is almost entirely the fresh simulator's first boot:
- Boot, install and grant, launch and readiness together took 4:53 to 13:11. Readiness alone varies from 2 s to
  7:41, depending on when `assetsd`'s background migrations run.
- Load average reached 400–490 during the boot, on 3 vCPUs.
- The contracts take ~13 s once the library is ready.

The next lever is a simulator that has already booted and migrated, restored from the Actions cache. It is being
measured separately; it is not part of this change.

## Delta accounting

- `test/integration` (the journeys, `Member`, their resource and build script): `testing-architecture`, delta.
- `test/control` (`RigClient`): `testing-architecture`, no delta. D8 makes the client do what its contract already
  said (no request timeout).
- `scripts/sim-contracts`, `.github/workflows/ios.yml`: `ios-ci`, delta.
- `api/src/dev/serve.ts` (the request log): `ios-ci`, the only spec that names the dev server, through its evidence
  clause. Delta.
- `CLAUDE.md`, `.claude/skills/ios-simulator`: documentation, no capability.

## Risks / Trade-offs

- [The member's calls diverge from what the app's uploader really sends] → They are built from `model/`'s wire
  types, so a changed shape breaks the compile. And the backend accepts them only if they match its public
  contract, which the backend contracts already pin.
- [The dropped invite-link-open and download-only coverage regresses unseen] → Both stay covered on the mocked
  integration surface. The link itself is still exercised, because its decoded id is what the member joins.
- [The platform's first-write wait stays long even on a quiet machine] → It is now its own stage, so it is visible
  and attributable. If it dominates, the next lever is starting it earlier, for example a boot before the build on a
  runner with memory to spare.
- [One simulator is still flaky on the standard runner] → The pre-journeys record was 1 flake in 28 runs.
  `resources.log` and the crash reports now say which resource ran out, and that decides whether a larger
  runner is worth paying for.
- [Booting after the build adds the boot to the serial path] → About 3 min on a quiet machine, against the build's
  measured +10 min when they overlap.

## Migration Plan

This changes test infrastructure only: a revert of the change's PR restores the two-simulator job. The
`ios-contracts` status context is unchanged, so the branch ruleset needs nothing.

## Open Questions

- Whether a cached, already-booted simulator removes the first-boot cost. It is being measured outside this change.
