# Evidence

No test demonstrates this defect, and none can before the fix. This file is what stands in its place, so
the archive carries what was actually established rather than what was argued.

## Why there is no failing test

The app-driven tier's decision consumes `EventConfig?`:

```kotlin
// capability/config/src/commonMain/kotlin/app/snapsync/config/ConfigPorts.kt
val config: StateFlow<EventConfig?>
```

To write a failing test, an input meaning **unreadable** must be supplied. That type has no such value:
`null` is the only non-joined value and it means *absent*. This is not code answering a question wrongly —
it is a question that cannot be asked. A test asserting the correct behaviour cannot be written against
today's types, so it cannot fail; it can only fail to compile, which is not evidence.

That is itself the finding: the defect is **type-level**, not behavioural.

## What is established, by reading

1. **The app-driven tier acts on absence through the two-state port.**
   `app/ios/src/iosMain/kotlin/app/snapsync/ios/UrlSessionUploadController.kt:180-189`

   ```kotlin
   // Read the membership once: an unreadable config (…) means not joined, so this cycle uploads nothing.
   val membership = configSource.config.value
   val config = membership?.let { buildUploadConfig(it.eventId, host) } ?: run {
       runCatching { reconciler.reconcile(null) }        // ← clears the joinedEventId marker
           .onFailure { log.w(it) { "leave-side marker clear failed" } }
       return@invocation CycleResult.COMPLETED
   }
   ```

   The comment states the conflation as intent: *"an unreadable config … means not joined"*.

2. **The port it reads documents that this is wrong.**
   `capability/config/src/commonMain/kotlin/app/snapsync/config/ConfigPorts.kt:64-67`

   > **This port cannot express "unreadable"** — `null` here means "no config, as far as this process can
   > tell". That is fine for the UI, and **fatal for the reconciler**; see `ConfigReader`.

   And `KeychainConfigStore.kt:33-35`: *"Readers that act on the absence of a config must use `read`, not
   `config`."* This reader acts on absence and uses `config`.

3. **The collapse is real, not theoretical.** `KeychainConfigStore.reload()` sets
   `state.value = read().joinedOrNull()`, and `joinedOrNull()` maps **both** `ConfigRead.None` *and*
   `ConfigRead.Unavailable` to `null`. A failed read is indistinguishable from a leave at this port by
   construction.

4. **The other tier is guarded.** `UploadExtensionRoot.kt:261` calls `cycleGate(...)` on the three-state
   `read()`, and returns `COMPLETED` on `Skip` having touched nothing. `cycleGate` is shared, tested
   (`CycleGateTest`, 6 tests), and lives in `:capability:upload` — reachable from the app tier, unused by it.

5. **The contract covers only one tier.** `openspec/specs/event-link/spec.md:305-336`, *"An unreadable
   config is not an absent config"*, says "the extension" five times; all three scenarios open *"the
   extension's cycle"*. The app-driven tier is not violating this requirement — it was never in its scope.

## What is NOT established

**Reachability.** The window requires a boot with no unlock since (`AfterFirstUnlock` reads fine on a
merely locked device). Investigated, not settled:

- `BGProcessingTask` — **closed**. WWDC 2019 §707: *"We do guarantee that we won't start your task until
  the user first unlocks their device."* `runUploadHeartbeat` cannot reach the state.
- Background `URLSession` relaunch — **unknown**. No Apple documentation either way. The only inference
  available is that Apple states the guarantee explicitly for `BGTaskScheduler`, which would be redundant if
  no mechanism could run in that window. That is inference, not evidence.
- Silent push — moot, already behind `ProtectedDataGate`.

Task 1.3 is the experiment that would settle it: reboot with an in-flight background upload, do not unlock,
observe whether the app is relaunched to deliver session events. **Not run.**

## On-device verification (SE2, iOS 26.5.2, app-driven tier forced)

Build `0.1.0(1)`, Debug archive re-signed and sideloaded; PhotoKit extension deregistered first
(`arm.onProvision → photokit.stop`) so no appex could upload behind the tier's back.

