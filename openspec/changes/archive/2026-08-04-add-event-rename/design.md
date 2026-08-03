## Context

The event name enters the system once, at `POST /events`, and is never writable again. It reaches
members by a single one-way path: the marker's `name` → `GET /events/:id` → `MembershipRefresh`, which
rewrites `EventConfig.name` on every foreground when the fetched value differs. That refresh machinery
already exists and already works — it is the reason a rename needs almost no propagation code.

Four things constrain the design, and each is load-bearing:

1. **The marker is declared write-once** (`event-creation`, *Event marker registry*), with a stated
   rationale: no auth and no owner field means a mutation route lets anyone holding the event id
   "retroactively widen every future joiner's default scope — or extend an event's own limits". Both
   named threats are about `startsAt`/`endsAt`/`capacity`/`lifetimeSeconds`. Neither is about `name`.
2. **There is no host or creator concept anywhere.** The creating device mints and then joins through
   the same gate as any guest. `:domain` contains no `isHost`, no `createdByDevice`.
3. **`EventConfig` is `feature/membership`'s one-writer durable state**, and its writers are
   enumerated in prose: join/provision saves it, leave clears it, `MembershipRefresh` reconciles it,
   `ReconfigureEvent` rewrites the participation fields.
4. **The self-leave requires two independent witnesses, one of them offline** (`leave-event`): the
   backend's `404` *and* the locally stored `deletesAt` having passed. This exists so a zone-wide
   backend fault cannot destroy every membership in the install base at once, irrecoverably — the
   config is the only record of the join.

## Goals / Non-Goals

**Goals:**

- A joined member can correct the event's name, and the correction reaches every other member.
- The correction is reachable in two taps from the screen the name is displayed on.
- The write-once rule is relaxed by exactly one field, in a way that makes the narrowness visible.
- No new destructive path, and no new authority concept.

**Non-Goals:**

- **Renaming already-created event albums.** The album is titled once, at creation, on each member's
  own device. Renaming them everywhere would need a new `AlbumManager.rename` port, an iOS
  `PHAssetCollectionChangeRequest` impl, and — because features are mutually blind, so
  `MembershipRefresh` may not call `AlbumCoordinator` — coordination in the `Foreground` flow off the
  refresh outcome. Deliberately deferred; see *Risks*.
- **Push propagation.** Not needed, and see D7 for what it would actually cost (which is not a new
  push kind — there are none).
- **An owner, an undo, a rename history, or a notification to other members.**
- **Handling `EventConfig.name == ""`.** See D9.

## Decisions

### D1. A shared rename, not a local display label

Rejected: a per-device display name overriding the fetched one. It is cheaper (no backend, no spec
amendment) but it solves the wrong problem — the motivating case is a host fixing a typo *for their
guests*, and a local label leaves the typo on every other screen. It would also require
`MembershipRefresh` to stop clobbering the name, turning a simple reconcile into a
which-value-wins rule.

### D2. Any joined member may rename; no owner is introduced

Rejected: stamping the creating device's id into the marker and requiring an attestation-bound match.
It is genuinely enforceable — `device-attestation` already proves device identity to the backend — but
it invents the owner concept the whole system has avoided, and it breaks permanently on reinstall or
device loss, on a value the host cannot recover any other way.

The security argument for the open version is that renaming is **strictly weaker than what the caller
already holds**: possession of the event id lets them upload into the event and download every photo
in it. A holder who wanted to harm the event has far better options than changing its title. The route
stays behind the device-token gate that already fronts `POST /events`, so it is not open to the
unattested.

### D3. `PATCH /events/:id` with a body of `{ name }`

Rejected: `PUT /events/:id/name`, which makes the narrowness *structural* — a route that cannot express
another field change, so widening it later would require a visibly new route rather than a quiet branch
in an existing handler. That argument is real and matches this codebase's habit of preferring
structural impossibility to documented restraint. `PATCH` was chosen for conventionality; the
narrowness is therefore carried by the spec text and the handler, and the amended write-once
requirement states it explicitly so a future reader cannot mistake the route for generally mutable.

The handler reads the marker through the existing `gateEvent` (absent or incomplete → `404`, non-404
read failure → `502`), replaces **only** `name`, and rewrites every other field **verbatim**. Verbatim
matters beyond tidiness: it is what makes the sweep race self-defusing. A `PATCH` that reads a marker
the nightly sweep then deletes will re-create it — but with its original `createdAt`/`startsAt`/
`lifetimeSeconds`, so `deleteByMs` is still in the past and the next sweep reaps it again. Restamping
any of those would resurrect the event for a fresh lifetime.

