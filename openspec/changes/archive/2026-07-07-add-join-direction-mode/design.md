## Context

A joined device is a full bidirectional participant: the background-upload producer contributes its
own photos and the download machinery imports every other contributor's photos. These two arms are
**already independently wired** — the upload arm is toggled by `provisionEvent`'s
producer-enable; the download arm is the main-app `DownloadController.reconcile` loop (background
`URLSession` + push + `BGProcessingTask`), driven separately. There is no per-direction opt-out today;
`Leave` stops both.

The join confirmation surface was built to grow this exact option. `StatusScreen.kt:133` carries the
reserved comment *"Future options (direction, albums, save-to album) slot in as rows in this same
column,"* and `EventConfig` already flows **whole-object** end-to-end (with a warning comment at
`SnapSyncRoot.kt:517` against destructuring it, precisely so new fields auto-propagate through
persistence and the extension read). `minPhotoDate` is the existing precedent for a per-membership
scalar that is chosen at join, persisted on `EventConfig`, and carried as an optional dev override on
the `EventLinkPayload` wire type.

This change adds a **join-time participation direction** (`Both` / `UploadOnly` / `DownloadOnly`),
resolved through a prior interview. The realization that unlocks a small footprint: the direction is
just a **masking layer over three existing gates** (producer-enable, reconcile, arrow render), not a
new subsystem.

## Goals / Non-Goals

**Goals:**
- Let a user choose, at join, to participate as upload-only, download-only, or both.
- Keep the choice **fixed for the membership** and persisted on `EventConfig`.
- Reuse the single join confirmation surface for scan-join **and** create-join (create auto-routes into
  it), so the selector has one home.
- Keep all skip logic in **tested capabilities**, never the untested app shell.
- No backend, engine, ledger, or status-vocabulary changes; no data migration.

**Non-Goals:**
- Runtime toggling of direction after join (change = leave & rejoin / switch).
- Per-photo or per-album opt-out (album selection remains a separate reserved future option).
- A dedicated download-progress UI or a visible mode label (the existing arrows suffice; masking is
  silent).
- Backend awareness of the mode (uploads stay ungated, the union stays identity-blind).

## Decisions

### D1: Direction is a persisted `EventConfig` field, not deeplink-carried

The mode is the **joiner's** local choice, not the inviter's to dictate, so it is **not** part of the
canonical shared QR. It is persisted on `EventConfig` (`direction: Direction = Direction.Both`),
mirroring `minPhotoDate`. Because `EventConfig` flows whole-object through the Keychain store
(`EventConfig.serializer()`, `ignoreUnknownKeys = true`) and the extension read, a default-valued field
requires **no port changes** and **no migration** — an already-persisted config without `direction`
decodes to `Both`.

_Alternative considered:_ encode direction in the deeplink. Rejected — it would let an inviter fix a
joiner's mode and pollutes the capability-URL semantics. (A **dev/test** override is the one exception;
see D6.)

### D2: Three-way segmented control on the single join surface

The selector is a three-way segmented control (`Both | Upload only | Download only`, default `Both`) in
the reserved slot on `JoiningEventScreen`, held in Compose local state like the cutoff, and passed
through the confirm intent. A segmented control (over two independent toggles) removes the
"both-off" invalid case entirely — there is no way to join with nothing enabled.

Because create-event **auto-routes into the same pending-join gate** (`event-creation-ui`:
auto-routed-but-not-auto-confirmed), the creator sees and picks the same control — no separate
create-path decision and no second selector location.

_Alternative considered:_ two toggle rows. Rejected for the `(off, off)` guard burden and awkward
cutoff-row grouping.

### D3: Upload arm — gate the producer-enable in `provisionEvent`

`provisionEvent` already receives the whole `EventConfig`. It enables the background-upload producer
**only when** `direction` includes upload (`Both`/`UploadOnly`). Under `DownloadOnly` the producer is
never enabled, so the OS never invokes the upload extension and the in-extension reconciliation never
runs — correct, nothing to reconcile for a non-contributor. No extension code changes.

### D4: Download arm — gate reconcile in `DownloadController`, the single choke point

