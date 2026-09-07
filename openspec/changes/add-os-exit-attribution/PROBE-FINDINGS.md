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

### 4. Delivery is ~24 h later, one-shot, and durable — NOT "immediately" ⚠️ REVISED

**This finding was recorded backwards on 2026-08-25 and is corrected here.** The first pass measured
30 minutes of silence after a real crash and treated it as possibly fatal to the design. It was not:
the payloads arrived, a day later, on the first launch of a subscribing build.

Timeline across both days:

| time (UTC) | event |
|---|---|
| 2026-08-25 22:05:31 | probe arms, subscriber registered |
| 22:06:15 | rig `POST /device/crash` → `abort()` |
| 22:06:16 | OS records `.ips` — `EXC_CRASH` / `SIGABRT` |
| 22:09 – 22:36 | app alive + subscribed 25 min, then a cold launch. **Nothing delivered** |
| 22:44 | a **third** crash (pid 14748), unobserved at the time |
| 2026-08-26 ~11:30 | another workspace installs a build **without** the probe |
| 22:10 | 8 process starts that day, **no arm line** — the probe was gone |
| 22:12:27.499 | probe build reinstalled and launched, arms |
| **22:12:27.796** | **`didReceiveDiagnosticPayloads(count=1)`** — 297 ms after arming |
| **22:12:27.861** | **`didReceiveMetricPayloads(count=1)`** — 362 ms after arming |
| 22:14 / 22:15 / 22:15 | three further launches — **nothing** |

**What this establishes.**

- **Dev-signed sideload builds DO receive MetricKit payloads.** The gating unknown is closed.
  The payload says so itself: `"isTestFlightApp" : false`.
- **Delivery is one-shot per payload**, on the *first* launch of a subscribing process after the
  payload exists. Three subsequent launches delivered nothing.
- **Payloads are durable across a non-subscribing build.** A build without the probe sat on the
  device for a day; the payloads were still delivered when a subscriber returned. This is Apple's
  *"any previously undelivered daily reports"* holding in practice, and it means a missed launch
  delays attribution rather than losing it.
- *"Diagnostic reports arrive immediately in iOS 15 and later"* does **not** mean minutes. The
  observed lag from crash to delivery was **~22 hours**, and the diagnostic arrived in the same
  breath as the daily metric payload — so in practice both channels run on **one ~daily cadence**.
  The two-tempo model (diagnostics prompt, metrics daily) does not survive this measurement.
- `pastPayloads` / `pastDiagnosticPayloads` read **0 at every arm**, including the arm 300 ms before
  a delivery. They are not an inspection route.

### 4a. What a crash diagnostic actually contains

```
exceptionType: 10 (EXC_CRASH)   exceptionCode: 0   signal: 6 (SIGABRT)
terminationReason: null
pid 14748 · appVersion 0.1 · appBuildVersion 1 · iPhone OS 26.6 (23G71) · iPhone12,8 · arm64e
```

- **`terminationReason` is null** for a plain SIGABRT. The human-readable string is not always
  there, so a design that leans on it must treat absence as normal, not exceptional. The three
  scalars are what you reliably get.
- Only **one** crash diagnostic was delivered, though at least three SnapSync crashes occurred in the
  window — and it was for the 00:44 crash, **not** the deliberate 00:06 abort. Whether MetricKit
  coalesces, samples, or simply had the others still queued is **not established**.
- The payload window is a **point**: `timeStampBegin == timeStampEnd == "2026-08-26 00:44:00"`.

### 4b. 🔴 The call stack is 314 KB — this breaks the plan to log it verbatim

One crash diagnostic: **314,196 bytes**, 19 thread call stacks, 351 frames,
**20 distinct `binaryUUID`s**.

Two consequences, both design-changing:

1. **The scrub collision is real, as predicted.** Those 20 `binaryUUID`s are UUID-shaped, and
   `crash-reporting`'s content-blind rule would replace every one with the redaction marker —
   destroying exactly the field offline `atos` symbolication resolves against.
2. **Writing it verbatim to `debug.log` does not work.** At 314 KB per crash, ~32 crashes fill the
   10 MB roll, and a single one would dominate the 700 KB diagnostic-dump budget and crowd out the
   log tail that dump exists to carry. The earlier suggestion to "write the diagnostic verbatim to
   `debug.log`, scalars to the reporting channel" holds **only for the scalars**. The call stack
   needs a deliberate decision of its own — summarise, drop, or truncate — and cannot simply ride
   an existing channel.

### 4c. ~~`applicationExitMetrics` has still NOT been observed~~ — **RESOLVED 2026-08-29, see §5**