Last-write-wins on concurrent renames is **forced, not chosen**: bunny has no compare-and-set, as the
capacity gate already documents ("read-then-write without coordination"). There is no ETag option.

### D4. `RenameEvent` lives in `feature/membership`, as the fifth writer

It follows `ReconfigureEvent` exactly: read the current config, **guard that `eventId` still matches**
the membership being edited (a result landing after a switch or leave must not resurrect the departed
membership), and save the **whole** object with only `name` replaced.

Rejected: seating it in `feature/creation` — where event-level backend operations otherwise live — and
having a flow fold the result in via `membershipRefresh.refresh(id, fetchEventDetails(id))`. That
keeps the one-writer story purest and reuses the existing fold, but costs an extra network round trip
on the happy path and a new flow that must transcribe under the closed grammar, to avoid a
cross-feature reference that the enumerated-writers convention already tolerates.

### D5. The persisted name is the backend's echo, not the typed input

`PATCH` responds with the updated public event; `RenameEvent` persists the `name` it reports. The
backend trims, so echoing is what guarantees the client and the marker never disagree about
whitespace — the same reason `CreateEvent` already routes the returned name rather than the typed one.

Rejected: an optimistic local write before the request, reverted on failure. It would make the config
a place where an unconfirmed value briefly lives, and require the old name to be held somewhere for
the revert, to save well under a second.

### D6. A rename `404` destroys nothing, and gets no distinct copy

`RenameOutcome` has three shapes — `Renamed(name)`, `InvalidName`, `Transient` — and a `404` maps to
`Transient`, exactly as `HttpEventCreation` maps everything but `400`.

Collapsing it is deliberate rather than lazy. A rename `404` is precisely the single witness that
`leave-event` spent a second, offline witness on **not** trusting alone. Giving it a distinct
`EVENT_GONE` reason and user-facing copy would surface that signal in the UI, and a surfaced signal
invites a future change to act on it — which would open a second door to the destructive outcome
`MembershipRefresh` exists to gate. The real teardown stays on its two-witness path; the member sees a
generic failure and the next foreground reaches `ABSENT` on its own terms.

### D7. Propagation is the existing foreground refresh; no push

`MembershipRefresh` already rewrites a changed name on every foreground. That is the entire
propagation mechanism, and it costs nothing.

Rejected: fanning out over the existing silent push. The cost is **not** a new push kind — there are
none; there is one untyped payload, `{ aps: { "content-available": 1 }, eventId }`, meaning "this event
changed". It would cost the renaming device calling the existing `EventNotifier.notify`, plus a third
entry in `SilentPush`'s receiver list running the same lambda `Foreground` already uses — since
today's two receivers (download, upload) refresh no membership, so a push currently propagates no name.
Rejected as unnecessary for a cosmetic field, and because the wake would also drive an upload cycle and
a download reconcile on every member's device for a title change.

### D8. The affordance is on the status screen, not the reconfigure surface

The reconfigure surface was the intuitive home — it already renders a **read-only** event-name header
above the participation controls. It is the wrong home for two reasons.

First, `reconfigure-membership`'s identity is *"change the settings **you** picked at join"*: per-member,
local-only, "a change reaches nothing on the backend", and a `Save` that is exactly one `ConfigStore.save`
and **cannot fail**. The event name is not a membership setting — it is a property of the shared event.
Putting it there would put a network call inside that `Save`, making it fail-able and forcing a
partial-commit decision (does a failed rename block a valid direction change?).

Second, the status-screen placement needs no such decision: the rename is its own self-contained
mini-flow with its own confirm, and `Save` stays network-free.

There is no gesture collision: the hidden double-tap for the diagnostic dump is on `ScreenLayout`'s
`title` (the "SnapSync" nav label), not on the `heading` slot the event name occupies.

### D9. `name == ""` is out of scope

A scan-path membership was believed to arrive nameless, and at the time this was written `Provision`
still branched on `MembershipRefresh.fetchNeed` for it. Tracing the live paths showed it cannot happen:
`EventDetails.Found.name` is required and non-null (a `200` lacking a name maps to `Failed`, never a
nameless `Found`), and `JoinEvent.confirm` takes a required name because the gate only provisions from a
loaded phase that carries one. Create, interactive join, and autoJoin therefore all persist a real name.
The empty string survived only as `EventConfig`'s legacy-decode fallback, which the next foreground fills.

