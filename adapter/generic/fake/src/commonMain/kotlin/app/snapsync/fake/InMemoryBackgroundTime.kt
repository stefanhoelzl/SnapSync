@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.fake

import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One hold the system has granted, as the system's own table shows it: the label it was begun with, and the
 * expiration handler it holds for it. [expire] is what the operating system does when this hold's time is up — it
 * invokes the handler, at most once, and does **not** end the hold (the holder must).
 */
class HeldBackgroundTime internal constructor(val label: String, private val onExpiry: () -> Unit) {
    private val expired = AtomicBoolean(false)

    /** The operating system's expiry for this hold: the handler runs once, however often this is called. */
    fun expire() {
        if (expired.compareAndSet(expectedValue = false, newValue = true)) onExpiry()
    }
}

/**
 * An honest in-memory [BackgroundTime] over the caller's [held] cell — the system's table of outstanding holds and
 * their expiration handlers, which is all `UIApplication` keeps for them. [begin] adds an entry and [end] removes
 * that entry exactly once, so a world or a test observes what the process holds by reading the cell, and plays the
 * operating system by calling an entry's [HeldBackgroundTime.expire] — the lever lives with whoever owns the cell,
 * never on this double (capability `port-contracts`, `BackgroundTimeContract`).
 */
internal class InMemoryBackgroundTime(private val held: MutableStateFlow<List<HeldBackgroundTime>>) : BackgroundTime {
    override fun begin(label: String, onExpiry: () -> Unit): BackgroundTimeHold {
        val entry = HeldBackgroundTime(label, onExpiry)
        held.update { it + entry }
        return Hold(entry)
    }

    private inner class Hold(private val entry: HeldBackgroundTime) : BackgroundTimeHold {
        private val ended = AtomicBoolean(false)

        override fun end() {
            if (ended.compareAndSet(expectedValue = false, newValue = true)) held.update { table -> table.filterNot { it === entry } }
        }
    }
}
