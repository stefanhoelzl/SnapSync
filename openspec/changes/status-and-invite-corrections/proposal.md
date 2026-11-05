# Proposal

## Why

Phase 4.9 audited the specs it touches against today's app and found two kinds of mismatch. In some, the spec says
something the app has never done, and the app is right. In others, the user decided the behaviour should change:

- turning receiving off would hide what has already arrived
- a QR code that has already been shown can reopen by itself
- a membership the phone can't read shows the create screen, as if the member had left

These ship with phase 4.9, which moves the status screen and the screen's door into the core's layers.

## What Changes

- **The status line's order, as the app shows it.** The still-reading line ranks just above "Up to date" or work in
  progress. A problem the app already knows, such as missing photo access, no network or a future start, shows at once
  rather than after a neutral line. The exception is a member who shares and receives nothing: the neutral line still
  shows for them before "Not sharing or receiving".
- **"Not receiving", with what arrived.** A member who switches receiving off sees that they're not receiving, and how
  many of the others' photos arrived, since those stay in their library. Sharing switched off is unchanged: the photos
  are withdrawn, so no count is shown.
- **A shown QR code stays until it's closed.** It no longer closes by itself when the phone stops being able to read
  the event's key. While the key can't be read, the screen still offers no new invite.
- **A membership that can't be read says so.** When the phone can't read whether it is in an event, it says so, says
  the member hasn't left, and offers to try again. It no longer shows the create screen in that case.
- **Where a failure message sits.** It is stated beside the action that failed (below Create on the create screen), as
  the create screen already does.
- **Date picking.** The one-dialog rules (one confirmation, the first tap sets the start, times kept) apply to the range
  a member shares. The event's own range is picked inline on the create screen, as `create-event` already describes.
- **A report the app wrote.** A text sheet's "unchanged text cannot be confirmed" rule excepts a description the app
  wrote for the member to send.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-status`:
  - "The joined screen shows one status line in a fixed priority": the still-reading line moves down the order. Gains
    one scenario.
  - "'Up to date' is never claimed before the app has looked": a known condition may show in place of the neutral line.
  - "The joined screen counts the photos shared and received": receiving switched off keeps the count of what arrived.
    Gains one scenario.
- `app-experience`:
  - "The first frame is never a guessed state": a known condition may show in the first frame. An unreadable
    membership says so and offers Try again. Gains one scenario.
  - "Failures are told calmly, never as a red field": the message is beside the action.
  - "Picking a date range is guided and only offers what is allowed": the dialog rules apply to the shared range.
  - "Text entry sheets stay usable while typing": the app-written report is excepted. Gains one scenario.
- `invite-link`:
  - "The joined screen offers the invite until the event's photos are deleted": a shown QR code stays until closed.
    Gains one scenario.

## Impact

- **Behaviour changes in the app:**
  - the received count while receiving is off
  - the QR code's lifetime while it is open
  - the new screen and Try again action for an unreadable membership
- **Spec-only corrections:** the other four describe what the app already does.
- **Change size:** no api change, no wire change, no stored-format change.
- **Tests:**
  - integration scenarios for receiving off with photos received, a shown QR across a key that stops and resumes
    being readable, and an unreadable membership with Try again
  - the existing tests that pin the four corrected behaviours get `@Verifies` against the corrected requirements
- **Delivery:** ships inside the phase-4.9 pull request (label `bug`), as its last commits.
