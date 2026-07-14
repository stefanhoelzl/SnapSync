## Context

The join gate is already a phase machine. `StatusContainerHost.startPending(eventId)` sets a
`PendingJoin` and runs `loadInto()`, which turns `GET /events/:id` into a `JoinPhase` —
`Ready` / `NotFound` / `LoadFailed` — and the reduction then renders it either as the full-screen
`UiState.JoiningEvent` (when `config == null`) or as the compact switch dialog carried on
`Joined.pendingSwitch` (when `config != null`). Both a scanned QR **and** a freshly created event enter
through the same door: `SnapSyncRoot` wires `onMinted = { host.onEventCreated(it) }`, and
`onEventCreated` is `startPending`.

Permission is entirely absent from that flow. `PermissionStatus` is a three-state
`StateFlow` (`NOT_DETERMINED` / `DENIED` / `GRANTED`, with iOS's `.limited` mapped to `DENIED`), and
`PermissionRequester.request()` is a fire-and-forget command — it returns nothing, cannot suspend, and
its outcome arrives only via `PermissionStatusSource`. Today the sole caller is a tap on the amber
`NeedsAccess` status line on the **joined** screen. So a person can scan, confirm, enroll, provision,
and arrive at the joined layer without ever having been told what photo access is for; the system dialog
then fires from a pill tap, cold.

That is backwards for this app. The inherited danger CLAUDE.md opens with is that a default meaning
"back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's event". The
moment someone is handed the photo-library dialog is the moment they should already have been told, in
plain words, that photos they take will be shared automatically with everyone in the event.

Two constraints shape everything below. The **design-system** spec forbids appearance parameters and any
Material 3 type in an `App*` signature, and requires semantic containers to own arrangement. And
**`join-event`** already requires that "no config is saved and **no upload producer is enabled** until
the user confirms" — a requirement this change turns out to be able to *violate* if it is not careful.

## Goals / Non-Goals

**Goals:**

- Explain photo access, in the app's own vocabulary, before the system dialog is ever raised on a first
  join — and make the dialog reachable in that flow only through that explanation.
- Cover the creator and the scanner with one surface, since they already share one gate.
- Keep the explainer honest: never promise a dialog that cannot appear, never claim a scope the
  membership cannot produce, never describe the app as a backup.
- Leave the permission ports, the three-state model, and the joined layer's existing affordance exactly
  as they are.
- Close the divergence between the composition root and the `upload-lifecycle` contract that this change
  would otherwise turn from a curiosity into the default first-run path.

**Non-Goals:**

- Changing `permission-gate` — the ports, the three-state read, `.limited → DENIED`, the Settings route.
  The explainer is a new *caller* of `request()`, not a change to it.
- Fronting the joined screen's amber pill with the explainer. (Considered and rejected — see Decision 5.)
- Explaining on the switch path. (Considered and rejected — see Decision 4.)
- Any change to the upload path itself: the ledger, the discovery cursor, the capture-date cutoff, the
  byte partition, reconciliation. `UploadArm` changes *when* a producer is armed, never what it does.
- Auditing the rest of `sync-status-screen`. Only the two provably-dead requirements go.

## Decisions

### 1. The explainer is a `JoinPhase`, produced by the details load — not a new `UiState`

`JoinPhase.ExplainAccess(name, defaultCutoff)`. `loadInto()`, on a `Found` result, chooses it over
`Ready` exactly when `configSource.config.value == null` **and**
`permissionSource.permission.value == NOT_DETERMINED`. `onAcknowledgeAccess()` calls `request()` and
swaps the phase to `Ready(name, defaultCutoff)` — which is the only reason `ExplainAccess` carries those
two fields; it never displays them. Cancel reuses the existing `onCancelJoin()` verbatim.

`join-event` already frames the gate as "a **distinct, extensible `UiState` family** rather than a bare
confirm dialog, because joining is where a member's participation is configured, and those options were
always going to accumulate". This is that growth.

*Alternatives considered.*

- **A new top-level `UiState` rung above `Joined`, after commit.** Rejected: it needs an
  "acknowledged" flag persisted somewhere, or a user who denies is trapped on the explainer forever. The
  phase approach needs no such flag — the phase shows once per join by construction.
- **Explaining *before* the details fetch**, as the gate's first phase. Rejected: an expired or invalid
  invite (`404 NotFound`) would have already fired the photo-library dialog for an event that does not
  exist.
- **Explaining *after* the confirm surface**, so the copy could name the exact chosen date and direction.
  Rejected: the ask then lands after the user believes they are done, and the point of the explainer is
  to precede the commitment, not to trail it.

*Permission is read as a snapshot, not observed.* The phase is chosen once, at load, and advances only by
user action. This keeps the join machine independent of the permission flow, and it means the reduction
does not need an acknowledged flag to stop re-deriving the explainer on every permission emission. The
degenerate case — the user backgrounds the app, grants in Settings, returns — leaves the explainer on
screen for a permission already held; the confirm then calls `request()`, which is a harmless no-op, and
advances. Not worth handling.

### 2. `NOT_DETERMINED` only. `DENIED` skips it

iOS raises the photo dialog **at most once**. From `DENIED`, `request()` is a silent no-op. An explainer
whose confirm produced nothing would be a lie, and the button would be a dead end. So `DENIED` goes
straight to `Ready` and, after joining, meets the joined layer's existing "Turn on full access in
Settings" affordance — the path that actually works from that state.

*Alternative considered:* show the explainer to `DENIED` users with an "Open Settings" confirm instead.
Rejected as a second copy/CTA variant on the single most consequential screen in the app, for a state the
joined layer already handles.

### 3. Copy: share-first, direction-neutral, in the app's existing vocabulary

> **Photo access**
>
> Photos you take will be shared automatically with everyone in the event.
>
> SnapSync needs access to your photo library to do this — and to save the photos other members share
> with you.
>
> Only photos taken after the date you pick next are shared.
>
> `[ I understand ]`  `[ Cancel ]`

Three deliberate constraints:

- **Share-first.** The automatic sharing is the half that deserves informed consent, so it is the first
  sentence.
- **Direction-neutral.** The explainer precedes the direction picker, which defaults to Both but can be
  set to Download-only. Full read-write access is genuinely required for *both* halves — reading your
  photos to share them, and writing other members' photos into your library — so naming both keeps the
  screen true whatever the user picks next.
- **No jargon, no event name.** The app never says "cutoff" to a user; its existing rows say "Only photos
  taken after this date are shared to the event" and "Share your photos and receive the event's photos".
  The explainer matches that register. The event name is deliberately omitted — this is a statement about
  what the app does, not about which event.

The third paragraph is the counterweight to the first: it tells the user the scope is bounded *before*
they grant, and it points at the row they are about to see. And because the system dialog lands over the
confirm surface, they answer it with the date row already behind it.

Both live rules inherited from the deleted `PermissionBlocked` requirement are preserved: CTA-only
priming (nothing auto-requests), and no "backup" framing.

### 4. The switch path does not explain

`loadInto()` feeds both surfaces, so the gating clause is `config == null` — a switch, which by
definition has a config, can never produce `ExplainAccess`. `SwitchDialog`'s exhaustive `when` therefore
carries one unreachable `-> Unit` branch, commented with why.

The mechanical reason is that `PendingSwitch` renders as an `AppConfirmDialog` (title, confirm, cancel),
which has nowhere to put three paragraphs — fitting one would mean growing a new `App*` dialog. But the
substantive reason is that the population is already covered: to be *switching*, you are sitting on the
joined layer, and if your permission were `NOT_DETERMINED` you would be looking at the amber "Allow photo
access" pill.

*A tempting inference, recorded because it is wrong:* one might conclude that after this change,
`Joined` + `NOT_DETERMINED` is unreachable — the gate's only exits are the confirm (which fires a dialog
that always resolves) or cancel (which joins nothing). It is not unreachable. `EventConfig` lives in the
**Keychain** (`KeychainConfigStore`, `kSecAttrAccessibleAfterFirstUnlock`), and iOS Keychain items
**survive app deletion** while TCC permission does not. So *join → grant → delete the app → reinstall*
lands the user directly on `Joined`, never touching the gate, with permission reset to `NOT_DETERMINED`
and the ALLOW pill showing. That is a permanent, recurring path — the same reinstall case
`event-rejoin-reconciliation` exists for — not a legacy cohort that drains away.

