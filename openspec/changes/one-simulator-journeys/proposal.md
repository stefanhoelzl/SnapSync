## Why

Since the all-real journeys joined `ios-contracts` (`changes/archive/2026-09-24-integration-over-control`, D11), the
job's median went from 12.8 to about 35 minutes. In the same period, 3 of 8 runs failed on flakes, against 1 in 28
before. The cause was measured across 17 runs' evidence. The second freshly created simulator the journeys added
roughly triples its setup cost on the 3-vCPU / 7 GB `macos-26` runner:
- the xcodebuild went from 3.8 to 10–23 min;
- the first PhotoKit write waits up to 13 min;
- the journeys run while the machine is still stalling, so an app misses its 5 s HTTP timeout or stops answering
  its rig port. One commit failed twice although it differed from a passing one only in `openspec/` markdown.

The second simulator exists only to be a *foreign member*: downloads take only assets whose `deviceId` differs from
this device's, and a device reset keeps the identity. The same member can be played from the journey test itself,
over the real backend's public HTTP surface, with real JPEG bytes. That removes the second simulator.

## What Changes

- The journeys run against **one** simulator app. The journey test itself plays the second member: it joins the
  app's event over the local backend's public HTTP surface, then uploads and publishes real JPEG bytes.
- The journeys are reshaped around that single app:
  1. The app creates an event and joins it `both`.
  2. Its own photos land in the event's union.
  3. The foreign member joins with the event id taken from the app's invite link, and uploads and publishes
     photos.
  4. The app downloads them and imports them into its real photo library.

  **Coverage dropped**: a second *app* opening the invite link, and the download-only join. Both remain covered
  by the mocked integration surface.
- `ios-contracts` creates, boots, installs, grants and launches **one** simulator, not two.
- A **photo-library warm-up** starts right after boot. An explicit, timestamped *photo library ready* stage then
  runs before the first contract, so the platform's first-use wait is no longer hidden inside a contract's timing.
- The journeys' Gradle compile moves off the serial path: it runs in the background after xcodebuild, and the
  journeys run reuses its daemon.
- The job keeps more evidence:
  - memory/CPU samples throughout the run;
  - host and simulator crash reports;
  - the journey's failure message, printed on failure;
  - a request log from the local backend.
- No retry is added for a journey's transient errors. A journey failure stays a signal.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `testing-architecture`: "All-real journeys are the contracts' safety net". The journeys run one simulator app;
  the second member is played over the backend's public surface with real bytes. The receiving side becomes the
  app itself.
- `ios-ci`: "Run the in-app port contracts on a simulator on every push". The job changes as follows:
  - one simulator, not two;
  - a photo-library readiness stage before the contracts;
  - the journeys run against the one app;
  - the added evidence (resource samples, crash reports, the backend's request log, the journey's failure message).

## Impact

- `scripts/sim-contracts`: one simulator, the warm-up and readiness stage, the background journey compile,
  resource sampling, and evidence collection.
- `test/integration` (`journeys` source set and its Gradle task):
  - the journey reshaped;
  - a small foreign-member client over the backend's public HTTP surface, using `model/` wire types, with a real
    JPEG resource;
  - the `appB` property removed.
- `api/src/dev/`: a request log for `dev:local` (dev infrastructure; the deployed backend is untouched).
- `.github/workflows/ios.yml`: the `ios-contracts` job comment. The job's steps are unchanged, and the status
  context stays `ios-contracts`.
- The `ios-simulator` / `rig-channel` skills and `CLAUDE.md`'s `:test:integration` entry, wherever they describe
  the journeys as two simulators.
- No production code changes, and no new required check.
