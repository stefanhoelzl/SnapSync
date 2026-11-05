# Design

## Context

See proposal.md, "Why". This change ships inside phase 4.9, which restructures the status screen and the screen's
door. The class-by-class target state is the phase's review document; this design records only the decisions behind
the three behaviour changes, and why the four corrections side with the app.

The app today:

- **Status priority.** The still-reading line ranks last for every member but one who shares and receives nothing. A
  presentation test pins it: "permission outranks a loading snapshot".
- **Receiving off.** The received count shows `Off` only once nothing was ever received. The download total counts
  imported photos, and turning receiving off keeps those.
- **The QR code.** It is hidden while the invite has no readable key, but the "shown" state stays underneath. When the
  key returns, the QR code reappears with nobody asking.
- **An unreadable membership.** It reads like no membership, so the create screen is shown. This can only happen when
  this process has never read the membership successfully and the foreground's re-read also failed. Once a read has
  succeeded, a later failed read keeps it.
- **Failure message.** It sits below Create, where the scan hint was. `create-event` already says so.
- **The create form's range picker** is inline, with no dialog and no confirmation. Its first tap places the last day,
  because the start is preset.
- **The seeded report sheet** can be confirmed unchanged, which `privacy-security` already describes.

## Goals / Non-Goals

**Goals:**
- The main specs say what the app does wherever the app is right.
- The three changed behaviours are each pinned by an integration scenario.

**Non-Goals:**
- The direct `bug` fixes in phase 4.9 that need no spec change. Those are already what the specs say, such as the arrow
  pulse, the rename dialog, and "Waiting for Wi-Fi…".
- Keeping counts for a deleted event across a process restart.

## Decisions

### D1. The app's status order is the right one

A condition the app already knows — no access, no network, a future start, the key lost — is something the member can
act on, and it needs no read of what is shared. Showing the neutral line first would hide it for as long as reading
takes, which is seconds on a large library under full access, and then swap it out. What "still reading" exists to
prevent is a premature "Up to date", and its own requirement keeps that.

The member who shares and receives nothing is the exception: for them the top rung is "Not sharing or receiving",
which needs the counts to say no work remains. So while those are unread they see the neutral line.

*Rejected:* putting the neutral line first, which is what the spec said. It would delay every known problem behind a
read that cannot change it.

### D2. Receiving off shows what arrived; sharing off shows nothing

The two directions differ in what switching them off does. Sharing off withdraws the photos not yet shared, and the
others no longer see what was declared, so "Not sharing" with no number is the true state. Receiving off stops what is
still arriving and keeps what arrived in the library. A count of those photos tells the member that turning receiving
off removed nothing.

The count shows once nothing is still arriving. While downloads already under way finish, the direction still shows
its progress, as it does today: work still draining in a switched-off direction is never hidden.

*Rejected:*
- "Not receiving" alone, which was the spec's wording. It is accurate, but it hides a true and reassuring number.
- A count for sharing off too. It would claim a share the member withdrew.

### D3. A shown QR code is captured whole, and stays

The invite rule exists so an invite is never offered without its key. A QR code already on screen was offered whole,
and an event's key never changes, so the code still opens the event for whoever scans it. Closing it protects nothing.

So the screen captures the invite when the member opens the QR code and shows that until they close it. The share and
QR actions follow the key as before, so no **new** invite is offered while the key can't be read. A deleted event, a
leave and a switch still close it. Whether the key can be read only changes when the app is re-entered, and there is
no realistic path where an open QR code's key becomes unreadable short of a damaged key slot. So this mostly removes a
reopening bug.

*Rejected:* clearing the shown state when the key is lost. That would fix the reopening too, but it needs a collector
and still closes a QR code that remains valid.

### D4. An unreadable membership is its own screen, with Try again

With nothing ever read, the app does not know the event's name or dates, so it cannot draw the joined screen. Showing
the create screen tells the member they have left, which is wrong. If they then create an event or join one, that
fails too, because any unreadable membership fails a join.

So the screen says the event can't be read right now and that the member hasn't left it. It offers Try again, which
runs what an opening of the app runs: the membership is re-read, then the foreground's work. While it runs, Try again
shows that it is working, and the app menu stays in reach, so "Report a problem" is too.

A row this version refuses is not covered by this. That row reads the same however often it is read, and the spec's
way back is joining again, so it keeps the create screen.

*Rejected:*
- Retrying on a timer. That's new background behaviour for a rare failure, and returning to the app already re-reads.
- "Try opening SnapSync again". It reads as needing a force-close, which isn't needed.

### D5. The other three are wording

The failure message is "beside the action": the rule is that a failure sits with the action it belongs to, and each
screen places it. The dialog rules are scoped to the range a member shares, which the event's inline range never
followed. The report sheet's exception follows `privacy-security`, which already says the member "may change" the
written description before confirming.

## Risks / Trade-offs

- [The QR code could outlive a key the phone has really lost] → The code is still the event's valid invite, and the
  member closes it at will. Nothing new is offered while the key is lost.
- [Try again could be tapped repeatedly while something is persistently broken] → Each tap is one request, answered on
  its status and refused while one runs. The menu offers the report.
- [The new count line is longer] → It is checked with the UI harness on the smallest screen.

## Migration Plan

None. No stored format or wire change. It ships with phase 4.9's pull request.