### 5. The amber pill keeps calling `request()` bare

Given Decision 4's finding, the obvious next move is to front the pill with the explainer too, making the
invariant *"`request()` is only ever reachable through the explainer"*. Considered, and rejected.

The pill's `NOT_DETERMINED` state is reached by exactly one kind of person: someone who already joined
this event, already saw this explainer, already granted, and has reinstalled the app. Re-explaining to
them is noise, and the pill tap ("Allow photo access") is itself a deliberate act. `request()` therefore
keeps two call sites — the explainer's confirm and the pill — and the joined layer, `JoinedLayer`,
`SyncHealth`, and `AccessPrompt` are untouched. The only genuinely un-explained population is installs
that joined *before* this ships and never granted; they get exactly what they get today, so nothing
regresses.

### 6. `AppExplainer` is a body, not a screen

`AppExplainer(headline: String, paragraphs: List<String>)` renders the neutral `StatusIndicator.Photos`
glyph, the headline, and the spaced paragraphs — and nothing else. `JoiningEventScreen` pins
`PrimaryButton("I understand")` and `SecondaryButton("Cancel")` in the same bottom action cluster that
already holds Join/Cancel and Retry/Cancel, so the explainer's buttons land where every other phase's do.

`StatusIndicator.Photos` already exists, documented as "a neutral photo-library glyph: an ask, not a
fault" — built for the deleted permission gate and unused since. This is what it was for.

