package app.snapsync.objc

import co.touchlab.kermit.Logger
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t

/**
 * Run [block] on [queue] at the Objective-C boundary ([objcBoundary]): a throw is logged as [name]'s and contained,
 * never unwound into GCD.
 *
 * The queue is the caller's to name — the main queue only from the platform-UI adapters the main-lane allowlist
 * holds (`docs/architecture.md`, "Concurrency and failure"), so this helper opens no lane by itself. It lives in the
 * app-only module, beside the only callers, because `platform.darwin` is outside the extension's framework allowlist.
 */
fun onQueue(queue: dispatch_queue_t, log: Logger, name: String, block: () -> Unit) =
    dispatch_async(queue) { objcBoundary(log, name, block) }
