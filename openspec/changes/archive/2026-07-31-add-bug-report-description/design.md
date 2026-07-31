## Context

The operator-initiated diagnostic dump shipped two days ago
(`changes/archive/2026-07-29-add-diagnostic-dump`). It is a hidden double-tap on the app-name label →
a confirmation dialog → one event carrying four sections: state, ledger, and the tails of both
process logs. Three properties of that design are load-bearing here:

- **The message is constant** (`diagnostic dump`), because Bugsink groups non-exception events by the
  first line of the message. That made every dump an occurrence of one issue instead of a new entry
  in the unresolved list beside real crashes.
- **The payload rides in context sections**, and the scrub (`scrubbedEvent`) reaches message text,
  exception values, and breadcrumbs — but not contexts. The dump therefore travels verbatim by an
  *incidental* property of where the scrub stops, which `DumpScrubExemptionTest` exists to protect.
- **The budget is a hard bound**, not a target: an over-budget event is rejected at ingest with a
  `413` the SDK swallows, so the sender observes success and the dump is silently lost.

Adding a user-written description touches all three. It also changes what the feature *is*: a
mechanism for pulling a log off a device becomes a channel for a person to say what went wrong. The
gesture, however, remains undiscoverable by design — the reporters are the developer and testers who
have been told the gesture exists.

Measured facts this design rests on (against the real Bugsink instance, 2026-07-29): attachments are
dropped entirely, breadcrumbs are capped at ~100, context strings up to 340 KB survive
byte-identical, and `MAX_EVENT_SIZE` is 1 MiB. Verified here (Bugsink docs + SDK metadata, 0.27.0):
Bugsink honors a client-supplied `fingerprint`, titles non-exception issues from the first line of
the message, and `SentryBaseEvent` exposes `tags`, `contexts`, and `setTag` while `Scope` exposes
`setTag`.

## Goals / Non-Goals

**Goals:**

- Collect a short, required, human account of the problem at the moment the dump is sent, and deliver
  it verbatim.
- Make a dump's subject legible from the Bugsink issue list without opening the event.
- Convert the redaction carve-out from an incidental property into an explicit, testable contract.
- Keep the affordance hidden, keep it structurally absent on builds with no reporting configuration,
  and keep the byte budget's hard bound untouched.

**Non-Goals:**

- Discoverability. The affordance is not being surfaced, promoted, or given accessibility semantics.
- Post-send feedback, delivery confirmation, or a rate limit — all unchanged, and all deliberately
  absent.
- Any change to which bytes of log are selected, or to the 700 KB budget.
- Renaming the domain vocabulary. The capability, feature, port, and command keep saying "diagnostic
  dump"; only user-facing copy and the event message say "bug report".
- Solving the staleness gap between when a problem happened and when the log tail is taken (see
  Risks).

## Decisions

### D1 — The description becomes the event message, so grouping changes from one issue to one per description

**Decision:** the event message becomes `Bug Report: <note>`, and the constant-message requirement is
replaced.

This directly reverses the two-day-old decision that pinned a constant message. The reversal is
deliberate and the reasoning is narrow: a constant message made every dump an occurrence of one
issue, which is exactly right when the payload is *only* a log — there is nothing to distinguish two
dumps by, so collapsing them keeps the unresolved list clean. Once a person writes what went wrong,
that reasoning inverts: two reports about different problems are now genuinely different issues, and
collapsing them hides the one fact that distinguishes them behind a click into each event.

**Alternatives considered:**

- *Constant message, description in a context section only.* Preserves one-issue grouping exactly.
  Rejected: the issue list then shows N occurrences of `diagnostic dump` and the only way to learn
  what any of them is about is to open each one — the same triage cost the description exists to
  remove.
- *Description as the message plus a pinned `fingerprint` so all dumps still collapse into one
  issue.* Bugsink honors client fingerprints, so this is available. Rejected: the issue title is
  taken from the event that created the issue, so the list would show the **first** report's wording
  forever while later reports say something else entirely — strictly worse than a constant, because
  it looks current and is not.
- *Bare description as the message, no prefix.* Rejected: a dump titled "photos not arriving" sits in
  the unresolved list next to thrown errors with nothing marking it as an operator report. The
  `Bug Report: ` prefix keeps dumps identifiable at a glance and greppable by the `/bugsink` skill,
  at the cost of ~12 characters of title.

