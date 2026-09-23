## Why

The update-required screen's only remedy — its **Update** button — has never opened anything on a supported
iOS, and nothing recorded that. `IosLinkOpener` called the deprecated one-argument `UIApplication.openURL(_:)`
and dropped its `Boolean`; measured on the SE2 (iOS 26.6.2, 2026-09-23) with the build's own store URL, that
form answers `false` and opens nothing, while `openURL(_:options:completionHandler:)` opens the App Store and
completes with `true`. A user stranded on an unsupported version taps the one button meant to get them out,
and the app neither moves nor knows.

Both `PlatformHandoff` ports (`SharePresenter`, `LinkOpener`) were declared fire-and-forget on the reading
"the user left, so the outcome is not our business". That reading covers only the hand-off that HAPPENED. The
causes the adapters silently absorbed — the system refusing a URL, no key window to present a sheet from —
leave the user where they were, and `module-architecture`'s "Absence is never silent" requires a collapse to
name the consequence for every cause it absorbs. Returning `Unit` also left both ports with nothing a
`port-contracts` clause could assert, so their doubles were licensed by nothing.

## What Changes

- `IosLinkOpener` uses `openURL(_:options:completionHandler:)`; the store button opens the App Store.
- **BREAKING (internal API)**: `SharePresenter.share` and `LinkOpener.open` become `suspend` and answer a new
  `Handoff` (`Accepted` | `Refused(reason)`). Nothing acts on the answer and `UiState` is unchanged; the tap's
  own log line carries it, and a refusal is logged at `Error`, which production reports to the operator. The
  inert `None` instances answer `Refused`.
- New port contracts (the contract code is their specification):
  - `LinkOpenerContract` — an unclaimed URL is refused (live on the simulator app; the inert `LinkOpener.None`
    also passes it); a claimed URL is accepted (recorded on a device through the adapter's new `internal`
    `UrlOpenerApi` seam, replayed on every CI build, so a return to the one-argument call reads `Diverged`).
  - `SharePresenterContract` — with a key window, the sheet is presented (live on the simulator app; disposal
    dismisses it). The no-key-window cause has no host that can enter it and stays adapter documentation.
- The contract mechanism gains: one contract name registered for two hosts (the rig answers the entry for the
  host it is running on), and a real-clock bound for clauses that wait on a platform callback, whose expiry
  reads `NotWithin`.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `min-app-version`: new requirement — the update-required state's store link is handed to the platform, and
  a hand-off that did not happen is reported rather than dropped.
- `ios-app-shell`: the share scenario of "iOS live composition root" — the share command now records whether
  the sheet was presented (the UI still observes no result).
- `port-contracts`: "Outcomes are explicit and none is silent" gains the real-clock bound for platform
  callbacks; "In-app hosts CI can reach are run live over the rig" gains the rule for a contract registered on
  a recorded host and an in-app CI host at once.

No delta, with reasons:
- `module-architecture` — "Absence is never silent" already demands what this change does; it names no port.
- `crash-reporting` — "Error-severity log lines become events" already governs the new `Error` line, which is
  the unexpected path the requirement reserves `Error` for.
- `sync-status-screen` — "The update-required store button" already requires the button to open the store
  link; this change makes the adapter honour it.
- `event-link` — specifies the invite link, not the surface it is shared through.

## Impact

- `:domain:ports` — `Handoff`, `SharePresenter`, `LinkOpener`, `PlatformHandoff` (docs).
- `:domain:compose` — the two tap commands record the answer; `AppCore`'s function count is unchanged
  (`complexity-budgets`: the refusal helper is file-level).
- `:adapter:ios:app-only` — `IosLinkOpener` (+ `UrlOpenerApi` seam), `IosShareSheet`; rig source set: the
  hand-off bindings, recorder and replayer; `iosTest`: the replay test; build script: embeds the recordings.
- `:adapter:generic:fake` — the `LinkOpener.None` binding.
- `:test:contracts` — the two contracts and the real-clock bound helper.
- `:test:rig` — contract lookup by host; the device registry gains `LinkOpener`.
- `:test:architecture` — `MainLaneContainmentTest` allowlists the rig binding that dismisses the sheet.
- `test/contracts/recordings/LinkOpener@IOS_DEVICE_APP.rec` — new, recorded on the SE2.
- Users: the Update button works. Changelog label `bug`.