The delivered metric payload carried **only** `diskSpaceUsageMetrics` — no exit metrics, no CPU, no
memory:

```json
{ "diskSpaceUsageMetrics": {...}, "timeStampBegin": "2026-08-26 11:09:50",
  "timeStampEnd": "2026-08-26 11:09:50", "appVersion": "0.1",
  "metaData": { "isTestFlightApp": false, "osVersion": "iPhone OS 26.6 (23G71)",
                "deviceType": "iPhone12,8", "bundleIdentifier": "app.snapsync", "pid": -1 } }
```

Its window is also a **point**, not 24 hours. This is consistent with Apple's *"Some metrics
originate from different system sources and arrive in a separate payload"* — but it means **the
central object of this whole design has not yet been seen on a device.** Everything about
`MXAppExitMetric`'s content, cadence, and window remains klib- and documentation-derived only.

**Answered on 2026-08-29 — see §5 below.** The payload observed on the 26th was a disk-space-only payload from a different system source; the daily aggregate carrying exit metrics is a separate payload with a true 24-hour window.

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

- 🔴 **`applicationExitMetrics` has never been observed on a device** — the central object of the
  design. Its real cadence, window and content remain documentation-derived
- whether the other two crashes were coalesced, sampled, or merely still queued
- what `terminationReason` holds for a **watchdog** kill (it is null for SIGABRT), which is the
  string a design would lean on to separate watchdog from background-assertion timeout
- ~~does a dev-signed build receive payloads~~ — **answered: yes** (`isTestFlightApp: false`)
- ~~whether analytics-sharing gates delivery~~ — **moot**: delivery works on this device as configured
- extension exits: structurally unattributable (daily report cadence vs a per-invocation process
  lifetime), unchanged by anything measured here


---

## 5. `applicationExitMetrics` — OBSERVED ✅ (2026-08-29)

Three and a half days after arming, a probe build was reinstalled and launched. One callback pair
delivered **5 metric payloads and 12 diagnostic payloads** at once — everything queued since the 27th,
across two intervening reinstalls of builds that carried no probe.

### The exit metrics, verbatim

Payload with a **true 24-hour window** (`2026-08-28 00:00:00 .. 2026-08-29 00:00:00`):

```json
"applicationExitMetrics": {
  "backgroundExitData": {},
  "foregroundExitData": {
    "cumulativeMemoryResourceLimitExitCount": 1,
    "cumulativeAbnormalExitCount": 6
  }
},
"memoryMetrics": {
  "peakMemoryUsage": "99409 kB",
  "averageSuspendedMemory": { "averageValue": "21561 kB", "standardDeviation": 21282.05, "sampleCount": 4 }
}
```

The 27th's window carried `"backgroundExitData": {}, "foregroundExitData": {}` and
`peakMemoryUsage: 174081 kB`.

**A real memory kill was attributed.** `cumulativeMemoryResourceLimitExitCount: 1`, foreground — the
same class as the `proc-thrashing` jetsam in §5-of-the-earlier-record. The premise of the whole design
is confirmed end to end: the OS did name the reason, and it reached the app.

### 🔴 5a. Zero-valued counters are OMITTED from the JSON, not reported as 0

`foregroundExitData` carried **two** keys, not six. `backgroundExitData` is `{}`, not ten zeros.

This matters for the "dump the payload's own JSON verbatim so a counter Apple adds appears for free"
idea. It still holds for *new* counters, but the JSON alone **cannot distinguish "this counter was
zero" from "this OS does not have this counter"** — an absence-is-never-silent problem living inside
the representation. The **Kotlin property accessors do not have this problem**: they are declared
non-null `ULong` and return 0. So a design that reads named properties gets a total function; one that
forwards JSON gets a partial one. That is an argument for reading the properties and pinning the set,
against the earlier suggestion that verbatim JSON dissolves the need for a vocabulary pin.

### 5b. There are two payload SOURCES, with different window shapes

| payload | window | carries |
|---|---|---|
| daily aggregate | **true 24 h** (`00:00:00 .. 00:00:00`) | exit · memory · cpu · gpu · disk IO · launch · responsiveness · network · display · location · cellular · signpost |
| disk-space | **a point** (`t .. t`) | `diskSpaceUsageMetrics` only |

Of 5 delivered payloads, 2 were daily aggregates and 3 were disk-only. **This is why the 2026-08-26
observation saw only `diskSpaceUsageMetrics`** and wrongly suggested exit metrics might never appear —
that was simply the wrong payload. A design must not assume one payload per day, nor that any given
payload carries exit metrics: `applicationExitMetrics` is nullable and frequently absent.