**Consequence accepted:** the unresolved-issue list grows one issue per distinct description. On an
instance whose reporter population is the developer plus a handful of testers, that is a small,
bounded cost, and each entry is self-describing.

### D2 — The redaction exemption becomes a property of the event, not of where the payload sits

**Decision:** the dump's `send()` sets a `non-redacted` tag on the event; `scrubbedEvent` skips
redaction when that tag is present. The tag name and the predicate live in `:domain` `model/` beside
`redactUuids`, unit-tested in `commonTest` on both JVM and simulator.

The description must travel verbatim for the same reason the log does — a report that says "stuck on
event `‹uuid›`" has had its one useful identifier destroyed. But the message is precisely the field
the scrub does reach, so moving the description there without an exemption would silently mangle it.

Widening the exemption is also an improvement in its own right. Today the dump survives because the
scrub happens not to touch contexts; a future, entirely reasonable-looking "we missed a field" change
would empty every dump with no failing test and no error. Once the exemption is carried *by the
event*, that hazard is gone: a dump declares itself exempt, so scrubbing contexts would be safe.

**Alternatives considered:**

- *Message-prefix check (`startsWith("Bug Report:")`).* Needs no SDK behavior at all, and a false
  positive would be harmless because every Kermit-sourced message is already redacted at
  `SentryLogWriter` before capture. Rejected as the contract: it couples the exemption to user-facing
  copy, so rewording the prefix silently revokes it. It was held as the fallback in case scope tags did
  not reach `beforeSend`; **that risk is now measured and closed** (below), so it is not needed.
- *Removing message scrubbing from `beforeSend` entirely,* on the grounds that `SentryLogWriter`
  pre-scrubs everything it captures. Rejected: it deletes the last gate for any future
  `captureMessage` call site that forgets to scrub.
- *Detecting the dump by inspecting `event.contexts`.* Rejected twice over: it would trip the
  existing Konsist guard (which bans context-reaching tokens in the scrub body), and it re-creates
  the coupling to payload placement that this decision removes.

**Naming:** the tag is `non-redacted` rather than `diagnostic-dump` — it names the property being
claimed, not the one feature that currently claims it.

**The forcing proof** (measured 2026-07-31, `iosSimulatorArm64Test` on macOS, Sentry KMP 0.27.0): a tag
set on the scope inside `Sentry.captureMessage { scope -> … }` **is present in `event.tags` when
`beforeSend` runs**, and `beforeSend` runs synchronously on the capturing thread. The assumption the
whole exemption rests on is therefore not an assumption: it is `ScrubExemptionSdkTest`, which also pins
the other direction (an untagged capture arrives without the tag, so the scrub is not disabled for
automatic events). It lives in `iosTest` because that is the only place it can run, and it gates on
`ios-test`. If an SDK upgrade reorders this, that test fails loudly rather than every future report
quietly arriving redacted.

### D3 — The guard is re-pointed at the tag rather than kept alongside it

**Decision:** `DumpScrubExemptionTest` stops asserting "the scrub never reaches contexts" and starts
asserting the two facts that are now load-bearing: `send()` **sets** the exemption tag, and
`scrubbedEvent` **consults** the predicate.

The silent-failure hazard did not disappear — it moved. Before: delete the wrong line and dumps
arrive empty. After: delete the tag and dumps arrive scrubbed. Same invisibility, new cause, so the
guard follows the cause. Keeping the old assertion as well was considered and rejected: it would
guard a hazard D2 retired, and a guard whose rationale has evaporated is the kind a later reader
deletes for the wrong reason.

The division of labour is clean: `commonTest` covers the predicate's *logic* (runs on JVM and
simulator), while the Konsist source guard covers the *wiring* in `iosMain`, whose tests run only on
macOS CI — the same reason the guard was a source scan rather than a unit test originally.

### D4 — A bottom sheet, contained as a semantic component

**Decision:** a new `AppBugReportSheet` in `:ui:components` — the app's first bottom sheet — with a
strings-and-callbacks signature mirroring `AppConfirmDialog`. The Material 3 `ModalBottomSheet`, the
text field, `imePadding`, and scrolling all live inside it; no `Modifier`, slot, or Material 3 type
appears in the signature.

A dialog with a field was considered: it needs no new interaction pattern and carries no
keyboard-inside-a-sheet risk. Rejected because a multi-line field inside a ~270 dp centre-aligned
alert is cramped, and the alert's shape (bold centred title, two stacked full-width actions) is built
for a yes/no question rather than composition.

