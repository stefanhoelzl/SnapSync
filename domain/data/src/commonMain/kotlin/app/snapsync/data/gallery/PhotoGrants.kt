package app.snapsync.data.gallery

import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * The photo-library grant the platform reports, in the domain's words ([PhotoGrant]): followed as it changes, the
 * platform re-reading it at every return to the foreground.
 *
 * [of] is the core's ONE translation of the platform's grant: every reading of it — the screen's, the read's
 * ([PhotoGrant.access]), the bug report's — starts from the [PhotoGrant] it answers.
 */
class PhotoGrants(source: StateFlow<LibraryGrant>) {
    val state: StateFlow<PhotoGrant> = PhotoGrantFlow(source)

    companion object {
        /** The platform's grant, read as the domain's: one-to-one, so a flow of it is as distinct as its source. */
        fun of(grant: LibraryGrant): PhotoGrant = when (grant) {
            LibraryGrant.NOT_DETERMINED -> PhotoGrant.UNDECIDED
            LibraryGrant.DENIED -> PhotoGrant.REFUSED
            LibraryGrant.LIMITED -> PhotoGrant.LIMITED
            LibraryGrant.GRANTED -> PhotoGrant.FULL
        }
    }
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class PhotoGrantFlow(private val source: StateFlow<LibraryGrant>) : StateFlow<PhotoGrant> {
    override val value: PhotoGrant get() = PhotoGrants.of(source.value)
    override val replayCache: List<PhotoGrant> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<PhotoGrant>): Nothing {
        source.collect { collector.emit(PhotoGrants.of(it)) }
    }
}
