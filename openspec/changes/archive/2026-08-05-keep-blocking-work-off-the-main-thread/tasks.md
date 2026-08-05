## 1. Confirm the platform assumption before building on it

- [x] 1.1 Probe the candidate dispatchers against `iosMain` with `compileIosMainKotlinMetadata` before
      building on them. **Result: `Dispatchers.IO` is `internal` on Kotlin/Native (coroutines 1.10.2) and
      does not compile**, which withdrew the first draft of D1/D6 and part of the spec delta.
      `newFixedThreadPoolContext(1, …)` and `Dispatchers.Default.limitedParallelism(1)` both compile; the
      design now takes the former. Probe removed.

## 2. The composition scope (door 1)

- [x] 2.1 Move `SnapSyncRoot`'s process-lifetime scope from `Dispatchers.Main` to a dedicated
      single-thread context (`newFixedThreadPoolContext(1, …)`, `@OptIn(DelicateCoroutinesApi::class)`),
      keeping the `SupervisorJob` and the `CoroutineExceptionHandler` untouched.
- [x] 2.2 Give the three direct UIKit touches that currently free-ride on the scope being main their own
      explicit main hop: `isProtectedDataAvailable()`, the `openURL` in `PhotoLibraryPermission`, and the
      two `NSOperationQueue.mainQueue` observer registrations.
- [x] 2.3 Leave the two existing hops (`IosDiscovery`, `PhotoKitCandidateSource`) on
      `Dispatchers.Default` — with no public `Dispatchers.IO` there is nowhere better without inventing a
      second pool. Only their rationale changes (7.2); the residue is recorded in design D6.

## 3. The tap door (door 2)

- [x] 3.1 Split the command decoration in `compose/` into two lane-declaring decorators — one that hops to
      the composition lane, one that stays on the main lane — with no default lane on either.
- [x] 3.2 Move every field of the command bundle onto the decorator matching its lane: `share`,
      `requestAccess`, `openSettings` and `choosePhotos` on the main lane, the rest on the composition lane.

## 4. Honest tap instrumentation (door 3)

- [x] 4.1 Make `CreateEvent.create` and `RenameEvent.rename` plain `suspend` functions and drop their
      `CoroutineScope` constructor parameters.
- [x] 4.2 Move their launches into the command decoration in `compose/`, inside the `Logger.invocation`
      wrap, so the tap line spans the real work instead of the hand-off.
- [x] 4.3 Update the two features' composition sites and their existing tests for the signature change.

## 5. Gates

- [x] 5.1 Add the main-lane containment gate to `:test:architecture` (Kotlin `Dispatchers.Main`,
      `MainScope()`, `dispatch_get_main_queue`, `NSOperationQueue.mainQueue`; Swift `DispatchQueue.main`),
      with a reasoned allowlist, reusing the `ZoneGates` helpers.
- [x] 5.2 Extend the same gate to fail on `runBlocking` outside test source sets, exempting the
      extension composition root's pinned use.
- [x] 5.3 Add the command-lane gate: every field of the user-command bundle is built through one of the
      two decorators.
- [x] 5.4 Add the constructor fence forbidding blocking platform calls in property initialisers and
      `init` blocks under `adapter/ios/**`, grandfathering `FileBackedConfigStore` by name with its
      reason. **Implemented as a Konsist gate, not a detekt rule** — same guarantee, same place as its
      siblings, without a rule-provider module. Follows calls transitively within the file: verified by
      emptying the grandfather list, which a direct-text check and a one-hop check both survived.

## 6. Harness fidelity

- [x] 6.1 Replace the world harness's `rememberCoroutineScope()` with a remembered serial non-UI scope
      matching the shell's lane structure. Leave the forge harness alone — it composes no live core.

## 7. Truth maintenance

- [x] 7.1 Sharpen the three "Kotlin/Native has no `Dispatchers.IO`" comments in `IosDiscovery`,
      `PhotoKitCandidateSource` and `ClearRequested`: what is absent is a *public* API (it exists as
      `internal`), and name what would falsify the claim.
- [x] 7.2 Rewrite the two hops' rationale: they buy concurrency on a serial scope, not safety from the
      main thread. Keep the build-521 forcing proof attached to the decision it still supports.
- [x] 7.3 Correct the two comments that assert the scope is main — `PhotoSelectionSnapshotSource`'s
      "serial main" note and `SentryDiagnosticsReporter`'s "composition runs on the main thread" — to say
      what now holds (serial, not main).
- [x] 7.4 Update the laws digest in `CLAUDE.md` to match the rewritten law, and state what applies to the
      extension process, which has no UI lane.

## 8. Verification

- [x] 8.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green.
- [x] 8.2 `./gradlew architectureDiagrams` and commit any regenerated output.
- [x] 8.3 Drive the world harness headlessly via `:test:harness-driver` and confirm the status screen
      still renders and updates with presentation state produced off the UI thread. **Done**: create-event
      driven through the real command bundle on the non-UI serial lane; the screen re-rendered to the
      joined state ("YOU'RE INVITED / Lane Test"), so Compose observes state produced off the UI thread.
- [x] 8.4 **Operator step, not automatable**: exercised on the SE2 (iOS 26.5.2, dev build carrying this
      change) — share ×2, choosePhotos, openSettings and reconfigure all presented without hang, crash or
      error, and the screen kept rendering. Door 3 confirmed from the log: `→ tap.rename` … `PATCH → 200
      (427ms)` … `← tap.rename (453ms)` — the tap span now covers the backend round-trip instead of
      closing in ~1 ms. `tap.reconfigure (98ms)` likewise spans the album seam, a 122-candidate gallery
      walk and `arm.onProvision`, all on the composition lane with the UI alive throughout.
