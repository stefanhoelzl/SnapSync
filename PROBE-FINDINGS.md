# PROBE — MetricKit exit attribution

**Temporary record.** It belongs in `openspec/changes/<id>/PROBE-FINDINGS.md` once a change is
opened for this work; it sits at the root only because entering the OpenSpec flow is the user's
call and no change exists yet. The probe code it describes (`MetricKitProbe`, the rig `crash`
command) is temporary too, and is deleted with the change it informs.

**Measured:** 2026-08-25/26, iPhone12,8 (SE2), **iOS 26.6**, dev-signed sideload (Debug, `-Psnapsync.rig=true`),
Kotlin 2.4.0 platform klibs, Xcode 26.6 / macOS 26.5.2 on the ssh-mac runner.

⏰ **Re-measure at the next iOS major.** Evidence is one device, one point release, one crash. Every
count below is n=1. The klib-derived facts track the **Kotlin** version, not the installed Xcode.

---

## Why this probe existed

Bugsink **SNAPSYNC-23**: the app was abruptly killed twice in the background, mid photo-download
burst, and nothing anywhere recorded why — no Sentry event (the SDK cannot catch a SIGKILL, and its
watchdog heuristic excludes background kills by design), no TestFlight crash log, no `debug.log`
line (the process stops mid-line), no Apple aggregate (`productData: []`, two testers). Jetsam and
watchdog have the same log signature and imply different fixes.

`MXAppExitMetric` counts exits **by reason**, split foreground/background. The probe answers what
reading could not, **before** any port, model type, or feature is committed to.

---

## Answered

### 1. Kotlin can implement `MXMetricManagerSubscriberProtocol` — compiles AND links ✅

`class MetricKitProbe : NSObject(), MXMetricManagerSubscriberProtocol` overriding
`didReceiveMetricPayloads` / `didReceiveDiagnosticPayloads`.

- `./gradlew compileIosMainKotlinMetadata` green on Linux (the typecheck).
- `nm` on the shipped arm64 binary: `_kclass:app.snapsync.metrics.MetricKitProbe` — the native link.
- The class runs on device.

This discharges the law *"a platform-capability claim is settled by a compile, not by a symbol
table."* **No Swift shim is needed** for the `MX*` generation.

`otool -L` shows `MetricKit.framework` linked — but this is **not** evidence for the above on its
own: Sentry ships `SentryMetricKitIntegration` and links MetricKit itself. The Kotlin symbol is the
evidence.

### 2. `addSubscriber` is not refused ✅

```
22:05:31.840 [Info/metricKitProbe] [metrickit] armed; pastPayloads=0 pastDiagnosticPayloads=0
```

8 ms after process start. No refusal, no throw — unlike the PhotoKit precedent
(`setUploadJobExtensionEnabled` → `PHPhotosErrorAccessUserDenied` 3311, refused in both directions).
Arming in `SnapSyncRoot`'s own initialization is cheap and early enough.

### 3. `pastPayloads` is NOT a retroactive archive ✅ (corrects a shared assumption)

Apple's current wording: *"Returns an array of the daily metrics reports generated **since the last
allocation of the shared manager instance**."* Not the widely believed 7-day archive.

Measured `pastPayloads=0 pastDiagnosticPayloads=0` on **all four** arms, including 18 s and 30 min
after a real, OS-recorded crash.

**Consequences.** There is no on-demand accessor: a rig route reading `pastPayloads` buys nothing at
launch. And, combined with *"MetricKit starts accumulating reports for your app after calling
`shared` for the first time"* — which nothing in SnapSync had ever done — **the SNAPSYNC-23 window
was never open.** It did not age out; it never accumulated. No build shipped at any speed could have
recovered it.

### 4. ❌ A crash diagnostic did NOT arrive within 30 minutes

The load-bearing negative. Method:

| time (UTC) | event |
|---|---|
| 22:05:31 | probe arms, subscriber registered |
| 22:06:15 | rig `POST /device/crash` → `abort()` |
| 22:06:16 | OS records `SnapSync-2026-08-26-000616.ips` — `EXC_CRASH` / `SIGABRT` / `Abort trap: 6` |
| 22:09:04 | app relaunched, armed, `pastDiagnosticPayloads=0` |
| 22:09–22:34 | app **alive and subscribed** for 25 min, polled once a minute |
| 22:36:33 | SIGKILL + **cold launch**, `pastDiagnosticPayloads=0` |

`didReceiveDiagnosticPayloads` never fired. The crash was real and the subscriber was armed 44 s
before it.

