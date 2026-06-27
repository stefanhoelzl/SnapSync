## Why

Once a device has joined an event, there is **no way to leave it**. The eventId lives in the
Keychain (survives reinstall), the ledger records the event's uploads, and the background-upload
extension stays enabled — so the only ways out today are deleting the app or scanning a *different*
event's QR. A user who is done with an event (or scanned the wrong one) has no in-app exit.

This change adds a **leave** action: the inverse of the join lifecycle
(`event-rejoin-reconciliation`). Leaving is **local-only** — it disables the producer, wipes the
ledger, and forgets the eventId — but it does **not** delete anything already uploaded to storage.
Re-scanning the same QR later re-joins and reconciles those objects back as `COMPLETED` (the join
already does this), so leave/rejoin is non-destructive and round-trips.

## What Changes

- **New leave use-case.** A `LeaveEvent` use-case (sibling to `JoinEvent`, same capability module)
  runs, in order, with best-effort semantics: **disable the extension** (no concurrent ledger
  writer) → **reset the ledger to empty** (`resetTo([])`) and **clear the discovery cursor** →
  **clear the Keychain config** (`ConfigStore.clear()`) → set `EventStatus = Idle`. Failures are
  logged, not rolled back; the worst partial state (config kept) self-heals by re-joining on next
  launch. The platform side-effects (extension disable, cursor clear) are injected as lambdas,
  exactly as `JoinEvent` takes `clearDiscoveryCursor` — so the use-case stays pure and tested.
- **Config store gains `clear()`.** `ConfigStore` adds `suspend fun clear()`; the iOS
  `KeychainConfigStore` deletes the Keychain item (`SecItemDelete`) and sets its `config` StateFlow
  to `null`. Symmetric with `save()`. With the config absent, the extension's next cycle is a clean
  no-op even independent of the disable.
- **Leave lives in the "joined" layer only.** The status screen is a four-layer progression —
  `loading → gate → joining → joined`. A flat, icon-only **Logout** button (Material
  `Icons.AutoMirrored.Filled.Logout`) renders **bottom-right only in the joined layer**
  (`InProgress` / `NothingToSync` / `Completed`); it is absent in `Loading`, `Setup`, `Joining`, and
  `JoinFailed`. Scoping it to the joined layer means a leave can never race an in-flight join, so no
  cancellation logic is needed. (Consequence: there is no leave escape from `JoinFailed` — a
  transient network state whose recovery is re-scan or relaunch.)
- **Confirm before leaving.** Tapping the button opens a confirmation dialog ("Leave event?",
  Confirm / Cancel) directly — no intermediate menu. Confirm fires a new
  `StatusContainerHost.onLeaveEvent()` intent; Cancel dismisses. Dialog open/close is local screen
  state, so `UiState` and the snapshot→state reduction are **untouched** — `:domain:presentation`
  gains only the intent (with a no-op default so non-iOS hosts and tests construct unchanged).
- **New design-system components.** A flat icon-only button, an `AppConfirmDialog`, and a
  bottom-right action slot on `ScreenLayout` — all semantic (no Material 3 in any `App*`
  signature). The icon set (`compose.materialIconsExtended`) is added to the components module (the
  only module allowed to import Material 3).
- **Harness renders, does not fake.** The desktop phone frame renders the leave button and its
  dialog in joined-layer presets so the action and dialog are reviewable offscreen; the Confirm
  callback is the container's no-op default (no leave fakes wired) — the harness exercises **UI
  only**.

## Capabilities

### New Capabilities
- `leave-event`: the leave feature — the `LeaveEvent` use-case (disable → `resetTo([])` + clear
  cursor → `ConfigStore.clear()` → `EventStatus = Idle`, best-effort, disable-first), its local-only
  guarantee (already-uploaded objects are untouched; re-join reconciles them back), the
  `onLeaveEvent()` presentation intent, the joined-layer-only visibility rule, and the
  confirm-before-leave requirement.

### Modified Capabilities
- `deeplink-config`: add `ConfigStore.clear()` to the store seam (clears the persisted config and
  updates the source to `null`, idempotent when already absent); the iOS Keychain adapter deletes
  its item and emits `null`.
- `sync-status-screen`: the screen renders the flat Logout leave action bottom-right **only** in the
  joined-layer states (`InProgress` / `NothingToSync` / `Completed`) and **never** in
  `Loading` / `Setup` / `Joining` / `JoinFailed`; activating it raises a "Leave event?" confirmation
  whose confirm invokes `onLeaveEvent()`. `UiState` and the reduction are unchanged.
- `design-system`: inventory grows by a flat icon-only button, `AppConfirmDialog(title,
  confirmLabel, cancelLabel, onConfirm, onDismiss)`, and a bottom-right action slot on
  `ScreenLayout`; `compose.materialIconsExtended` joins the components module under the Material 3
  containment exception.
- `ios-app-shell`: `SnapSyncRoot` constructs `LeaveEvent`, injecting the real `disableExtension`
  (`setUploadJobExtensionEnabled(false)`) and `clearDiscoveryCursor`, and wires it into the
  container; `MainViewController` routes the leave intent like the other gate intents.
- `event-rejoin-reconciliation`: clarify that after a leave (config absent, ledger empty), a
  subsequent QR scan provisions and runs one fresh join — the leave/rejoin round-trip.
- `desktop-test-harness`: the phone frame renders the leave action and its confirmation dialog in
  joined-layer presets, with the confirm wired to the container's no-op default (no leave fake).

## Impact

- **`:capability:rejoin`**: new `LeaveEvent` use-case (`commonMain`, tested) taking `ConfigStore`,
  `LedgerBackend`, `MutableEventStatusSource`, and the two platform lambdas (`disableExtension`,
  `clearDiscoveryCursor`).
- **`:capability:config`**: `ConfigStore.clear()` on the port; `KeychainConfigStore` `SecItemDelete`
  + StateFlow `null`; in-memory/fake stores gain `clear()`.
- **`:domain:presentation`**: `StatusContainerHost` gains an injected `LeaveEvent` (no-op default)
  and an `onLeaveEvent()` intent. `UiState`/reduction unchanged.
- **`:domain:ui`**: `StatusScreen` gains an `onLeaveEvent` callback param and renders the leave
  button + confirm dialog (local dialog state) in joined-layer states.
- **`:domain:ui:components`**: new flat icon button, `AppConfirmDialog`, `ScreenLayout` action slot;
  `compose.materialIconsExtended` dependency (`gradle/libs.versions.toml`).
- **`:app:ios`**: `SnapSyncRoot` constructs and wires `LeaveEvent`; `MainViewController` passes the
  leave callback.
- **`:app:desktop`**: phone frame passes `onLeaveEvent` (container no-op default); no control-panel
  change.
- **Docs**: `docs/design.md` (leave is the local-only inverse of join; the four-layer model and the
  joined-layer leave affordance).
- **Out of scope**: deleting the event's uploaded objects from storage (leave is local-only); a
  leave escape from `JoinFailed`; any "leaving…" transient status (leave is fast and local — it
  returns straight to `Idle`).
