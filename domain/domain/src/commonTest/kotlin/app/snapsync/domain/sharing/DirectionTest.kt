package app.snapsync.domain.sharing

import kotlin.test.Test
import kotlin.test.assertEquals

class DirectionTest {

    @Test
    fun `each direction runs exactly its arms`() {
        assertEquals(
            listOf(true to true, true to false, false to true, false to false),
            listOf(Direction.Both, Direction.UploadOnly, Direction.DownloadOnly, Direction.Neither)
                .map { it.includesUpload to it.includesDownload },
        )
    }

    @Test
    fun `the stored names are the membership column's values`() {
        // The membership row stores a direction by its name; renaming one strands every stored row.
        assertEquals(listOf("Both", "UploadOnly", "DownloadOnly", "Neither"), Direction.entries.map { it.name })
    }
}
