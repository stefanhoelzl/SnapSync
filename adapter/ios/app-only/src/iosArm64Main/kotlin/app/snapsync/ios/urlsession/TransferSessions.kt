package app.snapsync.ios.urlsession

import platform.Foundation.NSURLSessionConfiguration

/**
 * The device target's binding: a **background** configuration, so transfers survive suspension and the OS
 * relaunches the app on completion. Rationale and the measurement are on the `expect` declaration.
 *
 * Every shipped binary — TestFlight, App Store, and every sideloaded dev build — compiles this actual and
 * only this one. The simulator's default configuration is not merely unused here; it is absent from the
 * binary.
 *
 * ## The three properties, and why they are set here rather than at a call site
 *
 * All three are what a bare background configuration **already defaults to** — measured 2026-08-25 on
 * iOS 26.5: `discretionary=false`, `sessionSendsLaunchEvents=true`, `allowsCellularAccess=true`. They are
 * stated rather than inherited because the download transport was relying on them explicitly and the upload
 * platform implicitly, and one seam cannot serve both while leaving that difference unstated. Setting them
 * here changed neither session's behaviour; it made the download's intent survive the move.
 *
 * `discretionary=false` is the load-bearing one to keep: left `true`, the OS defers a transfer to windows it
 * considers favourable — in practice Wi-Fi and charging — and a download can sit indefinitely. With
 * `allowsCellularAccess=true` beside it, the pair says "run now, on whatever network you have", which is
 * what event photos need. (Both are only ever hints: a session created while the app is already in the
 * background is treated as discretionary regardless.)
 */
internal actual fun transferSessionConfiguration(identifier: String): NSURLSessionConfiguration =
    NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(identifier).apply {
        discretionary = false
        allowsCellularAccess = true
        sessionSendsLaunchEvents = true
    }

/** @see transferSessionBinding */
actual val transferSessionBinding: String = "background"
