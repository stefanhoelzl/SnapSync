## ADDED Requirements

### Requirement: The status screen is a function of its UI state

The status screen SHALL receive **every value it renders** through `UiState`. Beside the state it SHALL
receive only its callback bundle and the shareable-count query with that query's own recompute key
(capability `join-share-count`) — no other data input SHALL reach it by any other route.

The rule is: **what the screen SHOWS is `UiState`; how it DRAWS is local.** Dialog and surface visibility
is *shown*, and SHALL be reduced state. Scroll position, animation progress, focus, and a picker's
internal wheel state are *drawn*, and SHALL remain screen-local.

Exactly two exceptions SHALL exist, and both are stated rather than derived:

1. **In-progress text content** SHALL remain screen-local. A per-keystroke round trip through the
   presentation container fights the platform IME. A text surface's **presence and its seed** SHALL be
   reduced state; the characters typed since it opened SHALL NOT be. The consequence is stated so it is
   not discovered later: a consumer rendering this screen from a transported `UiState` can know a sheet
   is open and with what prefill, but not what has been typed.
2. **The design-system module** (`:ui:components`) MAY own the visibility of a control's own popup, which
   is part of how that control draws itself. This rule is scoped to the screen module.

This SHALL be enforced mechanically rather than by review: an architecture guard SHALL assert that
`:ui:screens` declares no Compose-remembered mutable state outside a named allowlist covering exception
1. An unenforced rule is what produced the drift this requirement removes.

#### Scenario: A data value renders only if it is in the state

- **WHEN** the status screen's parameter list is enumerated
- **THEN** it contains the `UiState`, the callback bundle, the shareable-count query and that query's
  recompute key, and no other data parameter

#### Scenario: A call site cannot silently omit a rendered value

- **WHEN** a host composes the status screen and supplies the state
- **THEN** every value the screen renders is present, because it travels inside that one state — there is
  no separate value a host can forget to pass

#### Scenario: Surface and dialog visibility is reduced state

- **WHEN** the leave confirmation, the rename sheet, the diagnostic sheet, or the reconfigure surface is
  open
- **THEN** the `UiState` says so, and a consumer rendering from that state alone shows the same surface

#### Scenario: The guard rejects new screen-held state

- **WHEN** Compose-remembered mutable state is declared in `:ui:screens` outside the allowlist
- **THEN** the architecture guard fails the build, naming the declaration

### Requirement: A rejected event link is told to the member on whatever layer is showing

A link the decoder rejects SHALL surface to the member **wherever it arrives**. The gate decodes every
delivered link regardless of layer, so the transient invalid-link message (capability `event-link`) SHALL
reach the joined layer as well as the create layer, and SHALL self-clear on the same window in both.

Before this it reached only the create layer: the message was set, the joined layer had nowhere to render
it, and a member who scanned a bad QR while already joined was told **nothing at all**. "Nothing happened"
and "that code wasn't valid" are different answers, and only the first was reachable (spec
`module-architecture`, "Absence is never silent").

#### Scenario: A bad scan while joined says so

- **WHEN** a member is joined and a link the decoder rejects is delivered
- **THEN** the joined layer states that the code was not valid, persisted config is unchanged, and the
  message self-clears

#### Scenario: One choreography, both layers

- **WHEN** the same rejected link arrives on the create layer instead
- **THEN** the create layer's one inline error carries it, and it self-clears on the same window — the
  set-then-clear is stated once, not once per layer

### Requirement: The join and reconfigure form is reduced state

The presentation container SHALL hold the member's **uncommitted choices** on both decision surfaces and
reduce them into `UiState`, rather than the screen remembering them: the two participation switches, the
album opt-in, and the four capture-range preset/custom values.

The container SHALL seed them: with the defaults at the join gate (all switches on, the full event window),
and with the reconstruction from the persisted membership at the reconfigure surface (capability
`reconfigure-membership` — the reconstruction rule itself is unchanged, only its location). The container
SHALL resolve them against the event window using the same rules for both surfaces, and SHALL carry the
resolved bounds, the derived direction, the commit-enabled verdict, and the shareable count in the state.

The resolved capture bounds SHALL be carried as **local wall-clock** values, converted in the reduction
using the container's injected time source, so the screen holds no clock or timezone knowledge and
requires no formatter parameter.

Seeding, resolution and discarding SHALL touch no port. Opening a surface, changing a choice, and
cancelling SHALL remain client-side acts that reach no use case; only the commit (Join, or Save) SHALL
cross a command.

