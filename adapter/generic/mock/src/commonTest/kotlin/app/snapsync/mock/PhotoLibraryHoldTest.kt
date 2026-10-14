package app.snapsync.mock

import app.snapsync.ports.AssetFilter
import app.snapsync.ports.PlatformAssetId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** A hold lever pulled twice, then released once, answers every caller it held — as BackendMock's hold does. */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoLibraryHoldTest {

    private val everything = AssetFilter(createdFrom = null, createdUntil = null)

    @Test
    fun a_single_hold_releases_its_walk() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        library.operator.holdEnumeration(on = true)

        val walk = launch { gallery.assets(everything) }
        runCurrent()
        assertTrue(walk.isActive, "the walk waits while held")

        library.operator.holdEnumeration(on = false)
        withTimeout(1.seconds) { walk.join() }
    }

    @Test
    fun a_second_enumeration_hold_does_not_strand_a_walk_the_first_one_held() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        library.operator.holdEnumeration(on = true)

        val walk = launch { gallery.assets(everything) }
        runCurrent()
        assertTrue(walk.isActive, "the walk waits while held")

        library.operator.holdEnumeration(on = true)
        library.operator.holdEnumeration(on = false)
        withTimeout(1.seconds) { walk.join() }
    }

    @Test
    fun a_second_adds_hold_does_not_strand_an_add_the_first_one_held() = runTest {
        val library = PhotoLibraryMock()
        val gallery = library.port()
        val album = gallery.createAlbum("Event")!!
        library.operator.holdAdds(on = true)

        val add = launch { gallery.addToAlbum(album, setOf(PlatformAssetId("A"))) }
        runCurrent()
        assertTrue(add.isActive, "the add waits while held")

        library.operator.holdAdds(on = true)
        library.operator.holdAdds(on = false)
        withTimeout(1.seconds) { add.join() }
    }
}
