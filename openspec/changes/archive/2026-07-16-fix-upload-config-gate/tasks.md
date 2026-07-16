## 1. Establish what can and cannot be shown

The original plan opened with a failing test. That is not possible here, and the reason is the finding —
do not fake one. The app tier's decision consumes `EventConfig?`; to fail, a test must supply an input
meaning "unreadable", and **the type has no such value**. There is no wrong answer to observe, only a
question that cannot be asked.

- [x] 1.1 Record the demonstration that *is* available, in the change directory, as evidence for the
      archive: `UrlSessionUploadController.kt` reads `configSource.config.value`; `ConfigPorts.kt` states
      that port *"cannot express unreadable"* and is *"fatal for the reconciler"*; `event-link` requires the
      three-state read and says "the extension" throughout. Code, KDoc, contract — no test.
- [x] 1.2 Write the test that **cannot exist until the fix lands**: a cycle whose membership read reports
      unreadable invokes no reconciliation. It will not compile against today's `UploadCycle`. That is the
      point — record it as the acceptance criterion for §3, not as a red test.
- [ ] 1.3 **Optional, and the only thing that could settle reachability:** on a device, reboot with an
      in-flight background upload and do not unlock; observe whether the app is relaunched to deliver
      session events (design *Context / Reachability*). A "no" narrows this change's justification to
      duplication alone and should be recorded either way — including in the archive if not run.

## 2. The gate type (`:capability:upload`, no new module deps)

- [x] 2.1 Extend `CycleGate.Run` to carry `contribution: Contribution` and `saveToAlbum: Boolean` alongside
      its `UploadConfig`. Primitives plus `:domain:gallery`'s `Contribution` only — adding a dependency on
      `:capability:config` means the design took a wrong turn (design D2).
- [x] 2.2 Change `CycleGate.Skip` from `data object` to `data class Skip(val detail: String)` (design D6).
- [x] 2.3 Extend `cycleGate(...)` to accept the contribution and album flag for the `Run` case; keep its
      platform-free primitive signature.
- [x] 2.4 Update `CycleGateTest` for `Skip(detail)` — mechanical, the six existing assertions.
- [x] 2.5 Add the `deviceIdReadable = false` case to `CycleGateTest`, currently untested: the gate takes a
      single `configReadable` Boolean and no test covers the identity half of that roll-up.

## 3. `UploadCycle` owns the entry decision

- [x] 3.1 Add the required `readGate: () -> CycleGate` parameter. No default (design D1, D4).
- [x] 3.2 Move the three-state decision into the head of `run()`: `Skip` → log `detail`, touch nothing,
      return `COMPLETED`; `NotJoined` → leave-side reconcile, return `COMPLETED`; `Run` → existing
      contribution gate and phases.
- [x] 3.3 Move the leave-side `runCatching { reconcile(null) }.onFailure { warn }` in from the roots — one
      decision, currently written identically in three places.
