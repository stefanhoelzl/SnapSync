## Context

Today the event's settings are a **surface of the joined layer**: `JoinedSurface.Reconfigure(form, range,
saveFailed)` replaces the status body, carries the member's uncommitted draft (`RangeForm`), and commits it
whole on Save through `UserCommands.reconfigure` → `ReconfigureEvent.reconfigure(eventId, direction,
cutoff, ceiling, saveToAlbum, mobileData)`. `StatusContainerHost` owns the draft in its local state
(`local.settings: Owned<SettingsSurface>`), and a failed save keeps the surface open with
`SettingsSurface.SaveFailed`. `ReconfigureEvent` saves the whole config, bumps the manifest version, then
re-drives the arms best-effort; it returns `Saved`, `SaveFailed` or `NotCurrent`.

`ReconfigureScreen` renders `ParticipationSections` — the same arrangement the join gate renders (one
share+receive card, then an album card, then a mobile-data card) — and pins `SaveActions` (two notes, the
section line, Cancel · Save) under it. The range is edited through `AppShareRangeRow`: a preset chip or the
calendar dialog's OK commits a range into the form.

`Direction` has three values (`Both`, `UploadOnly`, `DownloadOnly`), persisted by name in the membership
config both processes read; `includesUpload` is `this != DownloadOnly` and `includesDownload` is
`this != UploadOnly`. Both switches off is unrepresentable today, which is why the form disables Save.

The design was settled in an interview (kept outside the repo); motivation is proposal.md, requirements are
the two delta specs.

## Goals / Non-Goals

**Goals:**
- One reconfigure per change, through the existing `UserCommands.reconfigure` and `ReconfigureEvent` — no
  second write path to the membership config.
- What the sheet shows is the config in effect, not a draft, except for the one change waiting on the
  withdrawal question.
- The join gate and the sheet keep rendering one shared arrangement.

**Non-Goals:**
- Changing the join gate's behaviour: it still requires a direction, still disables Join with both off, and
  still has Join/Cancel.
- Debouncing or batching changes; each applies as it is made.
- A "neither" membership reached any way but the settings (the invite link's dev `direction` key, a join).

## Decisions

**D1 — The settings stay a joined surface; the screen draws them over the status.** `JoinedSurface.Reconfigure`
remains the state that says the settings are open (it is already membership-owned, closed by a switch or the
event closing, and carries the resolved range and count); what changes is the screen, which keeps composing the
joined status body and draws the surface in a sheet over it, like the QR sheet. *Alternative:* move the open flag
to `Overlays`. Rejected during apply: it moves the same facts to a new home and re-plumbs the membership ownership
the surface already has, for no observable difference.

**D2 — The controls show the membership in effect.** On open the form is seeded from `EventConfig` (as today);
after every applied change it is re-seeded from the config the change left behind — saved or not — so a failed
change shows the setting still in effect and a "From now" preset becomes the fixed range it applied. The local
state adds only a held `pendingWithdrawal`; `SettingsSurface.SaveFailed` is the failure line. *Alternative:* keep a
draft and commit each edit. Rejected: draft and config disagree after a failure or a refresh.

**D3 — One intent per change, applied through the existing command.** Each switch and each committed range
dispatches one intent; the host builds the full reconfigure from the current config with that one field
changed (`SettingChange.of(config)`) and calls `commands.reconfigure` as Save did. Only a range intent
re-resolves bounds from the form; every other change carries the config's own bounds, so a switch can never
move the range through a round trip. Orbit runs intents concurrently, so the settings' acts (open, close, every edit while joined, the
withdrawal answers) go through one queue on the host's scope, enqueued synchronously as they are tapped and run
one at a time: quick changes apply in order and the last stands, with no debounce. Each change builds on the
settings the previous one applied (`lastApplied`), not on the membership read, which can trail the save — measured
in the integration suite, where a second flip built on the stale read undid the first. `ReconfigureEvent` is
unchanged: per call it re-saves, re-bumps and re-drives, which is idempotent. *Alternative:* apply on close. Rejected in the interview: it is an implicit Save.

**D4 — Withdrawal is a question held in local state.** Turning sharing off, or a committed range whose start
is later or whose end is earlier than the config's, does not reconfigure; it sets `pendingWithdrawal` and the
screen shows `AppConfirmDialog` ("Stop sharing these photos?", the narrowing statement, Stop sharing / Keep
sharing). Stop sharing applies the held change through D3; Keep sharing clears it, and because the controls
render the config (D2), they are already back as they were. Widening and every other change apply directly.
A range that is narrower at one end and wider at the other asks (it withdraws something).

**D5 — A failed change reverts by construction.** On `SaveFailed` the surface becomes `SaveFailed` (cleared by the
next successful change or on close); the sheet shows the message line at its top. Nothing has to be undone on
screen: the controls are re-seeded from the config, which the failed save did not touch. `NotCurrent` (a switch landed
while the sheet was open) closes the sheet.

**D6 — `Direction.Neither`, the fourth value.** Both off persists as `Direction.Neither`; `includesUpload` and
`includesDownload` become explicit (`Both`/`UploadOnly` and `Both`/`DownloadOnly`) so it answers false to
both. Every existing consumer then does the right thing with no special case: the upload policy admits
nothing (the manifest re-projects empty, withdrawing the member's photos — which is why turning the last
direction off asks, D4, when that direction is sharing), downloads are cancelled by `ReconfigureEvent`'s
existing off-path, the extension withholds, the silent push's tail guard reads no download. `Direction.wire`
for it is `none`, but `fromWire` and the invite link's dev `direction` key keep refusing it, so it is reachable
only from the settings. `RangeResolution`'s `shareOn/receiveOn → Direction` mapping gains the both-false arm.
*Alternative:* a separate `paused` flag. Rejected: a second field that must agree with `direction` in every
consumer, where one enum value cannot disagree with itself.

**D7 — The both-off status line is a `SyncHealth` value ranked first after Loading.** `SyncHealth.Inactive`
(rendered "Not sharing or receiving") is reduced when `direction == Neither` and `syncHealth` would answer
`InSync` — i.e. no arrow is shown — so work left in a switched-off direction still shows as progress, per the
not-masked rule above `syncHealth`. It sits ahead of the access, network, not-started and unverified lines in
the priority. Counts are hidden because they are shown only for `InSync`/`Syncing`. It is not tappable (the
explanation rows already route to settings).

**D8 — One card in `ParticipationSections`.** Share (+ range row and notes), receive, album and mobile data
become rows of a single `AppToggleCard` separated by `AppToggleDivider`, for both callers. The album and
mobile-data `AppToggleSection` cards become `AppToggleRow` + note inside it.

**D9 — The sheet primitive.** A new `:ui:components` sheet over `ModalBottomSheet` with `skipPartiallyExpanded`,
a drag handle and no other chrome, its top held below the status bar plus a strip (≈ the title row's height)
so the scrim shows the joined screen above it. Scrim tap, back and swipe all dismiss through one
`onDismiss` that dispatches the close intent. `AppInfoSheet`/`AppQrSheet` are the existing sheets to match.

## Risks / Trade-offs

- [A config holding `Neither` cannot be read by an older build — a TestFlight downgrade would read it as no
  membership] → Accept: downgrades are not a supported path; the member rejoins. Noted in the release notes'
  internal label, not for customers.
- [Every switch flip re-runs the whole reconfigure: manifest bump, album ensure+gather, download reconcile] →
  Acceptable cost (each is idempotent and best-effort; the gather is detached). If it ever shows, coalesce in
  `ReconfigureEvent`, not in the UI.
- [A member who switches both off has silently stopped contributing, and may not notice] → The joined
  screen's status line says so first, outranking every other line (D7).
- [Removing the event-closing dependency question: does a `Neither` member still "settle"?] → A member that
  shares nothing has an empty manifest and nothing pending, so it settles at once; covered by a feature test
  in tasks.
- [The rig's `/user/reconfigure` and `cancelReconfigure` verbs change meaning] → Update the vocabulary and
  `:test:control`'s client together; `RigVocabulary` makes a gap a red build.

## Migration Plan

No data migration: existing configs hold one of the three old values and decode unchanged. Rollback is a
revert; a device that switched both off on the new build reads as not joined on the old one (Risks).
