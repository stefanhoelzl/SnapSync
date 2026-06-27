## Context

A joined device already holds everything needed to reconstruct the join QR:

| Store | Holds | Used here |
| --- | --- | --- |
| Keychain (`deeplink-config`) | the `eventId` | source of the invite URL |
| `:capability:config` | `encodeConfigUrl(EventConfigPayload)` | deterministic encoder, already used by the JVM `QrGeneratorMain` and the inverse of the `decodeConfigUrl` the app runs on a scanned QR |

The deeplink is **deterministic and total**: `eventId → snapsync://config?v=3&d=<base64url(json)>`.
The exact bytes a participant would scan are recoverable on-device with no network call and no secret
— the `eventId` *is* the upload capability (the edge endpoint authorizes by event id only). That is
the whole technical basis for this change: re-sharing is just re-encoding what we already persisted.

`docs/design.md` §1 currently says the app "does not create events, **does not display QR codes**."
That sentence predates any in-app invite need; this change reverses the **display** half (creation
stays external).

The status screen is the same four-layer progression the leave action uses:

```
   layer        UiState(s)                                   Invite QR + share?
   ──────────────────────────────────────────────────────────────────────────
   loading   →  Loading                                           ✗
   gate      →  Setup · PermissionBlocked                         ✗
   joining   →  Joining · JoinFailed                              ✗
   joined    →  InProgress · NothingToSync · Completed            ✓   (isJoinedLayer)
```

The invite affordances reuse the existing `isJoinedLayer` predicate verbatim — the same gate already
shown to be correct for the leave action.

## Goals / Non-Goals

**Goals:**
- A joined user can display the event's join QR and share its deeplink to invite others, in-app.
- The invite URL has a **single source** in presentation, feeding both the QR and the share, so they
  cannot drift; it is derived from `ConfigSource`, not recomputed in the UI.
- `:app:ios` stays wiring-only: the share is an injected lambda; the QR rendering is library or seam,
  both contained to the components module.
- No new `UiState`, no reduction change — the invite URL is a screen-level param (like
  `transientError`); visibility is a function of the rendered state.

**Non-Goals:**
- Creating events (stays external) or any web/universal-link that bootstraps installation for a
  recipient without the app.
- Access control on scanning — the QR is the join capability by design; there is nothing to gate.
- Share completion handling, share analytics, or a share-result UI.
- Re-using the JVM `QrGeneratorMain` (ZXing) for on-device rendering — ZXing is JVM-only and will not
  compile for `iosMain`.

## Decisions

### D1 — `event-invite-qr` is its own capability

The invite behavior (URL derivation, joined-layer visibility, fire-and-forget share, the
capability-exposure acknowledgement) reads cleanly as a parallel sibling to `leave-event` and
`event-rejoin-reconciliation` — the three together being the membership lifecycle: join, leave,
invite. `sync-status-screen` and `design-system` take only deltas for the rendering surface.
Folding the behavior into `sync-status-screen` would bury the product decision (and the exposure
trade-off) inside a rendering spec; rejected.

### D2 — Reverse the design.md §1 "does not display QR codes" rule, deliberately

This is the framing decision. The app gains the ability to **display** the join QR for the event it
has joined; it still does not **create** events. The reversal is safe-by-construction because the
device already holds the capability (the `eventId`), so displaying the QR grants nothing the device
could not already hand out. The exposure consequence is real and accepted (see Risks): the on-screen
QR **is** the live join capability.

### D3 — One invite-URL source in the host; screen-level param, not `UiState`

`StatusContainerHost` derives the invite deeplink from `ConfigSource` (`config.eventId →
encodeConfigUrl`) and exposes it as observable state. Both consumers read that one source: the screen
renders it as the QR, and `onShareInvite()` shares `inviteUrl.value`. Because the encode is
deterministic, the QR and the shared link are provably identical.

It does **not** enter `UiState`. The reduction (`reduceFrom`) stays the pure sync projection;
`NothingToSync` stays a `data object`. The invite URL rides into `StatusScreen` as a parameter exactly
as `transientError` already does, and `MainViewController` collects it alongside `state`. This keeps
the engine→status→presentation projection untouched and the invite URL (which comes from the config
seam, not the engine chain) out of the snapshot tests — its derivation is unit-tested on the host
instead.

### D4 — Share is a bare `share: (String) -> Unit = {}` lambda, fire-and-forget

The established house style for a fire-and-forget platform action is `leave` — `private val leave:
suspend () -> Unit = {}` on the host, defaulted to no-op, bound at the composition root — and
`onOpenSettings` riding the existing `PermissionRequester` seam. There is **no** seam-as-type in the
codebase for these; they are plain injected functions. Share is structurally identical, so it takes
the same shape: `share: (String) -> Unit = {}`, exposed as `fun onShareInvite() = intent {
inviteUrl.value?.let(share) }`. The host owns the URL; the iOS lambda just does
`UIActivityViewController(url)` — zero logic, nothing to test, in line with "`:app:ios` is
wiring-only."

