# Proposal

## Why

Several outcomes sit in a spec that is not about them: the end-of-event sequence is split over three specs, the invite
over three more, the app-wide behaviour lives inside the status screen's spec, and photos travelling in the background
are split by direction across `background-upload` and `receiving-photos`. `openspec/config.yaml` asks for one
capability per thing a user does or gets, each outcome in exactly one spec. Tests are about to be annotated with the
requirement they verify, so the layout should be final before those annotations are written. Separately, automatic
failure reports promise to carry no event or device identifier. Those identifiers are random, are already held by
SnapSync's own service, and go only to the operator's own error-tracking service, so removing them protects nobody and
makes failures hard to trace. The one thing that must never leave in a report is an event's key.

## What Changes for Users

- **Failure reports may name the event, the device and the photos involved — never an event's key.** "Automatic
  failure reports are minimal and anonymous" (`privacy-security`) is rewritten: a report MAY carry the random
  identifiers SnapSync already exchanges with its own service (device, event, photos) and SHALL NEVER carry an event's
  key or anything identifying the person. "The event's identity goes only to SnapSync's own service" drops its
  sentence forbidding the event's identity in failure reports and points at the rewritten requirement instead. Today's
  app still removes every such identifier, which keeps the narrower promise; a later change stops removing them.
- **The Privacy Policy and the store declarations follow.** The policy's crash-report paragraph and its Bugsink
  provider line, Play's data-safety notes, and — checked by hand — App Store Connect's privacy label.
- **The app stays portrait on Android phones too.** Today only the iPhone app is locked to portrait; the Android app
  rotates. The requirement becomes platform-neutral ("a portrait phone app"), and the Android app is locked to
  portrait. On a tablet or an unfolded foldable, Android may still rotate it.

## What Only Moves

Every moved requirement is REMOVED from its old spec (reason: "moved to `<capability>`") and ADDED word for word in the
new one; only `capability …` cross-references change. Three pairs merge, one requirement is retitled (above), and one
clause is dropped where it would repeat a moved requirement in the same spec.

- **`event-lifetime` owns the end-of-event sequence.** In from `photo-sharing`: "A member's share is settled only after
  the event has ended", "What a member shares is fixed once the event has closed". In from `manage-membership`: "The app
  leaves on its own once the event is finished for it". "A finished event closes" no longer repeats that what members
  share is fixed.
- **`invite-link` (new): the invite itself.** In from `join-event`: the link format staying openable, opening the
  app's join screen, the store route without the app, a damaged invite, an incomplete invite, reopening the current
  event's invite, another event's invite. In from `privacy-security`: the invite as the key, only the whole invite
  opening photos, the invite handed to Google Play. In from `manage-membership`: whoever holds the invite can join, and
  the joined screen offering the invite.
- **`app-experience` (new): how the whole app behaves.** The 11 app-wide requirements out of `sync-status`
  (orientation and appearance, keeping its place, silent background wakes, the first frame, taps, calm failures,
  accessibility, explanations, the date-range picker, text entry, the app menu).
- **`delivery` (new; `background-upload` is retired): photos travel between devices in the background, both ways.**
  Every requirement of `background-upload`, plus from `receiving-photos` the arrival without opening the app, the
  silent wake, and download problems. Three per-direction pairs merge: "Photos travel without the app being opened",
  "Each direction runs only if the member chose it", "Leaving or switching stops every transfer for the old event".
  `receiving-photos` keeps what happens in the library.

## Capabilities

### New Capabilities
- `invite-link`: the invite link and QR code — its format kept forever, it as the only key to an event, where it
  leads with and without the app, what a damaged, incomplete, current or other event's invite does, and the joined
  screen offering it.
- `app-experience`: how the app behaves on every screen — portrait phone app following the system appearance, a
  truthful first frame, its place kept, silent background wakes, taps, calm failures, accessible controls, matching
  explanations, the date-range picker, text entry, the app menu.
- `delivery`: photos uploaded and received in the background without opening the app, in the directions the member
  chose, announced by silent wakes, never lost on the way, stopped for an event the member left.

### Modified Capabilities
- `privacy-security`: the automatic failure-report requirement is rewritten (event, device and photo ids may be sent;
  never an event's key); one sentence of "The event's identity goes only to SnapSync's own service" changes; three
  invite requirements move to `invite-link`; "A link to a single photo…" re-points its quote at `invite-link`.
- `event-lifetime`: gains three end-of-event requirements; "A finished event closes" drops its repeated clause.
- `photo-sharing`: loses the two end-of-event requirements.
- `manage-membership`: loses the invite requirements and the automatic leave.
- `join-event`: loses the seven invite requirements; keeps the join screen.
- `sync-status`: loses the 11 app-wide requirements; the explanation's cross-reference names `delivery`.
- `receiving-photos`: loses three requirements to `delivery`, and "Leaving or switching stops receiving for the old
  event", merged there.
- `background-upload`: every requirement moves to `delivery`; the capability is retired.

## Impact

- `openspec/specs/` — the eight modified specs and three new ones; `background-upload/` deleted after archive; the
  Purpose paragraphs of the new specs and of every spec that lost or gained an outcome rewritten after archive.
- `app/android/src/main/AndroidManifest.xml` — `MainActivity` locked to portrait (the only code change).
- `site/src/pages/index.astro` — the Privacy Policy's crash-report paragraph and Bugsink line.
- `metadata/play/declarations.md` — the data-safety notes on crash logs.
- App Store Connect's privacy label — checked by hand, outcome recorded.
- Capability names in `CLAUDE.md`, `app/ios/CLAUDE.md`, `docs/`, two `.claude/skills`, and the non-Kotlin files that
  cite `background-upload` (Info.plists, entitlements, xcconfig, Swift, SQLDelight, scripts, CI action, build files,
  architecture guards). Kotlin KDoc citations are left to the next change, which removes spec citations from KDoc.
- No change to what the app does with crash reports: the identifier scrub stays until a later change removes it.