- [x] 3.4 Make the cycle long-lived: derive `contribution`, `eventId`, and `saveToAlbum` from the gate
      result inside `run()` rather than from construction-time arguments. Confirm nothing depended on
      per-run construction (design risk: this partially reverses the direction-gate fix's D2).
- [x] 3.5 Replace the `engine` constructor argument with `engineFor: (UploadConfig) -> SyncEngine`, since
      the host and event are no longer known at construction.
- [x] 3.6 Add `UploadCycleTest` coverage for the entry gate: skip touches nothing; not-joined reconciles
      and creates no job; run proceeds. The existing 44 phase tests should need no behavioral change — if
      they do, the move is not behavior-preserving.

## 4. Required ports (design D4)

- [x] 4.1 Remove the defaults from `onDiscovery`, `suppressedAssetIds`, `albumExcludedAssetIds`, and
      `onBatchUploaded`. Every construction site stops compiling; that is the review.
- [x] 4.2 Remove `UrlSessionUploadController`'s own `albumExcludedAssetIds = { emptySet() }` default.
- [x] 4.3 At each newly-broken site, state the answer explicitly — including the empty ones. An empty
      answer is legal; an unstated one is not.

## 5. Shrink the roots to translation

- [x] 5.1 `UploadExtensionRoot`: replace the `process()` body with the gate lambda (config read + identity
      probe + `cycleGate(...)`, supplying the `Skip` detail string) and construct the cycle once.
- [x] 5.2 Reduce `process()` to its three irreducible concerns: `runBlocking`, `postLivenessNotification()`,
      and the pending→`PROCESSING` requeue. Delete the now-unused timeout constants, hook bodies, and the
      leave branch. Target ~6 lines.
- [x] 5.3 `UrlSessionUploadController`: change `deviceId: String` to a probe-able port so the tier can
      express "unreadable this cycle" (spec `ios-url-session-upload`).
- [x] 5.4 Give the tier the same gate lambda, replacing the `configSource.config.value` read — the
      two-state flow whose KDoc says it is "fatal for the reconciler".
- [x] 5.5 Reduce `runCycle()` to invoking the cycle. Delete its copy of the leave branch, the manifest and
      notify hooks, the cutoff/contribution derivation, and the `DEVICE_MANIFEST_TIMEOUT_MS` /
      `NOTIFY_TIMEOUT_MS` constants it copied from the extension without inheriting its OS runtime cap.
- [ ] 5.6 NOT DONE — untriggerable on device (needs a boot with no unlock). Verify the extension's skip log still emits one line carrying the read status and identity
      probe result (spec `ios-photokit-upload`).

## 6. `:test:world` composes rather than mirrors

- [x] 6.1 Add an `unreadable` lever to the world's membership so `CycleGate.Skip` is reachable — today the
      config cell is nullable and cannot express it.
- [x] 6.2 Rewrite `World.runUploadCycle()` to supply a `readGate` over the config cell and invoke the real
      cycle. What remains is the requeue rule, which is the OS-invoked tier's and should be named as such.
- [x] 6.3 Delete the `?: DEFAULT_CUTOFF` fallback — with the contribution arriving in `CycleGate.Run`,
      there is nowhere to invent a cutoff, and inventing one contradicts the project's central invariant.
- [x] 6.4 Supply the ports the world currently omits by default — `onBatchUploaded` in particular, whose
      omission is why `upload-completion-notify` has no integration coverage.
- [x] 6.5 Update the world's own tests for the new composition.

## 7. Integration coverage for the bug class

- [x] 7.1 `:test:integration`: an unreadable membership does not clear the joined-event marker, leaves the
      ledger and cursor intact, and creates no objects — the assertion no test could make before.
- [x] 7.2 `:test:integration`: a cleared membership still drives the leave path, so the fix did not turn a
      real leave into a skip.
- [x] 7.3 Confirm task 1.1's failing test now passes.

## 8. Verify

- [x] 8.1 `./gradlew build` — the canonical check.
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable iOS proxy.
- [x] 8.3 `./gradlew iosSimulatorArm64Test` on the ssh-mac loop, or via CI — the shared tests as
      Kotlin/Native.
- [x] 8.4 Exercise the full-stack harness (`:app:desktop:run`, counts emerge from the real source) across
      joined, left, and unreadable memberships. The `:test:harness-driver` HTTP driver makes this headless.
- [x] 8.5 On the SE2, forced onto the app-driven tier: confirm a normal joined cycle still uploads, and a
      leave still clears the marker. This is the **control**, and it is the only device evidence available:
      the state the change guards cannot be staged on a device (it needs a boot with no unlock, which no
      `dvt launch` can produce). A gate that skips everything is indistinguishable from a gate that works
      unless the happy path is checked — and a silently-skipped upload is this project's defining failure.
- [ ] 8.6 PARTIAL — app log pulled and the NotJoined/decline lines verified; the Skip line's forensics were never emitted (untriggerable), and the extension's own log is moot on this tier (deregistered). Pull `debug.log` from both processes and confirm the skip line's forensics survived the move.

## 9. Record what this change did not do

- [x] 9.1 Note in the archive that the Konsist choke-point guard is now writable — the roots no longer call
      `reconcile(null)`, which is what made it impossible before — and that it catches only the
      extra-decision direction; required parameters catch the missing-decision direction. **Recorded in
      `evidence.md` and design *Open Questions*.**
- [x] 9.2 Note that `:test:world`'s engine construction still omits the attestation token, so no world test
      exercises the attested upload path. **Recorded below.**

### Residual gaps, recorded deliberately

- **Reachability is unproven** (task 1.3 unrun). `BGProcessingTask` is ruled out by Apple's first-unlock
  guarantee; the background-`URLSession` relaunch path is undocumented either way. The change is a
  structural closure, not a demonstrated bug fix, and `proposal.md` says so.
- **The `Skip` log line is unverified on device** (5.6 / 8.6, partial). Triggering it needs a boot with no
  unlock; no `dvt launch` can produce that. Shape asserted in `CycleGateTest` only.
- **`:test:world` still omits the attestation token** from `engineFor`, so no world or integration test
  exercises the attested upload path. Untouched by this change; the on-device control covers it in
  production form (the SE2 upload was attested end-to-end).
- **The Konsist choke-point guard is now writable and was not written.** With the roots no longer calling
  `reconcile(null)`, *"every `.reconcile(` and `.createJob(` is inside `UploadCycle.kt`"* becomes
  expressible for the first time. Deferred on scope, as `2026-07-16-fix-upload-direction-gate` deferred it
  *"on scope, not on merit"*. Note it catches only the extra-decision direction; the missing-decision
  direction is what the required parameters catch.
- **`ios-app-shell`'s deferral requirement conflates file protection with Keychain accessibility**, and
  `handleBackgroundUrlSession` measures `protectedData.isAvailable()` into its log params then ignores it.
  Both real, both out of scope; see design *Open Questions*.