#### Scenario: A choice survives a phase change without the screen remembering it

- **WHEN** the join gate advances `Ready` → `Committing` → `CommitFailed` and the member retries
- **THEN** the retry commits the range and direction the member chose, because those live in the state
  rather than in the composition

#### Scenario: The resolved range cannot invert

- **WHEN** any combination of preset and custom values is reduced against an event window
- **THEN** the upper bound resolves first and the lower bound is floored to it, so the resolved range is
  never inverted, and both bounds lie within the event window

#### Scenario: Cancelling discards the choices and writes nothing

- **WHEN** the member changes controls on either surface and cancels
- **THEN** the choices return to their seed, no port is touched, and no config write occurs

#### Scenario: The screen needs no formatter

- **WHEN** the status screen renders a resolved capture range
- **THEN** it renders wall-clock values the reduction already converted, and takes no clock, timezone, or
  formatter parameter

## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state
The presentation layer SHALL reduce config presence, permission, each observed `SyncStatus`, and any
**pending join** to a display-ready `UiState`. `SyncProgress`, its `SyncStatusSource` seam, the
`SyncStatus` vocabulary, and the three-state classification are owned by the `sync-status` capability —
this screen consumes them.

`UiState` SHALL have exactly these families: the create layer (`CreateEvent(error?)` /
`CreatingEvent`, owned by the `event-creation-ui` capability and outranking everything on config
absence); the **`JoiningEvent`** family (owned by the `join-event` capability, carrying the pending
`eventId`, the loaded event details, the member's uncommitted form, and its details phase), which
represents an interactive join confirmation in progress; and a single **`Joined`** state carrying the
persisted membership, the derived invite URL, the rename status, the selected joined surface, a health
descriptor and an optional **`pendingSwitch`** (owned by `join-event`) for a switch confirmation over the
joined screen. The prior joined states `InProgress`, `Completed`, and `NothingToSync`, the permission
state `PermissionBlocked`, and the standalone `Loading` state are **removed** and fold into `Joined` (the
joined loading first-frame is a health value, `SyncHealth.Loading`).

`Joined`'s membership SHALL be **non-null**: the reduction reaches `Joined` only when config is present,
so a nullable membership would state a combination the reduction makes unreachable and force the screen to
re-check it.

The event details a join confirmation carries SHALL be stated **once** per confirmation rather than
repeated on each phase that needs them. The phases that have loaded details SHALL be expressed as one
detailed phase carrying those details plus its step, so that a step which requires details cannot be
constructed without them; the phases that have none (`Loading`, `NotFound`, `LoadFailed`) SHALL carry
none. A retry therefore commits with the floor, the ceiling and the retention deadline still in hand
without any phase restating them.

The reduction SHALL be: a **pending interactive join with config absent** → `JoiningEvent`
(outranking the create layer); otherwise **config absent** → the create layer (per
`event-creation-ui`); **config present** → `Joined`, **always**, regardless of permission, carrying a
`pendingSwitch` when a switch confirmation is in progress. The `Joined` health descriptor SHALL be
derived from permission, the membership's **`startsAt`**, and the latest snapshot, in this precedence:

- permission is `NOT_DETERMINED` or `DENIED` → `NeedsAccess(permission)` (the sole **actionable**
  attention state; there is no separate "not syncing" state — the status projection's only operational
  signal is permission). **`LIMITED` SHALL NOT reduce to `NeedsAccess`**: a limited grant is a working
  state (capability `limited-photo-access`), rendered as the ordinary health line plus the
  "Choose more photos" affordance (see the added requirement below);
- else the membership's **`startsAt` is in the future** → **`NotStarted(startsAt)`**;
- else **no usable device token could be obtained** → **`Unattested`** (capability `device-attestation`);
- else `SyncStatus.Loading` → a joined loading first-frame;
- else `SyncStatus.Ready` → `InSync` when settled, else `Syncing(...)` (the completeness/activity
  arrow derivation is specified in *Joined-layer health descriptor and status line*).

`NotStarted` SHALL rank **below** `NeedsAccess` and **above** the snapshot-derived values. Permission
outranks it because permission is the only **actionable** state — it carries a tappable affordance, and a
member must resolve it *before* the event begins or they will miss the start; burying it behind a clock
line would ambush them with a permission prompt at the very moment the party starts. Everything below it
is outranked because, before the start, nothing of the member's **can** be syncing (the floor guarantees
it, capability `photo-selection-policy`), so a snapshot-derived line would say nothing true that the clock
line does not say better.

