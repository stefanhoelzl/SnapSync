## Context

The push-notification-infra change delivered the whole pipe but wired it inert: `/notify` fans out a
fixed **payload-free** silent push to an event's member devices, `apns-push-sender` posts
`{"aps":{"content-available":1}}`, and the app routes silent pushes to a `LoggingPushReceiver` that
only logs. Meanwhile `photo-download`'s `DownloadController.reconcile(eventId)` (discover foreign union
assets → enqueue background downloads → import) is triggered only on join/(re)provision and on
foreground entry — explicitly **no background poll**. So a co-contributor's uploads are invisible to a
backgrounded device until it next opens the app.

Two upload tiers both drive the same platform-agnostic `UploadCycle` (`:capability:upload`): the iOS
≥26.1 PhotoKit extension and the iOS 18–26.0 url-session app. The recipient's authority for "what
foreign photos exist" is the **event union** (`GET /event/<id>/files`), which lists an asset only when
its bytes are stored **and** its entry is in the backend **device manifest** — and that manifest is
PUT (via `UploadCycle`'s `onDiscovery` hook) **only at the end of a fully-drained cycle**.

## Goals / Non-Goals

**Goals:**
- Wake every member device to pull new photos shortly after a co-contributor's uploads settle, without
  requiring a foreground visit.
- Keep the trigger and the send logic platform-agnostic and unit-tested (JVM + simulator); nothing
  testable in the untested app shell.
- Preserve the endpoint's payload-free contract and the best-effort, failure-isolated posture of the
  whole push path.

**Non-Goals:**
- Guaranteed delivery. Silent pushes are OS-throttled and coalesced; foreground/join discovery remains
  the standing backstop. This is a latency improvement, not a new correctness guarantee.
- Embedding the file listing in the push (rejected — see Decisions).
- Server-side per-device delta tracking, sender exclusion, or any change to how downloads/imports run.
- Multi-simultaneous-event membership as a product feature (the receiver is guarded to a single active
  event; the eventId payload merely makes that guard precise and forward-compatible).

## Decisions

### D1 — Pure trigger, not an embedded listing
The push is a "poke": the receiver runs the existing `reconcile`, which reads the union authoritatively.
The listing is **not** embedded. Rationale, in order of force:
1. The union is the **complete** event (every asset across all devices), each resource carrying a
   ~600-byte presigned S3 URL — it overflows a ~4KB background push at a handful of photos and grows
   unbounded over an event's life. The `/notify` endpoint is stateless/payload-free and has no notion
   of per-recipient deltas, so it could only ever send the whole (over-budget) listing.
2. Silent pushes are coalesced/dropped — a payload can't be the source of truth; a catch-up union read
   is unavoidable regardless.
3. Presigned URLs expire (7 days) and only the backend can mint them; the self-heal path is a re-read.

So a union read is load-bearing no matter what, and embedding only adds a fragile, over-budget path on
top of one we must keep. Alternative considered — "backend lists once and fans the listing to all" —
still hits (1): the *complete* union is the thing that overflows, and computing a delta needs stateful
per-device tracking we deliberately don't have.

### D2 — Trigger lives in `UploadCycle`, gated on the drained cycle, after the manifest PUT
The notify is fired from `UploadCycle` (platform-agnostic `commonMain`, run by both tiers, tested), not
the `SyncEngine`. The engine is a pure state machine that performs no I/O and — decisively — **cannot
see discovery exhaustion**, so it cannot define "a batch settled"; a pure-ledger signal like
"pending == 0" fires once per cap-sized chunk mid-upload.

Firing point: at the end of a **`CycleResult.COMPLETED`** cycle, **after** `onDiscovery` (the
device-manifest PUT), when the cycle recorded **≥1 real completion** (Phase-2 `SUCCEEDED`, non-blank
key → `UploadCompleted`; re-acks excluded). This is not merely storm-avoidance: the backend manifest —
hence the union — refreshes **only** on a drained cycle, so notifying on any non-drained (`PROCESSING`)
cycle wakes recipients to a **stale union** with nothing new. Sequencing after the manifest PUT also
avoids the intra-cycle ordering trap (Phase-2 completions precede the Phase-3 manifest PUT).

```
UploadCycle.run():
  Phase 1 retries
  Phase 2 completions ─ completed++            (early-return PROCESSING → NO manifest PUT, NO notify)
  Phase 3 discover/create                       (early-return PROCESSING → NO manifest PUT, NO notify)
  drained: onDiscovery ⇒ manifest PUT ⇒ union fresh
           if (completed > 0) notify(eventId)   ◄── best-effort, bounded, non-throwing
           return COMPLETED
```

Wiring: an injected best-effort seam (`suspend () -> Unit`) that closes over the eventId at the
composition root — `UploadCycle` stays event-agnostic. Bound to `EventNotifier` (pure Kotlin,
mirroring `PushRegistration`): `POST <host>/event/<eventId>/notify`, no body, injected HTTP-client
seam, `runCatching` → log-and-swallow, **no retry**. Alternative (fire on every cycle with a
completion) was rejected: during a big upload it emits ~N useless notifies into a stale union plus one
real one at the drain.

### D3 — The push carries the eventId; the receiver is guarded to the active event
`apns-push-sender` bakes the eventId into the fixed payload
(`{"aps":{"content-available":1},"eventId":"<uuid>"}`); `/notify` supplies it from the route path (no
caller payload). The real receiver reconciles the pushed eventId **only if it equals the device's
active config event**, else no-op.

Why guard: **leave is local-only** — `LeaveEvent` clears local config but never touches storage, so a
left event's `events/<id>/device/<deviceId>.json` and its `config.json` (pushToken) persist, and the
backend keeps pushing that device for the left event forever. An unguarded reconcile of the pushed id
would silently re-pull a left event's *new* photos. The guard makes a left/other-event push a clean
no-op. The eventId (vs. blindly reconciling `config.eventId`) earns its keep precisely because a device
accumulates left-event memberships over time — each still pushing — and the id lets those be dropped
without waking a reconcile of the current event.

### D4 — Async receive seam; hold the OS fetch handler
The receive seam gains the eventId and becomes async (suspend/completion-shaped). The thin Swift
AppDelegate extracts `userInfo["eventId"]`, forwards it, and calls the OS `fetchCompletionHandler`
**only after** the receiver's synchronous portion (union read + enqueue) completes — reporting
`.newData` when work was enqueued. Background `URLSession` transfers and the import tail continue after
suspension via the existing background session + `BGProcessingTask` backstop. Calling the handler
immediately (today's log-only behavior) would risk suspension before anything is even enqueued.

### D5 — Module placement
- `EventNotifier` in `:capability:push` (co-located with registration/receiver; already has the Ktor
  HTTP-seam pattern). `UploadCycle` calls a generic seam, so the composition root binds
  `push.EventNotifier` into `:capability:upload` with no inter-capability compile dependency.
- The guarded receiver in `:capability:download` (it needs `DownloadController` + an active-event
  provider; depends on `:capability:push` only for the `PushReceiver` interface). `SnapSyncRoot` wires
  `ConfigSource` as the active-event provider.

## Risks / Trade-offs

- **Notify POST inflates the extension cycle / could hang** → fire it inline but wrap in a bounded
  timeout + `runCatching` (mirroring the existing in-cycle device-manifest PUT budget) so a slow/hung
  `/notify` never blows the OS upload-cycle budget. It must be awaited (not fire-and-forget) so the
  extension process doesn't die mid-POST, but bounded so it can't stall.
- **url-session tier manifest wiring** → RESOLVED in this change. Apply revealed the url-session tier
  (`UrlSessionUploadController`) did **not** write the device manifest (nor apply echo-suppression), so
  its uploads never entered the union — a pre-existing gap that would have made its notify useless. This
  change wires `DeviceManifestProducer` (`onDiscovery`) + `EventNotifier` (`onBatchUploaded`) +
  `suppressedAssetIds` into that tier's `UploadCycle`, bringing it to parity with the extension so its
  uploads appear in the union and notify meaningfully. Not device-verifiable here (no <26.1 device); it
  mirrors the extension root and is covered by the shared `UploadCycle` unit tests +
  `compileIosMainKotlinMetadata`.
- **Notify storm on big uploads that drain in waves** → each drained wave fires once; acceptable, and
  each wave's notify reflects genuinely newly-available assets.
- **Self-notify** → the endpoint fans out to all members including the sender (no exclusion); the
  sender's own reconcile skips own-device assets — a cheap no-op wake, no feedback loop (imports are
  echo-suppressed from re-upload, so importing never re-triggers notify).
- **Storage read-after-write lag** → a recipient reading the union microseconds after the manifest PUT
  could momentarily miss it (LIST propagation). Best-effort; the next notify or foreground reconcile
  catches it.
- **Delivery is best-effort** → OS throttling/coalescing means some pushes never arrive; foreground/
  join discovery remains the guaranteed backstop, so no photo is lost, only delayed.

## Migration Plan

Additive and backward-compatible; no data migration. Backend (`apns-push-sender` payload +
`/notify` dispatch) and app ship independently — an older app ignores the new `eventId` key, a newer
app tolerates its absence (guard treats a missing/mismatched id as no-op). Deploy backend first or
app first; the feature simply activates once both sides carry it. Rollback is reverting either side —
the receiver degrades to no-op, uploads stop notifying, and foreground discovery resumes as the sole
path (the pre-change behavior).

## Open Questions

- The in-cycle notify timeout is set to 8s (below the 12s device-manifest budget) on both tiers; tune
  against the observed OS cycle budget once device-verified.
- (Resolved) The url-session tier did not wire the device-manifest producer; this change wires it (+
  suppression + notify) — see Risks.
