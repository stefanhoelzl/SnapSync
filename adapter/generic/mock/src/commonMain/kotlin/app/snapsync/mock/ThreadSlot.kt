package app.snapsync.mock

/** One value per thread, as the platform keeps it: what one thread sets, no other thread reads. */
internal interface ThreadSlot {
    fun get(): String?

    fun set(value: String?)
}

/** A new, empty [ThreadSlot] — the platform's own per-thread storage. */
internal expect fun threadSlot(): ThreadSlot
