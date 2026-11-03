package app.snapsync.data.upload

import kotlin.test.Test
import kotlin.test.assertEquals

class RecentWalkTest {

    @Test
    fun `what a walk read is handed over once`() {
        val recent = RecentWalk()
        val world = SessionWorld()
        recent.put(mapOf(world.key() to SessionWorld.From))

        assertEquals(setOf(world.key()), recent.take().keys)
        assertEquals(emptyMap(), recent.take())
    }
}
