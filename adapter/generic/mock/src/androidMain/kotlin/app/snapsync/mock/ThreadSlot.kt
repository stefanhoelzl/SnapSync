package app.snapsync.mock

internal actual fun threadSlot(): ThreadSlot = object : ThreadSlot {
    private val local = ThreadLocal<String?>()

    override fun get(): String? = local.get()

    override fun set(value: String?) = local.set(value)
}