All download triggers (foreground `SnapSyncRoot.kt:349`, provision `:538`, push
`DownloadPushReceiver.kt:31`, and the desktop harness) funnel through
`DownloadController.reconcile(eventId)`. The direction gate lives **inside `reconcile`** (via an
injected direction read), so:
- one choke point covers every trigger uniformly, and
- the skip logic sits in a **tested capability**, honoring the hard rule that nothing testable lives in
  the app shell (2 of the 3 iOS call sites are in the untested `SnapSyncRoot`).

The `DownloadPushReceiver` keeps its **active-event** guard unchanged — a distinct concern ("is this
push for my current event") orthogonal to direction ("should I ever download here").

_Alternative considered:_ gate at each call site. Rejected — spreads logic across the untested shell
and risks the three sites drifting.

### D5: Status — silent arrow masking in `syncHealth`, `InSync` over enabled directions

`syncHealth(progress, download)` (`StatusContainerHost.kt:341`) computes the two arrows; `config` (and
thus `config.direction`) is already in scope in the enclosing `reduceFrom`. Threading the direction in,
the excluded direction's arrow is forced to `Arrow.HIDDEN`, and the existing collapse rule
(`upload == HIDDEN && download == HIDDEN → InSync`) then yields `InSync` over the enabled direction(s)
for free. No status vocabulary (`SyncProgress`/`DownloadProgress`) changes; the masking is a pure
reduction concern. No mode label is rendered — the single remaining arrow implies the mode.

Note: under `UploadOnly` the download arm never reads the union, so `DownloadProgress` stays `0/0` and
the download arrow would hide **on its own**. We mask **explicitly** anyway so `InSync` correctness is
not coupled to that emergent behavior.

### D6: Dev/test `direction` override on `EventLinkPayload`

The strict deeplink decoder rejects unknown keys, so the override must be a **declared** optional field
on `EventLinkPayload` (like `minPhotoDate`), additive within `v=3`, absent by default, and consumed on
the `autoJoin` path (`autoConfirm`). `autoJoin` still defaults to `Both`; the override lets the
headless USB loop exercise upload-only / download-only without WebDriverAgent taps.

### D7: Enrollment unchanged — download-only counts as a member

All modes enroll (write the empty manifest) at confirm. A download-only device therefore counts as an
active member and keeps the event alive while it is still consuming — correct, since reaping it would
delete bytes the consumer is still importing. Its manifest simply never upgrades to a real asset list.

### D8: Copy — extend sync/share framing to the permission surface

The permission priming copy ("…to back it up") is reworded to sync/share framing, extending the
existing `event-creation-ui` sharing-framing requirement to the permission surface. One generic,
direction-neutral wording (no per-mode branching), honest for all three modes.

### D9: Cutoff row shown-but-disabled under Download-only

The capture-date cutoff scopes uploads only. Under `DownloadOnly` the cutoff row is rendered but
disabled — visible so its existence is discoverable, inert because it has no effect on a
non-contributor.

## Risks / Trade-offs

- **Emergent vs explicit arrow-hiding under UploadOnly** → mask explicitly by direction (D5) so
  `InSync` never depends on the download arm happening to leave `DownloadProgress` at `0/0`.
- **A lone download-only consumer keeps an event un-reaped** → accepted and intended (D7): the bytes it
  is importing must not be GC'd out from under it; the event dies only when it too leaves.
- **Wasted pushes to an upload-only device** → harmless: push registration stays device-scoped and
  event-independent; the reconcile is gated off (D4), so the push is a client-side no-op.
- **Direction change requires leave & rejoin** → accepted (fixed-at-join). The rejoin path already runs
  a full reconcile/enumeration, so a mode switch needs no special migration — it "just works" via the
  existing switch = leave-then-join composition.
- **New field silently dropped by a future destructure of `EventConfig`** → the existing warning
  comment at `SnapSyncRoot.kt:517` and whole-object flow guard against this; do not destructure.

## Migration Plan

No migration. A persisted `EventConfig` predating this change decodes to `direction = Both` (the field
default under `ignoreUnknownKeys = true`), which is exactly today's behavior. No backend deploy, no
ledger reset. Rollback is removal of the field and its gates; persisted configs with an unknown
`direction` key still decode (the store already ignores unknown keys).

## Open Questions

_None outstanding — all forks were resolved in the pre-proposal interview (granularity, mutability,
download-only membership, status presentation, selector shape, dev override, cutoff behavior, and copy
framing)._
