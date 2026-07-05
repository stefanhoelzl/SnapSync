## 1. Backend — event id in the silent push (`apns-push-sender`, `event-notify-endpoint`)

- [x] 1.1 Extend the APNs silent-push body builder to include a top-level `eventId` sibling of `aps`, taking the event id as an argument; `aps` stays `{ "content-available": 1 }`.
- [x] 1.2 Pass the `/event/<eventId>/notify` route's path event id into the fan-out so every dispatched push carries it.
- [x] 1.3 Update/add backend tests: push body carries `eventId` alongside `aps`; `202`/`404`/`400`/`502` outcomes unchanged; still no caller payload accepted.

## 2. `EventNotifier` — the notify use-case (`:capability:push`)

- [x] 2.1 Add an `EventNotifier` (commonMain) mirroring `PushRegistration`: `POST <host>/event/<eventId>/notify`, no body, no auth token, via the injected `PushHttpClient` seam (add a bodyless `post(url)` to the seam + `KtorPushHttpClient`); `runCatching` → log-and-swallow, no retry.
- [x] 2.2 commonTest (JVM + sim) with a fake client: asserts the exact POST URL, no body, failure absorbed (non-throwing), returns normally on non-2xx.

## 3. Notify trigger in `UploadCycle` (`:capability:upload`)

- [x] 3.1 Add an injected best-effort notify seam (`suspend () -> Unit`, default no-op) to `UploadCycle`, kept event-agnostic (the seam closes over the event id at the composition root).
- [x] 3.2 Count real completions per run (Phase-2 `SUCCEEDED`, non-blank key → `UploadCompleted`; exclude re-acks); on the fully-drained `COMPLETED` path only, after the `onDiscovery` device-manifest write, fire the seam iff `completed > 0`.
- [x] 3.3 Wrap the seam invocation in a bounded timeout + `runCatching` so a slow/hung notify never fails, stalls, or delays the cycle (mirror the in-cycle device-manifest PUT budget).
- [x] 3.4 commonTest (JVM + sim) against a fake platform + real engine: notify fires once on a drained cycle with ≥1 completion (and after the manifest hook); does NOT fire on a cap-truncated `PROCESSING` cycle; does NOT fire on a drained cycle with zero completions; a throwing/slow notify does not fail the cycle.

## 4. Guarded silent-push receiver (`:capability:push` seam, `:capability:download` impl)

- [x] 4.1 Change the `PushReceiver` seam to carry the pushed `eventId` and be async (suspending / completion-shaped) so the caller can await the receiver's synchronous portion.
- [x] 4.2 Implement a guarded receiver in `:capability:download` taking an active-event provider + `DownloadController`: run `reconcile(eventId)` only if `eventId` == active event, else no-op (covers non-active, left, and no-config).
- [x] 4.3 Retire `LoggingPushReceiver` as the wired default (keep or drop per composition-root wiring in §6).
- [x] 4.4 commonTest (JVM + sim): active-event push → reconcile called with that id; non-active/left/no-config push → no reconcile; the await-before-complete contract is exercised (receiver's sync work observed complete before the completion callback fires).

## 5. `photo-download` — event-driven discovery

- [x] 5.1 Confirm no code enforced "foreground-only" beyond the trigger wiring; ensure `reconcile` remains safe to invoke from the push path (idempotent, non-throwing on union failure) — no new discovery code needed beyond the receiver trigger.

## 6. iOS composition roots + thin Swift wiring (`:app:ios`)

- [x] 6.1 App root: bind the `EventNotifier` (shared Ktor/Darwin client + injected host) into `UploadCycle`'s notify seam for the url-session tier; bind the guarded receiver (ConfigSource as active-event provider + `DownloadController`) into the receive path.
- [x] 6.2 Extension root: bind the `EventNotifier` into the extension's `UploadCycle` notify seam (reuse `darwinHttpClient`); verify the notify POST is awaited within `process()` but bounded so it cannot blow the OS cycle budget.
- [x] 6.3 `SnapSyncRoot.onSilentPush`: take the pushed `eventId` and the OS fetch completion handler; await the guarded receiver's sync portion, then invoke the OS handler (report `.newData` when work was enqueued).
- [x] 6.4 Thin Swift AppDelegate: extract `userInfo["eventId"]` from the remote-notification callback and forward it (+ the completion handler) to `SnapSyncRoot.onSilentPush`; keep the `PublicNSLogWriter` logging convention. No decisions in Swift.
- [x] 6.5 Verify the url-session tier's composition root wires the device-manifest producer via `onDiscovery` (the drained-cycle notify assumes the union is refreshed); if not, note/fix so its uploads reflect in the union.

## 7. Verify & spec

- [x] 7.1 `./gradlew build` (all targets + JVM/sim tests) and `./gradlew compileIosMainKotlinMetadata` green.
- [x] 7.2 On-device dev-loop smoke (headless per-build loop, root `CLAUDE.md`): device A uploads to a fresh event → confirm the object lands in the bunny zone AND a `POST …/notify` fires; device B receives the silent push and imports the foreign photo without a foreground visit (watch `idevicesyslog`).
- [x] 7.3 `npx --yes @fission-ai/openspec@1.4.1 validate notify-driven-download --strict` passes; archive after merge.
