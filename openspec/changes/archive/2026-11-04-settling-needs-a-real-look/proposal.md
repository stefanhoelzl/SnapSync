# Proposal

## Why

A member's phone can say its share is settled without having looked at the photo library since the member's range
ended, which the spec forbids. Both cases are measured on today's code:

- **A walk that finds the library unreadable.** Here access was withdrawn while the walk was reading. The phone
  declared the share settled **with no photos in it**, although the member had a photo in range.
- **A walk that began before the end and finished after it.** The phone also declared the share settled.

If every other member is done, the event can then finish and its photos be deleted before such a photo was ever
shared.

A member who leaves an event after their range ended and rejoins it while the service cannot be asked what it
already holds can also never settle again. The service treats the rejoin as a new, unsettled share. The phone
remembers it already told the service it was settled, so it never says so again, and the event stays unfinished
until the latest deletion date.

Both are fixed together with phase 4.4c, which moves settling and the manifest publish into the core's layers and
makes "settled" something only a real look after the end can produce.

## What Changes

- A phone settles a member's share only from a look at what the member shares that **succeeded** and **began after**
  the member's range ended: the library under full access, the selection under limited access. A look that could not
  be made, or began earlier, leaves the share unsettled, and the next look tries again.
- A member who shares nothing has nothing to look at: their phone settles once their range has ended, as today.
- After a rejoin, the phone tells the service again what it shares and whether it is settled, whatever it told it
  before the leave.
- sync-status's wording follows what it shows: "everyone has shared" means every member still in the event has
  finished sharing. Whether they have received everything is event-lifetime's "done", which the screen does not
  claim. Nothing changes in the app here; the spec said more than the screen does.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-lifetime`: "A member's share is settled only after their own range has ended". A look that could not be
  made, or began before the end, settles nothing. A member who shares nothing settles at the end. A rejoined member
  settles again. Gains three scenarios.
- `sync-status`: "An ended event is marked without stopping sync". "Everyone has shared" requires every member still
  in the event to have finished sharing, not to be done.

## Impact

- The app's settling and manifest publish. This is phase 4.4c's scope: the walk → publish step, the extension's
  publishes, and the end of a wake's settle step. "Settled" becomes evidence minted only from a successful look
  begun after the member's range ended, or from a policy that shares nothing.
- The join advances the manifest version, so a rejoin republishes (`domain/data`, membership writes).
- The skip record keeps whether the last publish declared the share settled and how many photos it declared
  (`ledger.db` migration: two columns; the one existing record is dropped, which costs one republish).
- The upload extension acts on the service's answer that the event's photos are deleted, as the app does.
- No api change, no wire change.
- Tests: integration scenarios for an unreadable look after the end, a look begun before the end, settling under
  limited access, and a rejoin after the end while the listing fails; unit tests of the settle verdict.
- Ships inside the phase-4.4c pull request (label `bug`).
