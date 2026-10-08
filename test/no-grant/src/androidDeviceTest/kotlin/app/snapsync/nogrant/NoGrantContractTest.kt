package app.snapsync.nogrant

import kotlin.test.Test

/** The no-grant contracts, run on the emulator ([NoGrantRun]). */
class NoGrantContractTest {

    @Test
    fun `the gallery satisfies its contract with no grant`() = NoGrantRun().run()
}
