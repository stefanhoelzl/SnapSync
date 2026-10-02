package app.snapsync.mock

import app.snapsync.model.AssetId
import app.snapsync.model.SelectionPolicy
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/** A hold lever pulled twice, then released once, answers every caller it held — as BackendMock's hold does. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLibraryHoldTest {

    private val everything = SelectionPolicy(emptyList())

    @Test
    fun a_single_hold_releases_its_walk() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        library.operator.holdEnumeration()

        val walk = launch { gallery.assets(everything) }
        runCurrent()
        assertTrue(walk.isActive, "the walk waits while held")

        library.operator.releaseEnumeration()
        withTimeout(1.seconds) { walk.join() }
    }

    @Test
    fun a_second_enumeration_hold_does_not_strand_a_walk_the_first_one_held() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        library.operator.holdEnumeration()

        val walk = launch { gallery.assets(everything) }
        runCurrent()
        assertTrue(walk.isActive, "the walk waits while held")

        library.operator.holdEnumeration()
        library.operator.releaseEnumeration()
        withTimeout(1.seconds) { walk.join() }
    }

    @Test
    fun a_second_adds_hold_does_not_strand_an_add_the_first_one_held() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        val album = gallery.createAlbum("Event")!!
        library.operator.holdAdds()

        val add = launch { gallery.addToAlbum(album, setOf(AssetId("A"))) }
        runCurrent()
        assertTrue(add.isActive, "the add waits while held")

        library.operator.holdAdds()
        library.operator.releaseAdds()
        withTimeout(1.seconds) { add.join() }
    }
}
