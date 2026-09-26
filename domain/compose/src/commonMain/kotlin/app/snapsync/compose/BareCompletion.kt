@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.compose

import app.snapsync.ports.Completion
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A [Completion] over a bare handler the operating system hands an inbound-port entry — a block with no expiry
 * signal of its own (the silent push's fetch handler, until the entry surface becomes event ports in 11g). Released
 * once; its only "time is up" is the process's background time, which the entry holds across it.
 */
internal fun completionOf(handler: () -> Unit): Completion = BareCompletion(handler)

private class BareCompletion(private val handler: () -> Unit) : Completion {
    private val released = AtomicBoolean(false)

    override fun complete() {
        if (released.compareAndSet(expectedValue = false, newValue = true)) handler()
    }

    override fun onExpired(action: () -> Unit) = Unit
}
