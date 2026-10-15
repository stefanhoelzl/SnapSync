package app.snapsync.mock

import app.snapsync.levers.StoreFault
import app.snapsync.levers.WorldEvent
import kotlin.concurrent.Volatile

/**
 * The faults an operator armed on a small store — the Keychain, the preferences — each refusing the next calls of its
 * [StoreFault] at one address, spent one call at a time. Both processes call the store from their own threads and the
 * operator arms from another, so every change is made under one lock.
 */
internal class ArmedFaults<A> {
    private class Armed<A>(val address: A, val kind: StoreFault, val remaining: Int, val told: WorldEvent?)

    private val lock = mockLock()
    private val armed = mutableListOf<Armed<A>>()

    /** Told each [WorldEvent] an armed fault names, as a call spends it, on the thread that made the call. */
    @Volatile
    var watcher: StoreWatcher = StoreWatcher {}

    /** The next [times] calls of [kind] at [address] refused; [told] is what the watcher hears each time, if anything. */
    fun arm(address: A, kind: StoreFault, times: Int, told: WorldEvent?) = lock.locked {
        require(times >= 1) { "times must be at least one, was $times" }
        armed += Armed(address, kind, times, told)
    }

    /** Whether a call of [kind] at [address] is refused — spending one armed fault when it is. */
    fun spend(address: A, kind: StoreFault): Boolean {
        val spent = lock.locked {
            val i = armed.indexOfFirst { it.address == address && it.kind == kind }
            if (i < 0) return@locked null
            val fault = armed[i]
            if (fault.remaining > 1) {
                armed[i] = Armed(fault.address, fault.kind, fault.remaining - 1, fault.told)
            } else {
                armed.removeAt(i)
            }
            fault
        } ?: return false
        spent.told?.let { watcher.told(it) }
        return true
    }
}

/** Told each refusal an armed fault names, as the app's call spends it ([ArmedFaults.watcher]). */
fun interface StoreWatcher {
    fun told(event: WorldEvent)
}

/**
 * Whether a store is sealed with the device — a phone before its first unlock since it restarted, when iOS keeps its
 * protected stores closed. One per mock, read on every call; the channel's switch is `DeviceLevers.lock`.
 */
internal class Seal {
    @Volatile
    var on: Boolean = false
}

/**
 * What a small store — the Keychain, the preferences — refuses a call with, asked before every call: [always] (a store
 * constructed unable to look), the device's [seal], or a fault an operator armed in [faults]. `null` lets it through.
 */
internal class StoreRefusals<A>(
    private val seal: Seal? = null,
    private val faults: ArmedFaults<A>? = null,
    private val always: String? = null,
) {
    fun of(address: A, kind: StoreFault): String? = when {
        always != null -> always
        seal?.on == true -> "sealed: the device has not been unlocked since it restarted"
        faults?.spend(address, kind) == true -> "refused by an operator's fault"
        else -> null
    }
}
