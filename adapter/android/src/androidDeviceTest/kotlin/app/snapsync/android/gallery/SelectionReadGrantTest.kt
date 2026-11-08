@file:OptIn(CompositionOnly::class)

package app.snapsync.android.gallery

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.permission.PhotoGrants
import app.snapsync.contracts.inboundOf
import app.snapsync.control.Verifies
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A partial grant's selection read that the member widens to full access while it runs (decision record
 * `limited-selection-decides`, D3): MediaStore answers an unscoped query with the whole library once the grant is full,
 * so the gallery re-checks the grant after the query and publishes nothing it read.
 *
 * The process's own grant cannot move mid-read — revoking a permission ends the process holding it — so the grant is
 * played ([PlayedGrant]): partial when the read starts, full when the gallery checks it again.
 */
class SelectionReadGrantTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The observer's reads run here, one at a time, so a task queued after a read runs once that read has ended. */
    private val lane = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + lane)

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        lane.close()
    }

    @Test
    @Verifies(
        spec = "photo-access",
        requirement = "Switching between full and limited access re-uploads only what was de-selected",
        scenario = "Widening right after changing the selection",
    )
    fun `a selection read the grant widened under is published as no selection`(): Unit = runBlocking {
        // This adapter's part: a read of the changed selection that full access overtook is discarded, so nothing the
        // old selection decided reaches the core; under full access the next walk shares the whole default gallery
        // again, and what is already shared is not sent again (`PhotoAccessIntegrationTest`, "Widening to full
        // access"). An iPhone has no such window: PhotoKit cannot answer outside the selection under a partial grant.
        val grant = PlayedGrant(widenedAfter = 1)
        val gallery = observing(grant)
        try {
            withTimeout(READ_MILLIS) { grant.rechecked.await() }
            withContext(lane) {} // the read that re-checked has ended
            assertNull(gallery.selection.value, "a read the grant widened under is never the member's selection")
        } finally {
            gallery.observeChanges(false)
        }
    }

    @Test
    fun `a selection read the grant stayed partial for is published`(): Unit = runBlocking {
        // The control: the same read, the grant partial throughout, is the selection.
        val gallery = observing(PlayedGrant(widenedAfter = null))
        try {
            assertNotNull(withTimeout(READ_MILLIS) { gallery.selection.filterNotNull().first() })
        } finally {
            gallery.observeChanges(false)
        }
    }

    /** The gallery over [grant], its selection observer open — which reads the baseline at once. */
    private fun observing(grant: PlayedGrant) = AndroidGallery(context, grant, scope).apply {
        inbound(GalleryHandlers(onImportPlaceholder = inboundOf { }, onImportSettled = inboundOf { }))
        observeChanges(true)
    }

    /**
     * A partial grant that reads full from the read after [widenedAfter] reads on, or never when it is `null`: the
     * member allowing every photo in Settings while the gallery queried. [rechecked] completes at the read that sees
     * the grant widened.
     */
    private class PlayedGrant(private val widenedAfter: Int?) : PhotoGrants {
        private val reads = AtomicInteger()
        val rechecked = CompletableDeferred<Unit>()

        override val grant: StateFlow<LibraryGrant> = MutableStateFlow(LibraryGrant.LIMITED)

        override fun current(): LibraryGrant {
            val read = reads.incrementAndGet()
            return if (widenedAfter != null && read > widenedAfter) {
                rechecked.complete(Unit)
                LibraryGrant.GRANTED
            } else {
                LibraryGrant.LIMITED
            }
        }

        override suspend fun requestAccess(): LibraryGrant = current()

        override suspend fun widenSelection(): LibraryGrant = current()
    }

    private companion object {
        const val READ_MILLIS = 15_000L
    }
}