`NotStarted` SHALL carry the start instant, so the screen can render *when* — a bare "not started yet"
invites the question it fails to answer. It carries its own copy although `Joined` now also carries the
membership: `SyncHealth` stays self-contained, so a health value can be rendered — and forged — without a
membership in hand.

`Unattested` SHALL rank **below** `NeedsAccess` and `NotStarted` and **above** every snapshot-derived
value. Uploads are gated on an App Attest device token (capability `device-attestation`); when none is
usable, nothing of the member's can upload at all, so a snapshot-derived line would say "Syncing" while
every upload `401`s — the lie this rung exists to prevent. It ranks below the two above it for the reason
they rank where they do: without library access, or before the event begins, nothing could upload anyway,
so an unusable token is not yet the member's problem, and two attention lines would only compete.

`Unattested` SHALL be raised **only** when there is no usable token **and** obtaining one failed — never
for a merely stale token, which the next wake renews. **"No usable token" means the token is absent,
unparseable, or past its expiry**, and nothing wider: a token inside the renewal margin still authorises
every gated request until it expires, so a renewal that fails against one changes nothing the member can
see and SHALL NOT raise this rung. The margin that decides *when the app renews* is `device-attestation`'s
and is deliberately wide; reusing it here would state that sharing is paused for up to a week while every
upload was in fact authorised.

**The value this rung reduces from SHALL NOT predate the screen's own entry.** Attestation is re-checked
only when the app process wakes, and a process may carry an outcome across an arbitrary suspension; a
foreground entry SHALL therefore reduce from the refresh that entry triggers, not from whatever the last
wake concluded. Until that refresh reports, the reduction SHALL fall through to the snapshot-derived
health exactly as it does when the token is usable — the first frame after a foreground entry SHALL NOT
render a verdict formed under conditions that no longer hold.

Decision record for both the narrowed predicate and the freshness rule: `changes/archive/2026-08-25-correct-attestation-health-surfacing`.

`Unattested` is **not actionable**: there is nothing for the member to do, and the screen SHALL NOT ask
them for anything (capability `design-system`). Because opening the app **is** a wake, looking at the
screen renews the token and clears the line — so it survives to be seen only when renewal itself keeps
failing (offline, or the backend refusing this device), which is a real problem that would otherwise be
invisible.

`UiState` SHALL carry **no** upload/download counts — the joined states no longer surface `synced`,
`total`, or an in-progress number. The event **name** SHALL NOT be carried separately from the membership
that already holds it, and the invite URL SHALL be carried as a reduced field derived once from the
membership's `eventId` (capability `event-invite-qr`), so that the rendered QR and the shared link cannot
disagree. Neither is supplied to the screen as a parameter.

The reduction MUST depend only on the latest snapshot (**no event history**, so that any missed
intermediate snapshot cannot corrupt the displayed state) **and the current instant**. This bounds how the
reduction may read the **snapshot stream**; it does not forbid reducing a current value that a source
remembers, which the create-failure status already is. The container's initial UI state SHALL be computed
from the sources' current values at construction. `UiState.Loading` and every `Joined` health value are
derived from real source values (never placeholders). Once a join has committed there is no join-status
reduction: during the (re)join provisioning the screen simply shows the current `Joined` health (typically
`Syncing`); the `JoiningEvent` family is the **pre-commit** confirmation gate only.

#### Scenario: A future start reduces to NotStarted
- **WHEN** config is present, permission is `GRANTED`, and the membership's `startsAt` is in the future
- **THEN** the UI state is `Joined` with health `NotStarted(startsAt)`, whatever the snapshot says

#### Scenario: Permission outranks the not-started state
- **WHEN** config is present, the membership's `startsAt` is in the future, and permission is `DENIED` or
  `NOT_DETERMINED`
- **THEN** the UI state is `Joined` with health `NeedsAccess(permission)` — the actionable state wins, so
  the member can grant access before the event begins

#### Scenario: A past start reduces from the snapshot as before
- **WHEN** config is present, permission is `GRANTED`, and the membership's `startsAt` is at or before now
- **THEN** the health derives from the snapshot exactly as it did before this change (`Loading` /
  `InSync` / `Syncing`)

#### Scenario: A limited grant reduces from the snapshot, not to NeedsAccess
- **WHEN** config is present, permission is `LIMITED`, and the event has started
- **THEN** the health derives from the snapshot (`Loading` / `InSync` / `Syncing`) exactly as under
  `GRANTED`, and `NeedsAccess` is not shown

