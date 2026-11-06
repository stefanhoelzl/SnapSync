package app.snapsync.data.gallery

import app.snapsync.domain.sharing.PhotoGrant
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow

/**
 * The photo-library grant the platform reports, in the domain's words ([PhotoGrant]): followed as it changes, the
 * platform re-reading it at every return to the foreground.
 */
class PhotoGrants(source: StateFlow<LibraryGrant>) {
    val state: StateFlow<PhotoGrant> = PhotoGrantFlow(source)
}

/** The platform's grant, read as the domain's: one-to-one, so the flow is as distinct as its source. */
fun LibraryGrant.photoGrant(): PhotoGrant = when (this) {
    LibraryGrant.NOT_DETERMINED -> PhotoGrant.UNDECIDED
    LibraryGrant.DENIED -> PhotoGrant.REFUSED
    LibraryGrant.LIMITED -> PhotoGrant.LIMITED
    LibraryGrant.GRANTED -> PhotoGrant.FULL
}

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class PhotoGrantFlow(private val source: StateFlow<LibraryGrant>) : StateFlow<PhotoGrant> {
    override val value: PhotoGrant get() = source.value.photoGrant()
    override val replayCache: List<PhotoGrant> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<PhotoGrant>): Nothing {
        source.collect { collector.emit(it.photoGrant()) }
    }
}
