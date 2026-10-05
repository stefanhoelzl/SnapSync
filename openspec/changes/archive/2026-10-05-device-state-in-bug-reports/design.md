# Design

## Context

The dump is assembled by `CollectDiagnosticDump` (`:domain:feature`, `feature/diagnostics`) into a `DiagnosticDump`
whose `state` and `ledger` sections are flat `Map<String, String>`s; `CrashReporting.sendDump` carries them as event
contexts (or saves them as `diagnostic-report.json` on a build with no DSN). The command reaches it as
`UserCommands.sendDiagnostics(note, screen)`, where `screen` is an opaque label the UI supplies. Its KDoc holds the
rule this change drops — "a dump reads no data the app does not already read" — and it is the reason the
selection size was left out.

What already exists to read from:

- **Network**: the `NetworkMonitor` port behind the `NetworkReadings` service. Its `watch()` is cold, and its
  first value is the access at that moment. The screen's `NetworkWatch` publishes "offline" only after a 5 s grace
  and resets to online when stopped.
- **Time zone**: `Clock.timeZone()` (process port). **Memory footprint**: `ProcessInfo.memoryFootprint()` — `null`
  on Android by design.
- **Device id**: `PersistedDeviceIdentity` — in the app role, `resolve()` *mints and persists* when nothing is
  stored.
- **Selection**: `AppCore.latestSelectionSnapshot` (`List<Resource>?`, `null` = not read yet), held only in
  `compose/`.
- **Screen numbers**: `SyncCounts` (shared/received `Progress(done,total)` or `Off`), derived in presentation's
  `StatusContainerHost` and rendered into `UiState`.

Nothing reads power saving, background allowance, battery or thermal state today.

Laws in play (`docs/architecture.md`): a feature sees services, never ports; a port is named for the need and covers
ONE external system; compose/ is wiring only, and each value-supplier it hands a feature is registered in
`CompositionSeamTest`; a port has one mock with port/operator faces, and a contract bound on a real host.

## Goals / Non-Goals

**Goals:**
- One place that decides every new key's name, value vocabulary and failure rendering.
- A report never stalls or fails because one reading did.
- The report's numbers are the ones the user **saw**, not a re-derivation that could differ.

**Non-Goals:**
- Automatic crash events: they stay as they are (no new tags or contexts).
- The extension process: it sends no dumps.
- Free disk space: excluded (its iOS required-reason API would require showing the value on the sheet).
- Live history (e.g. how long the device was offline, or Low Power transitions): one snapshot at confirm time.
- Push registration, version refusal, attestation state: considered and not added.

## Decisions

### D1 — The "reads only what the app reads" rule is replaced by a content bound
The rule did its job — it kept the dump from growing seams — but it also excluded exactly the device conditions
that explain background-upload stalls. The replacement is in the spec: a report holds this app's state and the
device's settings and conditions, never content, location beyond the zone, contacts, other apps' data, free
storage or the device name. The KDoc in `CollectDiagnosticDump` is rewritten to state the bound and point at the
spec, so the next field is tested against *that*, not against the old rule.
*Alternative:* an explicit allowlist of categories in the spec — rejected because every later field would need a
spec edit.

### D2 — One new port, `DeviceConditions`, for the readings nothing has today
`interface DeviceConditions : Port { suspend fun read(): DeviceConditionsReading }` — the device's power, battery,
thermal and background-allowance state, as the OS reports it to this app. One port, not four: they are read together,
at one site, for one purpose, and a single port keeps it to one mock, one contract and one adapter per platform.
*Not* folded into `ProcessInfo`: that port is "the OS's view of this process", and battery or thermal state is the
device's — the one-system rule would be blurred.

`DeviceConditionsReading` (`model/`) holds one `Fact<T>` per field:
`sealed interface Fact<out T> { Known(value) ; Unsupported ; Failed(reason) }`. Fields: `powerSaving: Boolean`,
`backgroundRefresh` (iOS: `available|denied|restricted`), `standbyBucket` (Android: `active|working_set|frequent|
rare|restricted|unknown(n)`), `batteryOptimizationExempt: Boolean` (Android), `batteryPercent: Int`,
`charging` (`charging|full|unplugged`), `thermal` (`nominal|fair|serious|critical`, Android's statuses mapped onto
them, `unknown(n)` otherwise). A platform answers `Unsupported` for what it lacks, so the "omit the key" decision
lives in one renderer rather than in each adapter.

Placement: **iOS** in `:adapter:ios:app-only` (battery and background-refresh status are UIKit, which the
extension-safety gate keeps out of `ext-safe`; the extension sends no dumps anyway). Low Power and thermal come from
`NSProcessInfo`; battery from `UIDevice` with `batteryMonitoringEnabled` switched on for the read and restored after,
on the main thread; background refresh from `UIApplication.backgroundRefreshStatus`, on the main thread.
**Android** in `:adapter:android`: `PowerManager.isPowerSaveMode` / `isIgnoringBatteryOptimizations` /
`currentThermalStatus` (API 29, under minSdk 30), `UsageStatsManager.appStandbyBucket` (own app, no permission),
and the sticky `ACTION_BATTERY_CHANGED` intent. **No new permission and no required-reason API on either platform.**

