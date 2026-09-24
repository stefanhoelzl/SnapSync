package app.snapsync.ios.discovery

import kotlin.test.Test
import kotlin.test.assertNull

/**
 * The token's widening boundary (see [libraryChangeTokenOf]): an absent platform token is "cannot tell", never a
 * crash. The call with `null` is also the guard — narrowing the parameter to cinterop's non-null claim stops this
 * compiling.
 */
class PhotoKitLibraryChangeTokenTest {

    @Test
    fun `an absent platform token reads as no token`() {
        assertNull(libraryChangeTokenOf(null))
    }
}
