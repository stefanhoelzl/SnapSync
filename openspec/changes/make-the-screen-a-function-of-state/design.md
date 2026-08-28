## Context

`UiState` is documented as the screen's *"display-ready projection"*, and the founding spec
(`2026-06-10-sync-status-screen-states`) wrote it for a screen that was a status line and nothing else.
The create layer, the join gate and the reconfigure surface all arrived afterwards, and none of them
re-examined that sentence. What accumulated instead:

- **Five data parameters beside the state.** `membership`, `inviteUrl`, `eventName`, `transientError`,
  `renameStatus`. Each was justified as mirroring the one before it.
- **Seven remembered choices per decision surface** — the `Participation` holder — plus the resolution
  that derives ten values from them.
- **Sixteen field declarations across four `JoinPhase` variants**, restating the same four event facts
  because a retry commits without passing back through the loaded phase and there is nowhere else to
  put surface-scoped data.

The failure mode is silent, and it has already happened: `app/desktop/.../StatusPane.kt` omits
`transientError`, so neither desktop harness can render the invalid-link banner, and nothing reports it.

The chain of justification terminates in one decision, `event-invite-qr` **D3**, which named no expiry
trigger. Its two premises are now false — see D2 below — and `reconfigure-membership` D4 and
`event-rename` D10 both cite it rather than arguing independently.

A large refactor (`origin/main`, thirteen commits, `complexity-budgets`) recently gave several of these
concepts names — `Participation`, `RangeSelection`, `StatusOverlayState`, `StatusActions` — and made the
resolution rules pure, shared functions. It did not move anything across the presentation boundary. This
change does, and therefore disagrees with fresh, deliberate, documented decisions rather than tidying
drift. It is written to read that way.

## Goals / Non-Goals

**Goals:**

- `StatusScreen` becomes a function of `UiState` and its callback bundle. No data input reaches it by
  any other route.
- The rule is **mechanically gateable**: no `mutableStateOf` in `:ui:screens` outside a short allowlist.
- Every standing decision this reverses is amended in its spec, with a superseding decision record.
- The clamping rules gain direct unit tests.
- No user-visible behavior changes.

**Non-Goals:**

- Moving user-facing copy into the reduction (see D13).
- Introducing an i18n bundle. It is the change that would make a no-string-literals gate possible, and
  it is a separate proposal.
- Collapsing `SyncHealth.NotStarted(startsAt)`, which becomes redundant with `membership.startsAt`
  (D13).
- Hoisting `:ui:components`' internal popup flags. The rule is scoped to the screen (D13).
- Changing what the shareable-count query computes, when it is permitted to read, or what it costs
  under a partial grant.

## Decisions

### D1 — The rule: what the screen SHOWS is `UiState`; how it DRAWS is local

Two candidate rules were on the table. The narrower one — *"input the reduction must resolve or
validate goes in state"* — requires a judgement call per surface, which is the shape that drifts. The
adopted rule is mechanical and needs no judgement.

Named exceptions, both stated rather than derived:

1. **In-progress text content** stays local. A per-keystroke round trip through an async container
   fights the IME (cursor jumps, dropped composition on non-Latin input). A sheet's **presence and its
   seed** are state; what is half-typed is not. Consequence, stated so it is not discovered later: a
   remote render can know a sheet is open and with what prefill, but not what has been typed — this
   change buys structural fidelity, not pixel fidelity.
2. **`:ui:components` may own its own popup visibility.** A design-system control's popup is part of
   *how it draws*; hoisting `AppCutoffSection`'s picker flags would make every component stateless and
   is a far larger change than this one.

**Why the form lift is in scope rather than deferred.** Its own benefits are modest after the recent
refactor — the duplication is gone, the resolvers are already pure, `Participation` already scopes the
lifetime. The reason to include it is the gate: with seven `mutableStateOf`s left in `:ui:screens`, the
allowlist would have to exempt the module's largest state holder, and the gate would assert
approximately nothing. A rule whose gate exempts its biggest violation is a comment, not a rule.

**Alternative rejected:** fold the parameters only and leave the form. It closes the silent failure mode
but leaves the rule unenforceable, which is how this drift accumulated the first time.

