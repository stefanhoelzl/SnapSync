# Design

## Context

See proposal.md for why, and `specs/` for the outcomes. This reverses D1 of
`changes/archive/2026-10-04-mobile-data-for-photos` (the choice as a field of `EventConfig`). The rest of that
design stands. The rule still rides on each transfer as a `TransferNetwork`, fixed when the transfer is created (D2).
The iOS request flags (D3), Android's `DownloadManager` flag (D4) and Android's upload wait plus wake (D5) are
unchanged. The waiting line is still computed from the current choice (D7). Only the source of the choice moves.

Where the choice is read today. Every one of these calls `transferNetworkOf(config.value)`:

- `UploadCore.uploadCycle()`, the network for the shared `UploadTransferService`. Both the app's uploader and the
  iOS ≥26.1 extension use it. The extension builds its own `ConfigService` over its own process ports.
- `TransferEntries`, the app uploader's background-session create and retry.
- `SnapSyncApp`, `DownloadJobs(network = …)`.
- `TailComposition`, `Heartbeat(transferNetwork = …)`. Through `WakeNetwork.UNRESTRICTED` it decides Android's
  unmetered wake.
- `StatusContainerHost`'s `heldForWifi`, which drives the waiting line.
- `CollectDiagnosticDump`, which writes `mobile_data` inside the event block.

It is written by `JoinEvent` (from `JoinChoice`) and by `ReconfigureEvent` (from the settings sheet's `SettingChange`
through the `reconfigure` command).

Constraints that shape the approach:

- **Both iOS processes need the choice.** The extension creates upload jobs too. `ExtensionPorts` already carries
  `Preferences` (the App-Group `UserDefaults` suite), as does `AppPorts`. Android has one process.
- **The choice was never released.** No compatibility is owed to configs holding `mobileData`. `ConfigFile`'s JSON
  ignores unknown keys, so an internal build's file still decodes.
- **What the adapters can report.** `PrefRead.Unavailable` is practically unreachable. On iOS it only means the suite
  could not be opened. On Android it only means a non-string value is under the key. Before the first unlock, iOS
  answers `Absent`, not an error, but no membership is readable then either, so nothing transfers.

## Goals / Non-Goals

**Goals:**
- One source of the choice: a service over `Preferences`, read on every transfer creation in both processes.
- No change below the `TransferNetwork` seam: no adapter, port or contract changes.

**Non-Goals:**
- Carrying any membership's per-event choice over (not released; decided in the interview).
- Device settings beyond this one. There is no general settings document or screen; the menu row is the whole
  surface.
- Telling the extension about a change. It reads on each job creation instead.

## Decisions

### D1. `MobileDataSetting`, a service over `Preferences`
`:domain:services` (`services.settings`) gets a concrete `MobileDataSetting(preferences)` with one key
(`app.snapsync.settings.mobileData`, values `on` | `off`):

- `transferNetwork(): TransferNetwork` reads the port on every call. The result is `Value("on")` or `Absent` → `ANY`,
  `Value("off")` → `UNRESTRICTED_ONLY`, and `Unavailable` or any other value → `UNRESTRICTED_ONLY`, logged. The
  failure branch is strict, like today's `transferNetworkOf(null)`: the cost of a wrong hold is a delay, while a
  wrong send spends the member's data. This was decided after the interview, once it was clear the branch is
  practically unreachable.
- `allowed: StateFlow<Boolean>` is the app's view for the menu and the waiting line. It is seeded from one read
  (unreadable shows `true`, the default) and updated by `set`.
- `set(on): Boolean` writes the value and publishes it only when the write answers `Ok`.
- `clear()` removes the key, for `ResetDeviceState`.

*Why read per call rather than cache:* the extension is a separate process that may live across an app-side change.
An App-Group `UserDefaults` read is cheap and current, and the old path also re-read the config per call.
*Alternative*: a field in a new shared `deviceSettings.json` through `Files` was rejected in the interview. It would
mean one more file and its absent/unreadable mapping for a single boolean.