**The control — an upload-capable membership still uploads** (task 8.5). Event `6a538d66` joined with
`direction: both`; cutoff clamped to the event's `startsAt` by the autoJoin gate, as `join-event` requires:

```
16:07:55  provisionEvent(eventId=6a538d66… named=true cutoff=2026-07-16T15:57:19Z)
16:07:55  gallery: enumerated 12 resource(s) since 2026-07-16T15:57:19Z
          (0 over-returned pre-cutoff, 4 suppressed, 6 origin-excluded) → N=6 own in-scope asset(s)
16:07:56  ← platform.createJob = LIMIT_EXCEEDED          (the in-flight cap)
16:07:57  ← platform.createJob = CREATED                 ×2
16:07:57  PUT /events/6a538d66/devices/4A2A…  → 201 (req=1538)   ← manifest WITH content
16:07:59  pump.onUploadCompleted                                  ← terminal success
16:07:59  POST /events/6a538d66/notify        → 202               ← after the manifest PUT
```

`onBatchUploaded` fires only when `completedThisCycle > 0`, so the `202` proves a genuinely-new completion
reached the ledger. The enrollment PUT was `req=63` (the empty manifest) against `req=1538` after upload —
the assets are in the union. Both re-plumbed hooks fire, in the required manifest→notify order.

**The decline path** (the direction gate, unchanged by this work but re-verified through the new gate):

```
15:53:42  → url-session.runCycle
15:53:42  cycle skipped — this membership contributes nothing (direction excludes upload)
15:53:42  ← url-session.runCycle = SKIPPED (3ms)
15:53:42  gallery: this membership contributes nothing → N=0 (no enumeration)
```

**The `NotJoined` path**, now reached from inside the shared cycle rather than the root:

```
15:58:30  → url-session.runCycle
15:58:30  no event configured but marker present — clearing the join marker
15:58:30  skipping cycle — no joined event / host
15:58:30  ← url-session.runCycle = COMPLETED (5ms)
```

**The dev device cannot reach the guarded state at all** (task 1.3, unperformable). The SE2 reports
`PasswordProtected: false`. With no passcode there is no data protection: no keybag, class keys available
from boot, `AfterFirstUnlock` items readable immediately, and `isProtectedDataAvailable` permanently true.
"Before first unlock" is not a state this device can enter. The app's own logs agree — **76**
`protectedData=` readings across three days, **`true` every one**, including overnight background wakes.

Two things follow, and the second matters more than the first:

- Task 1.3 cannot run here. Settling reachability needs a passcode-protected device *and* an instrumented
  build that logs somewhere unprotected — before first unlock the app container
  (`CompleteUntilFirstUserAuthentication`) is unwritable, so an app running in that window **cannot write
  `debug.log`**, and lockdown refuses `idevicesyslog`/the DDI tunnel. Silence would be indistinguishable
  from "never launched". Only a crash report (system-side) could prove a BFU relaunch, and only in one
  direction.
- **No device evidence in this change speaks to reachability**, in either direction. Every observation
  above came from a device on which the guarded state is structurally impossible. The `ProtectedDataGate`
  never deferring here is not reassurance; it is a null instrument. Do not read this log history as
  evidence the state does not occur in the field — on any passcode-protected phone, which is essentially
  every real user's, the state exists.

**What the device could NOT verify** (tasks 5.6 / 8.6, partial): the `CycleGate.Skip` log line carrying the
root's forensics. Triggering it needs an unreadable Keychain — a boot with no unlock since — which no
`dvt launch` can produce. The line's *shape* is asserted in `CycleGateTest`; its on-device emission is
unverified, and remains so. The extension's own `debug.log` was likewise not exercised: the extension is
deregistered on this tier by construction.

## Standing of the change

This is a **structural closure of an asymmetry**, not a demonstrated bug fix. It rests on the posture
`changes/archive/2026-07-14-fix-locked-device-keychain-access` already adopted knowingly:

> The attribute makes it improbable; the three-state read makes it impossible.

If reachability is later shown to be nil, decisions D1–D3 lose their safety argument and stand on
duplication alone. D4 (required ports) is unaffected either way.
