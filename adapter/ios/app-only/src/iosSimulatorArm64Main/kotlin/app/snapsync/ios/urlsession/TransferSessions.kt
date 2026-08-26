package app.snapsync.ios.urlsession

import co.touchlab.kermit.Logger
import platform.Foundation.NSURLSessionConfiguration

/**
 * The simulator target's binding: an ordinary **default** configuration, because a background one transfers
 * nothing here. Rationale, the daemon's refusal, and what this binding does not evidence are all on the
 * `expect` declaration — read it before writing a scenario against this host.
 *
 * **This is a degraded binding, deliberately.** It buys the one thing the host could not do at all — moving
 * bytes, in both directions, which is what makes two-member scenarios possible — and it gives up every
 * background-session property. It is a precondition for those scenarios, not coverage of the transport.
 *
 * Only `allowsCellularAccess` is set: it is honoured on both configurations. `discretionary` and
 * `sessionSendsLaunchEvents` are background-only and are deliberately **not** set here, so no line in this
 * file writes a property its own configuration ignores. (Measured: `sessionSendsLaunchEvents` defaults to
 * `false` on a default configuration and `true` on a background one, so copying the device actual's line
 * here would set it away from this configuration's own default, to no effect.)
 */
internal actual fun transferSessionConfiguration(identifier: String): NSURLSessionConfiguration =
    NSURLSessionConfiguration.defaultSessionConfiguration().apply {
        allowsCellularAccess = true
        announceBinding(identifier)
    }

/** @see transferSessionBinding */
actual val transferSessionBinding: String = "default"

/**
 * Say what this process got, and what will therefore happen, **before** it happens (law "Absence is never
 * silent").
 *
 * Without this line the first symptom is an `OsReceipt` expiry 20 s after a `handleEventsForBackgroundURLSession`
 * wake — a line that collapses two causes with different consequences: *the imports genuinely overran*, which
 * is a real concern on a device, and *this host has no daemon to signal drain*, which is not a concern at
 * all. The expiry line cannot tell them apart, so this one is written ahead of it.
 *
 * Logged at INFO and once per session construction (each transport builds exactly one), not per transfer.
 */
private fun announceBinding(identifier: String) {
    Logger.withTag("TransferSessions").i {
        "transfer session '$identifier': DEFAULT configuration (identifier ignored) — this is " +
            "iosSimulatorArm64. Transfers run in-process and die with it. This session never reports " +
            "didFinishEventsForBackgroundURLSession, so a handleEventsForBackgroundURLSession wake holds " +
            "its receipt to the deadline and EXPIRES: that expiry is this host, not a fault. Suspension " +
            "survival, OS relaunch and task reattachment are device-only and are not exercised here."
    }
}
