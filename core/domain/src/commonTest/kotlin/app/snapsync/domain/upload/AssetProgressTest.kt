package app.snapsync.domain.upload

import kotlin.test.Test
import kotlin.test.assertEquals

/** How far one photo has got, from its rows (capability `sync-status`): done, else handed over, else waiting. */
class AssetProgressTest {

    @Test
    fun `every row done is done, whatever was handed over`() {
        assertEquals(AssetProgress.DONE, AssetProgress.of(notDone = 0, handedOver = 0))
    }

    @Test
    fun `a row a job carries is handed over, beside a row still waiting`() {
        assertEquals(AssetProgress.HANDED_OVER, AssetProgress.of(notDone = 2, handedOver = 1))
    }

    @Test
    fun `rows left that no job carries wait`() {
        assertEquals(AssetProgress.WAITING, AssetProgress.of(notDone = 1, handedOver = 0))
    }
}
