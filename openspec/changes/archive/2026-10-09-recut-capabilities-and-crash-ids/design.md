# Design

## Context

See proposal.md for the why. The specs grew one change at a time, so an outcome usually landed where its first change
put it. That is why the app-wide behaviour lives in `sync-status`, the invite in `join-event`, and the background
transport is split by direction. The next change annotates tests with `@Verifies(spec, requirement title)`, so after
this change titles and homes should not move again.

The current code already keeps the narrower crash-report promise: `model/Crash.kt`'s scrub removes every UUID-shaped
token before a report leaves, so event, device and photo ids never reach Bugsink today. A later change removes the
scrub. The event key is never logged (capability `privacy-security`, "Only the whole invite opens an event's photos",
now in `invite-link`), so dropping the scrub does not put a key in a report.

Android locks nothing: `MainActivity` sets no `screenOrientation`, so it rotates. iOS is portrait-only through
`UISupportedInterfaceOrientations`. Both follow the system appearance: `values-night/themes.xml` gives a dark window,
and its `windowBackground` matches the screen, so there is no flash on launch.

## Goals / Non-Goals

**Goals:**
- Each outcome in exactly one spec, under the title the test annotations will use.
- Moves are reviewable as moves: word-for-word text, so the only diffs are the cross-references, the three merges,
  the portrait requirement and the two privacy requirements.

**Non-Goals:**
- No change to the crash scrub, logging or `Logger.invocation`.
- No removal of spec citations from Kotlin KDoc. That belongs to the next change, which deletes stale class-KDoc
  citations; until then those citations name `background-upload` and the old homes.
- No requirement change beyond the ones listed in the proposal.

## Decisions

**D1 — A move is REMOVED + ADDED, never MODIFIED across specs.** OpenSpec has no cross-spec move. A REMOVED entry
names the new home in its Reason and Migration, and the ADDED block is the main spec's text with only its capability
references edited. A capability reference that would point at the spec it now sits in becomes a quoted requirement
title instead (`(see "A finished event closes")`).

**D2 — The merged pairs keep every scenario.** No two scenarios in a pair state the same outcome: the two "wait for
Wi-Fi" scenarios cover different directions. The merged body keeps each original sentence, joined by direction
("Uploading:", "Receiving:"). The two Wi-Fi sentences fold into one ("Neither direction SHALL be held back…"), because
they said the same thing per direction.

**D3 — Failure reports may carry photo ids too, not just event and device ids.** Removing the scrub lets every
UUID-shaped token through, and photo upload keys are UUID-shaped. A promise limited to event and device ids would be
broken by that later change. The title "…minimal and anonymous" stays: no identifier in a report is linked to a
person ("No account and no personal identity"), and the title is about to become a test annotation.
*Alternative:* retitle it "…never carry an event's key" — rejected, because it churns a title for wording the body
already carries.

**D4 — "The event's identity goes only to SnapSync's own service" drops its failure-report ban instead of keeping a
second copy of the rule.** The sentence now points at the failure-report requirement, so the rule lives in one place.

**D5 — The portrait promise covers phones only.** From Android 16, an app targeting SDK 36 (ours) has its orientation
lock ignored on displays 600 dp and wider. A promise covering tablets would be one the platform breaks. The lock is
`android:screenOrientation="portrait"` on `MainActivity` (upright only, like iOS, not `sensorPortrait`). The release
lint fails on any warning, so if lint flags the lock, the task suppresses it at that line with the reason.

**D6 — "A finished event closes" keeps its "what any member shares" clause.** The review draft proposed dropping it as
a repeat of "What a member shares is fixed once the event has closed". Read in full, the two say different things: the
clause is about settings (no share or receive switch after the close, capability `manage-membership`), while the
moved requirement is about which photos make up the share. Both stay.

**D7 — Purposes of existing specs are edited at archive, and the order of `event-lifetime` is fixed there too.** A
delta cannot change an existing spec's Purpose, and ADDED requirements land at the end of the spec. The new specs carry
their Purpose in the delta. The seven existing specs whose Purpose names an outcome that left or arrived are rewritten
by hand after the archive, as `2026-10-07-mobile-data-per-device` did. `event-lifetime`'s requirements are reordered
to follow the event's life. `background-upload/` is left with no requirements and is deleted.

**D8 — Non-Kotlin mentions of `background-upload` are renamed now; Kotlin is left for the next change.** Info.plists,
entitlements, xcconfig, Swift, SQLDelight, scripts, the CI action, build files, the architecture guards, docs, skills
and both CLAUDE.md files are read by people and other agents with no KDoc sweep coming for them. The `.kt` citations,
104 files, are exactly what the next change deletes.

## Risks / Trade-offs

- [The App Store privacy label may need "Crash Data: Linked to You" once reports carry device ids] → a manual task
  checks App Store Connect and records the outcome. The label changes only when the later scrub removal ships, since
  until then no id is sent.
- [The Privacy Policy on the site would describe ids being sent before the app actually sends them] → it says reports
  *may* carry them, which is true both before and after. It still says the key is never sent.
- [A move silently drops text] → the deltas are generated from the main specs by script and checked by diffing each
  MODIFIED block against its main block. A task re-diffs the archived specs: every requirement title of the old tree
  appears once in the new tree, apart from the six merged and the one retitled.
- [Kotlin KDoc keeps citing `background-upload` until the next change] → accepted. No gate reads those citations, and
  the next change removes them.
