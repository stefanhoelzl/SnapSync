package app.snapsync.feature.download

import app.snapsync.ports.AssetRef
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The record of imports whose outcome the library has not reported (capability `photo-download`). Its one
 * reader — adjudication — is covered where the controller is driven; this pins the record itself.
 */
class UnreportedImportsTest {

    private fun ref(id: String) = AssetRef("device-a", id)

    @Test
    fun `a recorded ref is held and a forgotten one is not`() {
        val unreported = UnreportedImports()
        assertFalse(unreported.holds(ref("a")), "nothing is distrusted before a wait is abandoned")

        unreported.record(ref("a"))
        assertTrue(unreported.holds(ref("a")))

        unreported.forget(ref("a"))
        assertFalse(unreported.holds(ref("a")), "the library reported — absence is trustworthy again")
    }

    @Test
    fun `recording one ref does not distrust another`() {
        val unreported = UnreportedImports()
        unreported.record(ref("a"))

        assertFalse(
            unreported.holds(ref("b")),
            "a stalled import for one asset says nothing about a different asset's presence",
        )
    }

    @Test
    fun `forgetting one ref does not release another`() {
        val unreported = UnreportedImports()
        unreported.record(ref("a"))
        unreported.record(ref("b"))

        unreported.forget(ref("a"))

        assertFalse(unreported.holds(ref("a")))
        assertTrue(unreported.holds(ref("b")), "b's outcome is still unreported")
    }

    /**
     * The ordinary path: every import that reports normally calls this for a ref that was never recorded,
     * so the common case is a call that finds nothing. It must be a no-op rather than a fault.
     */
    @Test
    fun `forgetting a ref that was never recorded is harmless`() {
        val unreported = UnreportedImports()
        unreported.record(ref("a"))

        unreported.forget(ref("never-recorded"))

        assertTrue(unreported.holds(ref("a")), "the recorded ref is untouched")
        assertFalse(unreported.holds(ref("never-recorded")))
    }

    /** Recording twice is idempotent — one abandoned wait per ref is the only meaningful state. */
    @Test
    fun `recording the same ref twice needs only one forget`() {
        val unreported = UnreportedImports()
        unreported.record(ref("a"))
        unreported.record(ref("a"))

        unreported.forget(ref("a"))

        assertFalse(unreported.holds(ref("a")))
    }
}
