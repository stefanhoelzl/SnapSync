package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/** The app's reading of the grant the platform reports: the one translation between the two vocabularies. */
fun LibraryGrant.galleryAccess(): GalleryAccess = when (this) {
    LibraryGrant.NOT_DETERMINED -> GalleryAccess.NOT_DETERMINED
    LibraryGrant.DENIED -> GalleryAccess.DENIED
    LibraryGrant.LIMITED -> GalleryAccess.LIMITED
    LibraryGrant.GRANTED -> GalleryAccess.GRANTED
}

/**
 * [source] as the app reads it, each value [galleryAccess]'s reading. The reading is one-to-one, so this flow is as
 * distinct as its source.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class GalleryAccessFlow(private val source: StateFlow<LibraryGrant>) : StateFlow<GalleryAccess> {
    override val value: GalleryAccess get() = source.value.galleryAccess()
    override val replayCache: List<GalleryAccess> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<GalleryAccess>): Nothing {
        source.collect { collector.emit(it.galleryAccess()) }
    }
}
