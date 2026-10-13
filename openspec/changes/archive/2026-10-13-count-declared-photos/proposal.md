# Proposal

## Why

Two members of one event read numbers that don't match. A sharer that has shared 7 of its 200 photos reads
"7 of 200 shared". At the same moment a receiver reads "6 of 7 received", as if 7 photos were all there is.
The receiver counts only photos it can already fetch. A photo becomes fetchable only once all of its files
have arrived. So the receiver's total trails the sharer's by everything still uploading, and it settles to
"Up to date" between bursts while 193 photos are still on their way. The status line adds to the confusion
on the sharer. It reads "Photos arriving…" while the device only sends: that line stands for a transfer in
either direction, but it words only one of them.

## What Changes

- The received total counts every photo the other members have **declared** they share, not only the ones
  that can be fetched yet. While the sharer reads "7 of 200 shared", the receiver reads "6 of 200 received".
  Declared photos of a member who left are not counted, because they will never arrive. Photos that fully
  arrived from a member who left still count, because they stay in the event. The total can go down: a
  declared photo that is deleted, falls out of the sharer's range, or belongs to a member who leaves first
  drops out of it.
- A new status line, **"Waiting for photos…"**, with a still download arrow. It is shown when the only
  remaining work is photos the other members have not finished sharing. This device has nothing it can do.
  The device's own waiting work still reads "Photos queued…" or "Waiting for Wi-Fi…". "Up to date" is never
  shown while others' declared photos are outstanding.
- The line for a running transfer becomes direction-neutral: **"Photos on their way…"** replaces
  "Photos arriving…" in both directions. The arrows already say which direction is working.
- After the event's end, the "waiting for N members" / "everyone has shared" note is shown beside
  "Waiting for photos…" as well as beside "Up to date".

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `sync-status`: the status-line wording ("Direction arrows show remaining work and live transfer"). What the
  received count means ("The joined screen counts what was shared and received" and the download side of
  "Progress counts only what this membership shares"). Where the ended-event note is shown ("An ended event
  is marked without stopping sync").

## Impact

- **api/**: the union read answers a new response header carrying the event's declared total: the current
  union plus the not-yet-complete assets declared by members still in the event. Additive. `api/openapi.yaml`
  and its generated `:domain:wire` change, and so do the backend's union route and its tests.
- **Device**: the union read stores the declared total beside the union cursor (a downloads.db migration).
  The download read-model subtracts the device's own last published manifest count and reports the
  not-yet-listed remainder separately. The presentation reducer gains the "waiting for photos" health and
  widens where the ended-event note is shown.
- **UI**: two strings (en + de) change or are added, and `AppStatusLine` gets one more label branch.
- **Mocks / tests**: `BackendMock`'s router answers the header. The Backend contract gains a clause for it.
  Integration tests cover the receiver's total and the new line.
- **Compatibility**: an app reading a backend without the header falls back to today's fetchable total.
  Older apps ignore the header. No shim is needed in either direction.
- **Screenshots**: the marketing `in_sync` shot is unaffected.
