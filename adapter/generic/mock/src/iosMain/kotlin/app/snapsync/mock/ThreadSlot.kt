@file:OptIn(ExperimentalAtomicApi::class, BetaInteropApi::class)

package app.snapsync.mock

import kotlinx.cinterop.BetaInteropApi
import platform.Foundation.NSString
import platform.Foundation.NSThread
import platform.Foundation.create
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

private val slots = AtomicInt(0)

/** Each slot its own key in the current thread's dictionary, which only that thread reads. */
internal actual fun threadSlot(): ThreadSlot = object : ThreadSlot {
    private val key = NSString.create(string = "app.snapsync.mock.threadSlot.${slots.incrementAndFetch()}")

    override fun get(): String? = NSThread.currentThread.threadDictionary.objectForKey(key) as String?

    override fun set(value: String?) {
        val values = NSThread.currentThread.threadDictionary
        if (value == null) values.removeObjectForKey(key) else values.setObject(value, forKey = key)
    }
}
