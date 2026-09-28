## Why

An event lives its full 30 days even when its date range ended long ago and every member already holds
every photo: the server keeps the photos, the invite keeps admitting strangers, and each member's app
stays "joined" to an event that has nothing left to do. Only a manual leave by *every* member ends it
sooner, and that is best-effort and never promised. The event should end on its own once it is done —
the photos leave the server as soon as nobody needs them, and every member is quietly back to no event.

## What Changes

- **Manifest final.** After the event's range has ended, each device declares — at its own next wake —
  that the list of photos it shares is complete. The photos themselves may still be uploading.
- **Closing.** Once every active member's list is final, the event **closes**: it admits no new member,
  and nothing about it changes any more — no rename, no change to what a member shares (range, sharing
  switch, and a deleted photo no longer withdraws), no change to receiving or the album. The joined
  screen drops the QR code, the share action, settings and rename; only Leave remains. Closing is final.
  **BREAKING** (mission): a guest who scans after the event closed can no longer join — the mission's
  "a guest who scans days late still joins" becomes "…until the event closes".
- **Done means leave.** A device whose event is closed and that has received every photo of it leaves
  the event on its own, in the background too, with nothing shown. There is no "downloaded everything"
  marker: the leave is the marker. When the last member has left, the event's photos are deleted.
- **One clock for silent members.** An event also closes, and its photos are deleted even with members
  still in it, 3 days after the later of its range end and the last photo that reached the event.
  A member silent that long is left behind: what it had not received, it never will. The 30-day lifetime
  stays the outer bound.
- **Retention becomes a two-sided promise.** Photos are deleted about 3 days after the event has
  finished, and never later than 30 days after it began to live (was: always at 30 days, sooner only
  unpromised).
- **Early deletion removes the photos, not the event record.** Until the 30-day date, the server still
  knows the event and answers "completed", so a member still in it leaves on that answer (foreground or
  background) instead of disbelieving an early "gone" until the deadline.
- **Leaves are retried.** Every leave stays instant for the member; a leave request that fails to reach
  the server is retried in the background until it lands (today: fire-and-forget, never retried).
- **One new wake.** The moment an event closes, every remaining member is woken silently once — the
  only wake not announcing a new photo.
- **Status.** After the range ends, a member whose own part is in sync sees how many members the event
  is still waiting for.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-lifetime`: the range still bounds what may be shared, but an ended event now **closes** once
  every member's list is final or at the clock; photos are deleted when the last member has left or at
  the clock, and at the 30-day date at the latest; the late-joiner promise holds only until closing;
  "deleted sooner, not promised" becomes a promise.
- `manage-membership`: the invite, rename and settings disappear from a closed event; the app leaves on
  its own once it has everything of a closed event, and on a "completed" answer, in the background too;
  a leave that could not reach the server is retried.
- `sync-status`: the joined screen of a closed event carries only the name, the status and Leave; after
  the range end the member sees how many members the event still waits for.
- `photo-sharing`: once the event is closed, a member's shared photos are fixed — deleting one from the
  library or deselecting it no longer withdraws it.
- `receiving-photos`: the one silent wake that announces no new photo — the event closing.
- `join-event`: joining is refused once the event is closed (a late guest joins only until then). The
  join screen's layout and retention wording are being reworked separately and are not touched here
  beyond the refusal.
- `event-site`: an event whose photos were deleted early reads as an expired link.
- `privacy-security`: the notification token is also used for the one closing wake.

## Impact

- **api/**: manifest publish carries a "final" flag; event `closed` state (derived or stamped) and the
  refusals it implies (join, rename, a manifest change to a closed event); push fan-out on close; nightly
  sweep deletes on the clock and on emptiness as data-only (row kept until the deadline, then dropped);
  a distinct "completed" answer on event reads for a kept row; leave route idempotent for retries.
- **domain/**: finality decision in the upload feature (range ended + policy set fully declared); an
  auto-leave in the membership feature driven by "closed + everything received" and by "completed";
  a retried leave request; status read-model for "waiting for N members"; the joined screen's closed
  variant.
- **Specs + mission**: the eight capabilities above; the mission paragraph in `openspec/config.yaml` and
  its copy in `CLAUDE.md`; `docs/architecture.md` (lifecycle, sweep, manifest); the Privacy Policy on
  `site/` (how long photos are kept).
- **Named future — concurrent multi-event membership**: an auto-leave ends only the completed event's
  membership; nothing here deepens the single-membership assumption.
