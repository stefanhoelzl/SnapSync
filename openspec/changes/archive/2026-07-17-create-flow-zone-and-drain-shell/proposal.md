# Proposal: create-flow-zone-and-drain-shell

## Why

Migration step 8 of the `module-architecture` plan (`test/architecture/migration/PLAN.md`, "flow/ +
shell drain"), landed as three checkpoints (C1 `b7a22ae` · C2 `27ce632` · C3, this change's diff —
one OpenSpec change for the whole step, per the plan). Before this step, every OS-callback's
coordination — foreground refresh ordering, the silent-push fan-out, provision's
switch-leave→save→arm sequence — lived in the **untested** iOS app shell as `SnapSyncRoot` method
bodies, guarded by six scattered `isForging` checks and a re-derived tier flag; the laws "rules in
features, order in flows", "commands cross one door", and "one shared composition"'s sealed-mode
resolver had no code to hold.

## What Changes

- **C1 — `LogContext` repaid** (`b7a22ae`): the ambient log-context global moved out of `:domain`'s
  `model/` (a recorded violation-in-transit from step 5) to `:adapter:ios:ext-safe`, behind a new
  `ports/LogScope` seam driven by `Logger.invocation`; feature ctors take `logScope: LogScope =
  NoOp`. The `LaunchDirectives` parser and the sealed `CompositionMode`/`resolveComposition`
  resolver were committed to `model/` (unconsumed until C3).
- **C2 — the `flow/` zone** (`27ce632`): five trigger flows (`Foreground · Background · SilentPush
  · DownloadBackstop · Provision`), each importing `model/`+`feature/` only; **ZoneFlowTest armed**
  (deliberate-red proven). Flows are built in `compose/` (`AppCore`); the shell entry points became
  thin log-wrapped delegators. `UploadPushReceiver` re-homed to `feature/upload`;
  `FanOutPushReceiver` dissolved into `SilentPush`'s fan-out; **`:capability:upload` deleted**.
- **C3 — commands, rule sinks, the resolver switch** (this diff):
  - The `flow/` **user-tap command bundle** `UserCommands` (leave · create · commitJoin · share),
    built only in `compose/` (`AppCore.userCommands`), injected into `StatusContainerHost` by
    constructor — replacing its individual `leave`/`share`/`commitJoin` lambdas and its direct
    `EventCreator` reference (the step-6 interim). All six construction sites converted; command
    bodies byte-preserved.
  - Two micro-rule **feature sinks**: `EventName.storeEventNameIfChanged` (`feature/membership`)
    holds the shell's former name-persist rule (same-event + changed guard, cutoff-preserving
    whole-config save); `AlbumCoordinator.ensureAlbum` gains the membership opt-in gate as its own
    leading guard (`saveToAlbum`/non-empty-name), and `albumIdFor` the import-time lookup — callers
    call unconditionally.
  - The **`resolveComposition` switch**: `SnapSyncRoot` parses `LaunchDirectives` once, resolves
    the sealed `CompositionMode` once, and switches on it in **one** place, selecting a
    `ForgeShell`/`LiveShell` delegate (with the live tier's mechanism thunks bound in the same
    switch). The six `isForging` guards and every tier re-derivation
    (`useAppDrivenUpload`/`forceUrlSessionUpload`/`isSimulator`) are deleted; forge inertness is
    structural (`ForgeShell` holds no route to the live stack).
  - **Subscription-timing restoration**: the permission-grant collectors C2 had moved into
    `AppCore.init` are now an explicit `AppCore.installPermissionSubscriptions()` invoked only from
    the shell's host-assembly path — restoring the pre-C2 property that a cold background wake
    starts no producer off the StateFlow's replay.
  - Shell decision count (detekt, fresh): 26 → 14 Kotlin (+4 Swift pinned); `SnapSyncRoot` 15 → 4.
  - Comment-only stale-prose sweep of dead-module names; `EventDetails→JoinLoad` mapping moved from
    the untested shell into tested presentation (`toJoinLoad`); the duplicated `buildVersion()`
    consolidated as `appBuildVersion()` in `:adapter:ios:ext-safe`.

Behavior-preserving throughout: command semantics, forge behavior, entry-point `debug.log`
prefixes, and the two sunk rules' outcomes are unchanged (see design.md for the two deliberate
edge deviations in forge mode).

## Impact

- Specs: `ios-app-shell`, `diagnostic-logging`, `upload-lifecycle`, `push-registration`,
  `photo-download`, `event-album`, `join-event`, `event-creation-ui`,
  `event-rejoin-reconciliation`, `ios-url-session-upload`, `ios-photokit-upload` (deltas herein).
- No delta needed: `upload-completion-notify` (its notify seam and seats are untouched; no stale
  text), `architecture-guards` (the flow gate armed itself by directory creation, exactly as the
  self-arming requirement specifies — zero gate edits), `module-architecture` (this step
  implements it; the contract is unchanged), `sync-status`/`leave-event`/`device-attestation`
  (behavior and seats unchanged by this step), `desktop-test-harness`/`full-stack-harness` (the
  harness panes construct the bundle from their existing injected edges; harness contracts
  unchanged).
- Code: `:domain` (`flow/UserCommands`, `feature/membership/EventName`, `feature/album` gate,
  `compose/` bundle + subscriptions + fetch effect), `:domain:presentation` (bundle constructor,
  `toJoinLoad`), `:adapter:ios:ext-safe` (`appBuildVersion`), `:app:ios` (the resolver switch),
  `:app:ios:photokit-extension` (banner helper), `app/desktop` harness panes, tests.
