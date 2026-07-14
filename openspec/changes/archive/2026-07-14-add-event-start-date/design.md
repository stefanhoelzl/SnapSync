## Context

An event's marker carries `eventId`, `name`, `createdAt` — nothing else. `createdAt` is server-minted
wall-clock at `POST /events` time, and `StatusContainerHost.cutoffOrNow` seeds each joiner's
capture-date cutoff from it (falling back to `nowLocal()`). So "when the event began" is currently
approximated by "when the JSON blob was written", and the join screen offers an unbounded
`AppDateTimeField` for the member to correct it.

The constraint that shapes everything here is stated in CLAUDE.md and enforced by
`openspec/specs/photo-date-cutoff`: this app began as a personal one-way photo backup, and its
inherited defaults are *dangerous* under event sharing. "Back up everything of mine" becomes "upload a
guest's whole camera roll to a stranger's event". That spec's current answer is absolutist — the
cutoff "SHALL NOT be inherited from the event, and SHALL NOT be imposed by the event's host."

A host-chosen start date violates the letter of that rule. The design's job is to violate it in the
one direction that is safe, and to say precisely why.

## Goals / Non-Goals

**Goals:**

- The host can state when the event began, once, at creation.
- A joiner defaults to that instant instead of `createdAt`.
- Nothing captured before the event started is ever uploaded to it.
- Nothing syncs before the event starts.
- A member can see that an event has not begun yet, and when it will.
- Existing members keep their membership: no forced re-join, no migration.

**Non-Goals:**

- Editing the start date after creation. There is no owner and no auth on the backend; a mutation route
  would let anyone with the event id retroactively widen every future joiner's scope.
- An event *end* date. Out of scope.
- Restoring an arbitrary/custom cutoff on the join screen. The two presets are the whole surface.
- Any change to `:capability:upload`. See Decision 2.

## Decisions

### 1. `startsAt` is a floor, not merely a default

The obvious design is "the start date seeds the joiner's default cutoff." That alone is worth little
and costs a lot: the creator can *already* pick any cutoff on the join gate they are pushed into after
creating (`onMinted` → `startPending`), so the field would exist purely to influence *other* people's
defaults — which is precisely the host influence the spec forbids, with none of the safety.

So `startsAt` is also a **content floor**: a membership's effective cutoff is `max(chosen, startsAt)`.

This inverts the danger. A default that a host controls can only ever be *widened* by a malicious host
(set start = 2001, every guest's default becomes their whole camera roll). A floor can only ever
*narrow*. The host bounds the event's contents from below; the member freely chooses anything above it.
That is the invariant that replaces the old absolutist rule, and it is the one that actually protects
the guest:

```
    photo capture date ───────────────────────────────────────▶
                 startsAt              chosen
                    │                     │
    ────────────────┼─────────────────────┼──────────────────
        never             host set the         member's own
       uploaded           floor here;          choice above
                          member may           the floor
                          not go below
```

**Alternative rejected — default only, no floor.** Leaves the 2001 attack open and makes the field
pointless for the creator.

**Alternative rejected — enforce the floor at upload time.** Would thread `startsAt` into `UploadCycle`,
both upload tiers, the extension process, and `:test:world`, and would re-introduce a runtime filter in
the exact code path where a bug means uploading a stranger's camera roll. See Decision 2.

### 2. Clamp at join time. `UploadCycle` is not touched.

`JoinEvent` computes `max(chosen, startsAt)` and persists **that** as `minPhotoDate`. `startsAt` is
immutable, so the result is stable forever. The upload cycle keeps filtering on exactly one cutoff,
exactly as today.

The payoff is that the requested behaviour — *nothing syncs before the event starts* — is not a feature
at all. It is a **theorem**:

```
    A photo's capture date cannot be in the future.
    Pre-start:  minPhotoDate == max(chosen, startsAt) == startsAt > now
    ∴ no photo satisfies  creationDate >= minPhotoDate
    ∴ nothing uploads.                                        ∎
```

No gate, no new filter, no new failure mode, no `startsAt` anywhere in the upload path.

### 2b. The clamp applies to *every* cutoff entering a membership — including the deeplink override

`join-event`'s `autoJoin` path lets a decoded deeplink carry an explicit `minPhotoDate` (capability
`deeplink-config`), documented as a dev/test key so a headless `SNAPSYNC_DEEPLINK` launch can force a
cutoff and observe date filtering against the 2001-dated `SNAPSYNC_SEED_PHOTOS` assets.

