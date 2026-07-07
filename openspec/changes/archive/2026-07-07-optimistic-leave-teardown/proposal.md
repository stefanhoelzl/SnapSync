## Why

Leaving an event is the only button in the app whose screen change **waits on a network
round-trip**: `LeaveEvent` runs `disable → notify (DELETE) → clear`, and the screen only leaves the
joined layer once `config.clear()` fires `ConfigSource` to `null` — which sits behind the awaited
`DELETE /events/<eventId>/devices/<deviceId>`. When that DELETE is slow, the joined screen (QR,
health, buttons) visibly freezes after the user confirms "Leave". Create and Join already flip to a
loading state *before* awaiting their network calls; Leave does not. The DELETE is explicitly
best-effort (abandon-leak accepted, self-healing), so nothing should ever have blocked on it.

## What Changes

- Reorder the `LeaveEvent` sequence to **disable → clear → (background) notify**: the local teardown
  (`disableExtension` + `ConfigStore.clear`) runs awaited and fast, flipping the screen to the setup
  gate immediately; the backend `DELETE` is dispatched **fire-and-forget** on an app-lifetime scope.
- Snapshot the `eventId` **synchronously before** the clear (preserving today's read-before-clear
  guarantee) and pass it into the notify, so the backgrounded DELETE targets the correct event with
  no race against the cleared config.
- The notify fires **unconditionally** after the clear step (matching the existing "each step
  independent, best-effort" philosophy) — a failed `clear()` does not suppress it.
- The same non-blocking behavior applies to the **switch path** (provisioning a different event
  while joined): its "Joining …" no longer waits on the departed event's DELETE.
- Structural: `LeaveEvent` gains an injected `CoroutineScope` (the app's `SupervisorJob`) and a
  `ConfigSource` read side; the injected notify lambda becomes `suspend (eventId: String) -> Unit`.
- No user-facing acknowledgment is added — the instant flip to "Start an event" is the confirmation.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `leave-event`: The mandated leave sequence changes from `disable → notify → clear` to
  `disable → clear → notify`; the notify becomes non-blocking (fire-and-forget on an app-lifetime
  scope) so the local teardown and screen transition no longer wait on the backend DELETE; the
  `eventId` is snapshotted before the clear and passed into the notify; `LeaveEvent` gains an
  injected `CoroutineScope` and `ConfigSource`. The best-effort/no-rollback self-heal guarantees are
  preserved but re-derived for the new order.

## Impact

- **Code**: `capability/membership` (`LeaveEvent`) — behavior + constructor signature; the injected
  notify lambda's shape (`suspend (eventId: String) -> Unit`). `app/ios/.../SnapSyncRoot.kt`
  composition root rewires the `LeaveEvent` construction (untested app shell). `StatusContainerHost`
  and its `leave: suspend () -> Unit` seam are **unchanged**.
- **Tests**: new unit test in `capability/membership` (a never-completing notify still returns and
  clears config; the snapshotted `eventId` is passed to notify) and integration test in
  `test/integration` (`UiState` flips to `CreateEvent` while the DELETE is still pending).
- **Unaffected**: backend endpoints (`event-leave-endpoint` receives the same DELETE, possibly
  slightly later), the leave confirmation dialog, and all Create/Join UI.
