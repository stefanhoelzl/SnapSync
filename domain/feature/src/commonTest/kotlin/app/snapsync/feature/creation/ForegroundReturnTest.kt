package app.snapsync.feature.creation

import app.snapsync.feature.creation.readmodel.ForegroundReturn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes

/** The app's latest return to the foreground, as the create screen's draft reads it (capability `create-event`). */
class ForegroundReturnTest {

    @Test
    fun `before the first activation there is no return and no absence`() {
        assertEquals(0, ForegroundReturn.NONE.count)
        assertNull(ForegroundReturn.NONE.awayFor)
    }

    @Test
    fun `a return carries its count and how long the app was away`() {
        val back = ForegroundReturn(count = 3, awayFor = 16.minutes)
        assertEquals(3, back.count)
        assertEquals(16.minutes, back.awayFor)
    }
}
