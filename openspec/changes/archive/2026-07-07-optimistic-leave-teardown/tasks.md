## 1. LeaveEvent use-case (capability/membership)

- [x] 1.1 Add constructor params to `LeaveEvent`: a `ConfigSource` (read side) and a `CoroutineScope`; change the injected notify lambda from `suspend () -> Unit` to `suspend (eventId: String) -> Unit`.
- [x] 1.2 Reorder `leave()` to: snapshot `eventId` from `configSource.config.value` → `step("disable")` → `step("clear")` → `eventId?.let { scope.launch { step("notify") { notify(it) } } }` (fire-and-forget, unconditional after clear).
- [x] 1.3 Update the `LeaveEvent` KDoc to describe the new order, the read-before-clear snapshot, the fire-and-forget notify on the app-lifetime scope, and the preserved self-heal rationale.

## 2. Composition root wiring (app/ios — untested shell)

- [x] 2.1 In `SnapSyncRoot.kt`, update the `LeaveEvent(...)` construction: pass `configSource = config`, `scope = <SupervisorJob scope>`, and `notifyLeave = { id -> leaveNotifier.leave(id, deviceId) }`.
- [x] 2.2 Update the surrounding comment describing the leave sequence to match the new order.

## 3. Tests

- [x] 3.1 `capability/membership` commonTest: a never-completing (or latch-suspended) notify — assert `leave()` returns, `ConfigStore.clear()` ran, and the notify was dispatched with the snapshotted `eventId`.
- [x] 3.2 `capability/membership` commonTest: assert order (disable before clear) and that a thrown `clear()` still results in the notify being dispatched (unconditional).
- [x] 3.3 `test/integration` commonTest: drive `onLeaveEvent()` with a leave whose notify is still pending — assert `UiState` flips to `CreateEvent` (config → null) without awaiting the DELETE.
- [x] 3.4 (Switch) `test/integration` or `capability/membership`: a switch (`withLeave = true`) proceeds to enroll/provision without blocking on the departed event's pending DELETE.

## 4. Verify

- [x] 4.1 `./gradlew build` (JVM tests + all-target compile) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy) both green.
- [x] 4.2 `npx --yes @fission-ai/openspec@1.4.1 validate --specs --strict` passes.
