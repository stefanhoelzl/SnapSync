# Tasks — create-flow-zone-and-drain-shell

## C1 (landed b7a22ae)
- [x] `ports/LogScope` seam; `Logger.invocation(scope, …)`; `LogContext`+`IosLogScope` →
      `:adapter:ios:ext-safe`; feature ctors take `logScope: LogScope = NoOp`
- [x] `LaunchDirectives` parser + sealed `CompositionMode`/`resolveComposition` in `model/`
      (+ precedence unit tests, forge×link)

## C2 (landed 27ce632)
- [x] `flow/` zone: Foreground · Background · SilentPush · DownloadBackstop · Provision;
      ZoneFlowTest armed (deliberate-red proven)
- [x] Flows built in `compose/` (`AppCore`); shell entry points → thin log-wrapped delegators
- [x] `UploadPushReceiver` → `feature/upload`; `FanOutPushReceiver` dissolved into `SilentPush`;
      `:capability:upload` deleted

## C3 (this diff)
- [x] `flow/UserCommands` bundle; built in `compose/`; `StatusContainerHost` converted (def + 6
      construction sites: SnapSyncRoot, ForgeStatusHost (no-op — defaults), StatusPane,
      StatusContainerHostTest, JoinGateIntegrationTest, FullStackIntegrationTest); `EventCreator`
      interim collapsed out of presentation
- [x] `feature/membership/EventName.storeEventNameIfChanged` + tests; flows rewired to
      fetch-effect + rule (trigger conditions preserved)
- [x] `AlbumCoordinator.ensureAlbum(+saveToAlbum)` leading guard + `albumIdFor`; callers
      unconditional; tests extended
- [x] `resolveComposition` switch in `SnapSyncRoot`: one `when (mode)` → `ForgeShell`/`LiveShell`;
      forge guards ×6 and tier re-derivations deleted; forge inertness structural
- [x] `AppCore.installPermissionSubscriptions()` — pre-C2 install timing restored (host assembly
      only)
- [x] `toJoinLoad` → presentation; `appBuildVersion()` → `:adapter:ios:ext-safe`; stale-prose
      sweep (dead-module names, comment-only)
- [x] Diagrams regenerated (`architectureDiagrams`); CLAUDE.md module rows updated
- [x] Gates: `detektAppShell` (fresh) + `build` + `compileIosMainKotlinMetadata` +
      `:test:architecture:test` + world/integration/domain/ui jvmTests green; beacon 48→36
      (shells 30→18), no law increased
- [x] Spec deltas (11) + this ceremony; `validate --specs --strict` green; archive

## Archive gates (openspec/config.yaml)
- [x] No placeholder Purpose in the tree
- [x] Delta completeness: touched modules resolved to capabilities (see proposal Impact, incl.
      recorded no-delta reasons)
