## Context

Under a `LIMITED` grant the member's hand-picked selection IS the membership's own-photo scope
(capability `limited-photo-access`), and the joined layer already offers one resting affordance to
widen it: "Choose more photos", driving the system limited-library picker. The grant *itself* can
also be widened to Full Access — but only in the Settings app: PhotoKit's
`requestAuthorizationForAccessLevel` returns immediately once the status is determined and never
re-presents the dialog under `.limited` (platform contract; expiry trigger: an iOS release adding a
re-prompt API). The app currently offers no route there from the limited resting state — the
`openSettings` command exists but is reachable only through the `DENIED` status-line affordance.

Two prior measurements shape the surrounding flow and are consumed here unchanged: a Settings-side
permission flip terminates the app (TCC behavior), after which the ordinary cold launch under the new
grant runs the baseline read and the permission-aware `UploadArm` starts the `GRANTED` producer; and
the rejoin reconcile (`event-rejoin-reconciliation`) seeds already-uploaded photos as `COMPLETED`, so
a scope widening never re-uploads.

## Goals / Non-Goals

**Goals:**

- Give a limited member an in-app, discoverable route to Full Access, next to the existing
  selection-widening route.
- Keep the limited grant first-class: the new affordance is quiet, present-not-pushed, and never
  framed as fixing a problem.
- Close the change with an on-device proof of the whole upgrade path, end to end.

**Non-Goals:**

- No change to how the `LIMITED→GRANTED` transition is handled (it already works via a manual
  Settings flip today).
- No nudging: no badge, dialog, banner, or any escalation of the affordance under any sync health.
- No new command, port, `UiState` field, or design-system component.
- No guidance UI inside Settings territory (the OS owns everything after the deep-link).

## Decisions

**D1 — The tap is a bare Settings deep-link (reuse `openSettings`).** iOS offers exactly one
mechanism, so the only real choice is what wraps it. Alternatives: an explainer dialog before the
jump (rejected: adds a surface to a deliberately minimal screen for a two-step OS flow the label
already names), or a caption under the button (rejected: same reason, milder). The existing
`UserCommands.openSettings` → `PhotoAccessRequester.openSettings` chain is reused verbatim; the
joined-layer composable already receives `onOpenSettings` for the status line's `DENIED` case, so
the change is confined to `:ui:screens` rendering.

**D2 — No interstitial consent surface.** Functionally this button is a short route from "I share
these 5 photos" to "I share everything post-cutoff" — the exact direction the project's backup-era
warning is about. It is safe without extra ceremony because (a) the widening takes two deliberate,
OS-mediated steps whose label says what they do, and (b) the widened scope is bounded by the same
guardrails that bound every `GRANTED` member from day one: the required cutoff and the origin
exclusions (`photo-selection-policy`). A `LIMITED` member who upgrades ends in precisely the state a
full-access joiner starts in. Alternative considered: an in-app confirmation sheet restating the
consequence — rejected as nagging a user who has already chosen, and as implying the full-access
state is dangerous when it is the app's ordinary state.

**D3 — Same component, same condition, fixed order.** `SecondaryButton` (already borderless quiet
text — the iOS text-action idiom), rendered under the existing `canChoosePhotos` flag
(`permission == LIMITED`), directly below "Choose more photos", in every sync health. Ordering
rationale: selection-widening stays the primary, cheaper offer; the grant switch is the larger step
and sits second. No new `UiState` field — the two affordances are one predicate, and a second flag
could only drift from the first.

**D4 — Upgrade visibility rides the existing status line.** The status screen is numberless (state
text + `HIDDEN`/`STATIC`/`PULSING` arrows); after the upgrade the backlog simply renders as the
upload arrow waking until the wider scope drains. No new UI acknowledges the upgrade — deliberate:
the app doing its job is the acknowledgement.

## Risks / Trade-offs

- [The member taps the button, lands in Settings, and doesn't find "Full Access"] → The label uses
  the Settings page's own iOS 18+ vocabulary ("full access"), and the deep-link lands on the app's
  page where Photos is one row away. Accepted residual: the OS owns that surface; we do not add
  guidance UI for it.
- [The Settings flip kills the app mid-session] → Platform behavior, not ours to handle; the cold
  relaunch under `GRANTED` is the already-shipped path. The device pass observes the kill/relaunch
  explicitly so it is never mistaken for a crash.
- [A wider scope uploads something the member didn't intend] → Bounded by cutoff + origin
  exclusions, same as every full-access member; the policy admits-on-doubt by design and that
  posture is unchanged here.
- [Two stacked equal-weight text buttons read as a list rather than a hierarchy] → Accepted; both
  are genuine peer offers, and the fixed order carries the priority.

## Verification plan (device pass, pre-merge)

Operator-assisted over USB (taps on system surfaces cannot be driven headless — WDA is not set up):
the operator taps permission dialogs/Settings; the agent drives build, sideload, launches, log pulls,
and screenshots. Sequence: sideload a dev IPA (ssh-mac loop) → operator pre-creates a fresh event,
join headless via `SNAPSYNC_EVENT_LINK` → operator sets `LIMITED` with a small selection → confirm
the affordance renders and its tap lands on Settings → seed above-floor post-cutoff assets
(`SNAPSYNC_SEED_POLICY`; app-created assets auto-join the limited selection) → operator flips Full
Access (expect OS kill) → relaunch; `debug.log` shows `GRANTED`, the PhotoKit-tier producer start,
and reconcile seeding (nothing re-uploads) → re-provision to trigger an extension invocation →
confirm a newly-in-scope seed lands in the bunny storage zone (the authoritative check).