A service, `services.device.DeviceConditionsReadings`, passes it through to the feature (the feature-cannot-see-ports
law), as `NetworkReadings` does for the network.

### D3 — Readings are best-effort, bounded in time, rendered in one place
The feature runs the device reads concurrently, each under a short timeout (2 s — the slowest is a main-thread hop),
and catches a throw. A timeout or throw becomes `Failed(reason)`. Rendering, in `CollectDiagnosticDump` only:
`Known` → the value's label, `Unsupported` → **key omitted**, `Failed` → `failed (<reason>)` with the reason
truncated to 80 characters. The device read is ONE port call, so a timeout fails all of that call's facts together.
That is acceptable: they are local system reads, and a per-fact port surface would quadruple the contract for a
case not seen.

### D4 — Network: a fresh read, not the screen's notice
`network` = `NetworkReadings.watch().first()` under the same timeout, rendered `online` / `online_restricted` /
`offline` / `blocked`. The watch is cold, so this starts and stops one monitor for the read. *Alternative:* the
`NetworkWatch.access` value — rejected because it lags by the grace period and is reset to online when stopped, which
is exactly when a report could disagree with reality.

### D5 — The screen's numbers travel with the command, from what was rendered
`sendDiagnostics(note, screen)` becomes `sendDiagnostics(note, context: ReportContext)`, where `ReportContext`
(`model/`) carries the opaque `screen` label plus `shown: Map<String, String>`. The UI's
`UiIntent.SendDiagnostics(note, screen)` is **unchanged**: `StatusContainerHost.onIntent` adds `shown` from the
`UiState` it currently holds — `shown_shared` / `shown_received` = `"<done>/<total>"` or `"off"`, absent when not
joined — so the screens and the rig's `/user/sendDiagnostics` verb keep their shape. The feature records them
verbatim, as it does `screen`. *Alternative:* recompute `SyncCounts` in the core — rejected:
a report exists to catch screen-vs-store disagreements, so it must carry what was on screen, and a re-derivation
can drift from it.

### D6 — Values only the composition holds arrive as registered suppliers
`compose/` hands the feature `appFacts: () -> AppFacts` (`model/` value: `deviceId: Fact<String>`,
`timeZone: Fact<String>`, `memoryFootprintMb: Fact<Int>`, `selectionPhotos: Fact<Int>`), registered in
`CompositionSeamTest` beside `uploadFacts`. It holds no decision: each field is a direct read, mapped to a `Fact`.
- **Device id** via a new non-minting `PersistedDeviceIdentity.current()` that returns the already-resolved id or
  the reason it is not. A report must never mint and persist an identity — that would be a write, and the dump is
  read-only. Not resolved → `Failed("not resolved: …")`.
- **Time zone**: `Clock.timeZone().id`.
- **Memory footprint**: `ProcessInfo.memoryFootprint()`, `null` → `Unsupported` (Android).
- **Selection size**: only under `GalleryAccess.LIMITED`: the number of **distinct assets** in the snapshot (the
  snapshot holds resources, and a Live Photo has several); `null` snapshot → `Failed("not read yet")`. Any other
  grant → `Unsupported` (key omitted).

### D7 — Keys and section
All new keys go into the existing `state` section (the screen numbers too: `ledger` stays the store's counts, so the
two sources sit side by side and disagree visibly). Key names are constants in `model/DiagnosticDump.kt`:
`network`, `power_saving`, `background_refresh`, `standby_bucket`, `battery_optimization_exempt`, `battery_percent`,
`battery_charging`, `thermal`, `device_id`, `time_zone`, `memory_footprint_mb`, `selection_photos`, `shown_shared`,
`shown_received`.

### D8 — Budget stays inside the existing ≤ 4 KB row
14 keys, worst case all `failed (<80 chars>)`, add about 1.7 KB to the state section. The whole-event table in
`DiagnosticDump.kt` keeps its 4,000 B row; a test pins the state section's worst case (all facts failed, 100-char
event name, a 200-char note) under that row, so a later field that breaks it fails `build`.

## Risks / Trade-offs

- [Enabling iOS battery monitoring has a global side effect] → switch it on only for the read, restore the previous
  value, and do both on the main thread in one hop.
- [A main-thread hop stalls while the app is busy] → the 2 s timeout turns it into `failed (timed out)`; the report
  still goes.
- [The time zone is a coarse location] → the spec names it as the one allowed location-like fact, and the Privacy
  Policy names it explicitly.
- [Play may now read the report as "Diagnostics" (battery is Play's own example)] → re-check
  `metadata/play/declarations.md` and declare *App info and performance → Diagnostics* if the minimal form no longer
  covers it (task 6.2).
- [`UserCommands.sendDiagnostics`' signature change touches its implementers and test doubles (compose, the rig's
  `UserCommands`, presentation's test queries)] → the compile finds every one; the UI intent is unchanged, so no
  screen or wire caller moves.

## Migration Plan

No data migration: a report is a one-off event, and older builds' reports simply lack the new keys. The Privacy
Policy update ships in the same release (capability `privacy-security`). Rollback is a revert.
