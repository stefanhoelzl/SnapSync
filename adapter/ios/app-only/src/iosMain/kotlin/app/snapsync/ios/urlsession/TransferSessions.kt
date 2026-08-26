package app.snapsync.ios.urlsession

import platform.Foundation.NSURLSessionConfiguration

/**
 * **Which `URLSession` this app process moves bytes over, chosen by COMPILATION TARGET rather than at
 * runtime** (capability `ios-url-session-upload`, "The transport binding is fixed by the compilation
 * target"; also `photo-download`).
 *
 * `iosArm64` — every shipped binary — yields a **background** configuration, unchanged in every respect
 * including its session identifier and its `discretionary` / `sessionSendsLaunchEvents` /
 * `allowsCellularAccess` values. `iosSimulatorArm64` yields a **default** configuration, because a
 * background one transfers nothing there.
 *
 * Both app-process transports resolve their configuration here — [app.snapsync.download.IosDownloadTransport]
 * and [IosUrlSessionUploadPlatform] — so no build can hold a background binding for one and a default
 * binding for the other.
 *
 * ## Why the simulator cannot use the shipped binding
 *
 * `nsurlsessiond` resolves each client's bundle identifier as it evaluates the incoming XPC connection and
 * rejects a client that has none — which is every process an app author can build there, **including a real
 * installed app declaring a valid `CFBundleIdentifier`**. The daemon states this as its reason, at error
 * severity (measured 2026-08-25, macOS 26.5.2 / Xcode 26.6, iOS 26.5, with the client's own pid):
 *
 * ```
 * Evaluating new XPC connection … from pid <n> … with client bundle identifier (null)
 * Process with pid <n> does not have a bundle ID, rejecting connection
 * … invalidated … xpc_connection_cancel()
 * ```
 *
 * The client observes `NSCocoaErrorDomain` 4097 (`NSXPCConnectionInterrupted` — accepted, then torn down),
 * then *"failed to create a background NSURLSessionDownloadTask, as remote session is unavailable"*, and
 * every transfer ends `NSURLErrorDomain/-1`. Apple's own simulator processes resolve to real bundle
 * identifiers and theirs work. Six candidate fixes were tested and none works — ad-hoc signature, an Apple
 * Development identity, no signature at all, `application-identifier`/`team-identifier`/`get-task-allow`, a
 * second runtime, and any entitlement. **Do not spend time on them.**
 *
 * ## Why a target and not a runtime check
 *
 * `iosSimulatorArm64` is not a guess about the host: it is a compilation target whose output only ever runs
 * on a simulator, so a device binary contains **no route** to the default binding — *"a fact that is fixed
 * by the compilation target SHALL NOT be re-derived at runtime"* (spec `module-architecture`, "One shared
 * composition"). The alternative is production deciding at runtime which host it is on, which is the
 * `OsFacts`/`SIMULATOR_DEVICE_NAME` read that `changes/archive/2026-08-09-delete-simulator-session-downgrade`
 * deleted; it is not coming back. `:adapter:ios:ext-safe`'s `DeviceIdStores.kt` is the same shape for the
 * same reason.
 *
 * ## What the default binding does NOT evidence
 *
 * A default session runs in-process and dies with it. A simulator run is **not** evidence of: transfers
 * continuing across suspension or termination; the OS relaunching a terminated app for
 * `handleEventsForBackgroundURLSession` (device-only by vendor guidance — Quinn, *Testing Background Session
 * Code*, r. 16532261 — and unmeasurable there besides); reattachment to a prior process's tasks
 * ([IosUrlSessionUploadPlatform.reattach] can never find one); or the behaviour of
 * `__NSURLBackgroundSession`, including the invalidation defect
 * (`changes/archive/2026-07-12-fix-download-session-lifecycle` D5) — measured 2026-08-25, after the daemon
 * rejects and cancels the connection the client session does **not** call `didBecomeInvalidWithError`
 * (observed ~10 s after the transfer settled, n=1), so that host never reaches the path the defect lives on.
 *
 * Consequence a reader will meet as a symptom: a default session never sends
 * `URLSessionDidFinishEventsForBackgroundURLSession`, so a `handleEventsForBackgroundURLSession` wake holds
 * its `BackgroundEventsReceipts` receipt to the deadline and expires. The simulator actual says so at
 * construction, before it happens — the expiry is this host, not a fault. Nothing synthesises the drain: a
 * transport reporting events drained the OS never delivered would make a simulator run indistinguishable
 * from a device one, which is the false confidence D5 refused.
 *
 * ⏰ **Re-measure at the next iOS major**, alongside the PhotoKit and limited-access platform facts. Reproduce
 * with `xcrun simctl spawn <dev> log stream --style compact --level debug --predicate 'process ==
 * "nsurlsessiond"'` while a transfer runs; the runbook is `.claude/skills/ios-simulator/SKILL.md`.
 *
 * Decision record: `changes/bind-transport-session-by-target`.
 */
internal expect fun transferSessionConfiguration(identifier: String): NSURLSessionConfiguration

/**
 * Which binding this binary compiled — `"background"` or `"default"` — so a caller **reads** it rather than
 * inferring it from a log line or from a transfer that stalls.
 *
 * Public, unlike [transferSessionConfiguration], because the control channel reports it on
 * `/device/state`'s `build` map beside `uploadTier` and `uploadBase` (`:test:rig`, linked into `:app:ios`
 * only under `-Psnapsync.rig=true`). It reports a fact and decides nothing: no production code may branch
 * on it, which is the whole difference between this and the runtime host read that was deleted.
 */
expect val transferSessionBinding: String
