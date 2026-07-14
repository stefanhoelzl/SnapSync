package app.snapsync.keychain

import co.touchlab.kermit.Logger

/**
 * Whether the platform's protected data — the Keychain, and files in the app/App-Group containers — is
 * readable **right now**, plus a signal for when it becomes readable.
 *
 * iOS answers this question directly (`UIApplication.isProtectedDataAvailable` and
 * `UIApplicationProtectedDataDidBecomeAvailable`), so background work can *ask* instead of trying,
 * failing, and guessing what the failure meant. The adapter lives in `:app:ios`, not here: it needs
 * `UIApplication`, which is **unavailable to app extensions**, and this module is linked into the
 * extension framework too.
 */
interface ProtectedDataAvailability {

    /** `false` only before the first unlock since boot — when nothing protected can be read. */
    fun isAvailable(): Boolean

    /** Register [listener], invoked each time protected data becomes available (i.e. on unlock). */
    fun onBecameAvailable(listener: () -> Unit)
}

/**
 * Runs background work only when protected data is readable, and **defers** it otherwise — resuming it
 * the moment the device is unlocked rather than dropping it and hoping the OS wakes us again.
 *
 * This is the difference between *skip-and-hope* and *defer-and-resume*. The alternative — attempt the
 * work, let the Keychain read fail, and interpret the failure — is what produced both halves of the
 * build-297 bug: a failed device-id read looked like "no id" (so it minted one, and aborted the process
 * persisting it), and a failed config read looked like "no event joined" (so the extension cleared its
 * join marker, a false leave, on every locked wake).
 *
 * Deferred work is queued at most once per tag and runs **exactly once** when protected data next
 * becomes available. Not thread-safe by design: it is driven from the app's single main dispatcher.
 *
 * Decision record: `changes/archive/…-fix-locked-device-keychain-access`.
 */
class ProtectedDataGate(
    private val availability: ProtectedDataAvailability,
    private val log: Logger = Logger.withTag("ProtectedData"),
) {

    private val deferred = LinkedHashMap<String, () -> Unit>()

    init {
        availability.onBecameAvailable(::runDeferred)
    }

    /**
     * Run [work] now if protected data is readable; otherwise defer it under [tag] until it is.
     * A second deferral of the same [tag] replaces the first — the work is idempotent and re-entrant
     * (a backstop import, a push reconcile), so the freshest closure is the right one to keep.
     *
     * Returns whether the work ran immediately, purely so callers can log it.
     */
    fun runWhenAvailable(tag: String, work: () -> Unit): Boolean {
        if (availability.isAvailable()) {
            work()
            return true
        }
        log.w { "protected data unavailable — deferring '$tag' until the device is unlocked" }
        deferred[tag] = work
        return false
    }

    /** Whether protected data is readable right now — for the entry-point diagnostics. */
    fun isAvailable(): Boolean = availability.isAvailable()

    private fun runDeferred() {
        if (deferred.isEmpty()) return
        // Drain first: a work item that defers again (it cannot, but be safe) must not re-enter this loop.
        //
        // `toList()` COPIES each entry into a Pair. `entries.toList()` would not — it yields entry *views*
        // backed by the map, and reading them after `clear()` throws ConcurrentModificationException on
        // Kotlin/Native (the JVM tolerates it, which is exactly why this must run on the simulator too).
        val pending = deferred.toList()
        deferred.clear()
        log.i { "protected data became available — running ${pending.size} deferred item(s)" }
        pending.forEach { (tag, work) ->
            runCatching(work).onFailure { log.w(it) { "deferred work '$tag' failed" } }
        }
    }
}
