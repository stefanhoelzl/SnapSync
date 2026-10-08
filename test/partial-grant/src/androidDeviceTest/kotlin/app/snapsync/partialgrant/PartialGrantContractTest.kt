package app.snapsync.partialgrant

import kotlin.test.Test

/** The partial grant's contracts, run on the emulator ([PartialGrantRun]). */
class PartialGrantContractTest {

    @Test
    fun `the photo ports satisfy their contracts under a partial grant`() = PartialGrantRun().run()
}
