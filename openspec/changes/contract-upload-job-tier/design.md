## Context

Phase 6b of the testing-concept sequence. Phase 3 (`changes/archive/2026-09-22-establish-port-contracts`) built
the contract mechanism and proved record/replay on `SecureStore`. Phase 6
(`changes/archive/2026-09-23-photokit-contracts`) contracted the photo library and carved out the upload-job
tier (D10): the OS drives it inside the upload extension, a process no `Host` names, and a simulator
substitutes the subsystem outright because creating a job there terminates the process.

Current state, verified against the tree:

- **Real coverage of the tier.** `PhotoKitJobMappingTest` covers every per-job decision the adapter makes
  (`PhotoKitJobMapping.kt`); `PlatformVocabularyPinTest` pins the five job states from the klib;
  `PhotoKitSmokeTest` keeps one test that fetches both job sets without a grant and asserts they come back.
  What the adapter asks the OS — create, fetch, retry, acknowledge — is verified by nothing but device runs.
- **The doubles.** `FakeBackgroundTransfer` (`:test:world`, levered), `SimulatorUploadJobQueue` (the
  simulator target's `uploadJobQueue()` actual, levered) and `SimulatorExtensionRecord` (the simulator
  target's `uploadExtensionRegistry()` actual, levered). None is licensed by a contract.
- **Where production calls what.** `IosPhotoKitUploadPlatform` (`:adapter:ios:ext-safe`) is called only from
  the extension root. `PhotoKitExtensionRegistry` (`:adapter:ios:app-only`) is called only from the app.
- **Phase 8b owns `BackgroundTransferContract`** (tier-neutral clauses, `TransferUnderTest.ledger` as the
  observation handle, the URLSession bindings). Agreed with 8b: this change adds the PhotoKit states, clauses
  and bindings to that file, rebased on it.

### The device probe (SE2, iOS 26.6, 2026-09-23)

A throwaway rig build (never committed) measured what this design rests on:

| question | answer |
|---|---|
| registration against `http://127.0.0.1:18099/api/v2` | succeeds; disable and enable both return `true`, the read-back is `true` |
| job API from the app process | create, fetch (both actions), retry and acknowledge all succeed, and assetsd uploads app-created jobs (production calls it only from the extension, which is why it is recorded there — D1) |
| delivery to a loopback receiver in the app | assetsd PUTs the full body 0.1–5 s after creation, plaintext, user agent `assetsd … CFNetwork` |
| a 200 answer | job `Succeeded` (4), in the acknowledge set only |
| a 403 or 500 answer | job `Failed` (3), in **both** the retry and acknowledge sets, `error` nil; no automatic re-send |
| retry to a new destination | a fresh PUT to it; 200 → `Succeeded` |
| a second failure | `Failed`, acknowledge set only (retry spent) |
| acknowledging a failed-once job | removes it from the retry set too |
| `resource` on fetched jobs | nil for every job, including a retry-spent one whose photo is still in the library |
| `process()` budget | ~60 s (heartbeat reached +59 s in three calls), then killed **without** `notifyTermination` |
| `notifyTermination` | arrives ~55 ms after every **normal** return |
| after `PROCESSING` | the next call came exactly 5 min 0 s later |
| after a killed call | backoff: next call ~6 min, then ~11 min; neither a new photo nor a re-registration triggers a call meanwhile |
| triggers outside backoff | re-registration → call in ~1 s; a new photo → call in ~3 s; a job finishing → no call |

## Goals / Non-Goals

**Goals:**

- The PhotoKit upload-job adapter is held to clauses recorded **inside the process production runs it in**,
  and replayed against the current adapter on every build.
- The upload doubles are licensed: `SimulatorUploadJobQueue` and `SimulatorExtensionRecord` as `Fake`
  bindings of the contracts whose real implementations are recorded.
- `UploadExtensionRegistry` is recorded under both grants, so the partial-grant refusal is evidence, not prose.
- The OS's handling around `process()` — which no clause can observe, because a clause runs only inside a
  call — is recorded in `ios-photokit-upload` with this probe as its evidence.

**Non-Goals:**

- The tier-neutral `BackgroundTransfer` clauses and the URLSession bindings (phase 8b).
- Anything else under a partial grant, including `PhotoSelectionChangeSource`: phase 6 D10's placement stands.
- `GalleryStatusSource` (phase 6 D1: its real implementation is feature code over the contracted
  `CandidateSource`).
- The OS's acknowledgement obligation (error 50008): the OS reports it only in the system log, which no
  process can read, so it stays in the adapter's documentation.
- Recording the job API from the app process.

## Decisions

### D1. A new host, `IOS_DEVICE_PHOTOKIT_EXT`, and the job clauses are recorded only there

Host identity is platform × process kind × entitlements. The upload extension is a different process kind
from the app: the OS launches it, it has a ~60 s budget, and only it receives `process()`. So it is a distinct
host, named for the extension type so a future extension kind (share, notification) gets its own value
rather than a shared "extension" one.

The probe showed the app process can call the whole job API. The job clauses are nevertheless recorded only
on the extension host, because production never calls that API from the app: a recording there would be
evidence about a context that does not ship, and could pass where the extension differs.

*Alternatives:* recording on `IOS_DEVICE_APP` only — reliable to reach, but the wrong process. Both hosts —
rejected for the same reason; the app recording adds nothing a reader could trust about production.
A generic `IOS_DEVICE_EXT` — rejected: a second extension kind would share a host it does not share
reachable states with.

### D2. A run reaches the extension through the App Group and a re-registration

The rig cannot reach the extension, and the extension cannot host a server (it lives ~60 s and only while the
OS allows). So the run is requested and answered through the shared App Group:

1. `POST /contract/<name>?host=IOS_DEVICE_PHOTOKIT_EXT` on the app's rig checks the preconditions (D6), writes a
   run-request file naming the contract into the App Group, and re-registers the extension (disable →
   enable), which empties the job queue and makes the OS invoke the extension (~1 s, measured).
2. In a rig build, the extension's entry sees the request, runs the named contract **instead of** the upload
   cycle, writes the recording and the live outcome table to a result file, deletes the request, and returns
   `COMPLETED` (not `PROCESSING`, which would schedule another call in 5 min).
3. The verb waits a bounded time for the result file and answers it verbatim — the same body an in-app device
   run answers. A timeout answers a distinct status and never a partial recording.

A new photo also triggers a call, but only when the OS is not backing off; re-registration is the more
direct trigger and the queue-emptying the preconditions need anyway.

*Alternatives:* a Ktor server inside the extension — the extension is not running when the operator calls,
and would die mid-request at 60 s. Returning `PROCESSING` to chain runs — 5 min per call, measured.

### D3. How the rig build's extension routes `process()` without a seam in the shell

The shell (`UploadExtensionRoot`) is wiring-only and gated at zero decisions; production source may gain no
declaration naming a contained module. Today its inbound port comes from a private top-level
`extensionRootEntries()` in the shell file.

That function moves to its **own source directory**, and the extension's build script selects one of two
directories by `-Psnapsync.rig`: the production one, whose `extensionRootEntries()` returns the core's
entries exactly as today, or a rig-contributed one, whose `extensionRootEntries()` wraps them in a
`ContractRunningEntries` decorator from `:adapter:ios:ext-safe`'s rig source set. The decorator holds the
run-request branch; the contributed file holds none, so the shell gate's zero-decision rule still holds over
it. Without the property, the rig directory, the decorator and `:test:contracts` are absent from the binary.

This is a new shape of containment — a **substituted** file rather than an added one — because the rig must
replace a call path rather than add a call site. `module-architecture`'s containment requirement gains it.

*Alternatives:* an eager initializer like the app's rig hook — it can add behaviour at load, but cannot
redirect `process()`. A mutable hook in the shell that the rig assigns — a permanently compiled seam, which
the containment law forbids.

### D4. One call; 8b's clauses as written, plus one PhotoKit-only state

8b's `BackgroundTransferContract` (merged first) shapes the clauses: each creates its own job **in the clause
body** against a fixture route that answers what the clause chose, then polls `drainTerminals()` until the
outcome is recorded — the bounded-wait pattern, `NotWithin` on expiry. Every job state settles in 0.1–5 s, so
the whole contract fits one `process()` call; nothing crosses calls.

The tier-neutral clauses cannot state the PhotoKit tier's single free retry: the URLSession tier answers it
trivially, and no clause may be reached only by a fake. So this change adds **one state**, `SINGLE_FREE_RETRY`
— "the tier offers a failed transfer once for retry before settling it" — which the URLSession bindings declare
unreachable, and the PhotoKit-only clauses on it:

- a transfer the destination refused is offered by `fetchRetryJobs()`;
- `retryJob` re-points it, and the retried transfer lands and is recorded `COMPLETED`;
- a transfer refused again is not offered again, and `drainTerminals()` hands it up for re-creation when its
  resource is live (finding D9);
- every presented job is acknowledged: after a drain, none is presented again.

`AT_CAP` is declared unreachable in the extension: the PhotoKit cap is not known, and filling it with jobs that
never answer would outlast the budget. `AT_CAP_DEFERS` keeps its real host in 8b's simulator-app binding.

*Alternatives:* a binding that enters each job state before the clause — the shape this design first had, before
8b's contract existed; the clauses are 8b's, and they create in the body. Splitting the run across two calls —
rejected by the operator: one call, sized under the budget.

### D5. The seam: PhotoKit calls as data, and every input a poll reads is recorded

`IosPhotoKitUploadPlatform` gains an `internal` seam, `UploadJobApi`, over the operating-system effects it
makes, and nothing else changes in the adapter's logic:

- `fetch(set)` → a list of plain job facts: destination path, `Content-Type` header, state, error, whether a
  resource is present and its type, and opaque handles for the job and the resource;
- `create(request, resource)`, `retry(job, request)`, `acknowledge(job)` → the OS's answer (ok, code, description).

8b's clauses **poll**: `awaitWithin { drainTerminals(); landed(route) != null && row == COMPLETED }`. On a device
the number of polls varies run to run. It replays exactly anyway, provided every input the poll's condition reads
comes from the recording: replay answers each call instantly, in recorded order, so the loop stops at the same
iteration it stopped at on the device. So in the extension binding:

- the seam's calls are recorded;
- the fixture reads — `objects.landed(route)` — are recorded too, as `landed(route) -> <content type>|none`;
- the ledger the adapter records through is a fresh SQLDelight store in a temporary file per clause, deterministic
  given its inputs (the in-memory fakes do not link into device builds).

Converting a real `PHAssetResourceUploadJob` into facts is the one step replay cannot cover; it stays in
`PhotoKitJobMapping.kt`'s documentation with the nil-field evidence, and the extension recording is its evidence
on the device. Replay runs in `:adapter:ios:ext-safe` `iosTest` on `IOS_SIM_KEXE`, beside
`IosKeychainReplayContractTest`; handle tokens and timestamps are masked.

*Alternatives:* a seam at the Objective-C object level — a job has no public initializer, so replay would need a
hand-written job stand-in, the object-shaped model phase 6 rejected (D2). A one-call `awaitPresented` wait — it
suited bindings that enter job states, which 8b's clauses do not use.

### D6. Preconditions: checked where only a person can fix them, automatic where they can be undone

In this order, before the run request is written:

1. **Checked, refusing the run** with the existing refusal status: a full photo grant (only a person can set
   it); no active membership (the re-registration wipes every in-flight job, and an automatic leave would
   destroy a real membership on the shared phone — the refusal names the rig's reset verb).
2. **Automatic**: the re-registration of D2, under the rig build's loopback base.

The membership check comes first because the re-registration is what destroys jobs.

### D7. The upload receiver lives in the app, speaks 8b's route grammar, and its answer is a stimulus

The rig build bakes `http://127.0.0.1:18099/api/v2` as its upload base (the rig's own port, through the existing
`local` deployment). A clause's route is `base + TransferFixture.path(...)`, and the rig answers
`/api/v2/<Contract>/<CLAUSE_ID>/<name>/<answer>` exactly as `scripts/transfer-fixture.py` does for the simulator
app — `s200-n0-len`, `s500-…`, `hold` — so one grammar serves every transfer host. The app is running for the whole
run — its rig verb is waiting — so the receiver is up when assetsd delivers.

The extension cannot read the app's memory, so the receiver writes each landed route, with its content type, into
the App Group; the extension binding's `FixtureObjects` reads it from there, through the recorder.

`port-contracts` makes a binding over a stand-in service `Fake`, because a stand-in decides the answers a port's
obligations are about. Here the obligations are the OS queue's and the adapter's: record the outcome, acknowledge,
offer for retry, re-point. No clause asserts what the receiver answered; the answer is what puts a job into the
state a clause needs, as a seeded asset puts a library into one. The binding therefore stays `Live`, and
`port-contracts` states the distinction.

A loopback IP literal is exempt from App Transport Security, and the local deployment already bakes one; the probe
measured that assetsd honours it. `ios-photokit-upload`'s "MUST be HTTPS" is corrected to "HTTPS for every
non-loopback host".

*Alternatives:* the local backend through a tunnel — real refusals only, no 500 on demand, a tunnel per session. A
receiver in the extension — the OS uploads after `process()` may have returned.

### D8. `UploadExtensionRegistry` recorded on `IOS_DEVICE_APP` under both grants

Production registers from the app, so the registry's host is `IOS_DEVICE_APP`. `PhotoKitExtensionRegistry`
gains a two-call internal seam (`setEnabled`, `isEnabled`). A photo grant is a precondition of a run, not host
identity, and a binding declares the one grant it runs under — so one host now needs one recording per
grant. Recordings become `<Contract>@<HOST>[.<grant>].rec`; a grant-independent contract (`SecureStore`) keeps
its unsuffixed name. The partial-grant run needs a person to switch the grant in Settings between the runs.

### D9. The three findings land as a failing clause, then the fix

Phase 6's pattern (D4 there): the clause lands first with its failing outcome recorded in the commit message,
and the fix follows in the next commit.

- **The destination guard.** `URLWithString(...) ?: FAILED` catches almost nothing: an empty string is not
  nil since iOS 17 (found by 8b on the URLSession adapter). The adapter requires an http(s) URL with a host.
- **`resource` nil.** Every fetched job in the probe had a nil `resource`, so the re-create path
  (`resourceIsLive`) would never fire. The retry-spent clause asserts the port's documented behaviour; if the
  extension recording confirms nil, the adapter re-resolves the resource from the ledger key instead, and the
  clause passes against that.
- **`onTerminate`.** The core logs "the OS terminated this cycle" at `Warn` on every `notifyTermination`, and
  its comment claims a killed cycle would otherwise look like a hang. Measured: `notifyTermination` arrives
  only after a normal return, and a kill sends nothing. The line becomes `Info` and states what it means; the
  port's KDoc says the same. No spec states `onTerminate`'s meaning, so this needs no delta.

## Risks / Trade-offs

- **[A run that overruns 60 s is killed silently, and the OS then backs off 6–11 min]** → stages are sized
  well under the budget (each wait is bounded at 10 s, and there are few clauses). The result is written once,
  at the end; a killed run leaves none, and the verb's timeout says whether the OS never invoked the extension
  (the request is still there) or the run was killed (it was taken).
- **[The OS may behave differently inside `process()` than in the app]** → that is exactly what the extension
  recording shows; the app probe is only the reason to expect it to fit.
- **[`resource` nil may be app-process-only]** → the clause asserts the port's contract either way; the fix
  follows only if the extension recording confirms it.
- **[Re-registration wipes jobs]** → the membership precondition refuses before it runs.
- **[The rig build's upload base points at loopback]** → only a rig build; the next normal build re-registers
  under its own base. The recording session ends with a normal build installed.
- **[The partial-grant recording needs a person]** → one Settings change per re-recording; recorded in the
  runbook.
- **[Depends on 8b]** → rebased on 8b's merged contract; nothing here lands before it.

## Migration Plan

Additive except the three fixes. Order:

1. Rebase on 8b's merged `BackgroundTransferContract`.
2. The grant-keyed recording name, the new host, and the coverage gate's reading of both.
3. The registry seam, its bindings, its two recordings.
4. The job seam, the `SimulatorUploadJobQueue` `Fake` binding, the PhotoKit states and clauses.
5. The extension runner, the substituted entries directory, the receiver and the verb's selector.
6. Record on the SE2; land each finding as clause then fix.
7. Retire `PhotoKitSmokeTest`; update the `rig-channel` and `snapsync-device` runbooks.

Rollback is a revert. The only production changes are the destination guard, the re-create path (if the
finding holds) and a log line.

## Open Questions

- Whether the in-flight cap (`LIMIT_EXCEEDED`) is reachable within one call's budget. If the cap is larger than
  the jobs one call can create, the clause is not written and the cap stays documented.
- Whether `resource` is nil inside the extension as well (D9).
- Whether the OS answers job-API calls identically inside `process()` (the recording will say).