### D2. Every network call site takes the setting, and `EventConfig` loses the field
`transferNetworkOf(EventConfig?)` and `EventConfig.mobileData`/`transferNetwork` are deleted.
`transferNetworkOf(Boolean)` stays only if a caller remains. The four composition sites pass
`mobileData::transferNetwork`. `UploadServices` gains a `mobileData: MobileDataSetting`, filled by `AppServices` in
the app and by `extensionServices()` over `ExtensionPorts.preferences` in the extension. `JoinChoice`, `RangeForm`,
`SettingChange` and the `reconfigure` command lose their `mobileData`. `JoinEvent` and `ReconfigureEvent` stop
writing it.

### D3. The choice is a device fact in `UiState`, changed by a user command
`UiState` gains `mobileData: MobileDataState(on, notSaved)` beside `build`, not inside a layer, because the menu shows
it on every layer. `StatusSources` gains `mobileData: StateFlow<Boolean>`, wired in `ComposedApp.statusSourcesOf`
from the service. `UserCommands` gains `setMobileData(on): Boolean`. `UiIntent.MobileData(on)` keeps its name and
routes to that command instead of the form. On `false` the presentation sets `notSaved`. The switch reads `on`, which
never changed, so it shows the setting still in effect with no separate revert. `notSaved` clears on the next flip or
when the menu closes. `heldForWifi` reads `mobileData.on` instead of the config.
*Alternative*: the setting on `Layer.Joined.membership` was rejected because the menu is offered with no event.

### D4. The menu row is a new design-system primitive
`:ui:components` gets `AppMenuSwitch(icon, label, note, checked, onCheckedChange)`. It is a menu-styled row with the
same switch look as `AppToggleRow`, with the note beneath and an optional error line. `AppMenuIcon` gains a
mobile-data glyph. `StatusScreen.AppMenu` puts the row first, then `AppMenuDivider`, then the existing rows.
`MenuActions` gains `onMobileData`. Strings: `mobile_data_toggle`, `mobile_data_on_note` and `mobile_data_off_note`
stay, read from the menu. A new `mobile_data_not_saved` string is added in English and German.
`ParticipationSections` loses the row and its `ParticipationState`/`ParticipationActions` fields.
*Alternative*: reusing `AppToggleRow` inside the drawer was rejected because it is drawn for a card and would sit
oddly among `AppMenuItem` rows.

### D5. Reset, dump and the control channel follow
- `ResetDeviceState.reset()` gains a best-effort `mobileData.clear()` step, so a rig reset returns to "on" as
  decided.
- `CollectDiagnosticDump` writes `mobile_data` (`on` | `off` | `unreadable`) in the device block, outside the
  `config != null` branch, so a report without an event carries it too.
- `:test:rig`: `/user mobileData?on=true|false` dispatches `UiIntent.MobileData`. `rangeChoices()` drops its
  `mobileData` param, so `confirmJoin`, `reconfigure` and `setRange` no longer take it. The verb joins
  `RigVocabulary`. A client reads the value from `UiState.mobileData`.
- `MirrorHarness` posts the new verb from `MenuActions.onMobileData`.

## Risks / Trade-offs

- [An extension job created a moment after an app-side flip may use the old rule]. App-Group `UserDefaults`
  propagate across processes through `cfprefsd` with no ordering promise. → Accepted, because the spec already lets
  a transfer keep the rule it started with, and the next job reads the new value.
- [The switch is reachable while a join is being confirmed, but not while it commits]. The menu is masked during
  the commit. → A flip seconds earlier already governs the new membership's first transfers. No race matters, since
  transfers read per creation.
- [An internal tester who had a membership set to off starts on after updating]. → Accepted in the interview. The
  value was never released.
- [`Unavailable` shows "on" in the menu while transfers hold for Wi-Fi]. → Only in a broken-build case; logged and in
  the dump as `unreadable`.

## Migration Plan

- No data migration. A config's stale `mobileData` key is ignored on decode and dropped by the next write. The
  preference key starts absent, which means "on".
- The change ships in one PR. Rollback to the previous build brings back the per-event field (default on) and ignores
  the preference key.
- At archive, the `mobile-data` spec's `## Purpose` ("when they choose so for a membership") is rewritten in the
  main spec, because a delta cannot carry it.
