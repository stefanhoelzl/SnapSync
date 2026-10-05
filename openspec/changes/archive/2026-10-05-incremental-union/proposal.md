# Proposal

## Why

Every read of an event's photo list returns the whole list, and every listed file carries a link the server
signs anew on that read. Each photo that arrives wakes every other member, and each of them then reads
everything again. So the traffic, the server's signing work and each phone's background time all grow with
roughly the square of the event's photo count. A 10-member event of a few thousand photos turns every push
into megabytes of mostly discarded data, inside a background wake the OS keeps short.

## What Changes

- **Stable download links instead of signed ones in the list.** Each photo file gets a stable address on
  SnapSync's service, which redirects to a freshly signed storage link when a download starts. The list no
  longer carries signed links: the app and the event page build the address themselves. Released app
  versions keep receiving a link per file (now the stable address), so they keep working unchanged.
- **Reading only what is new.** The list can be read from a position (a cursor): the answer holds only the
  photos that became available since, and hands back the next position. A push says which position it
  announces, so a device that is already past it reads nothing. A device reads the whole list when it is
  opened (the user is waiting, and it heals anything a partial read missed), when it joins, when photo access
  becomes usable, when it starts receiving after a settings change, for the end-of-event leave check, and
  whenever it holds no position.
- **Withdrawn photos stop downloading.** A full read drops photos no longer in the event that the device has
  not received yet, so it stops fetching them. A photo that comes back is fetched again. Copies already
  received are kept. This is what `photo-sharing` already promises; the code now keeps it.
- **A record of the event's photo list.** The service keeps, per event, when each photo became available or
  was withdrawn, and every read of the list: by which device (only an app that proves it is genuine) and why,
  or that a browser read it, with nothing identifying the browser. The record is deleted with the event.
- **Single-photo links stop expiring on a clock.** A link to one photo works while that photo is part of the
  event and the event's photos still exist. It names the event, so it grants what the invite link grants.
  **BREAKING** for the current promise that such a link stops working within 7 days.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `privacy-security`: a link to a single photo no longer expires within 7 days. It works while the photo is
  in the event and carries the event's key. A web visitor's read is recorded, with nothing identifying them.
  New promise: what the service records about reads of an event's photo list, and that the record is
  deleted with the event.

## Impact

- **api/**: a new download redirect route; `/files` gains `urls=false` and `cursor=`, a cursor response
  header, optional token verification and a trigger header; a new per-event log table plus its migration,
  written at the two existing "the list gains a photo" points, at publish removals and on every read;
  the silent-push payloads (APNs and FCM) carry the announced position.
- **App (both platforms)**: the union read service and backend port (`urls=false`, cursor, trigger, token),
  a per-event cursor store, the download planner building redirect URLs, pruning on full reads, the push
  receiver skipping when already past, the read triggers wired per caller.
- **site/**: the event page reads with `urls=false` and builds the URLs; the Privacy Policy describes the
  recorded reads.
- **Store metadata**: the App Store privacy answers and Play's Data safety form are reviewed for the recorded
  reads.
- **Tests**: a new `DownloadContract` clause, "a redirect is followed to the body" (already drafted in the
  working tree, measured on the Android emulator and the SE2), the backend contract's union clauses, the
  JVM rig's integration tests.
- **Compatibility**: released apps and the old shape keep working until the minimum app version passes the
  first version that reads with `urls=false`. The `url` field is removed after that.
