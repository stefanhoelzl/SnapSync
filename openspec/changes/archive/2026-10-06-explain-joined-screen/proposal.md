## Why

The joined screen is built around a QR code, but scanning is not how most members invite people — they send
the link. It also tells a member nothing about how the event works: that their photos go to the group,
that the group's photos land in their gallery on their own, and what to do when photos stop arriving in the
background. Members asked what they were supposed to do on that screen. Its status line speaks the old
backup vocabulary ("In sync", "Synchronization pending…"), which the messaging glossary
(`metadata/messaging.md`: photos *arrive* and *land*, never *sync*) has since retired everywhere else.

## What Changes

- **The joined screen explains how the event works.** Beneath the status line, a "How it works" section
  states, in the member's own terms, what happens to their photos and what happens to the group's, and
  ends with what to do when photos are not arriving: open SnapSync and they catch up. Each statement
  follows the member's current choices — the range they share, limited or full access, whether sharing
  and receiving are on, whether an album is made — and, where something is not happening, says how to
  change it (a link to the event's settings, or to allowing photo access). It is always shown; it is not
  dismissible.
- **Sharing the link is the main way to invite; the QR is shown on demand.** The always-visible QR hero is
  replaced by two equal actions, "Share invite link" and "Show QR code", docked to the bottom of the screen
  with the event's settings and Leave. "Show QR code" opens the QR in a sheet over the screen, captioned for
  the people who scan it. The QR stays scannable in dark appearance, and both routes carry the same invite.
- **The status line speaks the glossary.** "In sync" becomes "Up to date"; while photos transfer it reads
  "Photos arriving…", while work waits "Photos queued…"; the neutral line before the app has looked reads
  "Loading…". "Waiting for Wi-Fi…", the access, network, not-started and cannot-verify lines are unchanged,
  and so is the order in which the line chooses what to say.
- **Settings and Leave are labelled actions** instead of bare icons, and the separate share icon is gone (the
  invite actions replace it). Visual only; no spec change.
- **German follows.** Every new or changed word ships in English and German; the German joined statement
  becomes "Du nimmst an diesem Event teil".

## Capabilities

### New Capabilities
(none)

### Modified Capabilities
- `sync-status`: the joined screen carries an explanation of how the event works that follows the member's
  choices and names the remedy when photos are not arriving; the status line's words change to the
  glossary's ("Up to date", "Photos arriving…", "Photos queued…", "Loading…") across every requirement that
  quotes them.
- `manage-membership`: the invite is offered as a share action and an on-demand QR code instead of an
  always-visible QR; the QR's caption addresses the people who scan it.
- `photo-access`: without access, the line inviting the member to turn it on is no longer the only place
  that offers it — the explanation links to the same action; "In sync" is renamed in its scenarios.

## Impact

- `:ui:screens` — `JoinedLayer` (the explanation, the docked invite and actions, the QR sheet), `StatusScreen`
  (the bottom cluster), strings in `values` and `values-de`.
- `:ui:components` — `AppStatusLine` strings; the joined layer uses `ScreenLayout`'s existing pinned bottom slot; an inline-link text
  primitive; the slashed-icon variant.
- `:domain` — the joined layer already carries everything the explanation reads (the membership's choices
  and range, the grant); new are only a screen-local "QR shown" overlay flag and its open/dismiss intents.
  The explanation's links reuse existing intents (open the event's settings, the access action).
- `:test:integration` `Shots.kt` — the marketing raws change (`in_sync`), so the screenshots are refreshed;
  harness and UI tests follow.
- No backend, upload, download or invite-link format change.
