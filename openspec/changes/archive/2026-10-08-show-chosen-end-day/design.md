# Design

## Context

The joined screen's dates line and the joined explanation's "what you share" line both render through one label in
the design system, which receives the range's start and end as local wall-clock values (the presentation layer
converts the stored instants in the device's zone). The label decides which shape to print — open-ended, one day with
its times, or a first and last day — after working out the range's last day. That step carried a rule of its own: an
end at exactly 00:00 on a later date closed the day before it ("an event ending Tue 00:00 lasts through Monday").

The rule surfaced while taking `:ui:components` to zero missed coverage (phase L8): its guard `end.date > start.date`
had a branch no test reached. Reaching it showed the rule is not merely unreachable-guarded but wrong for one valid
input — a midnight that repeats when clocks go back (America/Havana, 1 Nov 2026: 00:00 at 04:00 UTC and again at
05:00 UTC), where an end after the start in instants is equal to it in local time. More importantly, it contradicts
what the host picked and what the rest of the product shows (proposal.md — Why).

## Goals / Non-Goals

**Goals:**
- The last day shown is the local calendar day of the stored end, with no adjustment.

**Non-Goals:**
- The range label on the create and join screens (it already shows the picked day).
- How far the event is in its life ("ends in …") — computed from instants elsewhere, unchanged.
- A range type guaranteeing start before end: the label no longer depends on it.

## Decisions

- **Remove the rule rather than move it.** The alternatives discussed were keeping the guard and testing the repeated
  midnight, or computing the last day from the instant just before the end (which needs the zone, so it would move
  into the presentation layer behind a new range type). Both keep a display adjustment the user did not ask for; the
  chosen behaviour is "show what was picked", which needs no computation at all.
- **An end at the next midnight is a two-day range.** It follows directly from showing the end's own day: 18:00 to
  00:00 next day reads as both days, without times. Recorded as a scenario so the consequence is a decision, not an
  accident.

## Risks / Trade-offs

- [A host who picks the next midnight to mean "until the end of today" now sees two days] → It is what they picked,
  and it matches the create screen and the event page; the picker offers 23:59 for "until the end of today".
