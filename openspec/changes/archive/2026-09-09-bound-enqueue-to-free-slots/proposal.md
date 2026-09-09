## Why

Every upload cycle on the app-driven tier resolves up to **16** ledger rows to uploadable resources
in order to create at most **4** jobs — and resolves 16 even when **zero** slots are free and it will
create none. Each resolution is synchronous PhotoKit XPC on the app's single composition lane:
measured 54 ms for 16 keys against ≤4 creations (iPhone12,8 / iOS 26.6, 2026-09-09), where the same
call costs 11 ms for one key and 19 ms for three.

The bound is a shared constant serving two tiers whose limits are unrelated — the app tier's cap is
four concurrent transfers, the PhotoKit tier's is the OS's durable job queue — so it cannot be right
for both. It is priced in its own KDoc as *"asking for more than the platform will accept costs a
resolve, not a stage"*, which was written before anyone measured what a resolve costs, and before it
was established that a resolve is the one span in a cycle that cannot be interrupted.

## What Changes

- `BackgroundTransfer` gains **`freeSlots(): Int?`** — how many jobs this platform will accept right
  now, or `null` for a platform that will not say. The app-driven adapter derives it from the cap it
  already measures against its live task set; the PhotoKit adapter answers `null`, exactly as it
  already answers `fetchRetryJobs` and `drainTerminals` with a constant empty list.
- The cycle's enqueue pass bounds its work-source read by `freeSlots()`, falling back to the existing
  batch constant when the platform answers `null`.
- **Zero free slots is backpressure, not "no work".** An enqueue pass that resolves nothing because
  the platform is full SHALL report the cycle truncated, so it publishes `PROCESSING` and the pump
  re-arms. Without this the change introduces a stall: an empty read would report a drained cycle
  while `DISCOVERED` rows sit in the ledger, and a completion-triggered cycle would arm nothing.
- The free-slot count clamps at zero. The spec already states the cap does not bind across process
  death, so a relaunch can hold more live tasks than the cap allows and the difference can be
  negative.

Not a breaking change: `enqueueBatchSize` keeps its documented job — bounding an otherwise unbounded
read on a platform that cannot answer — and no existing requirement is removed.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-url-session-upload`: the producer's top-up read is bounded by what the platform will accept
  rather than by a fixed batch; the shared resolve seam gains a companion that reports free capacity;
  and an enqueue pass blocked by a full platform is stated to be truncation rather than absence of
  work.

## Impact

- `:domain` `ports/` — `BackgroundTransfer` gains one method.
- `:domain` `feature/upload` — `UploadCycle.enqueue` bounds its read and maps zero slots to truncated.
- `:adapter:ios:app-only` — `IosUrlSessionUploadPlatform` reports `cap - live tasks`, clamped at zero.
- `:adapter:ios:ext-safe` — `IosPhotoKitUploadPlatform` answers `null`.
- `:adapter:generic:fake` + `:test:world` — the fake transfer answers the port's new member, and the
  world gains a lever for it.
- No schema migration, no wire change, no `:app:*` change.

**What this does not claim.** It does not claim to fix the suspended-discovery-walk freeze
(`SNAPSYNC-16`). It shrinks the window in which a suspension can freeze a drain — that window is
uninterruptible PhotoKit time, and this removes the surplus — but the rate at which that freeze
occurs in the field on a post-fix build is **unmeasured**. One occurrence was observed on the bench
(2026-09-09, nine minutes, app unresponsive), on a foreground app idling into suspension rather than
a background wake overrunning its receipt. The freeze is a beneficiary here, not the reason.
