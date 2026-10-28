# Proposal

## Why

`event-album`'s scenario "A photo shared before the join arrives shortly after" still promises that such a photo
reaches the new album "without being uploaded again". That stopped being true with
`changes/archive/2026-10-06-per-event-storage-layout`: each event now holds its own bytes, so a photo shared to one
event and covered by the next is uploaded once per event. `photo-sharing` says so ("A photo in two events is shared to
each": it "may be uploaded again for it"). That change's delta touched only `photo-sharing`, so the two specs now
contradict each other. The integration suite found this while claiming `event-album`'s scenarios: the app uploads the
photo again for the new event and places it in the new album on its first pass, exactly as `photo-sharing` allows.

## What Changes

- The scenario keeps its album promise: the photo appears in the new album after the device's first pass over the
  library.
- It drops "without being uploaded again". Whether a photo is uploaded again for another event is `photo-sharing`'s
  outcome, and an outcome lives in one spec.
- No app behaviour changes. The app already does what the corrected scenario says.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-album`: requirement "Turning the album on gathers what is already there" — its scenario "A photo shared
  before the join arrives shortly after" no longer promises that the photo is not uploaded again.

## Impact

- `openspec/specs/event-album/spec.md` only. No code, API or stored-format change.
- The integration test that claims this scenario (phase 19, "claim: event-album") asserts the corrected outcome.
