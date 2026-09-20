## Context

The joined event screen renders, top to bottom: the `SNAPSYNC` nav label, the event name as the
heading, the accent eyebrow `SHARE THIS EVENT`, a white card holding the invite QR with the caption
`Scan to join this event`, the single sync-health line, and a bottom cluster of three flat icon-only
buttons (settings · share · leave).

A user looking at that screen asked *"what do I need to do here?"* — they were already joined, and
the caption told them to scan something.

Two facts bound the design:

1. **The eyebrow is not the missing piece.** `AppEyebrow("Share this event", Accent)` entered on
   `945dd02e` (2026-07-21, the event-UI redesign) and is contained by `v0.1`, `v0.2` and `v0.3` — the
   current App Store release. It shipped, the user saw it, and it did not register. The caption is
   what gets read: it is a full sentence in body type sitting inside the card the eye is already on,
   while the eyebrow is a small uppercase wide-tracked label that reads as decoration.
2. **`AppQrCode` has exactly one call site** (`JoinedLayer.kt:58`). There is no second surface whose
   caption could drift from this one.

The existing wording was deliberate, not accidental. The comment above the call site reads: *"A tracked
accent eyebrow names what the code is FOR to the current member … while the card's own caption
instructs the person scanning it — two audiences, one statement each, so neither line repeats the
other."* That rationale is what this change overturns, so it has to be answered rather than ignored.

## Goals / Non-Goals

**Goals:**

- The joined screen must not read as asking the member to scan anything.
- Whatever replaces the caption must survive being read by a **guest**, not only by a host.
- Leave the repository free to tune this copy again later without a spec edit.
- Keep the committed screenshot raws honest, since the App Store listing and `site/` derive from them.

**Non-Goals:**

- Re-laying out the invite hero, changing what leads, or changing the QR's visual weight.
- Removing or rewording the `SHARE THIS EVENT` eyebrow.
- Labelling the icon-only share button, or otherwise making the non-QR invite path visible.
- Touching the create screen's scan hint.
- Any change below the UI: no domain, adapter, backend, or build change.

## Decisions

### The caption addresses the member, not the scanner

The two-audience model is retired. A person scanning a QR is **looking through their own camera** at a
code roughly 196dp across; they are not reading a 14sp line of body text on a stranger's phone, and
their own OS shows them a link preview in their own UI the moment the code resolves. The caption's
nominal second audience does not read it.

The audience that does read it is the member holding the device — and to that reader, a bare
imperative *"Scan to join this event"* next to a scannable code is simply false. A line that is
invisible to the reader it was written for and misleading to the reader it actually has is not
serving two audiences; it is serving none.

*Alternative considered:* keep the caption scanner-facing and make the eyebrow louder instead.
Rejected because it treats the failure as one of prominence when it is one of truth — the misleading
sentence would still be the most readable thing in the card, and the screen would then carry two
competing instructions.

### The string is "Let someone else scan this to join"

- **"someone else"** is the phrase hardest for a reader to apply to themselves.
- **"let"** frames the act as permission the member grants, not a task the member owes.
- It still teaches the mechanic — the code *is* scanned — but assigns that act away from the reader.

*Alternatives considered:*

- *"Show this to invite others"* — removes the word "scan" entirely, which is the cleanest defence
  against the reported misreading, but stops explaining how the code works and leans on the reader
  inferring it from the glyph.
- *"Others scan this to join"* — a statement of fact with no imperative at all, but terser and colder,
  and "others" is weaker than "someone else" at excluding the reader.

### "Guests" is prohibited, and this is the sharpest lesson here

The obvious phrasing — *"Guests join by scanning this"* — is **wrong for the same reason the current
caption is wrong**. Host and guest see the identical joined screen, and the user who was confused was
himself a guest. Any role word the reader can plausibly apply to themselves reproduces the bug in a
new costume: the guest reads "guests", recognises himself, and is back to believing the line is
addressed to him.

Generalised: **a caption on this screen may not use a noun the reader might be.** That is why the
chosen string names the third party only as *someone else*.

### The specs stop quoting the literal

`event-invite-qr` and `desktop-test-harness` both quote `"Scan to join this event"` today. They will
require the caption's *intent* instead — a caption telling the member that someone else scans the code
to join — and quote nothing.

Rationale: pinning display copy in the contract of record buys nothing the tests do not already buy.
`StatusScreenTest` asserts the literal in three places (one absence, two presence), which is a
mechanical pin that fails a build; a spec quoting the same words is a second copy that can only ever
drift, and drift in the contract of record is the more expensive kind.

*Alternative considered:* keep the literal pinned and add a sentence to the requirement stating why
the caption is member-addressed. Rejected: it re-freezes the copy for a benefit the tests already
provide. The *why* is preserved here, in this decision record, which is what the archive is for — and
the intent requirement is enough to make a scanner-addressed rewrite visibly non-compliant.

*Consequence to accept:* the exact words now live in one Kotlin file and this document. A future
reviewer wanting to know why it says what it says must find this record; the requirement alone will
not tell them.

### The screenshot raws are re-captured in this change, not after it

`screenshots/in_sync-{light,dark}.png` render this card, and both the App Store listing (at release
time) and the `site/` landing page (on merge) derive from the committed raws. Nothing regenerates
automatically. Shipping the string without the raws would leave the store advertising the exact copy
this change calls misleading, for as long as it took someone to notice.

## Risks / Trade-offs

- **The longer caption was expected to wrap to two lines and grow the card taller. It does not.**
  Measured in the forge harness (`:test:harness-driver:driveForge`, `Complete (34 of 34)` preset,
  390pt pane): the caption renders on **one** line and the card sizes to its content, growing
  *horizontally* to ~260pt. The screen's vertical rhythm is unchanged, and there is margin to spare
  even on a 375pt SE2. The shorter candidates were rejected on meaning, not length, and it turns out
  length cost nothing. The re-captured screenshots remain the check for both themes.
- **The re-capture can pick up a system notification.** A *"Ready for Apple Intelligence"* banner
  landed in a capture on 1 of 2 runs previously, and it cannot be detected automatically (a colour
  check false-positives on `in_sync`, which legitimately renders the event name in the top band). →
  Mitigation: eyeball all six raws before committing and re-dispatch the workflow if one is polluted.
- **De-pinning the string leaves the wording defended only by `StatusScreenTest` and this record.** A
  future copy change that satisfies the intent requirement but re-aims the imperative at the reader
  would pass every gate. → Mitigation: the prohibition is stated here in full, and the call-site
  comment is rewritten to carry the short form of it beside the string itself.
- **`create-{light,dark}` will re-diff even though the create screen is untouched**, because those
  captures contain a wall clock. → Expected and documented behaviour; a diff anywhere outside that
  90×32px region means the UI really moved and is the signal to stop and look.
- **The unlabelled share button remains the only way to invite someone who is not in the room, and it
  is invisible.** → Knowingly deferred; recorded here so a later report about it lands on a deliberate
  deferral rather than an oversight.

## Migration Plan

None. Display copy and the requirements describing it; no stored data, no protocol, no version gate.
Rollback is reverting the commit.

## Open Questions

None.
