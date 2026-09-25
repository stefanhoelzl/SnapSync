## 1. The foreign member (D2)

- [x] 1.1 Commit a small real JPEG as a `journeys` test resource: a valid baseline JPEG that PhotoKit imports, with its creation date set by the journey
- [x] 1.2 Add the member client to the `journeys` source set: join (`PUT events/<id>/devices/<id>`), upload (`PUT files/devices/<id>/<asset>/<role>?filename=`) and publish manifest (`PUT events/<id>/devices/<id>/manifest`). Build payloads from `model/`'s `DeviceManifest`, `uploadKey` and `ResourceRole`; send no `authorization` header. Every step fails naming itself on a non-2xx
- [x] 1.3 Confirm against a local `deno task dev:local` that a header-less member can join, upload and publish, and that its asset appears in `GET events/<id>/files`

## 2. The journey on one app (D3)

- [x] 2.1 Reshape `Journeys.kt`: drop `appB`; journeys 1 and 2 stay unchanged; journey 3 decodes the event id from A's `inviteUrl` with `decodeEventUrl`, has the member join, upload and publish N real JPEGs dated inside the event window, fires A's foreground, and awaits the download to settle with the whole-library census grown by N
- [x] 2.2 Remove `appB` from the `journeys` task's properties and rewrite `test/integration/build.gradle.kts`'s journeys comment (one simulator, the member played over the public surface)
- [x] 2.3 Keep the missing-address failure: the task still fails naming `appA` or `backend` when either is absent

## 3. One simulator, readiness, and the early compile (D4, D5)

- [x] 3.1 `scripts/sim-contracts`: create, boot, install, grant and launch ONE simulator; drop `DEVICE_B`, `RIG_PORT_B`, `DATA_B` and the second advertisement check
- [x] 3.2 Commit a warm-up JPEG with an EXIF capture date decades in the past. Right after `bootstatus`, run `xcrun simctl addmedia` with it in the background, and add a `photo library: ready` stage that awaits it before the first contract
- [x] 3.3 After `xcodebuild: done`, start `./gradlew :test:integration:journeysClasses` in the background with a daemon (no `--no-daemon`), with its own stage marks. Await it before the journeys, run the journeys on the warm daemon, and `./gradlew --stop` in `cleanup`
- [x] 3.4 Update the script's header comment to describe the new flow and stages

## 4. Evidence (D6)

- [x] 4.1 Start a resource sampler at script start: load average, `memory_pressure`, and swap every 10 s into `$OUT/resources.log`, killed in `cleanup`
- [x] 4.2 In `cleanup`, copy `~/Library/Logs/DiagnosticReports` entries from this run, and the simulator's crash logs, into `$OUT/crash/`
- [x] 4.3 On a journey failure, print each failing test's name and assertion message from the JUnit XML in the job log, alongside Gradle's summary
- [x] 4.4 In `api/src/dev/serve.ts`, log one line per request served (method, path, status, duration ms) to the backend's output; leave the deployed app untouched and keep `deno lint`/`deno check` green

## 5. Docs and skills

- [x] 5.1 `.github/workflows/ios.yml`: update the `ios-contracts` job comment to one simulator plus the member played over the public surface
- [x] 5.2 `CLAUDE.md` (`:test:integration`'s journeys line) and `.claude/skills/ios-simulator/SKILL.md`: remove any claim that the journeys need two simulator apps; the skill's two-member instructions for manual work stay

## 6. Verify

- [x] 6.1 `./gradlew :test:integration:compileJourneysKotlin` and `./gradlew build` pass locally
- [ ] 6.2 Push and read the PR's `ios-contracts` runs: all green, and stage timestamps recorded. At least three runs, re-running the job if needed
- [ ] 6.3 Record the measured stages in design.md under a "Measured" heading: the build, the readiness wait, `CandidateSource`'s time, the journeys' Gradle time, the job total, and the `resources.log` peak. Settle D4's open question (boot during the build or after) from them, and move the boot if the numbers say so
