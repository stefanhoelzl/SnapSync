# Design

## Context

Claiming phase 22's app specs with integration tests turned up six scenarios that no test can claim as written
(proposal.md, "Why"). Each was checked against the code and, where that left any doubt, with a probe test:

- **A finished event.** The app keeps the member on the joined screen. `EventCompletionIntegrationTest` already
  holds this ("the only member settles its share and stays in the finished event"), as event-lifetime's "The
  app never leaves an event on its own" requires. The create screen named by app-experience's scenario cannot
  appear. The requirement's own exception, "unless the membership ended on its own", is stale since
  `changes/back-dated-events`.
- **Switching sharing off.**
  - The selection policy turns to deny-all at once. The status reads its upload total over the photos the
    policy admits, so the total is 0 the moment sharing is off, and a withdrawn upload in flight raises no
    arrow and no count.
  - Photo-sharing's "Deleting or no longer sharing a photo withdraws it" already says a withdrawal reaches "a
    photo that was still uploading". Manage-membership's "let uploads already under way finish and count as
    shared" contradicted it.
  - The not-masked rule (`changes/archive/2026-10-06-settings-sheet`, D7) protected only the status reduction.
    The work it meant to keep visible is never counted in the first place.
- **The transient "0 of 1 received"** that switching off produced was a bug in its own right. It was fixed in
  this phase's first commit and is not part of this change.

## Goals / Non-Goals

**Goals:**

- The contract says what a member sees today, so phase 22 can claim every rewritten scenario against the
  unchanged app.

**Non-Goals:**

- Joining a new event on its own. When the member returned to a finished event and opened another invite, the
  user first wanted an automatic join, then chose (a1): the join screen without the switch question. A join
  stays an act the member confirms, as everywhere else.
- Cancelling the operating system's transfer of a withdrawn upload. It is recorded as a follow-up.

## Decisions

**D1 — The spec follows the app on switched-off sharing, not the other way round.**
- The confirmation a member answers before sharing goes off promises that their photos will not reach anyone
  new.
- Letting the in-flight upload finish and count as shared would break that promise for exactly one photo. It
  would also contradict photo-sharing's withdrawal rule, and the rule that a member shares only what they
  agreed to: once they switch sharing off, they no longer agree to share that photo.
- *Alternative:* change the app so the upload finishes, is listed and is counted. Rejected for those reasons,
  and because it would need the status to count work outside what the policy admits, a second source of
  truth for the upload total.

**D2 — The not-masked clauses are removed, not kept as a guard.**
- "If the app ever does work in a switched-off direction, that arrow SHALL be shown" names a state that, after
  D1, no path produces. A spec states outcomes a member observes, and this one can no longer be observed.
- The rewritten scenarios state the outcome a member does see: the arrow and the counts go at once.
- *Alternative:* keep the clause as a defensive promise. Rejected: no test can claim it, so it would stay an
  unclaimed scenario forever.

**D3 — The finished-event scenario points to invite-link instead of restating its rule.**
- The skip-the-question rule lives in invite-link's "Opening another event's invite asks before switching",
  together with its offline and server-error fallbacks.
- App-experience's scenario names the case a returning member meets and cites that requirement, so the
  outcome lives in exactly one spec.

**D4 — Four requirements are replaced whole, under new names.**
- OpenSpec 1.13.2 rejects a MODIFIED block that drops a scenario the main spec still has, and it can neither
  rename nor remove a single scenario. It also rejects a requirement that is both REMOVED and ADDED under one
  name.
- Each corrected scenario's old name states the opposite of its new outcome, so the name cannot stay. The four
  requirements holding one are therefore REMOVED and ADDED again under new names. Their bodies and their other
  scenarios carry over word for word.
- Cost:
  - the 17 existing claims on those requirements, and the two sync-status requirements that cite the status
    line by name, follow the new names;
  - no reordering: the sync put each replacement where the old requirement stood.
- *Alternatives*, both rejected by the user:
  - keep the old scenario names and rewrite only their bodies, which leaves four names contradicting their
    own scenarios;
  - rewrite the bodies under the old names, then rename the four headings in the main specs by hand after
    archiving, outside the delta.

## Risks / Trade-offs

- [The bytes of a withdrawn in-flight upload can still reach the service.] → The photo is never listed, so no
  member receives it, and the nightly sweep deletes it with the event. Cancelling the transfer on withdrawal is
  the recorded follow-up.
- [A member who expected the last photo to go through sees it withdrawn.] → The confirmation dialog already
  says no photo reaches anyone new. Switching sharing back on offers the photo again (manage-membership,
  "Narrowing then widening restores the photos").

## Migration Plan

Spec-only. No app build changes behaviour. Archiving updates the three main specs, and phase 22's later
commits claim the rewritten scenarios.