### D2 — `event-invite-qr` D3 has expired; both of its premises are false

D3 reads: *"The reduction (`reduceFrom`) stays the pure sync projection… This keeps the
engine→status→presentation projection untouched and the invite URL (which comes from the config seam,
not the engine chain) out of the snapshot tests."*

- **"The pure sync projection"** — `reduceFrom` takes eight inputs today (config, permission, snapshot,
  creation, download, pending, nowTick, attested). Exactly one is the engine chain. The `NothingToSync`
  state D3 names as staying a `data object` no longer exists.
- **"Comes from the config seam, not the engine chain"** — `config` is `reduceFrom`'s **first**
  parameter, and the reduction already folds `config.startsAt` into `SyncHealth.NotStarted` and
  `config.endsAt` into `Joined.ended`. Config-seam data entering `UiState` — the exact thing D3 said
  would not happen — happened twice, under two later changes, neither of which reopened D3.

The boundary D3 drew does not exist any more. What survives is D3's **other** half — one invite-URL
derivation, so the rendered QR and the shared link cannot drift — and that survives the fold intact:
`encodeEventUrl` is called once, in the reduction, and `onShareInvite` reads the result off the state.

D3 named no expiry trigger, which is why nothing noticed. Every decision in this document names one.

### D3 — `Joined` carries `EventConfig`, not a three-field projection

`Joined(membership: EventConfig, inviteUrl: String, renameStatus, health, pendingSwitch,
canChoosePhotos, ended)`, with `membership` **non-null** because `reduceFrom` returns `Joined` only
inside its `config != null` branch. That deletes five `membership != null` guards and one `!!` in
`StatusScreen`, all of which guard a combination the reduction already makes unreachable.

**Alternative rejected:** carry `eventId` + `eventName` + `inviteUrl` and leave the aggregate behind.
Three fields copied out of a nine-field object, unmodified, is not a projection — it is a subset with
maintenance, and it fails the way `TitleNeed` failed: a new surface needs one more field, so the type
widens reactively. `UiState` is not a string projection; it is a typed projection already built out of
`model/` vocabulary (`EventStart`, `EventEnd`, `DeletesAt`, `Arrow`, `PermissionStatus`), and
`EventConfig` is a `model/` type that fits that pattern exactly.

Accepted cost: with the aggregate in hand, a composable *could* re-derive a range from `minPhotoDate`
rather than reading the lifted form. The `mutableStateOf` gate does not catch that. The three-field
subset only narrows the temptation — `eventId` and `name` are equally re-derivable-from — so it buys
little against a real cost.

### D4 — The invite URL is NOT persisted into `EventConfig`

`inviteUrl` is a reduced field on `Joined`, recomputed from `membership.eventId` on each reduction —
not a new `EventConfig` field.

1. It is not a function of `eventId` alone. `LINK_ORIGIN` is **generated at build time** from the
   `snapsync.domain` Gradle property (`domain/build.gradle.kts`). A config written by one build and
   read by the next, after a domain change, would carry a QR pointing at an origin the app no longer
   uses, with the correct `eventId` unused beside it. The `eventId` is the stable half; the encoding is
   not.
2. It would reintroduce exactly the drift D3's surviving half prevents — a stored string and a fresh
   derivation that can disagree. `event-invite-qr` requires the derivation be *"deterministic and
   require no network call and no secret"*, which is a statement that it never needs storing.
3. `EventConfig` is decoded by the **extension process** (`FileBackedConfigStore`, `:adapter:ios:ext-safe`).
   A display-only field would enter a type decoded by a process with no UI, in a store where
   *undecodable* means *"left the event"*.

**Expiry trigger:** if the invite URL ever becomes **server-issued** rather than derived — a short link,
a signed link, anything the device cannot reconstruct — it stops being a derivation and becomes a fact
about the membership, and belongs in `EventConfig`.

### D5 — `eventName` is deleted, not folded

It is `config.name` with a nullable wrapper, and the wrapper is a fossil: its KDoc still describes
`MembershipRefresh.fetchNeed` / `TitleNeed`, deleted by `2026-08-03-remove-nameless-config-fallback`,
which also made `EventConfig.name` a required non-null constructor parameter. Today `eventName` is null
**iff** the state is not `Joined` — a distinction `UiState` already discriminates.

