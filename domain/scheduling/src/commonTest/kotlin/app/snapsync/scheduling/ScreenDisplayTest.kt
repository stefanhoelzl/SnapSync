package app.snapsync.scheduling

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The screen is shown every state its host reduces, in order, for the life of the process. */
@OptIn(CompositionOnly::class)
class ScreenDisplayTest {

    private class Shown : Ui<String, Unit> {
        val states = mutableListOf<String>()

        override fun inbound(handlers: UiHandlers<String, Unit>) = Unit

        override fun show(state: String) {
            states += state
        }
    }

    @Test
    fun `every state the host reduces is shown`() = runTest {
        val world = World(this)
        val ui = Shown()
        val states = MutableStateFlow("first")
        ScreenDisplay(world.runtime, ui).show(states)
        settle()
        states.value = "second"
        settle()
        assertEquals(listOf("first", "second"), ui.states)
    }
}