### 5c. Durability confirmed at three days and across probe-less builds

Payloads for the 27th, 28th and 29th were all delivered in one batch, having survived two reinstalls
of builds with no subscriber. Apple's *"any previously undelivered daily reports"* holds strongly:
**a missed launch, or a build without the integration, delays attribution rather than losing it.**

### 🔴 5d. Logging diagnostics verbatim DESTROYS the log — measured

One delivery of 12 diagnostic payloads wrote **15,156,294 bytes**:

```
debug.log.1   11,118,442 bytes   (rolled)
debug.log      4,037,852 bytes   (current)
callback duration: 18,078 ms
```

Consequences, all measured rather than projected:

- it **blew the 10 MB roll in a single delivery**;
- it **destroyed the prior log history** — the rolled sibling now begins at 2026-08-28 20:01, and
  everything older is gone;
- it blocked for **18 seconds** inside the callback.

So the earlier suggestion — *"write the diagnostic verbatim to `debug.log`; it is un-redacted,
size-bounded, and rides the next operator dump"* — is **not a tradeoff but self-destruction**: it
obliterates the diagnostic channel it was meant to feed, and takes the operator dump's log tail with
it. **The call stacks must not reach `debug.log` at all.** Only the scalars can.

This also re-frames §4b: the problem is not 314 KB per crash, it is 314 KB × however many payloads
the OS has queued — which was **12** after three days on a device that crashes during development.

### 5e. Delivery timing, restated

The `didReceive*` callbacks fired ~1 s after `addSubscriber`, on the first launch of a subscribing
build. Confirmed again: delivery is prompt **relative to launch**, and arbitrarily delayed relative
to the events described.

---

## 6. The population this actually serves (Bugsink, read 2026-08-29)

The issue statement framed this around SNAPSYNC-23 — one background kill, unrecoverable. Reading the
board shows a larger, still-live population that the design serves *first*.

### `SNAPSYNC-1 · WatchdogTermination` — open, 40 events, the biggest issue in the project

Sentry's own message text is the whole problem, stated by the vendor:

> *"The OS watchdog terminated your app, **possibly because it overused RAM**"*

Sentry cannot distinguish a watchdog kill from a memory kill. That is exactly the SNAPSYNC-23
question, and it has been accumulating since 2026-07-21.

### What the 40 events really are

```
2026-07-21 08:58   iPhone12,8  build 519
2026-08-01 09:06   iPhone11,2  build 542   ← the XS tester, iOS 18.7.9
2026-08-03 07:15   iPhone11,2  build 542   ←   (SNAPSYNC-23's own device)
2026-08-06 00:41   iPhone11,2  build 542   ←
2026-08-10 01:03   iPhone17,1  build 605   ← a third device
2026-08-25 19:50:19 ┐
… ×14, ~10 s apart  │ iPhone12,8  build 675  ← ONE crash loop, ~3 minutes
2026-08-25 19:53:09 ┘
2026-08-25 21:30    iPhone12,8  build 687
```

- **~7 incidents, not 40.** Fourteen of the events are a single three-minute relaunch loop.
- **20 of 40 are `environment: development`** — our own dispatched builds on the SE2. Only 20 are
  production, across **three** real devices.
- **All 40 carry `in_foreground: true`.** Measured, not inferred: Sentry's watchdog heuristic excludes
  background terminations by construction, so SNAPSYNC-23's case is not under-represented in this
  population — it is **absent from it entirely**.
- No `App Hanging` issue exists anywhere in 35 issues spanning July–September, so sentry-cocoa's
  app-hang tracking is not producing events here. The MetricKit hang histogram duplicates nothing.

### Three consequences for the design

1. **The immediate payoff is FOREGROUND.** Six production incidents across three devices in five
   weeks, where the only account is "watchdog, possibly RAM". The foreground exit counters
   (`cumulativeAppWatchdogExitCount` vs `cumulativeMemoryResourceLimitExitCount`) partition exactly
   that population. The design pays off against data that **already exists**, not only against the
   next background kill.
2. **The daily window deduplicates storms, for free.** That 14-event loop would arrive as one
   window carrying `watchdog: 14` (or `memoryResourceLimit: 14`) — a single threshold event naming
   the reason, instead of fourteen undifferentiated Sentry events. The aggregation accepted as a
   limitation is also a virtue.
3. **Crash diagnostics look weak, twice over.** The counters name the reason; Sentry already catches
   everything except `SIGKILL`; and a `MXCrashDiagnostic` for a SIGKILL carries a null
   `terminationReason` (measured) so it says *less* than the counter does. Both the duplication
   analysis and this population argue against reading `MXDiagnosticPayload` at all.