The screen already half-abandoned it: two of the four name-render sites read `membership.name` and two
read `eventName`. Deleting the parameter makes all four agree.

### D6 — `transientError` coalesces into `CreateEvent.error`, with the precedence stated

One banner, one field. `StatusScreen`'s render site already computes `transientError ?: state.error`;
this moves that expression into `reduceFrom` and states the precedence — **the transient wins** — in the
spec rather than leaving it an unspecced implementation detail.

The self-clear choreography stays in presentation, as `event-creation-ui` requires. Mechanically the
existing `MutableStateFlow` becomes another `combine` input.

**The "no event history" objection dissolves on inspection.** `sync-status-screen` says the reduction
*"MUST depend only on the latest snapshot (no event history)"*, which reads as a ban on remembered
values — but the sentence lost its reason in an edit. The original wording
(`2026-06-10-sync-status-screen-states`) is *"…so any missed intermediate snapshot cannot corrupt the
displayed state."* It is about the snapshot **stream** — the reduction must not accumulate across
emissions — not about reading a remembered value. If it were the latter, `CreationStatus.Failed`, which
is a remembered failure and *is* reduced, would already violate it. This change restores the clause.

### D7 — The phase collapse keeps "Ready implies details" unrepresentable-otherwise

```
JoinPhase
├── Loading · NotFound · LoadFailed
└── Detailed(event: EventDetails, step: Step)
       Step = ExplainAccess | Ready | Committing | CommitFailed    (all data objects)
```

Sixteen field declarations become four, once, and the three `when` extractors in `JoinPhaseWindow.kt`
become one access.

**Alternative rejected:** a flat `JoiningEvent(eventId, event: EventDetails?, phase)`. It removes the
same duplication but makes `Ready` with absent details **representable**, which the current shape —
for all its repetition — does not. Trading a type guarantee for tidiness is the wrong direction.

### D8 — The form is lifted; the count is a sibling flow, not a feedback cycle

The seven `Participation` values move into `StatusContainerHost` as its own `MutableStateFlow`, seeded
by the reduction: defaults at the join gate, and the existing lossy reconstruction from the persisted
membership at the reconfigure surface (`reconfigure-membership` D5 is unchanged — only its location
moves). The pure resolvers (`resolveFrom`, `resolveUntil`, `directionOf`, `nowWithinWindow`) are called
from the reduction **unchanged**.

The count is the one input that is `suspend` while the reduction is pure and synchronous. It is **not**
fed back from `UiState`:

```
form: MutableStateFlow ──┬──────────────────────────────► combine ──► reduceFrom ──► UiState
                         └──► shareableCount query ──────┘
```

`form` and `count` are sibling inputs. Precedent: `nowTick` is already a side flow feeding this
`combine`.