The clamp SHALL apply to it too. Two consequences, one cost and one win:

- **Cost:** the dev loop can no longer force a cutoff *below* the event's start. It adapts by creating
  the event with an early `startsAt` — which the unbounded picker (Decision 6) now permits, and which is
  arguably the more honest way to express "an event whose photos begin in 2001" anyway.
- **Win:** it closes a live hole. A `minPhotoDate` in the link payload is not actually inert in
  production — it is decoded from *any* `snapsync://` URL, so a hostile QR carrying `autoJoin=true` +
  `minPhotoDate=2001` today auto-confirms a join at near-whole-library scope **with no tap**. Under the
  clamp the hostile value is raised to the event's own start, and the attack collapses to "the attacker
  set their own event's start date early" — which the guest can see, and which the floor bounds.

An unclamped exemption for the dev key was rejected precisely because that exemption *is* the hole.

### 3. The join surface collapses to two presets

`Now` | `Event start`, defaulting to `Event start`, with the resulting instant rendered as a label. The
unbounded `AppDateTimeField` leaves the join screen.

Pre-start, `Now` clamps to the same value as `Event start`, so it is **disabled** rather than being a
button that visibly does nothing.

**Cost, accepted:** the late-arriving guest loses their exact answer. Party starts 18:00, guest arrives
21:00 — `Event start` sweeps in their breakfast photos, `Now` drops the party photos they already took,
and there is no third option. This is a real reduction in expressiveness and is the price of the
simplification.

### 4. Not-started is an event-level fact, and `EventConfig` stores `startsAt`

The status line shows `◷ Starts 14 Jul, 18:00` while `startsAt > now`.

Given Decisions 1–3, `minPhotoDate > now ⟺ startsAt > now` for every reachable state — so the state
*could* be derived from `minPhotoDate` alone, with no new persisted field. That was tempting and is
rejected: it is an implicit invariant that holds only because the preset set is exactly
`{now, startsAt}`. Restore a custom picker later, let someone choose a future cutoff, and the app would
claim an event that started last week has not begun. `startsAt` is stored explicitly so the test reads
what it means.

Precedence is `NeedsAccess > NotStarted > Syncing > InSync`. Permission is the only *actionable* state;
burying it behind a clock line would ambush the member with a permission prompt the moment the party
starts.

### 5. `EventConfig.startsAt` defaults **at decode** to `minPhotoDate`

`startsAt` is a required, non-null `String` in the app — but a config persisted before this change
lacks the key.

Treating it like `minPhotoDate` (no default → decode fails → reads as *no config* → forced re-join) is
tempting for symmetry and is **wrong**, because the two are not symmetric. `minPhotoDate`'s harshness
buys protection against uploading a whole camera roll. `startsAt`'s would buy a clock icon — and the
blast radius is severe: `EventConfig` is the **only** place the `eventId` lives
(`StatusContainerHost.inviteUrl()` derives the QR from it). A decode failure therefore destroys the
member's event id and their QR. Nothing in the app surfaces it back. A host who is the only member yet
would be locked out of their own event permanently, with the uploaded photos stranded in bunny.

So the default is `minPhotoDate`, which is the only value that is *guaranteed* consistent with the
clamp invariant (`minPhotoDate >= startsAt`, satisfied with equality). `createdAt` would place the floor
below the member's cutoff — inert, but a fact they never saw. `""` would plant exactly the empty-string
landmine the `minPhotoDate` KDoc spends a paragraph warning about.

It lands the not-started state correctly too: a legacy member joined an event that had already begun
(their cutoff was `<= now` when they picked it), so `startsAt <= now` and the clock line never appears.

**Implementation note.** Kotlin permits a default that references an earlier constructor parameter:

```kotlin
data class EventConfig(
    val eventId: String,
    val name: String = "",
    val minPhotoDate: String,
    val startsAt: String = minPhotoDate,   // declared AFTER minPhotoDate
    ...
)
```

Whether the `@Serializable` plugin honours a cross-parameter default in its synthetic constructor must
be **proven by a decode test against a legacy JSON blob**, not assumed. If it does not, the fallback is
a private surrogate DTO with `startsAt: String? = null` and a `KSerializer<EventConfig>` mapping
`startsAt ?: minPhotoDate` — same external contract, no doubt.

