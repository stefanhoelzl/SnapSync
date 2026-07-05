package app.snapsync.push

import co.touchlab.kermit.Logger

/**
 * The seam invoked when the app receives a silent (`content-available`) push (capability
 * `push-registration`). The iOS app-shell wiring forwards the OS's remote-notification callback here.
 * Shaped as a single-method interface so a later change can substitute a real handler (e.g. triggering
 * download discovery) without touching the app-shell receive wiring.
 */
fun interface PushReceiver {
    fun onSilentPush()
}

/**
 * The infrastructure-phase receiver: it just **logs** receipt (via Kermit), so the delivery pipe is
 * observable end-to-end on device — a `POST /event/<id>/notify` produces a log line visible in
 * `idevicesyslog` — without implementing any use-case behavior.
 */
class LoggingPushReceiver(private val log: Logger = Logger.withTag("PushReceiver")) : PushReceiver {
    override fun onSilentPush() {
        log.i { "silent push received" }
    }
}
