package app.snapsync.services.gallery

import app.snapsync.model.GalleryAccess
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GrantReadingTest {

    @Test
    fun `every platform grant reads as the access of the same name`() {
        for (grant in LibraryGrant.entries) assertEquals(grant.name, grant.galleryAccess().name)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class) // runCurrent on the test scheduler
    fun `the access state follows the platform grant as it changes`() = runTest {
        val grant = MutableStateFlow(LibraryGrant.NOT_DETERMINED)
        val access = GalleryAccessState(grant).grant
        val seen = mutableListOf<GalleryAccess>()
        val following = launch { access.collect { seen += it } }
        runCurrent()
        grant.value = LibraryGrant.LIMITED
        runCurrent()
        following.cancel()

        assertEquals(listOf(GalleryAccess.NOT_DETERMINED, GalleryAccess.LIMITED), seen)
        assertEquals(GalleryAccess.LIMITED, access.value)
        assertEquals(listOf(GalleryAccess.LIMITED), access.replayCache)
    }
}