### 6. Canonical form on the wire; the backend patches legacy markers

`POST /events` requires `startsAt` in exactly `yyyy-MM-dd'T'HH:mm:ss'Z'` — second precision, UTC, no
offset, no fractional seconds. This is the same invariant `photo-date-cutoff` already pins for the
cutoff (it is compared lexicographically against PhotoKit `creationDate` and parsed by a bare
`NSISO8601DateFormatter`), and the app already has `Cutoff.instantToCutoff` to produce it. Requiring it
at the boundary means the marker is *directly* usable as a cutoff with no client-side normalization —
unlike `createdAt`, which carries milliseconds and must be round-tripped through
`CutoffFormatter.toLocal`/`toCutoff` to be safe.

Bounds: none. An event may start arbitrarily far in the past or the future.

`GET /events/:eventId` synthesizes `startsAt = createdAt` when the stored marker predates this change,
so the app never sees a null and every downstream type stays total. One fix, one place.

A `startsAt`-shaped 400 is unreachable from our client (it always sends a canonical value), so no new
`CreationFailureReason` is added — the existing single 400 → `INVALID_NAME` mapping stands.

### 7. Ticking, and what wakes the first upload

`now` advances via a **1-minute tick in the presentation layer** (`StatusContainerHost`, which already
owns a `CutoffFormatter` and a scope), running only while foregrounded and only while `startsAt > now`.
`:domain:status` stays a clock-free ledger projection.

When the start passes, the first upload rides a **natural trigger** — a new photo, OS cadence, or app
foreground. On iOS ≥ 26.1 the PhotoKit extension's `process()` is OS-scheduled and cannot be forced, so
no scheduled wake-up is even possible on the tier most users are on; adding a `BGTaskScheduler` wake on
the iOS 18–26.0 tier alone would make the two tiers behave differently at the single most visible moment
of the feature. At a real event a trigger lands within moments of the first photo.

## Risks / Trade-offs

- **The `photo-date-cutoff` spec's central promise changes.** → Its replacement must state the surviving
  invariant explicitly: the event supplies a default *and* a floor; the floor can only **narrow** a
  membership's scope, never widen it; the member chooses freely above it and always sees the resulting
  instant before committing.

- **The late-arriving guest has no correct option** (Decision 3). → Accepted. `Now` is the safe answer
  and is one tap; the photos they lose are ones they took before joining.

- **The host can set a start far in the past, widening every guest's *default*.** → The guest always
  sees the resulting instant on the join screen before committing, and `Now` is one tap away. The floor
  cannot widen scope *beyond* what the host set; it is the guest's own `Now` that bounds them from
  below.

- **A cross-parameter `@Serializable` default may not work** (Decision 5). → Proven or disproven by a
  legacy-blob decode test before anything else is built; surrogate serializer is the fallback.

- **An event that starts while the phone is idle in a pocket does not upload until a natural trigger**
  (Decision 7). → Accepted; unavoidable on the ≥ 26.1 tier.

- **Two presets that render identically pre-start.** → `Now` is disabled pre-start rather than being a
  no-op button.

## Migration Plan

There is none, by design.

- **Old event markers** (no `startsAt`): patched at read by `GET /events/:eventId` → `startsAt =
  createdAt`. They behave exactly as they do today — the cutoff is seeded from creation.
- **Old configs** (no `startsAt`): decode with `startsAt = minPhotoDate`. The member keeps their event,
  their QR, their cutoff, and never sees the not-started state.
- **Rollback**: the backend change is additive (a new marker field, a new required request field). A
  rolled-back app would send no `startsAt` and be rejected with a 400 — so the backend must not be rolled
  forward ahead of the app. Ship the app and backend together, or make the backend tolerate a missing
  `startsAt` for one release.

## Open Questions

- Does the `@Serializable` plugin honour `val startsAt: String = minPhotoDate`? (Decision 5 — resolve
  with a test as the first task.)
- Does Compose MP 1.11.1's Material 3 expose a `TimePickerDialog` with a built-in display-mode toggle,
  or must the calendar↔dial swap be hand-wired? (Cosmetic; does not affect the contract.)
