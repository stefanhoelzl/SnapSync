package app.snapsync.domain.version

import kotlin.test.Test
import kotlin.test.assertEquals

/** A build's store page: its name and its page, one value. */
class StoreTest {

    @Test
    fun `a store page is its name and its page as given`() {
        val store = Store("App Store", "https://apps.apple.com/app/id1")
        assertEquals("App Store" to "https://apps.apple.com/app/id1", store.name to store.url)
    }
}