`formatRange` moves to `:ui:components` beside `appDateTimeLabel`/`appDateLabel` — it takes two
`LocalDateTime`s and reads no clock or zone, so it is already pure and sits on `CutoffFormatter` only by
co-location. With the range resolved to `LocalDateTime` in the reduction (which already holds the
device's zone), `cutoff` leaves the screen's signature, and device wall-clock values travel in the state.

**`join-share-count`'s SHALL is honoured, not contradicted.** That spec requires the count be a *query
parameterised by the candidate cutoff*, "not a passive reduction of committed state", because the cutoff
is uncommitted. It still is: the query still exists, still takes the candidate cutoff, and still runs
against **uncommitted** choices. Only its caller moves — from a `LaunchedEffect` in the view to a flow
in the container. The reduced field is the query's *result*, not a reduction of committed state.

### D9 — The reconfigure surface is a nested sum on `Joined`, not a new family

`Joined(…, surface: JoinedSurface)` where `JoinedSurface = Status | Reconfigure(form)`.

This keeps `reconfigure-membership` D4's **letter** — it introduces no new `UiState` family — and its
**reason**: D4 rejected a family because it *"would route screen-open through a flow command for a pure
navigation act"*, and opening remains a container-local intent touching no port and calling no
`UserCommands` member.

**Alternative rejected:** a sibling `UiState.Reconfiguring` family. `StatusScreen` renders the switch
dialog **outside** the reconfigure branch, so a switch confirmation can appear over the reconfigure
surface; a sibling family would have to duplicate `pendingSwitch`, `membership` and `ended` to model a
surface that is still, in every other respect, the joined layer.

Consequence: `screenLabel` loses its second parameter and becomes a pure function of state. It needs
`reconfigureActive` today only because `UiState` cannot say which surface is showing — which also means a
diagnostic dump taken mid-edit currently cannot distinguish the two.

### D10 — `photoPermission` stays a parameter, and moves next to the query

It is **never rendered.** It terminates at `LaunchedEffect(chosenCutoff, chosenUntil, permissionKey)` in
`JoinReadySurface.kt` — a recomposition key so a late first-join grant makes the count appear. Under D1's
rule it is not drift, because it is not shown. It belongs beside `shareableCount` in `StatusActions`,
where the query already lives.

### D11 — The `StatusContainerHost` constructor is bundled first, and it blocks everything else

The host has **14** constructor parameters against a `core`-tier ceiling of **15**
(`config/detekt/core.yml`, `constructorThreshold`). The form source plus the count flow makes 16. The
tier contract states that every number is a ceiling that may only fall, and that raising one needs a
stated forcing proof in the PR — there is none here, so the constructor is bundled instead. This is the
first task, and nothing else can land before it.

### D12 — Order of work, and why the tests come early

1. Bundle the `StatusContainerHost` constructor (D11) — blocking.
2. Add unit tests for the four pure resolvers. They are the safety net for step 4, and they are the
   reason the rest of the migration can be checked without leaning on Compose UI tests.
3. Fold the five data parameters and collapse the phases (D3–D7). Self-contained; the screen still holds
   its form.
4. Lift the form, relocate the count, retire `cutoff` from the signature (D8, D9).
5. Amend the six specs and write the superseding decision record.

Steps 3 and 4 are separately shippable. If step 4 is abandoned, step 3 still closes the silent failure
mode — but the gate is not installable, which is the whole of D1's argument.

### D13 — What is deliberately not done

- **Copy stays where it is.** Moving 136 string literals into the reduction would relocate them to a
  place an i18n bundle would move them out of again, would take copy ownership away from
  `:ui:components` (which owns tone the way it owns spacing), and would deepen an assumption against
  localization — an unnamed future, which CLAUDE.md's rule says not to build **for** and not to deepen
  assumptions **against**. A bundle, not this change, is what would make a no-string-literals gate
  possible.
- **`SyncHealth.NotStarted(startsAt)` stays**, duplicating `membership.startsAt` once `Joined` carries
  the config. Collapsing it trades away `SyncHealth`'s self-containment, which the forge harness relies
  on to forge health values without a membership. The redundancy is named here rather than fixed.
- **Component popups stay in `:ui:components`** (D1's second exception).

### D14 — Decisions taken during implementation

These were settled while building, not while proposing. They are recorded here because each changed the
shape of the change, and a reader of the archive would otherwise find them only in a diff.

**`UiState` became a wrapper, not a sealed interface.** The diagnostic sheet's gesture is on the app-name
label, which EVERY layer renders, so its flag had no home on any one family. Three options were weighed —
a sealed-interface property implemented by all four families, a wrapper, or leaving the sheet's presence
screen-local as a second named exception. The wrapper was chosen: `UiState(layer, overlays)`, with `Layer`
holding the four families. It is the honest model (the overlays genuinely are not layer-scoped), and it
avoids widening the exception list, which was the whole point of D1's gate. Cost: every `UiState.X`
reference became `Layer.X` — 145 across 19 files.

**`FromChoice` / `UntilChoice` moved to `:domain model/`.** The form lives in Compose-free
`:ui:presentation`; the picker lives in `:ui:components`; the two modules deliberately have no edge
between them. `model/` is where `Arrow` already sits for exactly this reason — "the ONE enum both
presentation's reduction and this skin render from" — so this follows the precedent rather than inventing
one. The alternative, an edge from components to presentation, would invert the intended layering.

**The container's new commands are grouped, and the rig's guard was widened to see them.** Fifteen new
intents took `StatusContainerHost` past the `core` tier's `TooManyFunctions` ceiling, so they are grouped
into `form`, `surfaces` and `access` holders — a real grouping (each is one surface's questions), not an
arity dodge. That grouping moved them one level in, where `RigControlChannelTest`'s four-space scrape no
longer saw them: the guard would have silently stopped asking about fifteen commands at the exact moment
the host grew by fifteen. The guard now matches nested declarations too, and the seven that are not wired
carry stated reasons like every other exclusion.

**`cutoff` does NOT leave the screen, contrary to D8.** Two surfaces still need it: the **create form**
(its own local name/date state, which this change does not lift) and the joined layer's clock line. What
D8 got right is that the RANGE form no longer needs it — its bounds arrive resolved. The parameter stays,
and `:ui:screens`'s allowlist names the create form as the one decision surface still holding its own
state.

**Screen tests split rather than shrink.** A screen test can no longer tap a control and assert the
result, because the tap fires an intent and the state a test supplies does not change. Each affected test
became two — *does the tap reach the form* and *does the resolved state render* — with the rule itself
covered in `RangeResolutionTest`. The alternative considered was deleting the interaction half as
redundant; it was rejected because that half is the only thing anywhere proving a control is wired at
all, which is precisely the "silently does nothing" failure this change exists to prevent.

## Risks / Trade-offs

- **This reverses standing decisions rather than tidying drift.** → Every reversal is written into the
  spec it contradicts, with a superseding decision record; none is left as a silent divergence. D2, D3
  and D4 each carry the evidence that the original premise no longer holds, and each new decision names
  an expiry trigger — the omission that let D3 rot unnoticed.
- **Behavior drift during the form lift.** The clamping rules are subtle (resolve `until` first, floor
  `from`'s ceiling to it, so an inverted range is unrepresentable). → The resolvers move **unchanged**,
  and their unit tests land before the lift (D12 step 2), so a regression fails a unit test rather than
  a UI test — or nothing.
- **The count's read behavior under a partial grant.** `limited-photo-access` permits reads only on the
  cold-launch baseline and on observer emissions. → Unchanged: the count re-filters the already-held
  selection snapshot in memory and performs no library walk, so moving its caller from the view to the
  container adds no read and changes no alert behavior. Stated explicitly because a reader has to be
  able to check it.
- **Structural, not pixel, fidelity.** The IME exception (D1) leaves in-progress text out of state, so a
  remote render knows a sheet is open and its prefill but not what has been typed. → Accepted and
  stated; revisit only if a consumer commits to pixel fidelity.
- **Test churn.** `StatusContainerHostTest` (85) grows; `JoinScreenTest` (36) and `StatusScreenTest`
  (60) shed logic-through-UI cases. → This is a quality gain, not only churn: range inversion is caught
  today **only** by a Compose UI test.
- **The complexity ratchet.** Bundling can push field counts around rather than down. → D11 handles the
  one measured ceiling; `ui.yml` already records that `LongParameterList` bundling terminates because a
  bundle of bundles has as many fields as it has groups.

## Migration Plan

Single branch, ordered as D12. Steps 3 and 4 are separately revertable; step 5 lands with whichever of
them ships. No data migration: `EventConfig`'s decode contract is untouched (D4), and `RenameStatus`
gaining `@Serializable` adds no field and changes no persisted payload. No user-visible behavior change,
so no staged rollout.

## Open Questions

- **Does the gate land in this change or follow it?** The `mutableStateOf` allowlist gate in
  `:test:architecture` is D1's justification, but writing it before step 4 completes would fail the
  build mid-migration. Landing it in the same change is preferable; landing it immediately after is
  acceptable. It must not be left unwritten — an ungated rule is what produced this proposal.
- **Do the three separable cleanups ride along?** (a) `renameFailureText` formats display copy in the
  screen while the parallel `CreationFailureReason` copy is formatted in presentation; (b) the
  100-character event-name cap exists three times, one of them an unnamed literal, mirroring
  `api/src/validators.ts`; (c) `onOpenUrl` fires the transient error on a decode failure even while
  joined, where no banner renders, so a bad scan while joined is swallowed silently. Each is small and
  independently justified; (c) is arguably an "Absence is never silent" defect in its own right and may
  deserve its own change.
