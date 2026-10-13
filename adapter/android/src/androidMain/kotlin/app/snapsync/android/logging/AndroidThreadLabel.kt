package app.snapsync.android.logging

import app.snapsync.ports.ThreadLabel

/** Android's [ThreadLabel]: one slot per thread, a [ThreadLocal]. */
class AndroidThreadLabel : ThreadLabel {
    private val slot = ThreadLocal<String?>()

    override fun swap(label: String?): String? = slot.get().also { slot.set(label) }

    override fun current(): String? = slot.get()
}
