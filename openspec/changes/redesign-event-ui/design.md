## Context

SnapSync's only screen carries three surfaces: the create/host layer, the pre-commit join gate, and
the joined event home. The join gate is where a member's *participation is configured* — and it had
accreted engineering vocabulary. The direction was chosen from a three-way segmented control labelled
`Both` / `Upload only` / `Download only`; the cutoff was a two-preset selector (`Now` / `Event start`)
after the free date/time picker had been deliberately removed from that surface; the album was a
checkbox nested with the direction rows; and a first join showed a full-screen `AppExplainer` that
deliberately did **not** name the event.

A user-approved redesign (11 rounds + a 4-lens independent review + fixes) replaced all of that. The
code in `ui/screens` and `ui/components` is the settled truth; the specs lag it. Nine of the reviewers
independently flagged the direction labels as vocabulary a guest should never have to learn, and the
strings also did not fit a 390pt segmented control. This change brings the contract of record back in
line with the code, PROPOSE-stage only — no production code is touched here.

Crucially, the redesign is presentation-only in the domain sense: `UiState`, `JoinPhase`, `SyncHealth`,
`JoinEvent`, and the `(cutoff, Direction, saveToAlbum)` triple the confirm crosses are **unchanged**.
`Direction` still exists and is still what `JoinEvent` persists — the screen now *derives* it from two
switches instead of asking for it by name.

## Goals / Non-Goals

**Goals:**
- Make `join-event`, `event-album`, `event-creation-ui`, `photo-selection-policy`, and `design-system`
  truthful against the shipped PoC.
- Record the two design decisions that were deliberately left open (below), without resolving them.
- Enumerate the productionization work in `tasks.md` as what it is — finishing a mostly-built branch,
  not building the UI.

**Non-Goals:**
- **A distinct receive-progress line.** Threading download counts to a dedicated line was considered
  and rejected: the joined status line's two direction arrows already carry receive activity
  (capability `sync-status-screen`), and `UiState` deliberately carries no counts. `photo-download`
  therefore needs no delta.
- Changing `UiState` / `JoinEvent` / the persisted config shape. The UI blocks a nothing-membership;
  the domain types were not changed to forbid it (see Open Questions).
- Any backend change. `event-creation`'s routes are untouched.
- Resolving the two open questions.

## Decisions

**D1 — Direction is derived, never named.** The `Both` / `Upload only` / `Download only` segmented
control is removed. The surface presents two switch-header sections — **Share my photos** and
**Receive everyone's photos** (both default ON) — and derives the `Direction` on confirm
(share+receive → `Both`, share only → `UploadOnly`, receive only → `DownloadOnly`). "Direction" was
only ever a name for a pair of answers; asking the two questions plainly and computing the name is
strictly more honest. The receive title names the **source** ("everyone's photos") rather than "save
to your library", which reads as backing up *your* photos — the exact mental model this app must avoid,
and which also breaks pronoun parity with "Share my photos".

**D2 — Both-off is representable and blocks Join with a stated reason.** A membership that neither
shares nor receives does nothing. Rather than silently flip a switch the guest did not touch (a
surprise, and a lie about what they chose), Join is **disabled** with the reason stated directly above
it. The dead (both-off) `Direction` value never reaches a commit, so it is inert.

