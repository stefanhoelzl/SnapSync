package app.snapsync.logging

import app.snapsync.ports.ThreadLabel

/** The JVM's [ThreadLabel]: one slot per thread, a [ThreadLocal]. */
class JvmThreadLabel : ThreadLabel {
    private val slot = ThreadLocal<String?>()

    override fun swap(label: String?): String? = slot.get().also { slot.set(label) }

    override fun current(): String? = slot.get()
}
