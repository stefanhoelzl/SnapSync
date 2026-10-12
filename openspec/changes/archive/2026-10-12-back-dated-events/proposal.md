# Proposal

## Why

A host cannot share the photos of a gathering that already happened. Creating an event whose range is over
(start < end < now) is accepted by every layer, but the event dies within one wake: the host is its only member,
settles at once, the event closes (nobody may join), the host's app leaves on its own and the sweep deletes it. So
"bring photos already taken into the event" — a promise `create-event` makes today — cannot be kept for the most
natural case. Measured on the JVM rig: create → join → upload → back on the create screen within one wake.

The cause is that "closed" (nobody joins, nothing changes) and "the app leaves on its own" both assume the members
joined before the end. Rather than special-case one path, this change drops the close entirely and makes deletion
the only end of an event's life, while keeping storage reclaimed quickly once everyone has everything.

## What Changes

- **Back-dated creation.** Creating an event whose end is already past opens a sheet that explains the photos are
  kept only for a limited time and asks how long guests have to join: 1, 7, 14 or 30 days (one button each,
  creating at once) or Cancel. The chosen date is the event's **open-until date**; for any other event it is the
  range's end. The joined screen of a back-dated event says until when guests can join.
- **BREAKING (observable): an event no longer closes.** Joining, rejoining, sharing (and withdrawing), receiving,
  renaming and changing settings all keep working until the event's photos are deleted. The rule "what a member
  shares is fixed once the event has closed" is removed, and the "closed" join refusal and the closed joined
  screen go away for new events.
- **Deletion is the only end.** An event someone joined has its photos deleted about a day after it is
  *finished* — its open-until date has passed and every member still in it has shared and received everything —
  or 3 days after the later of its open-until date, the last join and the last photo's arrival, or about a day
  after every member left; the hard cap moves to 30 days after the open-until date (today's cap, 30 days after
  the start, can cut off a 30-day event on its last day). An event nobody joined lives to the cap.
- **The app never leaves an event on its own.** A finished event stays on the joined screen; a deleted one is
  shown as over, with what was received still in the library, and Leave there does not ask. Opening another
  event's invite skips the "switch?" question only when nothing can be lost: the current event's range has ended,
  every member still in it has shared everything and this phone has received everything — or its photos were
  deleted. In every other case, offline included, the question is asked as today.
- **Status.** An ended event says "Waiting for N members" while others are still sharing and, once everyone is
  done, that everyone has shared; a late guest or new photos turn it back. The joined screen and the join screen
  always show how many members the event has.
- **Pushes.** The one-off wake "the event has closed" is no longer sent.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-lifetime`: no close; open-until date; deletion rules (finished, idle clock, all left, cap re-anchored);
  the app never leaves on its own; a member's "done" is reversible.
- `create-event`: the back-dated sheet choosing the open-until date.
- `join-event`: joining is possible until deletion; only a deleted (or full) event refuses; the member count on the
  join screen.
- `invite-link`: the invite works and is offered until deletion; switching skips the question when nothing can be
  lost.
- `manage-membership`: rename, settings and rejoin until deletion; leaving a deleted event does not ask.
- `sync-status`: the deleted state replaces the closed joined screen; the waiting line without a total and its
  "everyone has shared" end; the member count; "guests can join until" on a back-dated event.
- `photo-sharing`: withdrawal and re-inclusion work until deletion, not until a close.
- `privacy-security`: retention wording, and the notification token no longer announces a close.
- `delivery`: no close wake.
- `mobile-data`: a photo still waiting for Wi-Fi is bounded by deletion, not by a close.
- `event-site`: the deletion moments named on the event page follow the new lifetime.

## Impact

- **api/**: `events` gains the open-until date (stored at creation, validated against the window and the
  sheet's choices); the sweep's verdict (finished / clock / all left / cap); a per-device "received up to union
  generation" report; the event read serves the open-until date and the member counts the screens need;
  `closedAt` becomes derived for builds already shipped; the close refusals and the close push go, kept only for
  events closed before rollout. Schema migration for libSQL and the dev SQLite; `api/openapi.yaml` and the
  generated wire.
- **App**: `CreateEvent`/presentation (the sheet, the open-until date), `EventCompletion` (settle and report, never
  leave), the deleted membership state, the switch decision (a server read at link open), the joined/join screens'
  new lines, strings in English and German, the world harness and integration tests.
- **Compatibility**: no `MIN_APP_VERSION` raise; shipped builds see `closedAt` only when it is safe for them to
  leave. Two `PENDING_CLEANUPS.md` entries (legacy closed events; the derived `closedAt` for old builds).
- **Docs**: `docs/architecture.md` (lifecycle, sweep), `openspec/config.yaml` mission context, `CLAUDE.md`'s
  mission paragraph, the Purpose paragraphs of the touched specs.