*Alternatives considered.* Reusing `StatusHero` + stacked `StatusHint` (rejected: `StatusHint` is a muted
*caption*, and using it as body copy on the app's most consequential consent screen misuses its
semantics, while leaving paragraph spacing to the screen). Cramming the paragraphs into
`StatusHero.detail` with embedded newlines (rejected: it smuggles layout into a data string, which is
exactly what "semantic containers own arrangement" exists to prevent). Giving `AppExplainer` its own
buttons (rejected: it would break the screen's body/actions split and misalign the buttons against the
other phases).

### 7. The upload arm must stop arming a producer for no event — and this change *requires* it

This is the non-obvious one, and it is not optional.

The explainer creates a state the system has never routinely seen: **photo access `GRANTED` while
`config` is still `null`**, for as long as the user lingers on the confirm surface — and permanently, if
they cancel. Previously the only affordance that could grant lived on the joined screen, so a grant
implied a membership.

`SnapSyncRoot` answers that state with:

```kotlin
private fun uploadArmEnabled(): Boolean =
    config.config.value?.direction?.includesUpload ?: true   // no membership ⇒ armed
```

So a grant at the explainer fires `uploadArm.onPermissionGranted()` → `producer.start()` **before the
user has confirmed the join**. That directly violates `join-event`'s existing requirement that "no config
is saved and **no upload producer is enabled** until the user confirms". The explainer cannot ship
correctly without fixing it.

