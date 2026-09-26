package app.snapsync.world

import app.snapsync.compose.EntryHooks
import app.snapsync.compose.platformEntries
import app.snapsync.model.GalleryAccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **The partial grant's selection observer opens only from host assembly** (`docs/architecture.md`, "Events arrive
 * through `listen`"). It used to open whenever the core was composed — on every wake — while its one consumer
 * installed only at host assembly, with no replay between them: a background wake paid a read nobody consumed, and
 * a process launched in the background and foregrounded later could lose the baseline. Now composing registers the
 * gallery's handlers and nothing else, and only assembling the host opens the observer.
 */
class SelectionObserverTimingTest {

    @Test
    fun background_transfer_and_push_wakes_open_no_observer_and_host_assembly_does() = worldTest {
        val w = World(this)
        w.permission.set(GalleryAccess.LIMITED)
        val entries = platformEntries(
            core = { w.core },
            hooks = EntryHooks(
                markActive = {},
                openUrl = {},
                assembleHost = { w.statusHost },
                deliverPushToken = {},
                uploadHeartbeatTaskId = "world.upload.heartbeat",
                uploadTransferChannel = "world.upload.session",
            ),
        )

        suspend fun wake(start: (() -> Unit) -> Unit) {
            val released = CompletableDeferred<Unit>()
            start { released.complete(Unit) }
            withTimeout(5_000) { released.await() }
        }
        wake { done -> entries.onBackgroundTask("world.upload.heartbeat", done) }
        wake { done -> entries.onBackgroundTransfers("world.upload.session", done) }
        wake { done -> entries.onSilentPush(mapOf<Any?, Any?>("eventId" to "E"), done) }
        assertFalse(w.gallery.observing, "a wake that never builds the screen opened the selection observer")

        entries.onForeground() // dispatched: the entry assembles the host on the composition's lane
        withTimeout(5_000) { while (!w.gallery.observing) yield() }
        assertTrue(w.gallery.observing, "host assembly is what opens it")
    }
}
