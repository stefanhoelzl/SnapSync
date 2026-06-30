package app.snapsync.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The device's **in-flight upload count** for own-device progress (`sync-status`): the number of the
 * device's photos with any non-`COMPLETED` ledger row — a job answered (or retrying) but not yet
 * observed complete. This is the only place the true "uploading now" set exists; storage truth cannot
 * see it (a single-resource photo flips absent→complete atomically, so "partially present" is ~0).
 *
 * The seam exposes a **count only** — never the ledger nor any write capability — so the status domain
 * keeps no `:domain:engine` dependency and the extension stays the sole ledger writer. [inFlight] is a
 * level-triggered count; [refresh] re-reads it. It refreshes on **foreground entry** (wired in the iOS
 * composition root); the status projection clamps it to remaining and never lets it drive
 * classification (display-only).
 */
interface InFlightSource {
    val inFlight: StateFlow<Int>
    suspend fun refresh()
}

/**
 * The real [InFlightSource]: [refresh] calls the injected [read] (on iOS, a **read-only** read of the
 * shared App-Group ledger's `aggregates().pending`) and publishes the count. The read is a
 * `suspend () -> Int` so the engine/ledger types never reach `:domain:status` — the composition root
 * supplies the read, keeping this logic platform-free and testable. Any failure yields `0` (the
 * ledger may not exist yet, e.g. the extension never ran), never throwing to the status projection.
 */
class ReadingInFlightSource(private val read: suspend () -> Int) : InFlightSource {
    private val _inFlight = MutableStateFlow(0)
    override val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    override suspend fun refresh() {
        _inFlight.value = runCatching { read() }.getOrDefault(0)
    }
}

/**
 * A settable, in-memory [InFlightSource]: holds its count synchronously and re-emits on [set]. Used by
 * the desktop harness (the in-flight knob) and tests; the iOS app backs the seam with
 * [ReadingInFlightSource] over the read-only ledger aggregate. [refresh] is inert here.
 */
class MutableInFlightSource(initial: Int = 0) : InFlightSource {
    private val _inFlight = MutableStateFlow(initial)
    override val inFlight: StateFlow<Int> = _inFlight.asStateFlow()

    override suspend fun refresh() = Unit

    fun set(count: Int) {
        _inFlight.value = count
    }
}
