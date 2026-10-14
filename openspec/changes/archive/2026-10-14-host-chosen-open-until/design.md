# Design

## Context

See proposal.md — Why. The rule as the backend holds it today (decision record `changes/back-dated-events`, D1):
a create may carry an open-until date; absent, it is the end; present, it must be at least the end and at most
the window maximum (30 days) after the later of the end and the server's now read one hour late
(`openUntilAdmitted`, `OPEN_UNTIL_CLOCK_SLACK_MS` in `api/src/lifecycle.ts`), else `400 invalid openUntil`. It is
written once and never changed: the only change route, rename, writes only the name. The backend mock mirrors the
rule (`BackendRouter`), and `api/test/back-dated.test.ts` pins it.

What the app sends: builds before v0.6 never send an open-until date; v0.6 and later send one only from the
back-dated sheet, as the phone's now plus 1, 7, 14 or 30 days, when the phone's clock says the end has passed.

## Goals / Non-Goals

**Goals:**
- The contract of record says what the backend enforces, and the retention bound guests rely on is stated
  explicitly in the requirement that fixes the date.

**Non-Goals:**
- Offering a later open-until date for a running event in the app. Nothing closes at the end and every join
  restarts the 3-day idle clock, so a running event's late guests are covered without it; whether the app should
  ever offer it is a separate product question.
- Enforcing the 1/7/14/30 menu on the server. It stays the app's, as D1 decided.

## Decisions

### D1 — Align the spec with the backend, not the backend with the spec

The spec's rule "the end, for an event created before its end" is relaxed to "the host's chosen day within the
bound, else the end".

*Alternatives:*
- **Refuse** a later open-until date unless the end is already past at creation (`400 invalid openUntil`, with the
  existing hour of clock slack). Rejected: it protects guests only against the host — the one party the design
  already trusts with how long data is kept — and adds a refusal a phone whose clock runs more than an hour ahead
  can hit on an event ending within that hour, with a retry that asks the same question again.
- **Clamp** such a date to the end silently. Rejected for the same reason, and because the stored event would
  differ from the one asked for without the host being told.

### D2 — The bound is stated as "about 30 days"

The backend admits up to the window maximum after the later of the end and its now read one hour late, so a
creating phone a little fast is not refused for offering "30 days" from its own clock. An hour is no outcome a
user can observe; "about" keeps the requirement true without naming the mechanism, as the deletion requirement
already does with "within about a day".

### D3 — Rename the requirement; leave the deletion requirement's scenarios as they are

The requirement is renamed to "An event stays open until the date its host chose", since it now governs every
event; "The date range closes nothing" cites it by name and is restated with the new name only. The scenario "An
event created ahead of its start" (deleted no later than 30 days after its end) describes an event with no chosen
day, which is every event the app creates before its end, so it stays true and is not restated: a MODIFIED delta
of that long requirement would risk dropping text for no gain.

### D4 — The Purpose and the mission prose are edited by hand

A delta cannot change a Purpose. The `event-lifetime` Purpose ("its end, or, for an event created after its end,
the date the host chose") and the same wording in the mission summary of `openspec/config.yaml` and `CLAUDE.md`
are edited directly in the change's PR.

## Risks / Trade-offs

- [A host using the backend directly can keep a running event open up to about 30 days past its end, so guests'
  photos can stay on the server up to about 60 days past the end] → the same bound a back-dated event already has;
  the spec now states it, and no app offers it.
- [The archived decision record of `back-dated-events` still says the server bounds only the span without saying
  the spec disagreed] → this change's design is the record that reconciles them; the archive is not edited.
