# Proposal

## Why

The contract promises that a received photo whose sender filename is unknown still gets a SnapSync-marked name. No
received photo can have an unknown sender filename any more. The service refuses every upload that does not name its
file, and nothing else writes a photo's name. The service's store holds no photo without a name: the count on
2026-11-05 found 0 of 749 stored photos without one, and none from before the per-event storage layout. The
integration-test programme cannot claim the scenario, because no member can reach it. So the contract promises an
outcome that does not exist, and the code keeps a path that nothing exercises.

## What Changes

- The receiving-photos scenario "A photo without a sender filename is still marked" is removed. Every received photo
  carries the sender's filename with its mark ("The sender's filename is kept", unchanged).
- The receiving side stops producing a name from nothing. An empty sender filename is now a malformed input and is
  refused where it enters the receiving side:
  - in the event's photo list served by the service, it makes that read unusable, as a photo id that is no id does
    today;
  - in a download a phone recorded earlier, it settles that photo as one that cannot be imported, and the photo is
    never put into the library.
- The marking takes only a non-empty sender filename, so it has no empty case left.
- Unchanged: recognising photos that earlier builds marked, including the bare `snapsync-<token>.<ext>` form some
  libraries hold today. Those marks stay a stored format.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `receiving-photos`: requirement "Received photos keep full fidelity" loses its scenario "A photo without a sender
  filename is still marked". The requirement's text is unchanged; it never promised the unknown-name case. The
  requirement is renamed "Received photos arrive in full fidelity", because the OpenSpec CLI cannot drop a scenario
  from a requirement that keeps its name (design D5).

## Impact

- The marking of a received photo's name and its unit tests (the golden values for recognition stay).
- The read of the event's photo list and the read of a staged download, which now parse the sender filename, and the
  unit tests of each.
- No backend change, no stored-format change, no migration. No user ever saw the removed outcome: label `internal`.
