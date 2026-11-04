# Proposal

## Why

A photo that was uploading when the member left an event can stay unshared for good after they rejoin it. Leaving stops
every upload in progress, but the phone remembers each such photo as "on its way". When the member rejoins and the app
cannot ask the service what it already holds — offline, or the service failing at that moment — it keeps what it
remembered of the event, and a photo remembered as "on its way" is never sent again: nothing is uploading it, and nothing
treats it as waiting. It breaks the promise that a photo is never lost on the way.

## What Changes

- At a join, a photo the phone remembers as "on its way" for the event being joined is treated as waiting to be
  uploaded, so the next pass sends it. A leave or a switch stopped every upload of that event, so nothing is uploading
  it.
- When the service can be asked at the join, nothing changes: what it holds becomes what the phone remembers, as today
  (requirement "A photo already in an event is not uploaded again for it").
- A photo the phone remembers as already uploaded stays uploaded: it is not sent again.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `delivery`: "A photo is never lost on the way" gains a scenario for the photo whose upload a leave stopped, after the
  member rejoins while the service cannot be asked what it holds.

## Impact

- The join-time load (`ShareSetLoad`, phase 4.4b's `UploadRowsReset`): where the listing fails and the joined event's
  rows are kept, their in-flight rows return to needing a job in the same transaction as the purge of other events.
- Unit tests of the rows' reset; an integration test of a leave mid-upload and an offline rejoin.
- Ships inside the phase-4.4b pull request (label `bug`).