#### Scenario: Settled snapshot reduces to In sync
- **WHEN** config is present, permission is `GRANTED`, the event has started, and a `Ready` snapshot with
  `completed == total` is observed (downloads also settled)
- **THEN** the UI state is `Joined` with health `InSync`

#### Scenario: Work remaining reduces to Syncing
- **WHEN** config is present, permission is `GRANTED`, the event has started, and a `Ready` snapshot with
  `completed < total` is observed
- **THEN** the UI state is `Joined` with health `Syncing(...)`, and no synced/total counts are carried

#### Scenario: Permission off with config present reduces to NeedsAccess, not a gate
- **WHEN** config is present and permission is `DENIED` or `NOT_DETERMINED`, for any snapshot
- **THEN** the UI state is `Joined` with health `NeedsAccess(permission)` — the joined layer still
  renders (name, QR, share, leave), and there is no hero-replacing `PermissionBlocked` screen

#### Scenario: Absent config outranks everything
- **WHEN** config is absent (creation status `Idle`), no interactive join is pending, for any permission and any snapshot
- **THEN** the UI state is the create layer, not `Joined`

#### Scenario: A pending interactive join outranks the create layer
- **WHEN** config is absent but an interactive join has been decoded and is awaiting confirmation
- **THEN** the UI state is the `JoiningEvent` family for that pending event, not the create layer

#### Scenario: A newer snapshot replaces the displayed health entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any missed in between
- **THEN** the `Joined` health derives from the latest snapshot alone

#### Scenario: A token near expiry that could not be renewed does not reduce to Unattested
- **WHEN** config is present, permission grants photo access, the event has started, and the device holds a
  token that is inside the renewal margin but has not expired, whose renewal failed
- **THEN** the `Joined` health derives from the snapshot as usual, and is not `Unattested`

#### Scenario: An expired token that could not be replaced reduces to Unattested
- **WHEN** the same conditions hold but the token is absent, unparseable, or past its expiry, and obtaining
  a usable one failed
- **THEN** the `Joined` health is `Unattested`

#### Scenario: The first frame after a foreground entry does not reduce from an earlier wake's verdict
- **WHEN** a prior wake concluded that no usable token could be obtained, and the screen is entered before
  the refresh that entry triggers has reported
- **THEN** the `Joined` health derives from the snapshot, not `Unattested`; the rung is raised only if that
  refresh also fails

#### Scenario: A joined state carries its membership and invite URL
- **WHEN** config is present and the reduction produces `Joined`
- **THEN** the state carries the persisted membership as a non-null value and the invite URL derived from
  its `eventId`, and the screen receives neither as a separate parameter

#### Scenario: A loaded join step cannot exist without its event details
- **WHEN** a join confirmation is on a step that renders or commits the event's name, floor, ceiling or
  retention deadline
- **THEN** those details are present by construction, because the step is carried by the detailed phase
  that holds them

#### Scenario: A remembered current value may be reduced
- **WHEN** the create-failure status or the transient invalid-link error holds a current value
- **THEN** the reduction may fold it into the state — the no-event-history rule bounds how the snapshot
  stream is read, not whether a current value may be read

### Requirement: The joined heading reflects a renamed event

The event-name heading SHALL render the persisted membership's current name, so a successful rename
performed on this device is visible as soon as it is persisted, and a rename performed by another member
becomes visible when the membership refresh folds it in (capability `join-event`). The heading SHALL read
that name from the membership the `Joined` state already carries; there SHALL NOT be a second event-name
value beside it.

The rename SHALL NOT introduce a `UiState` family or a new branch in the status reduction: the rename
status SHALL be carried as a field of the `Joined` state, alongside the membership it concerns. It is not
a layer and not a health — it changes one string and one dialog's state — so it adds a field, never a
family or a precedence rung.

#### Scenario: The heading updates after a local rename
- **WHEN** a rename succeeds and the new name is persisted
- **THEN** the heading renders the new name without any further user action

#### Scenario: The heading updates after a remote rename
- **WHEN** another member renames the event and this device's foreground refresh persists the new name
- **THEN** the heading renders the new name

#### Scenario: The reduction gains no rename family or rung
- **WHEN** the status reduction's `UiState` families and the `Joined` health precedence are enumerated
- **THEN** no family and no health rung represents a rename; the rename status is a field of `Joined`

#### Scenario: One name, one source
- **WHEN** the heading, the rename dialog's prefill, and the reconfigure surface's header each render the
  event name
- **THEN** all three read the membership's `name`, and no separate event-name value is carried
