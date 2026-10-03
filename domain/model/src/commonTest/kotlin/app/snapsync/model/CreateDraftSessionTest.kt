package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** The create draft against the app's foreground life (capability `create-event`, "A long absence starts a fresh draft"). */
class CreateDraftSessionTest {

    private val shown = CreateDraftSession(activation = 4, epoch = 2)

    @Test
    fun `a return under 15 minutes is a new activation in the same draft`() {
        assertEquals(CreateDraftSession(5, 2), shown.afterReturn(5, 14.minutes + 59.seconds))
    }

    @Test
    fun `a return after 15 minutes or more starts a fresh draft`() {
        assertEquals(CreateDraftSession(5, 3), shown.afterReturn(5, 15.minutes))
        assertEquals(CreateDraftSession(5, 3), shown.afterReturn(5, 3.minutes * 60))
    }

    @Test
    fun `a cold launch's first activation keeps the draft`() {
        assertEquals(CreateDraftSession(5, 2), shown.afterReturn(5, awayFor = null))
    }

    @Test
    fun `a return already seen changes nothing`() {
        assertEquals(shown, shown.afterReturn(4, 20.minutes))
    }
}