It is also already a contract violation on its own terms. `upload-lifecycle` says the arm is enabled
"exactly when photo access is `GRANTED` **and** *the configured membership's* direction includes upload" —
with no configured membership there is no direction, so the conjunct is false. The `?: true` is a
divergence between the untested composition root and the spec, rationalized in a `UploadArm` KDoc ("the
arm is allowed and is inert anyway") that the spec never blessed. The same KDoc, two paragraphs earlier,
warns against precisely this: lifecycle decisions "live here — in a tested, platform-free capability —
rather than in the iOS composition root, because this *is* behavior, and the root is wiring-only and
untested. Parking it there is exactly why the destructive-provision bug had no test."

**The fix.** `UploadArm`'s seam becomes three-valued:

```kotlin
// null = no event joined
private val membershipIncludesUpload: () -> Boolean?
```

`onPermissionGranted()` starts only on `== true`; `onProvision()` likewise (at provision the membership
is non-null by construction, so its `else stop()` is unaffected); `onLeave()` is untouched. The root
deletes `uploadArmEnabled()` and wires a pure projection — `{ config.config.value?.direction?.includesUpload }` —
with no `?:` in it at all. A single lambda, so there is no two-read race between "is there a membership"
and "does it upload".

The producer is then armed at **provision**, the only moment there is an event to upload to:

```
explainer → [I understand] → request() → GRANTED, config == null
                                            │
                                  onPermissionGranted()
                                  membershipIncludesUpload() == null
                                            └─▶ no verb            ← nothing armed
                                            │
Ready → [Join] → enroll → provision ────────┤
                                            ▼
                                  uploadArm.onProvision()
                                  isGranted() && includesUpload
                                            └─▶ start()            ← armed at the join
```

*How bad was the old behavior, concretely?* Both tiers' cycles do guard on config —
`UploadExtensionRoot.process()` re-reads it and returns `COMPLETED` ("skipping cycle — eventId
present=false") before ever constructing `UploadCycle`; `UrlSessionUploadController.runCycle()` does the
same ("url-session cycle skipped — no joined event"). So no bytes move, no library walk runs, and
`UploadCycle`'s no-default `photoCutoff` — "a cycle without a cutoff would upload the whole library" — is
never reached. The work is inert. But on the app-driven tier `BackgroundUploadPump.onStart()` re-arms
*unconditionally* (it is the only trigger that can create the first `BGProcessingTask`), and
`onBackgroundTask` then "always re-submits", so a first-time iOS 18–26.0 user who taps "I understand" and
cancels leaves a self-perpetuating background heartbeat for an event that does not exist. Wasted wakes,
not lost data — but this capability carries a scar in exactly this area, and the fix is four lines in a
tested module.

*Alternative considered:* accept it and record it as verified-inert, keeping the change scoped to UI.
Rejected once it became clear the `?: true` makes the explainer violate `join-event`'s existing gate
requirement. "Inert" was never the standard; "no upload producer is enabled until the user confirms" is.

### 8. Two dead `sync-status-screen` requirements go with it

That spec still carries "Status screen renders UI state" and "Status screen renders permission-blocked
states", both describing the hero-replacing `UiState.PermissionBlocked` gate deleted by
`2026-06-27-permission-on-status-screen`. Neither exists in code, and the spec's own surviving
requirement already says they were removed — so it has been contradicting itself since that change
archived. Adding a third permission surface on top of that would leave the repo describing three, two of
them fiction. Their two live rules (CTA-only priming; no "backup" framing) are re-homed onto the new
`join-event` requirement rather than dropped.

## Risks / Trade-offs

- **The explainer adds a screen to the first-run path, and its Cancel abandons a just-created event.** →
  Pre-existing: every join phase already pins a Cancel that clears the pending join, and a creator's
  `POST /events` has already minted the event by then, so cancelling at `Ready` orphans it identically.
  The explainer adds an *earlier* place to hit the same button, not a new outcome. Out of scope; worth a
  separate look at whether a creator's cancel should delete the minted event.

- **The copy makes an unconditional sharing claim before the direction is chosen.** → A Download-only
  joiner is told "photos you take will be shared automatically" and then opts out of exactly that. The
  claim describes the *maximum* the grant enables, which is the right thing for a consent screen to
  describe, and the second sentence names the receive half so the permission is justified in both
  directions. Accepted deliberately; the alternative (moving the explainer after the gate so the copy can
  be exact) trades informed consent for precision at the wrong moment.

- **`SwitchDialog` gains an unreachable branch.** → Kotlin's exhaustive `when` forces it. It is one
  `-> Unit` line with a comment naming the invariant (`config != null` on every switch, so `loadInto`
  cannot emit `ExplainAccess`), and the container test asserting a switch never explains is what keeps it
  dead.

- **The `UploadArm` change touches the tier machinery from a UI change.** → It is four lines in a tested
  `commonTest`-covered capability plus a deletion in the root, it removes a decision from the untested
  root rather than adding one, and it brings the implementation *back* to a contract it already violated.
  The new `commonTest` cases run on both JVM and `iosSimulatorArm64`. The risk of *not* doing it is a
  `join-event` requirement violated on the change's own happy path.

- **Legacy installs that joined before this ships and never granted see no explainer.** → They keep
  today's amber pill and today's bare `request()`. Nothing regresses; they simply do not gain.

- **The `autoJoin` dev deeplink bypasses the explainer entirely.** → Correct and intended:
  `autoConfirm()` never enters `startPending`, so the headless dev/test path is structurally unaffected
  and the on-device loop in CLAUDE.md keeps working unchanged.

## Migration Plan

None. No persisted state, no schema, no wire format, and no backend behavior changes. The explainer is
chosen from a live permission read at join time, so there is nothing to migrate and nothing to backfill;
existing joined installs are unaffected on next launch. Rollback is a revert.

## Open Questions

None outstanding. Two were resolved during design and are recorded above rather than left open: whether
`Joined` + `NOT_DETERMINED` survives this change (it does — the Keychain-outlives-uninstall reinstall
path, Decision 4), and whether the new `GRANTED`-with-no-config state is safe to tolerate (it is inert,
but tolerating it would violate `join-event`, so it is fixed rather than tolerated — Decision 7).
