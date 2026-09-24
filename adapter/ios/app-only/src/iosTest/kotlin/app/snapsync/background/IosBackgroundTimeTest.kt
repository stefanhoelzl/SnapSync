package app.snapsync.background

import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [IosBackgroundTime]'s own logic over its operating-system boundary — the part no host can put under the contract,
 * because no binding can make the system say "time is up" (see `BackgroundTimeContract`).
 */
class IosBackgroundTimeTest {

    /** A system that grants every task, or refuses them all, and remembers what it was asked. */
    private class System(private val grants: Boolean) : BackgroundTimeApi {
        val handlers = mutableListOf<() -> Unit>()
        val ended = mutableListOf<ULong>()
        private var next = 1uL

        override fun begin(name: String, expirationHandler: () -> Unit): ULong? {
            handlers += expirationHandler
            return if (grants) next++ else null
        }

        override fun end(identifier: ULong) {
            ended += identifier
        }
    }

    private val log = Logger.withTag("IosBackgroundTimeTest")

    @Test
    fun `the system's expiry is reported once and ends nothing`() {
        val system = System(grants = true)
        var expiries = 0
        val hold = IosBackgroundTime(log, system).begin("push") { expiries++ }
        system.handlers.single().invoke()
        system.handlers.single().invoke()
        assertEquals(1, expiries, "the core hears of the expiry once")
        assertTrue(system.ended.isEmpty(), "the expiry only requests the stop; the core ends the hold")
        hold.end()
        assertEquals(listOf(1uL), system.ended)
    }

    @Test
    fun `a hold is ended with the system exactly once`() {
        val system = System(grants = true)
        val time = IosBackgroundTime(log, system)
        val first = time.begin("first") {}
        val second = time.begin("second") {}
        first.end()
        first.end()
        second.end()
        assertEquals(listOf(1uL, 2uL), system.ended, "a repeated end never reaches the system again")
    }

    @Test
    fun `a refusal is an immediate expiry and its hold ends nothing`() {
        val system = System(grants = false)
        var expiries = 0
        val hold = IosBackgroundTime(log, system).begin("late") { expiries++ }
        assertEquals(1, expiries, "reported before begin returned")
        hold.end()
        assertTrue(system.ended.isEmpty(), "there is no task to end")
        system.handlers.single().invoke()
        assertEquals(1, expiries, "and a late handler reports nothing more")
    }

    @Test
    fun `a throwing expiry callback is contained at the boundary`() {
        val system = System(grants = true)
        IosBackgroundTime(log, system).begin("throws") { error("the core's stop threw") }
        system.handlers.single().invoke()
    }
}