**This falsifies the plan of manufacturing a watchdog kill to verify the chain "in minutes"**, and
it undercuts the design split that treated diagnostics as the prompt per-incident channel and
metrics as the daily heartbeat. Apple's *"Diagnostic reports arrive immediately in iOS 15 and
later"* does not mean what it appears to mean here.

Three explanations remain, **not yet separable**:

1. dev-signed builds receive nothing at all;
2. diagnostics are batched on roughly the metric cadence;
3. delivery is gated on device analytics-sharing, or on idle/charging conditions.

The analytics-sharing setting is **not readable headlessly** — no lockdown domain exposes it
(`pymobiledevice3 lockdown` has no `get_value`).

**The distinguishing experiment is already running**: the first daily metric payload, earliest
~22:05 UTC on 2026-08-26. A metric payload arriving with no diagnostic isolates it to diagnostics;
nothing arriving means dev builds are excluded and all verification moves to TestFlight.

---

## Found without looking

### 5. SnapSync was jetsam-killed on this device, hours before the probe existed 🔴

`JetsamEvent-2026-08-25-164509.ips` — 16:45 local, **573 processes**:

```
SnapSync      rpages=112,233   states=[active, frontmost]   reason=proc-thrashing
kernel_task     7,112          ← next largest, by 16×
```

`lifetimeMax=114,218` pages. Page-size units are unverified: ~438 MB at 4 KB pages, ~1.75 GB at
16 KB. Either way SnapSync dwarfed every other process and was killed for **proc-thrashing**.

Three things follow:

- the design's premise is **live and current**, not a one-off from 2026-08-20;
- this kill was **frontmost**, so it belongs to the **6-counter foreground** set
  (`memoryResourceLimit`), not the background 10. Both sets matter;
- the **573 processes** figure matches the SNAPSYNC-23 write-up exactly, suggesting a recurring
  device condition rather than a coincidence.

### 6. `JetsamEvent-*.ips` is an evidence channel MetricKit does not replace 🟡

These files sit on the device and pull over USB, carrying per-process page counts and kill reasons —
strictly richer than any `MXAppExitMetric` counter. Useless for a user in the field (no remote
route), valuable for operator-side investigation. Worth naming in the design as the thing MetricKit
is **not** a substitute for.

### 7. 26 extension SIGABRTs — observed, NOT attributed 🟡

26 × `BackgroundUploadExtension-*.ips`, all `EXC_CRASH` / `SIGABRT` / `Abort trap: 6`, between
21:55 and 23:48 local. That window overlaps another workspace's device session
(`lost-upload-acks`, verifying force-quit behaviour), so they are plausibly induced by it. **No
conclusion drawn.** Worth an independent look.

---

## Tooling defects hit along the way

### 8. The ssh-mac re-sign read the wrong file — fixed on `main` independently ✅

The runbook read `TEAM_ID` / `ASSOCIATED_DOMAIN` from `Config.xcconfig`; `deployment-configuration`
had moved them to the generated `Deployment.xcconfig`. Both resolved **empty**, so the app was signed
claiming `keychain-access-groups: [".app.snapsync.shared"]` (no team prefix, not a writable group)
and `associated-domains: [""]`. Install refused `0xe8008015` — which reads as a bad profile, not a
bad parse.

Same unusable-group failure the runbook's own warning describes, reintroduced from the opposite
direction. `main`'s `52fd2256 internal(runbooks): six corrections` fixes exactly this, hit
independently by another session hours earlier. **This branch is rebased onto it.**

### 9. The rig aborts the app when it cannot bind its port 🔴 still open

After the manufactured crash, the relaunch 18 s later could not bind 127.0.0.1:18099 (socket still
held). The rig logs the failure at `Error` — and then Ktor's accept job throws inside a coroutine
with no exception handler:

```
kfun:io.ktor.server.cio.backend.httpServer$acceptJob$1...
kfun:kotlinx.coroutines#handleCoroutineException(...)
(anonymous namespace)::terminateWithUnhandledException(ObjHeader*)
→ SIGABRT
```

So a rig build **kills itself** on a port collision instead of running without the channel. Dev-only
code, but it cost a full crash cycle here and reads as "the app crashes on launch".

---

## Still open

- **the gating question** — does a dev-signed build receive MetricKit payloads at all? (pending,
  ~22:05 UTC 2026-08-26)
- whether diagnostics ever arrive, and on what cadence
- whether analytics-sharing gates delivery
- extension exits: structurally unattributable (daily report cadence vs a per-invocation process
  lifetime), unchanged by anything measured here