**Confirmed independently, and then closed, by `remove-nameless-config-fallback`** — which landed on
`main` while this change was in review. It removed `EventConfig.name`'s `""` default outright (making the
name a required constructor parameter), moved the blank-name guard to `HttpEventDirectory`, and deleted
the redundant `Provision` fetch along with `fetchNeed`/`TitleNeed` entirely. So the branch cited above no
longer exists, and the state this decision declined to build for is now unreachable by construction
rather than merely by tracing. The decision is unchanged; its premise is simply stronger than when made.

So there is no live "loading the event name" window on the joined screen — the loading state belongs to
the *join gate*, before the config is saved. A "Name this event" placeholder would be copy for a state
no user can reach, which is the same reasoning `CreationFailureReason` uses to omit an
invalid-`startsAt` reason.

### D10. `RenameStatus` mirrors `CreationStatus` but adds `Succeeded`

`CreationStatus` deliberately has **no** success value: "a successful create provisions config, which
moves the reduction off the create layer entirely". That does not transfer — a successful rename leaves
the member on the same screen with a dialog that must close. `RenameStatus` is therefore
`Idle | InFlight | Succeeded | Failed(reason)`, and the screen closes the dialog on `Succeeded` and
fires a reset command returning the seam to `Idle`.

Rejected: closing when the status returns to `Idle` after `InFlight` (an inference from the screen's own
memory of the sequence, which breaks if the sequence ever changes), and closing when the observed
`eventName` changes (races a concurrent foreground refresh, and cannot close the dialog when the
backend trims the new name to the old one).

### D11. `AppBugReportSheet` generalizes rather than gaining a sibling

Its existing shape — title, body, placeholder, max length, confirm/cancel, `onConfirm(text)` — is the
rename dialog's shape minus an initial value, an inline error, and a busy state. It becomes a general
text-prompt sheet carrying those three, with two call sites.

Rejected: a purpose-named sibling component. Zero regression risk on the diagnostic dump and one fewer
spec delta, but two near-identical text-prompt overlays in a design system whose stated inventory
"grows demand-driven with the screens that need it" — and the second demand has arrived.

## Risks / Trade-offs

- **Permanent album-title drift** → Devices that already opted into an event album keep the old title
  in Photos forever; only albums created after the rename get the new one. On the motivating case — a
  host fixing a typo — the typo survives in every existing member's photo library. Accepted for scope;
  the follow-up is a `AlbumManager.rename` port plus `Foreground`-flow coordination off the refresh
  outcome, which is a change of its own.
- **The most discoverable shared mutation in the app** → A pen on the primary screen means any guest
  renames the host's event in two taps, with no undo, no history, and no notice to other members.
  Mitigated only by the trust model the mission already assumes (a short-lived event among people who
  are there together) and by the fact that a holder of the event id can already do worse.
- **Silent propagation delay** → A member who does not open the app keeps seeing the old name
  indefinitely. Accepted: the field is cosmetic, and D7 records what fixing it would cost.
- **The write-once rule now has an exception, and exceptions attract more** → Mitigated by amending the
  requirement to state the exception and its bound explicitly, rather than deleting the rule; the two
  threats it names (scope widening, limit extension) are restated so a future proposal to make another
  field mutable has to argue against them by name. `PUT /events/:id/name` would have carried this
  structurally (D3); the spec text carries it instead.
- **Generalizing `AppBugReportSheet` touches the diagnostic dump's surface** → Mitigated by the
  existing `:ui:screens` jvmTest coverage of the bug-report gesture and sheet
  (`DiagnosticDumpGestureTest`), which must stay green unchanged.
- **A rename racing a foreground refresh** → Harmless in both orders. If the refresh lands first it
  writes the old name and the rename overwrites it; if the rename lands first the refresh fetches the
  already-updated marker and finds nothing to change. `RenameEvent`'s `eventId` guard covers the only
  genuinely wrong outcome (a rename result landing after a switch or leave).

## Open Questions

None outstanding. The one open at drafting — whether `sync-status-screen` needs a delta for the heading
affordance — is settled by precedent: it already enumerates the joined layer's affordances, including
`reconfigure-membership`'s settings action ("The joined layer offers a settings affordance next to share
and leave"). The rename affordance is specified there on the same pattern; `event-rename` owns the
behavior behind it.