A generic `AppBottomSheet(content)` was rejected on the design system's containment rule: it hands
layout authoring back to the call site, and there is no second sheet in prospect to justify the
generality.

`AppTextField` gains a multi-line capability rather than a sibling component being introduced —
single- vs multi-line is a property of the same input, not a different one, unlike the
`AppConfirmDialog` / `AppDestructiveConfirmDialog` split where destructiveness is a design-time
choice the call site expresses by picking a component.

### D5 — The sheet is the whole consent moment; the dialog is removed

The dialog was "the consent moment and the only disclosure of the payload". The sheet inherits both
roles: it names what will be sent, and Send is the consent. No confirmation dialog fires afterwards —
two consent moments on a gesture only an informed operator can find is friction without a payoff, and
typing a description is itself a deliberate act. Dismissing by swipe, scrim, or Cancel sends nothing.
There is still no post-send feedback, for the unchanged reason: the SDK may queue and retransmit on a
later launch, so "sent" is a claim the app cannot honestly make.

### D6 — Required, trimmed, capped at 200 characters

Send is inert until the trimmed description is non-empty, so an invalid submit is unreachable and no
error text is ever needed. The trimmed value is what is sent, so whitespace cannot become an issue
title.

200 characters is chosen because the description **is** the issue title: it must stay scannable in
the unresolved list. Two sentences — "photos stopped arriving after I rejoined yesterday, counter
stuck at 12/40" is ~75 characters — fit comfortably. The cap is enforced once, by the field
(`AppTextField` already refuses input beyond `maxLength`); it is not re-enforced in the domain, which
would give one number two owners.

The description does **not** count against `DIAGNOSTIC_LOG_BUDGET_BYTES`. At 200 bytes against ~280 KB
of measured headroom under the 1 MiB ceiling, subtracting it would create an arithmetic dependency
between a UI field and a measured transport constant for less than a tenth of a percent. The budget
keeps meaning exactly what its documentation says: bytes of **log** carried.

### D7 — The affordance stays hidden

Considered and declined: making the entry point discoverable now that the feature asks a question of
a person. The reporter population is the developer plus testers who can be told the gesture; the
requirement that the label expose no click action and no accessibility semantics is unchanged. This
is recorded because the next reader will find a "Report a problem" sheet behind an undiscoverable
gesture and reasonably wonder whether that is an oversight. It is not.

### D8 — The report names the surface it was written from

The state section carries a `screen` label. Most of what it says is inferable from the rest of the
report (`joined` plus the config distinguishes create from joined; a join in progress leaves `join
gate: …` lines in the log) — but three surfaces are **screen-local by design** and inferable from
nothing: the reconfigure surface, a pending switch, and which join phase is showing. Opening the
reconfigure surface "touches no port" as its own decision record puts it, which is exactly why it
reaches neither the log nor the ledger. A report sent from a stuck join gate could otherwise not say
so.

The label is derived in the **screen**, not the container: `UiState` does not carry the reconfigure
flag, so only the composable that owns that flag can name the surface. It crosses the command as an
**opaque string**, and `:domain` records it without interpreting it — a feature that enumerated UI
screens would be presentation vocabulary in the wrong zone.

Deliberately coarse: a surface name plus the join phase where there is one. It carries no event id or
user data, both of which the state section already names in fields that say what they are.

Rejected: attaching a **stack trace** instead. The send path is constant — double-tap → sheet → Send —
so every report would carry the same frames, unsymbolicated on iOS (the reporting server ingests no
dSYMs), while making a deliberate report render as a crash in the list the `Bug Report:` prefix exists
to keep them out of. It would answer "where was the user" with the one thing that never varies.

### D9 — The log appears twice on a report, and that is left alone

A delivered report carries the log in two forms, and only one of them is this capability's doing:

- **contexts** (`app_log` / `ext_log`) — designed here: both processes, up to the 700 KB budget,
  **verbatim** under the exemption;
- **breadcrumbs** — inherited: `SentryLogWriter` turns every Kermit line into a breadcrumb so that
  *crashes* carry recent context (a crash has no log contexts, so for a crash they are the only log
  there is), and the dump event simply inherits the SDK's rolling buffer of the last ~100 lines,
  **redacted**, app process only.

