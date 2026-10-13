package app.snapsync.feature.links.readmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The link the app was last opened with, until the screen has opened it: a delivery the platform made — cold at
 * launch or warm — is kept here, so it waits for a screen that is not built yet instead of the delivery waiting for
 * the screen.
 */
class OpenedLinks {
    private val latest = MutableStateFlow<String?>(null)

    /** The link waiting to be opened, or `null`. */
    val pending: StateFlow<String?> get() = latest

    /** The app was opened with [url]; `null` — the delivery opened nothing — changes nothing. */
    fun offer(url: String?) {
        if (url != null) latest.value = url
    }

    /** The screen opened [url]: it no longer waits. A newer link offered meanwhile stays. */
    fun consume(url: String) {
        latest.compareAndSet(url, null)
    }
}