**D3 — The cutoff is three options, and there is no share-without-cutoff.** `Now` · `Event start` ·
`Custom`, defaulting to **Event start**. Custom re-introduces a date+time picker (the two-preset spec
had removed it) because a guest who arrived partway through an event has no exact answer among "now"
and "the start". Custom opens the picker **directly** (tap = open; only OK commits; cancel restores
the previous choice), and the chosen instant is stated **once, bold**, as the Share section's
"Shared from …" value — the Custom row never repeats it (one prominent statement beats two faint ones).
The floor (event start) is enforced **twice**, mirroring the backend's silent `max(chosen, startsAt)`:
pre-floor days are unselectable in the picker **and** the committed value is coerced up (a day-grain
calendar cannot forbid an earlier *hour* on the floor's own day). Share OFF hides the cutoff entirely.

**D4 — The explainer names the event.** The photo-access explainer now leads with the same event hero
shown across Loading → Explain → Ready → Committing, so identity never jumps. This **reverses** the
PoC-era decision to keep the explainer anonymous. The three consent facts survive as card rows
(share-first, then that full access is genuinely needed for both halves, then the cutoff), and
"I understand" remains the only route to iOS's one-shot system dialog (CTA-only priming, unchanged).

**D5 — The album opt-in is a standalone minor section, not nested.** The spec's own text says the
album mirrors *both* the member's own uploads *and* the foreign downloads — so nesting it under the
Share switch or the Receive switch would be a **false statement** about what feeds it. It is a
standalone second-level checkmark row ("Create an album", default off), ranked below both switches (a
preference, not a consent decision). Its note adaptively names exactly the feeds the current switches
produce — four wordings, including "Nothing is shared or received, so nothing is collected." when both
are off. Off draws an empty-circle affordance; the semantics are a **checkbox** (commits with Join, so
not a switch's "applies immediately" contract), which stays present-but-disabled when dimmed rather
than dropping out of the semantics tree.

**D6 — Create failures are a banner above Create, never a reddened field.** The name field is
client-guarded on both knowable rules — empty (Create disabled until the trimmed name is non-empty)
and over-length (the field caps at 100) — so a returned `400` is a rule this client cannot name. A
submission-level failure (or a transient invalid-link error) therefore renders in an `AppErrorBanner`
above the action, not as a red field, which would falsely blame the host's typing. The create surface
gains a host-framed hero (HOST AN EVENT eyebrow + drawn mark badge + "Start an event"), one question
over the name field, and the start date as a stated-consequence card ("Only photos taken after this
time are shared — the earliest cutoff any guest can pick"). `CreatingEvent` keeps the identical header
so the form reads as *settling*, not as a new screen.

**D7 — The date/time picker is hand-drawn, not Material 3.** The old requirement mandated an M3
`DatePicker` + `TimePicker` clock-dial contained inside the component. The M3 `DatePicker` clipped on a
390pt phone pane (it is a window-centered overlay). The PoC replaces it with a component drawn from
`Box`/`Text`/`Canvas` on the frozen scheme tokens, rendered in-tree as a `Popup`: a drawn calendar plus
**time wheels** (a snapping `LazyColumn` per field — chosen over ±1 steppers, which made a distant time
absurd to reach). No M3 `DatePicker`/`TimePicker`/clock-dial remains. Material 3 containment still holds
(the drawing lives only in the components module).

**D8 — The design language is drawn and consistent, and every line re-derives from state.** The
surfaces share one grammar: switch-header sections with recessed checkmark wells, minor standalone
sections, trailing checkmarks, a hand-drawn iOS-metric switch (M3's `Switch` reads as Android and its
off colours invert in dark mode), the drawn app mark (Canvas paths, the app-icon geometry), the drawn
calendar + time-wheels picker, iOS-alert dialog anatomy (an optional `body` on `AppConfirmDialog` /
`AppDestructiveConfirmDialog`), and borderless secondary actions. Light-mode contrast was corrected to
measured AA, and off-switch/well colours are pinned so the frozen palette survives both themes. The
**truthfulness rule** is load-bearing: every consequence line the surface renders must re-derive from
current state (the album note, the "Shared from …" value, the both-off Join reason), so the screen can
never assert a feed or an instant the membership will not produce. Accessibility baseline: every
interactive row carries an explicit role/state (`Role.Switch` on section headers, `Role.Checkbox` on
toggle rows, single tap target per row so assistive tech announces one control), dimmed controls stay
present-but-disabled, and reduce-motion is honoured (the status-line pulse already respects it).

**D9 — Where each surface change is pinned.** The album affordance's *layout on the join surface* is
owned by `join-event` (the surface owner); *why the affordance is direction-independent* and its
adaptive note are owned by `event-album`. The two specs cross-reference rather than restate, so neither
drifts. The joined layer (name/QR/status line/share/leave) is unchanged in contract, so
`sync-status-screen` takes no delta.

## Open Questions

**OQ1 — Should a nothing-membership be accepted or rejected by the domain/backend?** The UI blocks
both-switches-off (Join disabled). But `UiState` and `JoinEvent` were **not** changed — the dead
`Direction` value simply never reaches a commit. Whether the domain should make a nothing-membership
*unrepresentable* (a fourth `Direction`, or a sum type that omits it) or the backend should reject one,
is unresolved. Recorded, not decided.

**OQ2 — Should `UiState.CreateEvent.error` carry a typed reason?** It is currently an untyped `String`;
presentation formats `CreationFailureReason` (`INVALID_NAME` / `SERVER`) into copy before it reaches
the surface. If invalid-name vs server-error should ever diverge in *treatment* (not just wording), the
presentation contract needs the typed `CreationFailureReason` on the surface rather than a pre-formatted
string. Today both render identically in the one banner, so the string suffices. Recorded, not decided.