**No close-event handling.** `UIActivityViewController` is a system-presented modal *over* our view,
not part of our Compose tree (unlike the leave confirm dialog, which is our local Compose state we
must track). And `UiState` is a continuous projection of ledger × config × permission — none of which
sharing touches — so the sheet opening, closing, completing, or bouncing to another app cannot desync
the screen; it recomposes from the live flows on return. A future "did they actually share" signal,
if ever wanted, is consumed **inside** the iOS lambda (`completionWithItemsHandler`) and never
surfaces into `commonMain`. So the lambda stays `(String) -> Unit`. A named `InviteSharer` type was
considered and rejected as the only one-of-its-kind seam in a codebase that injects plain functions.

### D5 — Render with qrose in `commonMain`, gated by a compile spike; render-seam fallback

`io.github.alexzhirkevich:qrose` (1.1.2) ships real iOS klibs (`iosArm64`, `iosSimulatorArm64`,
`iosX64`), depends only on `compose.ui`, and renders a QR directly as a Compose `Painter` — so
`AppQrCode` can render in `commonMain` with **no platform render seam**. The one unknown is binary
compatibility under this toolchain: qrose 1.1.2 is built against Kotlin 2.3.0, this project is on
Kotlin 2.4.0 / Compose MP 1.11.1 (one Kotlin minor ahead; Gradle resolves `compose.ui` up to 1.11.1).
That is a klib/Compose-ABI question metadata cannot answer, so **task 0 is a throwaway
`compileIosMainKotlinMetadata` spike**:

```
   add qrose → drop rememberQrCodePainter("…") into one composable → compileIosMainKotlinMetadata
        ├─ green → keep the library path (expected), delete the throwaway composable
        └─ red   → pivot AppQrCode's insides to a DI render seam:
                       iOS → CIQRCodeGenerator (stock CoreImage)
                       JVM → ZXing (already a dependency — QrGeneratorMain uses it)
                   AppQrCode then takes the same (content, caption?) but renders an injected bitmap
```

Crucially, the **`AppQrCode(content, caption?)` contract is identical on both branches** — only its
implementation differs — so the spike outcome does not ripple into the specs, only the components
module's internals. The library path is expected; the seam is a cheap, proven contingency (zero new
deps on either side).

### D6 — Joined-layer-only visibility, reusing `isJoinedLayer`

The QR, caption, and share render only in `InProgress` / `NothingToSync` / `Completed`, gated by the
**existing** `isJoinedLayer` predicate. They are absent in `Loading`, `Setup`, `PermissionBlocked`,
`Joining`, and `JoinFailed`. The invite URL is non-null whenever config is present (so it exists
during `PermissionBlocked` too), but the screen renders it only in the joined layer — the gate is in
the screen, identical to how the leave action is scoped.

### D7 — Bottom-end **action cluster**; `AppQrCode` + share icon button, both semantic

The share and leave affordances are both flat icon-only actions anchored bottom-end. `ScreenLayout`'s
existing single `bottomEndAction` slot evolves into a container-owned **action cluster**: the screen
supplies the action composables, the container row-arranges them end-aligned with consistent spacing
(the design system already reserves "action ordering/stacking" as a container concern). Order is the
screen's composition order (share, then leave). `AppQrCode(content, caption?)` is a new semantic
component carrying only data — the deeplink string and the caption text — with the QR library import
**confined to the components module** (like the Material icon artifact), so no screen and no `App*`
signature carries a rendering type. The caption is rendered by `AppQrCode` itself because screens may
not import Material 3 `Text`.

## Risks / Trade-offs

- **The on-screen QR is the live join capability.** Anyone who glances at, or is handed, a joined
  phone can scan and join, becoming an uploader to the owner's event/storage. Accepted: this is a
  personal TestFlight app among trusted people, upload is one-way, and an **existing** member
  re-scanning is idempotent (the `event-rejoin-reconciliation` join seeds already-stored photos as
  `COMPLETED`, so nothing re-uploads). The only new exposure is a brand-new scanner — a deliberate
  product trade-off for discoverability, recorded as a requirement so it is not rediscovered as a bug.
- **qrose-on-iOS is the one assumption** (D5). Mitigated by the task-0 spike and a cheap render-seam
  fallback behind an unchanged component contract; the feature ships either way.
- **Neither the QR nor the shared link bootstraps installation.** Both presuppose the recipient
  already has SnapSync (Camera and a tapped `snapsync://` link both dead-end otherwise). Fine for the
  TestFlight audience; a universal-link + App Store fallback is a separate future change. Recorded as
  out-of-scope, not a defect.

## Migration Plan

Additive. `StatusScreen` gains `inviteUrl: String?` and `onShareInvite: () -> Unit = {}` parameters
(defaulted), so existing call sites and status-screen tests compile unchanged. `StatusContainerHost`
gains an injected `share: (String) -> Unit = {}` (no-op default) and exposes the invite URL, so
existing constructions and presentation tests compile unchanged and a share is inert without a real
binding. `ScreenLayout`'s bottom slot widens from one action to a cluster — the leave action moves
into it; the single-action call shape is subsumed. New components and the `qrose` dependency are
contained to `:domain:ui:components`. No data migration.

## Open Questions

- None blocking. A universal-link/App-Store-fallback invite for non-app recipients, and any future
  access control on event membership, are out of scope and would be separate changes.
