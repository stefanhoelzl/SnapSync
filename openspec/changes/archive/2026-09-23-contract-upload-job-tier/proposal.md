## Why

The OS-driven upload tier — the PhotoKit upload-job queue behind `BackgroundTransfer` and the extension
registration behind `UploadExtensionRegistry` — is the last photo surface no port contract holds. Phase 6
(`changes/archive/2026-09-23-photokit-contracts`, D10) carved it out because it runs in a process no `Host`
names: the upload extension, which only the operating system launches. Its create, retry and acknowledge
paths are verified only by manual device runs, the doubles every upload test stands on
(`FakeBackgroundTransfer`, `SimulatorUploadJobQueue`, `SimulatorExtensionRecord`) are licensed by nothing,
and two of those paths have shipped bugs before (the nil `destination`/`resource` fields,
`PhotoKitJobMapping.kt`).

A device probe on 2026-09-23 (SE2, iOS 26.6) settled what had blocked this: the extension registers against a
loopback upload base, assetsd delivers each job's bytes to a receiver inside the app within 0.1–5 s, every job
state the adapter handles is reachable inside one `process()` call, and that call has a ~60 s budget. So the
contract can run where production runs it — inside the extension — without waiting on the OS between clauses.

## What Changes

- **A new host, `IOS_DEVICE_PHOTOKIT_EXT`**: the upload extension process on a device. It is a recorded host:
  the contract runs inside the extension's `process()`, records every operating-system call through a new
  internal seam in `IosPhotoKitUploadPlatform`, and every CI build replays the recording on
  `IOS_SIM_KEXE`. The job clauses are recorded **only** here — the app process can call the job API (measured),
  but production never does, so a recording there would be evidence about a context that does not ship.
- **How a run reaches the extension**: the rig writes a run request into the App Group and re-registers the
  extension, which makes the OS invoke it; a rig build's extension runs the requested contract instead of the
  upload cycle and writes the recording back to the App Group, which the rig's existing contract verb returns
  verbatim. The run returns well inside the budget, because a killed call is followed by a 6–11 minute backoff.
- **An upload receiver inside the app**: jobs upload to a loopback route the rig answers in 8b's fixture
  grammar, with the status the clause chose; it writes what landed into the App Group for the extension to read. The binding stays `Live`: the HTTP answer is a stimulus that puts the
  OS queue into a state, not an obligation of the port.
- **One `process()` call runs the whole contract.** Every job state settles within seconds, so 8b's clauses —
  which create their jobs in the clause body and poll until the outcome is recorded — run as written; the fixture
  reads they poll are recorded alongside the job calls, so a replay stops each poll where the device did.
- **PhotoKit bindings on 8b's `BackgroundTransferContract`** (phase 8b owns the contract and its
  tier-neutral clauses): one new state, `SINGLE_FREE_RETRY`, carrying the PhotoKit-only clauses (offered for
  retry, re-pointed, retry spent handed up, every presented job acknowledged); the extension-host binding; and a
  `Fake` binding for `SimulatorUploadJobQueue`.
- **`UploadExtensionRegistry` contracted** on `IOS_DEVICE_APP` (production calls it from the app) through a
  two-call seam in `PhotoKitExtensionRegistry`, recorded under **both** a full and a partial grant; the
  refusal under a partial grant (`3311`) becomes a recorded clause. `SimulatorExtensionRecord` gets a `Fake`
  binding.
- **Recordings keyed by grant**: `<Contract>@<HOST>[.<grant>].rec`, so one host can carry one recording per
  photo grant it was run under.
- **Findings carried as clause findings**, each landing as a failing clause followed by its fix:
  `IosPhotoKitUploadPlatform`'s `URLWithString(...) ?: FAILED` guards catch almost nothing (an empty string
  is not nil since iOS 17 — found by 8b); `resource` was nil on every fetched job in the probe, which would
  leave the retry-spent re-create path dead; and `onTerminate` reads `notifyTermination` as a kill although it
  arrives only after a normal return.
- **`PhotoKitSmokeTest`'s last test retires** — the upload-job fetch it keeps is covered by the contract.
- Everything else under a partial grant — including `PhotoSelectionChangeSource` — stays where phase 6 placed
  it: `limited-photo-access`'s measured record and fake-backed tests. `GalleryStatusSource` stays
  uncontracted (phase 6 D1).

Nothing a user sees changes. No shipped binary gains any of this: every addition is under
`-Psnapsync.rig=true`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `port-contracts`: the `IOS_DEVICE_PHOTOKIT_EXT` host and its matrix row; recordings keyed by grant; the
  device run reaching the extension through the App Group rather than only the rig channel; a service stood up
  to answer a clause's setup as a stimulus, which keeps a binding `Live`.
- `module-architecture`: `:test:contracts` linked into the upload extension under `-Psnapsync.rig`, and the
  containment shape that lets a rig build's extension route `process()` to a contract run without the shell
  carrying a seam.
- `architecture-guards`: the contract-coverage gate reads grant-keyed recordings and the new host.
- `testing-architecture`: the upload-job subsystem is contracted rather than smoke-tested; the smoke test's
  last test retires.
- `ios-photokit-upload`: a loopback upload base is admitted without an ATS exception (it already is for the
  local deployment; now measured on the PhotoKit tier), and the measured facts about how the OS invokes the
  extension — budget, kill without notice, re-invocation cadence, triggers — enter the capability's record.

## Impact

- **Code**: `:adapter:ios:ext-safe` (seam in `IosPhotoKitUploadPlatform`, its rig source set: binding,
  recorder, extension runner; the guard fix), `:adapter:ios:app-only` (seam in `PhotoKitExtensionRegistry`,
  rig bindings), `:app:ios:extension` (rig-gated entry source directory), `:test:contracts` (states, host,
  grant-keyed recording names), `:test:rig` (the run request, the receiver, the verb's extension selector),
  `:domain:compose` (`onTerminate`'s log line), `:test:architecture` (coverage gate),
  `test/contracts/recordings/` (three new recordings).
- **Depends on phase 8b** (`transfer-contracts`): its `BackgroundTransferContract` must be merged first; this
  change adds to that file.
- **Device**: one SE2 session to record, with a full grant, no membership, and a person to switch the grant
  in Settings for the registry's partial-grant recording.
- **CI**: replays only; no new job.
