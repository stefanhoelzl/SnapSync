## Context

`PlatformHandoff` bundles the two ports through which the app hands something to the platform and stops being
involved: `SharePresenter` (the invite link, into the share sheet) and `LinkOpener` (the update-required
screen's store button). Both returned `Unit`, documented as fire-and-forget because "there is no outcome this
app is entitled to know or act on".

This change began as a `port-contracts` phase whose expected result was a written exclusion: a clause asserts
on what the port answers, and `Unit` answers nothing. Checking whether there really was no outcome found three
discarded ones in the iOS adapters — the share presentation's completion, a nil presenter, and `openURL`'s
`Boolean` — and then, on the device, that the discarded `Boolean` was hiding a dead button.

### Measurements (SE2, iOS 26.6.2, 2026-09-23, rig build)

| call | URL | iOS answered | screen afterwards |
|---|---|---|---|
| `openURL(_:)` (deprecated; the shipped form) | build's store URL `apps.apple.com/de/app/id6781692480` | `false` | SnapSync unchanged |
| `openURL(_:options:completionHandler:)` | same | `true` | App Store on the SnapSync Photos page |
| `openURL(_:options:completionHandler:)` | `https://www.apple.com/` | `true` | (contract recording) |
| `openURL(_:options:completionHandler:)` | `snapsync-contract-unclaimed://link-opener` | `false` | (contract recording) |

The first two rows were taken with a throwaway rig route that never reached the branch. The last two are the
committed recording `LinkOpener@IOS_DEVICE_APP.rec`.

## Goals / Non-Goals

**Goals:**
- The store button opens the App Store.
- A hand-off that did not happen is recorded, and one that strands a user reaches the operator.
- Both ports are held by contracts that a real implementation passes, so their doubles are licensed.

**Non-Goals:**
- Acting on a refusal in the UI (a "could not open the App Store" message). The screen already states the
  remedy in words; a UI reaction is a separate product decision.
- Observing what the user does inside the share sheet — still not this app's business.
- `PhotoAccessRequester.openSettings`, which also passes `completionHandler = null`. It is a different port,
  already contracted, whose outcome is read back through the permission read-model.

## Decisions

### D1 — `openURL(_:options:completionHandler:)`, pinned by a recording

The modern form is the only one measured to work. Pinning it with a comment alone would be the kind of prose
belief `port-contracts` exists to replace, so the adapter's single `UIApplication` call goes through an
`internal` seam (`UrlOpenerApi`) that the device recording is taken at. An adapter that stops making exactly
that call — including by reverting to the one-argument form, which would bypass the seam — reads `Diverged` on
every CI build.

### D2 — The ports answer `Handoff` = `Accepted | Refused(reason)`

- *Rejected: keep `Unit`, log in the adapter.* The first attempt. It records a refusal but leaves nothing a
  clause can assert, so the contracts — and the licence for the doubles — would still be impossible; and the
  record would sit in each platform's adapter rather than at the tap it belongs to.
- *Rejected: `Boolean`.* Cannot carry why, and the inert `None` would have to answer `false` with nothing to say
  that "no platform" is the reason.
- *Rejected: a third case, `Unanswered`, for a completion that never fires.* UIKit calls both completions, and
  no measurement has shown otherwise. A lost completion suspends only that tap's coroutine; its `→ tap.*` line
  then has no `←` line, which is visible in the device log. The bound belongs to the clause that waits (D6), not
  to the adapter.

`suspend` follows from answering: both answers arrive in a main-queue completion.

### D3 — Recorded in the core, at `Error` when refused; acted on by nothing

The tap commands in `snapSyncApp` render the answer onto the tap's own `←` line and log a refusal at `Error`.
One place covers every platform's adapter, and the line sits inside the tap's log context. `Error` because
`crash-reporting` reserves it for the unexpected path, and a refused hand-off is that: on the update-required
screen it means a user who cannot leave a dead end. In the harnesses and the world, `None` answers `Refused` and
logs `Error` too — those builds carry no DSN, so nothing is reported, and the line is true.

`AppCore` sits at its `TooManyFunctions` ceiling (11), and a ceiling may only fall, so the refusal helper is a
file-level extension function and the existing `onUiLane` became generic in its result, rather than a new
member.

### D4 — The seam owns the main-queue hop

`SystemUrlOpenerApi` marshals onto the main queue itself. A replay answers synchronously, and the simulator's
Kotlin/Native test executable never services the main queue while a test blocks it, so a hop left in the
adapter would deadlock the replay. The main-lane guard still sees exactly the files it saw.

### D5 — Which state runs where

| contract / state | JVM, sim kexe (fake: `LinkOpener.None`) | `IOS_SIM_APP` (live) | `IOS_DEVICE_APP` (recorded) |
|---|---|---|---|
| `LinkOpener` / `UNCLAIMED` | reached | reached | reached |
| `LinkOpener` / `CLAIMED` | unreachable: no platform | unreachable: opening it backgrounds the app under test mid-run | reached (last clause) |
| `SharePresenter` / `PRESENTABLE` | no binding | reached; disposal dismisses the sheet | — |

- The simulator-app `CLAIMED` exclusion is a belief, not a measurement: the `https` scheme is claimed by the
  simulator's Safari, and leaving would background the process that is serving the rig's request. The rig
  excludes `onOpenAppStore` for the same reason. The device records it instead.
- *Dropped: an `UNPARSEABLE` state.* `NSURL.URLWithString` has become lenient in recent iOS (a belief, not
  measured here), so no fixed input is known to parse to nil on every host. The branch stays in the adapter and
  answers `Refused`; the value comes from the build's own `Deployment.plist`.
- *No-key-window share* has no host that can enter it while the rig drives a foreground app, so it is adapter
  documentation, not a clause only a fake could run. `SharePresenter.None` therefore has no binding: the
  contract's one state needs a surface to present over.

### D6 — Waiting on a platform callback uses the real clock

A clause body runs in `runTest`, whose virtual clock skips an idle wait at once. `withTimeout` there would
expire before a main-queue callback could arrive, so `withinRealTime` moves the wait to `Dispatchers.Default`
and turns expiry into `WaitExpired`, which the runner already reads as `NotWithin`.

### D7 — One contract, two hosts

`LinkOpener` is registered for the device (recording) and the simulator app (live) under one name. The rig
looked contracts up by name alone, so it would have run whichever came first. It now prefers the entry for the
host it is running on; another host's entry still answers, with that entry's own refusal.

### D8 — Recordings embedded per module

The replay test lives in `:adapter:ios:app-only`, and the embedded recordings map is `internal` to the test
compilation it lands in. So app-only carries a twin of ext-safe's `embedContractRecordings` task. There is no
shared build logic in the tree, and introducing one for a 20-line task was not worth it.

## Risks / Trade-offs

- [The simulator-app live runs, and the replay test, run for the first time in CI] → the `ios-contracts` job and
  the macOS test job gate the PR. A failure there is a finding, not something to re-classify as unreachable
  (which the coverage gate refuses anyway).
- [The device recording's last clause backgrounds the app] → the rig answered while the app was backgrounded
  (measured). A future run the OS suspends first would answer when the operator foregrounds the app.
- [A future iOS changes `openURL`'s answer for `https`] → a re-recording shows it in that host's history, per
  `port-contracts`.
- [The share clause presents real UI on the CI simulator] → disposal dismisses it synchronously on the main
  queue before the next clause or contract runs.

## Migration Plan

None. The port change is internal and every implementation is in-tree. Rollback is a revert: the store button
would then return to doing nothing.
