## Why

A device that has joined an event already holds the **full join capability** — the `eventId` lives
in the Keychain (`deeplink-config`), and `encodeConfigUrl(EventConfigPayload(eventId))`
**deterministically** rebuilds the exact `snapsync://config?v=3&d=…` deeplink a scanner receives.
The same eventId is the upload authorization; there is no organizer secret and no per-device
difference. So **any** joined participant can already, in principle, re-share the event — today they
just have no way to surface it.

This change surfaces it: the status screen displays the **join QR** (with a "Scan to join this
event" caption) and a **share** action, so a joined user can invite others without going back to the
external event-creation tool. It **reverses an explicit design decision** — `docs/design.md` §1
currently states the app "does not create events, does not display QR codes." Displaying the QR is
now an intentional product capability; creating events stays external.

## What Changes

- **Reverse the no-QR rule.** `docs/design.md` §1 is amended: the app MAY **display** the join QR for
  the event it has joined (it still does **not** create events). This is the framing decision the rest
  of the change rests on.
- **Derive the invite URL in the host.** `StatusContainerHost` exposes the invite deeplink as
  observable state derived from `ConfigSource` (`eventId → encodeConfigUrl`), a single source feeding
  both the rendered QR and the share action so the two can never drift. `:domain:presentation` already
  depends on `:capability:config`; no new module dependency.
- **Invite affordances live in the joined layer only.** The QR, its caption, and the share action
  render **only** in `InProgress` / `NothingToSync` / `Completed` — the same `isJoinedLayer` gate the
  leave action already uses — and never in `Loading` / `Setup` / `Joining` / `JoinFailed` /
  `PermissionBlocked`. `UiState` and the snapshot→state reduction are **unchanged** (the invite URL
  rides as a screen-level param, exactly as `transientError` does).
- **Render the QR with a KMP library (qrose).** `io.github.alexzhirkevich:qrose` renders the QR
  directly in `commonMain` Compose inside a new `AppQrCode` component — no platform render seam.
  Because qrose-on-iOS under this toolchain (Kotlin 2.4.0 / Compose MP 1.11.1) is the one assumption
  in the plan, **task 0 is a throwaway `compileIosMainKotlinMetadata` spike** that gates the library
  path; if it fails to link, the fallback is a DI render seam (iOS `CIQRCodeGenerator` / JVM ZXing,
  already a dependency) behind the **same** `AppQrCode` contract.
- **Share is fire-and-forget.** A new `onShareInvite()` intent hands the deeplink string to the
  platform. The container takes the share action as a bare **`share: (String) -> Unit = {}` lambda**
  (no-op default) — the `leave` pattern verbatim, **not** a named seam type. iOS presents
  `UIActivityViewController`; the desktop harness copies to the clipboard / logs. No completion/close
  handling: `UiState` is a continuous projection, so the system-presented sheet cannot desync it.
- **New design-system components, bottom-end action cluster.** `AppQrCode(content, caption?)` and a
  flat icon-only **share** action join the inventory. `ScreenLayout`'s single `bottomEndAction` slot
  becomes a container-arranged **bottom-end action cluster** hosting both the share and leave icon
  buttons. The qrose import is confined to the components module (like the Material icon artifact),
  never reaching a screen or an `App*` signature.
- **Harness renders, does not fake.** Forged joined-layer presets carry a fixed sample `eventId` so
  the QR and share render and are reviewable offscreen; the share callback is wired to a
  clipboard/log stub (test equipment), exercising **UI only**.

## Capabilities

### New Capabilities
- `event-invite-qr`: the invite feature — deriving the invite deeplink from the joined event's
  `eventId` (`encodeConfigUrl`, deterministic, same URL a scanner gets), displaying it as a scannable
  QR with the "Scan to join this event" caption, the `onShareInvite()` presentation intent over a
  bare `share: (String) -> Unit = {}` lambda (fire-and-forget, no-op default), the joined-layer-only
  visibility rule, and the explicit acknowledgement that the displayed QR carries the full join
  capability (any scanner becomes an uploader; an existing member re-scanning reconciles and uploads
  nothing new).

### Modified Capabilities
- `design-system`: inventory grows by `AppQrCode(content, caption?)` (renders a scannable QR plus an
  optional caption; the QR library is contained to the components module) and a flat icon-only
  **share** action; `ScreenLayout`'s bottom-right slot becomes a container-owned **action cluster**
  arranging multiple end-aligned icon actions (share + leave) with consistent spacing.
- `sync-status-screen`: in the joined layer the screen additionally renders the invite QR with its
  caption above the hero and a share action in the bottom action cluster; both are absent in all
  non-joined states; the invite URL enters as a screen-level param and `UiState`/the reduction are
  unchanged.
- `ios-app-shell`: `SnapSyncRoot` exposes the invite URL from the container and binds the share lambda
  to present `UIActivityViewController` with the deeplink; `MainViewController` passes the invite URL
  and routes the share action like the other intents.
- `desktop-test-harness`: forged joined-layer presets supply a fixed sample `eventId` so the QR and
  share render; the share callback is a clipboard/log stub (UI-only, no real platform share).

## Impact

- **`:domain:ui:components`**: new `AppQrCode(content, caption?)` and flat icon-only share action;
  `ScreenLayout` bottom slot → action cluster; new `qrose` dependency (`gradle/libs.versions.toml` +
  module build) — gated by the task-0 spike, with the CIQRCodeGenerator/ZXing render-seam as the
  fallback behind the same component contract.
- **`:domain:presentation`**: `StatusContainerHost` derives/exposes the invite URL from `ConfigSource`
  (via `encodeConfigUrl`), gains a `share: (String) -> Unit = {}` injected lambda (no-op default) and
  an `onShareInvite()` intent. `UiState`/reduction unchanged. No new module dependency.
- **`:domain:ui`**: `StatusScreen` gains `inviteUrl: String?` and `onShareInvite: () -> Unit = {}`
  params and renders the QR + caption + share action in joined-layer states only.
- **`:app:ios`**: `SnapSyncRoot` binds `share` to a `UIActivityViewController` presentation and exposes
  the invite URL; `MainViewController` collects/passes it and routes `onShareInvite`.
- **`:app:desktop`**: forged joined-layer presets carry a sample `eventId`; share wired to a
  clipboard/log stub.
- **Docs**: `docs/design.md` §1 amended (app MAY display the join QR; still does not create events);
  §5 notes the joined-layer invite affordance.
- **Out of scope**: bootstrapping installation for recipients who do **not** yet have the app — both
  the QR and the shared `snapsync://` link presuppose the app is installed (Camera and a tapped
  custom-scheme link both dead-end otherwise). An HTTPS universal link with an App Store fallback would
  be a separate, backend-touching change. Also out of scope: any access control on who may scan
  (the QR is the join capability by design); share analytics or a share-result UI.
