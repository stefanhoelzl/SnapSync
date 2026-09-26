package app.snapsync.world

import app.snapsync.model.GalleryAccess
import app.snapsync.model.WakeId
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
        suspend fun wake(start: (() -> Unit) -> Unit) {
            val released = CompletableDeferred<Unit>()
            start { released.complete(Unit) }
            withTimeout(5_000) { released.await() }
        }
        wake { done -> w.wake.fire(WakeId.Heartbeat, bareCompletion(done)) }
        wake { done -> w.appUpload.handBack(bareCompletion(done)) }
        wake { done -> w.pushNotifications.deliverMessage(mapOf<Any?, Any?>("eventId" to "E"), bareCompletion(done)) }
        assertFalse(w.gallery.observing, "a wake that never builds the screen opened the selection observer")

        w.lifecycle.foreground() // dispatched: the entry assembles the host on the composition's lane
        withTimeout(5_000) { while (!w.gallery.observing) yield() }
        assertTrue(w.gallery.observing, "host assembly is what opens it")
    }
}
