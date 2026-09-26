package app.snapsync.compose

import app.snapsync.model.Handoff
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class InertTest {

    @Test
    fun the_inert_system_ui_answers_that_nothing_was_handed_off() = runTest {
        // What every off-device composition (the harnesses, the world) stands on: there is no platform to
        // hand anything to, and saying so explicitly is what keeps the graph constructible there. Inert means it
        // answers — that nothing was handed off — not that it throws or is absent.
        assertTrue(NoSystemUi.share("https://example.invalid/join") is Handoff.Refused)
        assertTrue(NoSystemUi.openUrl("https://example.invalid/app") is Handoff.Refused)
        NoSystemUi.openSettings()
    }
}
