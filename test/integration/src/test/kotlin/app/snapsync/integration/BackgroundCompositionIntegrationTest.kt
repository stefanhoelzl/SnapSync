package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A background relaunch composes the app without opening a database: what the screen would read is built only when a
 * screen is, and nothing reads a store merely because the graph that could read it exists. Measured in phase 4.3, where
 * a query's flow built at composition opened the shared database on every background relaunch — a launch the OS gives
 * seconds to, under a locked phone's protection class.
 *
 * The world's database face reports every open any process made, by name, in order. The relaunch delivers nothing — no
 * push, no wake, no transfer — so any open it causes is the composition's.
 */
class BackgroundCompositionIntegrationTest {

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Background wakes do their work without showing anything",
    )
    fun a_background_relaunch_opens_no_database_at_composition() = behaviour {
        var before = emptyList<String>()
        var after = emptyList<String>()
        given {
            joinedEvent()
            step("the opens so far, read", act = { before = databases.opened() }, until = { settled { true } })
        }

        whenever {
            host.relaunch(scene = false)
        }
        then { settled { true } }
        whenever { after = databases.opened() }
        then { settled { true } }

        assertTrue(before.isNotEmpty(), "the join opened the databases, so the face reports opens at all: $before")
        assertEquals(before, after, "a background relaunch, before anything is delivered, opens no database")
    }
}
