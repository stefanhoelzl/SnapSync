package app.snapsync.world

import app.snapsync.ports.Completion
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/** A completion handler with no expiry signal, as a silent push, the rig and a background-session relaunch hand one. */
internal fun bareCompletion(onComplete: () -> Unit): Completion = object : Completion {
    override fun complete() = onComplete()
    override fun onExpired(action: () -> Unit) = Unit
}

/**
 * Whether this launch's status host has been assembled — observed at the platform's UI, which host assembly starts
 * showing states to. Waits briefly for the UI lane's first show, so a `false` means none arrived.
 */
internal suspend fun World.hostAssembled(): Boolean =
    withTimeoutOrNull(200.milliseconds) { while (ui.shown.value == null) delay(5.milliseconds) } != null
