## Why

On iOS ≥26.1 with the app-driven tier forced, the OS-driven PhotoKit extension stays **registered**.
`SnapSyncRoot` passes `osUploadProducer = { null }` on that tier, so `PhotoKitUploadProducer` is never
constructed and its `stop()` — the only caller of `setUploadJobExtensionEnabled(false)` — never runs. The
system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and survives relaunch **and**
reinstall, so nothing clears it: the OS can invoke the extension while the app-driven pump also runs, and
two `LedgerWriter`s meet over one App-Group ledger, breaching `sync-ledger`'s single-record-writer
invariant.

The verb already exists. It is unreachable because `ComposedProducers.osDriven` answers two different
questions with one nullable — *"does this OS have the mechanism?"* and *"may this build run it?"* — and
collapsing them is what leaves a present mechanism with no route to its own teardown. Patching that
nullable was the first design; it produced a growing pile of special cases (a stop-only list, a
supertype, a one-shot latch, a tri-state). Separating the two questions dissolves all of them, and the
defect stops being a case to handle.

## What Changes

- **The app holds ONE upload producer**, resolved from `(OS facts, permission, override)` and re-resolved
  when an input changes. `ComposedProducers` and `UploadArm.selectedProducer()` are deleted. The
  exactly-one-started invariant becomes **structural again** — the arm can only name one producer, so
  "both started" has no expression — which `upload-lifecycle` records as the guarantee it gave up when
  the mechanism choice became runtime-dependent.
- **No empty cells.** Unusable access resolves to an `Idle` producer, never `null`, so a trigger can
  never reach nothing and strand an OS completion handler.
- **Triggers move onto the mechanism** (`onForeground`, `onSilentPush`, `onBackgroundTask`,
  `onSelectionChanged`) and are delivered unconditionally; each tier states its answer explicitly,
  including "nothing". The four tier-dependent `LiveShell` thunks and the tier branch in the shell's mode
  switch go away. Every trigger becomes a plain `suspend fun onX()`: `OsReceipt` construction hoists to
  the entry point, so no mechanism ever holds a raw OS completion handler.
- **The deregistration falls out.** The `(≥26.1, app-driven)` cell instantiates a producer that
  relinquishes the OS registration before pumping. The forced build and the already-required
  `GRANTED → LIMITED` deregistration become the **same cell**, not two stories.
- **The blanket repair stops firing on the tier switch.** `clearRequested()` (ledger-wide) and the shared
  cursor clear are repairs for jobs the OS wiped, needed where PhotoKit re-registers. On a switch to the
  app-driven tier they are redundant *and blunter than what follows*: that tier reconciles stranded
  `REQUESTED` rows precisely from `getAllTasks` and, by its own contract, "SHALL NOT depend on
  `clearRequested`". This also stops a limited-access member's in-flight rows being wiped once per
  process on a shipped path.
- **The read discipline moves from the fan-out to the mechanism.** The `GRANTED`-exactly guard wrapping
  the upload push receiver is an invoker-gate; whether a cycle walks the library or consumes the
  selection snapshot is a property only the mechanism knows.
- **`resolveComposition` is absorbed.** Once `CompositionMode` reaches one case (see *Impact*), producer
  resolution is a strict superset of it, so both it and `UploadTier` are deleted rather than reconciled.
- **This change owns the tier-force replacement.** `triggers-into-channel` deletes
  `SNAPSYNC_FORCE_URLSESSION_UPLOAD` and builds nothing in its place; a resolution **override** here is
  what restores it. It names a mechanism **kind** (not a Boolean, so either mechanism can be pinned), is
  read **fresh at each resolution**, and is **readable before the first resolution of every process** —
  because an OS-initiated cold relaunch (`handleEventsForBackgroundURLSession`) calls straight into an
  entry point with no opportunity for a control-channel request to arrive first, so an override settable
  only at runtime would already be gone and the relaunched process would resolve a different mechanism
  while reporting nothing unusual.
- **The exclusivity guard is retargeted, not deleted** — from "no path starts both" (now a compile error)
  to the two places the risk moved: the resolver's cells, and the arm's newly stateful transitions.

## Capabilities

### New Capabilities

None. This reshapes existing contracts; no new capability is introduced.

### Modified Capabilities

- `upload-lifecycle`: producer **selection** replaced by producer **resolution** — one instance resolved
  from OS facts and permission, exclusivity restored structurally, the trigger surface added to the
  mechanism seam, and the blanket-repair carve-out conditioned on what runs next.
- `ios-photokit-upload`: the disable-repair requirement splits — the repair belongs to **re-registering**,
  not to every disable, so it does not fire when the disable is a switch to a tier that reconciles
  precisely. "Switching to limited deregisters the extension" is now served by the same resolution cell as
  a forced build.
- `ios-url-session-upload`: per-version tier selection restated as resolution; the tier-force flag's
  meaning re-expressed as a runtime input rather than a launch directive.
- `limited-photo-access`: the no-autonomous-reads discipline is enforced at the mechanism, not at the
  trigger fan-out.
- `architecture-guards`: "The upload producers are never both started" retargeted; the platform-identifier
  gate's one **accepted** pin moves from `CompositionMode`'s tier members to the producer kind.
- `ios-app-shell`: the shell's one switch loses its tier arm; the four tier-dependent shell thunks are
  replaced by one resolved producer.
- `module-architecture`: "One shared composition" — composition selection is no longer a launch-directive
  function to a sealed mode.

## Impact

**Sequencing — the change this follows has landed.** `retire-launch-env-triggers` deleted
`LaunchDirectives` (both `SNAPSYNC_FORGE_STATE` and `SNAPSYNC_FORCE_URLSESSION_UPLOAD`), moved forge to its
own Xcode target, and **deleted `CompositionMode` outright** rather than reducing it to one case —
`resolveComposition` is now `(backgroundUploadSupported: Boolean) -> UploadTier`. It also landed the
`setEnabled` `Boolean`/`NSError` fix behind a tested `registrationOutcome` classifier. The deltas here are
built from the **current** spec text, not from a prediction of it.

**It also owes that change a seam, now formally assigned.** `SNAPSYNC_FORCE_URLSESSION_UPLOAD` is being
deleted outright with no replacement built alongside it; the resolution override here is the replacement. A third change
(`rig-simulator-host`) depends on it and is blocked until this lands — a simulator is ≥26.1 and so
resolves the OS-driven mechanism, whose extension the OS never invokes there, so forcing the app-driven
mechanism is what makes uploads exist on that host at all. Until both land, the app-driven tier is unexercisable **under a full grant** on a
≥26.1 device — a `.limited` grant still exercises the pump, scheduler, background `URLSession`, staging
and ledger writing, so the gap is the full-library discovery walk, not the tier.

**Code:** `:domain feature/upload` (`UploadArm`, the producer seam, the resolver, `Idle` and the
relinquishing producer), `:domain compose/` (assembly), `:domain model/` (`CompositionMode`/`UploadTier`
deletion), `:app:ios` (`SnapSyncRoot`'s mode switch and `LiveShell` thunks; `PhotoKitUploadProducer` and
`UrlSessionUploadController` gain their trigger answers), `:test:architecture` (`ProducerExclusivityTest`,
`PlatformIdentifierTest`, `CompositionSeamTest` pins).

**Not in scope:** the discarded `Boolean`/`NSError` at `PhotoKitUploadProducer.kt:79` — carried by
`triggers-into-channel` as an independently correct, user-facing fix that should not wait on this. The
leave path's blanket repair, whose redundancy argument is weaker and is not ours. Any change to the
`3202` disable→enable ritual.
