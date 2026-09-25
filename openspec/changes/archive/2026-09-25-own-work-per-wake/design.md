## Context

**Today.** Every OS entry point (`domain/compose/AppEntries.kt`, implementing the inbound port `PlatformEntries`)
runs its flow inside an `OsReceipt` that holds the OS completion handler until the work finishes or a
`ReceiptDeadlines` constant expires (silent push 20 s, background-session events 20 s, BGTask 120 s), after which
the handler is released and the work "runs on". What each wake runs:

| wake | today |
|---|---|
| silent push | reloadConfig → attestation → download reconcile (union + plan + enqueue) → upload pump = full `UploadCycle` |
| download-session relaunch | adopt session → stage → PhotoKit import, awaited |
| upload-session relaunch (18–26.0) | `pump.onSessionEvents` = full cycle |
| heartbeat BGTask (18–26.0) | full cycle |
| download backstop BGTask | `importReady` |
| upload completion (foreground) | full cycle (walk included), coalesced by `BackgroundUploadPump` |
| foreground | everything, concurrently |

**Evidence** (all measured; sources in the decision record's scratch bench and the Bugsink dumps):

- *Field, iPhone XS / iOS 18.7.9, builds 605–609:* our 20 s receipt released on 45 % of download wakes and 66 % of
  upload-session wakes; iOS suspended the app ≤ 0.4 s after the release; a download wake delivers ~24 transfers =
  20–40 s of serial imports on that device, so batches were routinely left staged (92 on one day). The backstop
  found work in 0 / 108 runs. Push handler median ~1 s; its 4 overruns were a 101-asset plan (≈15.5 s of
  per-asset commits — fixed by stage 1's batched planning) or the upload cycle queued behind another cycle's walk.
- *Walk probe, SE2 / iOS 26.6.2:* background execution runs in the darwinbg task role (role 8), clamping the whole
  process ~9× (walk 0.26 → 2.3 s, CPU-bound). A background-URLSession cold relaunch starts at role 6 and drops to
  role 8 ~1 s in. PhotoKit calls made at `QOS_CLASS_BACKGROUND` are 6–7× slower (commits ~400 ms vs ~60 ms).
- *Every upload completion runs a full cycle:* 16 cycles in 30 s on the XS in background, the walk 74 % of each;
  101 cycles for 100 uploads on the SE2.
- *Extension (SE2 / iOS 26.6):* `notifyTermination` arrives ~55 ms after every **normal** return of `process()`;
  a call past assetsd's **60.0 s** timer is SIGKILLed with no signal; `ProcessInfo.performExpiringActivity`'s
  `expired=true` never fires (its assertion is created inactive — assetsd's assertion defines the lifetime). The
  extension's memory limit is **32 MB** (a wrapped cycle was jetsam-killed and relaunch-looped).
- *Walk memo probe:* `PHPhotoLibrary.currentChangeToken` + `isEqual` ≈ 2 ms in darwinbg vs a 1.3–2.0 s walk (4.5k–
  6.3k assets); held across idle and relaunches; changed on every create/album add; never equal while the library
  changed; 1–9 s of trailing changes after any write.
- *SE2 benchmark (baseline → stage 1):* the SE2 never reproduced the XS's cut-off imports — all 113
  staged-but-unimported keys in its runs were duplicates of a separate pre-existing enqueue bug (fixed
  independently). The expiry part of this change is therefore justified by the field data and Apple's contract,
  not by an SE2 gain.

**Apple's contract** (docs + DTS + WWDC): silent push "up to 30 seconds of wall-clock time", no expiry callback on
the handler; background-URLSession relaunch has no documented budget and DTS calls holding its completion handler
"a *really* bad idea" (a watchdog-backed assertion — overrun is a kill without warning), recommending
`beginBackgroundTask` for follow-on work; background time is per app, not additive per task; BGTask
`expirationHandler` = "cancel ongoing work … as short a time as possible" and a `BGProcessingTask` gets "minutes";
never base logic on `backgroundTimeRemaining`; cooperative expiry "influences future scheduling".

## Goals / Non-Goals

**Goals:**
- Each wake does the work its event is about, and releases the OS handler as soon as that is done.
- No self-chosen deadline anywhere; "time is up" comes only from Apple's signals.
- Everything else runs as expiry-aware opportunistic work, stopping cooperatively.
- Delete the download backstop BGTask.
- Spend less CPU in darwinbg: one walk per wake (not per completion), and none on an unchanged library.

**Non-Goals:**
- Batching photo imports into one PhotoKit commit (measured: −30–35 %/photo in darwinbg, but one bad file fails the
  batch and consumes the staged bytes of the good photos ahead of it — declined).
- A cooperative stop in the upload extension (no signal exists; see D8).
- `synchronous=NORMAL` on the SQLite stores; `wantsIncrementalChangeDetails=false` (declined).
- Batched extension acks (a stage-1 item dropped in the rebase; needs a new contracted seam member + device
  recording — separate follow-up).

## Decisions

### D1 — Own work per wake, then one shared tail
Each entry point runs only its own work (table in the proposal), after a shared prelude (reloadConfig →
refreshAttestation, <1 % of push time). The remaining work — ① import staged downloads ② upload top-up
(re-create retry-spent failures, enqueue known `DISCOVERED` rows) ③ discovery walk → manifest publish (full grant
only), looping to ② when ③ added rows — runs in **one process-wide, single-flight tail runner**. An overlapping wake
runs its own work outside the runner (so it never waits behind a walk — the XS push that waited 22.5 s behind a
walk), then joins the running tail. Order ① → ② → ③: staged bytes are paid for and are what the user sees; the walk
is the costly unbounded step.

- **Joining** a running tail means the runner makes exactly one more pass covering what the joiners need — the
  **union** of their scopes (a staged download needs ① alone, a freed slot ② alone; together ① and ②, never a
  walk) — the pump's "no request is lost" rule carried over; never a second runner, never a queue. A unit that
  throws fails the whole tail and every waiter; the ③ → ② loop runs ② once more, never ③ again.
- **A staging in a running process** (no relaunch delivered it) requests ① alone, detached: a staged photo changes
  nothing ② or ③ would see, and a burst stages one resource at a time. It re-arms nothing.
- **The membership transitions' arm** requests the full tail **detached**: a transition runs inside a flow (a
  join's `Provision`) or a tap, neither of which may await the tail, and no OS handler waits on it.
- **Foreground goes through the runner too:** its own work is the prelude + reconcile (union/plan/enqueue), the
  stored-upload settle, the staged-byte reclaim, and status and membership refresh; import, top-up and walk are the tail. `DownloadController.reconcile` stops draining
  imports itself — the drain is ① in every wake. The one import left outside the tail is the once-per-process
  interrupted-import sweep at host assembly (it settles rows a dead process left unconfirmed, then drains); it
  imports under the same per-asset claims as ①, so it can never import an asset twice. Foreground also holds the
  process's background time across its own work and its tail (D5), so a tail the member leaves running by
  switching away stops on Apple's signal rather than being frozen mid-unit.
- **The push's upload arm is no longer a receiver.** `flow/SilentPush` runs the download receiver alone; whether the
  wake joins the tail — only for the active event — is a tested guard (`PushTailGuard`) the inbound port's
  implementation asks after the flow returns (a flow answers nothing).
- **The heartbeat BGTask has no own work** beyond the prelude: it is the tail, under its BGTask (its purpose,
  catching new captures, is ③).
- **Under a limited grant** the tail runs ① and ② only, never ③; ② resolves from the in-memory snapshot and reads no
  library. A silent push therefore now reaches ② under a limited grant (accepted: uploads already-selected rows
  sooner, no read).
- **A tail cut short by Apple's expiry counts as work remaining (`PROCESSING`)** for the heartbeat re-arm policy, so
  a relaunch chain still re-arms. The outcome is computed over the latest pass that ran an upload unit (an
  import-only pass keeps the previous one's): the latest unit's `SKIPPED` wins, then a cut or any unit's
  remaining work is `PROCESSING`, then any `FAILED`, else `COMPLETED`. A download-session relaunch re-arms like an
  upload-session relaunch — only when work remains.
- Accepted consequences: ① is not direction-gated (as `importReady` is not today — an upload-only membership simply
  has nothing staged); a tail may run ③ twice when a joiner needs it (the memo makes the second cheap); a cold
  background wake under a limited grant tops up nothing, because its snapshot is unread until a foreground launch
  (the existing Unread rule).
*Alternatives:* strictly own work only (smallest handlers, but staged imports/uploads wait far longer on iOS 18);
a per-wake allowlist (no shared queue, more rules); own work preempting the tail (more machinery for a case D1's
"own work outside the runner" already covers).

### D2 — No separate "settle"; completions trigger top-up only
Terminal upload outcomes are already recorded by the transport in the URLSession delegate (the upload wake's own
work), so the cycle's "settle" reduces to re-creating retry-spent failures, which is top-up. The manifest projects
ledger rows in every state, so only discovery changes it → walk and publish are one unit. A freed slot therefore
needs ② only.

### D3 — Apple's signals only; delete `ReceiptDeadlines`
BGTask → its `expirationHandler`, **forwarded into the core** through `PlatformEntries` (today Swift completes the
task itself, racing the receipt); silent push and URLSession wake → `UIApplication.beginBackgroundTask`'s expiration
handler, behind a new outbound port named for the need (app-only adapter; UIKit). Never `backgroundTimeRemaining`.
The background task begins **no later than** the OS handler is handed over, so a wake's own work — and a URLSession
drain signal that never arrives, now that no deadline releases the handler — is covered by Apple's expiry.
*Alternative:* keep measured constants as a backstop — rejected: the XS evidence shows our constant is what cut the
imports.

### D4 — Cooperative stop at the next boundary, answered at once
*(Amended at apply, by the user's decision: the first wording waited for the unit in flight before ending the task.)*
On expiry the handler requests the tail's stop **and**, in the same call, releases any OS handler the wake still
holds and ends the background task (a `BGTask` is completed through the core's completion) — **at once**, never
waiting for the unit in flight. That unit (a PhotoKit change block, a ledger write, a store transaction) is not
cancelled: it runs on until it completes or iOS suspends the process. No new unit starts: the stop is checked
between units and between two items of an iterating unit (two imports, two job creations); an import the tail is
merely awaiting stops being awaited, and keeps its claim. This replaces "release, let the work run on". Every unit
is already a safe retry (staged bytes + store; ledger idempotent upserts; a stalled import keeps its claim).
Ending at once is Apple's/DTS's recipe (end the task inside the expiration handler), and it leaves nothing for a
watchdog to decide; waiting for the unit, as first written, risked exactly the watchdog termination the expiry
exists to prevent. A stop while no tail runs is a no-op, so a wake whose time is already up — a refused
`beginBackgroundTask` included, which the port reports as an immediate expiry — requests no tail. Because the
stopped tail reaches its end only when the unit in flight does, its re-arm and the second half of the expiry log
may land only when the process next runs.
*Alternatives:* hard cancel (an in-flight PhotoKit transaction cannot be recalled anyway); keep running (Apple:
"cancel or defer the work"); end only after the unit completes (the first wording — a watchdog risk for no gain,
since the unit is a safe retry either way).

### D5 — Release the OS handler after own work; the tail runs under `beginBackgroundTask`
Push and URLSession handlers are released as soon as own work is done (the URLSession one after staging, at
`urlSessionDidFinishEvents`, on the main thread). Background time is per app, so a background task costs nothing
and adds the expiry signal; it is begun no later than the handover and held across own work, release and tail —
and foreground entry takes one too. A BGTask holds `setTaskCompleted` until its tail finishes or its expiration
handler fires — then completes it at once (D4) (the BGTask is what grants the minutes). The handlers are held by
one `ports/` type, `OsCompletions`, with two release paths — after the own work (`releaseAfter`), or at once on
the OS's expiry — each handler released exactly once; an expiry release runs on the signal's own thread, which for
the background-time expiry is main (UIKit calls `beginBackgroundTask`'s expiration handler there), satisfying the
URLSession handler's main-thread rule. The upload mechanism holds no handler: its transport reports completions
and the drain to the core (`AppUploadEvents`), which owns the upload session's `OsCompletions`.

### D6 — The discovery walk is atomic under a stop
A stop abandons an in-flight walk; its decide stage writes nothing durable, so the next wake retries. The walk
unit records what it found and publishes, but creates no job: it hands the resources it read to the one top-up
that follows it. The upload cycle serialises its own units (a selection change's own work walks outside the
runner), so the decide-here-act-there split keeps one writer at a time. A partial walk
is never authoritative (it would retract photos it did not see). With stage 1 the walk is ~0.3 s darwinbg on the
SE2's event window, so chunking is not worth it.

### D7 — Delete the download backstop BGTask
0 / 108 field runs found work; leftover staged imports are drained by ① on any later wake and by foreground.
`BackgroundSchedulerContract`/`PlatformEntriesContract` clauses about the backstop go with it.

### D8 — Upload extension: no cooperative stop
Measured: the extension's only end is assetsd's 60.0 s timer (SIGKILL), with no signal (`notifyTermination` only
after normal returns; `performExpiringActivity` never fires). Its work after stage 1 is 0.6–1.4 s per `process()`;
every unit is a safe retry; the next invocation continues. The measured facts are recorded in `ios-photokit-upload`
so no one builds a stop on a signal that does not exist. The cycle's self-chosen 12 s manifest timeout
(`deviceManifestTimeoutMs`, in the shared `UploadCycle`) is removed on **both** tiers (a clock of ours, D3); the 5 s
per-request HTTP timeout stays — it bounds a request, holds no handler and ends no wake, and with no handler deadline
left it is what stops a stalled union read from holding the push handler until Apple's expiry.
*Alternative:* stop at a measured budget (e.g. 45 s) — rejected: still a clock of ours, for a case that does not
occur.

### D9 — Walk memo, app process only
An in-memory memo keyed on (`PHPhotoLibrary.currentChangeToken`, the membership's selection policy — a superset of the fetch predicate, which only the adapter builds — and the grant) reuses
the last walk's candidates while the token is unchanged. It reproduces exactly what a fresh full walk returns, so it
stays authoritative for deletion (amends the `always-full-enumerate` decision: "every walk is a full enumeration"
becomes "every walk's answer is a full enumeration"). The grant is part of the key so a memo never upgrades a
non-authoritative result (`IosDiscovery` is authoritative only under a full grant). **Not in the extension**: its
32 MB limit leaves ~12 MB headroom and it holds nothing across `process()` calls.
*Task:* verify that a change made outside the process (a Camera photo) moves the token — the only case the probe
could not produce headlessly — measured at apply: a Camera photo on the SE2 moved it (15/15 external changes on the
simulator), so the memo **serves**; shadow (walk every time, log a would-be-stale answer at Error) remains the
one-line revert.

### D10 — No event-album collection cache (considered, measured, declined at apply)
A cache of the event album's `PHAssetCollection`, dropped only by the library change observer, was proposed to
save the per-import re-fetch (4–8 ms of a ~60–100 ms commit). The risk that gated it was measured on the iOS 26.5
simulator (n=6): a change request against a **deleted** collection is non-nil, the commit succeeds, the asset is
created and its staged file consumed, and only the album add is dropped — silently. So a cache would have been
safe, but it would also have lost the per-import lookup's immediate "album no longer resolves" warning, for a
saving of ~0.1–0.2 s per 24-photo wake. Declined: the importer keeps fetching the album per import.

### D11 — Ledger counts refresh only while foregrounded
The pump's per-cycle `onCycleComplete` is replaced by a refresh after tail units only while the app is
foregrounded; foreground entry re-reads anyway.

### D12 — Change-driven push registration
Ask the OS for the token at every app entry (cold start in either state + each foreground entry — cheap per Apple,
and the only way the app learns a rotation; the ask moved from Swift to the Kotlin root's `onLaunch` and its
`didBecomeActive` observer); PUT only when (token, env, deviceId) differs from the persisted
last-registered value, plus on join and on a fresh credential. Every OS answer reaches the comparison — the token
source carries a `deliveries` flow beside its `StateFlow`, which would conflate an unchanged re-delivery away and
so never re-send a failed publish. The record is an App-Group file behind a `PushRegistrationRecord` port (dies
with the install, so a reinstall publishes at its first entry); an unreadable record publishes (the safe
direction), an unreadable device identity publishes nothing and waits for the next entry. Accepted: a registration the backend loses for an
unknown reason heals only at the next join, rotation or credential.
The registration subscription is installed on **every** cold start, foreground or background (today only on host
assembly) — by the shared host composition as it composes the graph, once per process, never by the root or by
host assembly — so a rotation or renewal learned in a background wake is published from that wake. Host assembly
itself becomes foreground-only: no background entry (push, transfer, task, token) assembles the host.
A fresh credential includes periodic token renewals (`DeviceAttestation.tokenChanged` fires on both), so those
re-PUT too — cheap and rare.
*Alternatives:* PUT on every foreground cold start as a healing net (declined — the known loss paths are already
healed by join and by a fresh credential); keep PUT-per-launch (0.5–3 s per background wake).

### D13 — Spec sync for the merged stage-1 commits
Wording only: `planAll`/`settledAmong`/`markAllEnqueued`; folded selection changes (a burst shares one read);
album lookup gated on the grant; rows created from the walk's resources; the USER_INITIATED PhotoKit read lane;
the in-memory attestation copy and `provideForRetry`; `SyncEngine.isWork`.

### D14 — Close the grant-flip gap in the limited-grant snapshot source
`limited-photo-access` requires that no snapshot built under a limited grant is emitted once the grant has become
full, but the selection lane checks the grant generation only on the baseline path: a change queued before the flip
is still enumerated and emitted (an existing test asserts it). Pre-existing (not from stage 1); fixed here because
this change rewrites the same path: the change path gets the same generation check, and the test is inverted.

### D15 — Standard output can never block the process (found during verification, pre-existing)
A process started by DVT/Xcode (every device harness here) has stdout/stderr on a pipe the device's `DTServiceHub`
drains; `NSLog` copies each line to stderr with one `writev` under a CoreFoundation-wide lock. When the reader
stops, the ~8 KB pipe fills and every logging thread wedges behind that write — the "every thread silent, rig
dead" hangs seen during the benchmarks (each after 7.76–7.99 KB of output; reproduced on the simulator with a
stopped reader, 2/2). Not caused by this change or stage 1 (the baseline build hung the same way); a SpringBoard
launch has `/dev/null` there. Fixed here because it wedged this change's device verification: both process roots
set `O_NONBLOCK` on fds 1 and 2 first, so a full pipe drops the stderr copy (`debug.log` and the unified log are
unaffected).

## Risks / Trade-offs

- [Handler released before the tail's work] → the tail holds its own background task; background time is per app
  (DTS), so nothing is lost; and the expiry handler stops it cleanly instead of a watchdog kill.
- [`beginBackgroundTask` expiry arrives "shortly before" with no number; handler must return in <~1 s on the main
  thread] → the handler flips the stop flag, releases what the wake holds and ends the task, and returns — it never
  waits for the current unit, which runs on until suspension as a safe retry (D4).
- [Deleting the backstop leaves a device that gets no push and is not opened with staged-but-unimported photos] →
  0 / 108 field runs found such work; any later wake's ① or foreground drains it.
- [The expiry gain is not reproducible on the SE2] → field-evidenced (XS) and Apple-contract-driven; measure after
  shipping via Bugsink dumps (the expiry log line).
- [Walk memo trusts a token for authority] → never observed equal across a change; trailing changes only cause
  extra walks; external-change verification is a task; the grant is in the key.
- [Tail starvation: a burst of own work keeps the tail from running] → the tail runs whenever no own work is
  pending and on every foreground; single-flight joins, not queues.
- [Change-driven push registration] → accepted residual: an unexplained backend loss heals only at the next
  join/rotation/credential.
- [QoS USER_INITIATED in background] → small, unmeasured energy cost; darwinbg caps the gain; accepted.

## Migration Plan

App-internal; no data migration. The removed BGTask identifier must also leave `BGTaskSchedulerPermittedIdentifiers`
and the Swift registration in the same build (registering an unlisted id, or listing an unregistered one, is an OS
error). A device updating mid-backlog keeps its staged bytes and store rows; the first wake's tail imports them.
Rollback = revert; the store schema is unchanged.

## Open Questions

- ~~PhotoKit's behaviour on a change request against a deleted collection~~ — measured (sim 26.5, n=6): request non-nil,
  commit succeeds, asset created, file consumed, album add silently dropped; D10's cache is safe. Device check pending.
- ~~Whether an external change moves `currentChangeToken`~~ — measured: yes (SE2 Camera photo; simulator 15/15);
  iCloud remains unmeasured.
- ~~Whether `beginBackgroundTask`'s expiry fires in the simulator's relaunch setting~~ — measured: yes, 27.4–28.5 s
  after entering the background, never in the foreground (sim 26.5, n=5).
- The XS/iOS 18 darwinbg ratio (field 10–25× vs SE2 ~9×) — informational.
