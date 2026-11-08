package app.snapsync.format

import app.snapsync.domain.sharing.PhotoGrant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The grant as a bug report names it — runtime identity: the operator's reports are read by these spellings. */
class PhotoGrantTextTest {
    @Test
    fun `each grant reads as the report has always named it - a whole library as full`() {
        assertEquals(
            listOf("NOT_DETERMINED", "DENIED", "LIMITED", "FULL"),
            PhotoGrant.entries.map(PhotoGrantText::report),
        )
    }
}