Considered and declined: clearing breadcrumbs on the dump event. They render as the reporting UI's
timeline widget — the way to see what happened without opening a 700 KB context section — and their
cost is already inside the budget, which was chosen leaving headroom for exactly this ("the SDK's own
contributions… up to 100 breadcrumbs"). The half-redacted duplicate is a curiosity rather than a
hazard: the verbatim copy is the evidence, the redacted one is the skim.

Also considered and rejected: carrying the log **as a stack trace** instead. A stacktrace is a capped
list of structured frames; the tails are thousands of lines and up to 700 KB, so the container is one
to two orders of magnitude too small and most of the log would be dropped to make the rest render in a
widget built for call stacks. It would also make grouping follow the log's contents, and contexts are
the only carrier *measured* to survive at size (340 KB byte-identical, 2026-07-29) — on the same day
attachments were measured being dropped entirely, which is the silent-loss failure this capability
exists to avoid.

### D10 — Both harnesses render the sheet; the DSN rule stays structural

The world harness wires the real command — its reporter is configured and records the assembled dump,
so an agent can drive the whole path headlessly through `:test:harness-driver`. The forge harness
renders the sheet UI-only and echoes the description to the engine console, matching how Leave and
the invite affordances are already rendered UI-only there.

`forgeStatusHost` in `:ui:presentation` is deliberately **not** touched. It is also what
`SNAPSYNC_FORGE_STATE` mounts on device for App Store screenshots, where no DSN is baked — and the
rule that a build with no reporting configuration offers no affordance is enforced structurally, by
the command being null. Wiring a command into the shared factory would put a working-looking sheet
into a composition that can send nothing.

## Risks / Trade-offs

- ~~**A scope tag may not reach `beforeSend`**~~ → **CLOSED** (2026-07-31). Measured on the iOS
  simulator against Sentry KMP 0.27.0: the tag is present in `event.tags` when `beforeSend` runs.
  Pinned permanently by `ScrubExemptionSdkTest`, so an SDK upgrade that reordered it would fail a
  gating test instead of silently degrading every report. The message-prefix fallback is not needed.
- ~~**The keyboard may cover the sheet's Send button on a small device (SE2)**~~ → **HAPPENED, then
  fixed** (measured 2026-07-31 on the SE2, iOS 26.5, CMP 1.11.1). `imePadding()` plus a scrolling body
  was NOT enough: on a wrap-height sheet the confirm action sat entirely behind the keyboard, because
  the IME inset does not reach the sheet's own popup window and the padding resolved to zero. Fixed by
  presenting the sheet at **full height** (`skipPartiallyExpanded = true`), which lays content out from
  the top and puts the field and both actions above the keyboard whether or not any inset is reported.
  `imePadding()` is retained but is not what makes it work — which is recorded in the component and the
  spec, because the obvious "simplification" back to a wrap-height sheet is exactly the combination
  that was measured failing. The `ReconfigureScreen` content-swap fallback was not needed.
- **The unresolved-issue list grows one issue per description** (D1) → accepted, bounded by the
  reporter population, and each entry is self-describing. The `Bug Report: ` prefix keeps dumps
  separable from crashes.
- **The description may refer to a moment the log tail no longer contains.** The tail is the newest
  bytes at send time; a problem noticed on Tuesday and reported on Friday may have rolled off
  entirely, and nothing about the payload reveals the mismatch — the log looks complete, it is simply
  from the wrong days. Considered and declined for this change: recording tail timestamps in the note
  section would make the window legible, but the state and ledger sections still describe *now*, and
  a tester who reports usually reports soon. Recorded so it is not rediscovered as a bug.
- **A user may type personal information into a free-text field that travels verbatim and unscrubbed**
  → the sheet names what is sent before it is sent, and the act is deliberate and confirmed. This is
  the same consent posture as the rest of the dump.
- **`.claude/skills/bugsink/SKILL.md` hardcodes the old message** as its "this is not a crash" rule →
  it ships with this change; a stale triage instruction would misread every future report as a crash.

## Migration Plan

None required. No persisted state, no schema, no backend contract changes; the dump is assembled and
sent in one act. Older builds continue sending the constant-message form, which groups exactly as it
does today — the two forms coexist in the issue list without interfering.

## Open Questions

- ~~Does a scope tag set inside `Sentry.captureMessage { scope -> … }` reach `beforeSend`?~~
  **Answered yes**, by measurement rather than by shipping and hoping (see D2's forcing proof).
- Whether the affordance should become discoverable is deferred, not settled by this change (D7).
