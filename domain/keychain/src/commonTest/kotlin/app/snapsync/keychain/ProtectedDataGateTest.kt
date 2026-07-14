package app.snapsync.keychain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeAvailability(var available: Boolean) : ProtectedDataAvailability {
    private val listeners = mutableListOf<() -> Unit>()
    override fun isAvailable(): Boolean = available
    override fun onBecameAvailable(listener: () -> Unit) {
        listeners += listener
    }

    /** Simulate the user unlocking the device. */
    fun unlock() {
        available = true
        listeners.forEach { it() }
    }
}

class ProtectedDataGateTest {

    @Test
    fun `work runs immediately when protected data is available`() {
        val availability = FakeAvailability(available = true)
        val gate = ProtectedDataGate(availability)
        var ran = 0

        val immediate = gate.runWhenAvailable("backstop") { ran++ }

        assertTrue(immediate)
        assertEquals(1, ran)
    }

    @Test
    fun `work is deferred while protected data is unavailable and does not run`() {
        val availability = FakeAvailability(available = false)
        val gate = ProtectedDataGate(availability)
        var ran = 0

        val immediate = gate.runWhenAvailable("backstop") { ran++ }

        assertTrue(!immediate)
        assertEquals(0, ran, "nothing may touch the Keychain while protected data is unavailable")
    }

    @Test
    fun `deferred work resumes on unlock and runs exactly once`() {
        val availability = FakeAvailability(available = false)
        val gate = ProtectedDataGate(availability)
        var ran = 0
        gate.runWhenAvailable("backstop") { ran++ }

        availability.unlock()

        assertEquals(1, ran, "deferred work must resume at unlock rather than await the OS's next wake")

        // A second unlock must not re-run it: the queue was drained.
        availability.unlock()
        assertEquals(1, ran)
    }

    @Test
    fun `several deferred entry points all resume`() {
        val availability = FakeAvailability(available = false)
        val gate = ProtectedDataGate(availability)
        val ran = mutableListOf<String>()
        gate.runWhenAvailable("backstop") { ran += "backstop" }
        gate.runWhenAvailable("silentPush") { ran += "silentPush" }

        availability.unlock()

        assertEquals(listOf("backstop", "silentPush"), ran)
    }

    @Test
    fun `re-deferring the same tag keeps only the freshest work`() {
        val availability = FakeAvailability(available = false)
        val gate = ProtectedDataGate(availability)
        val ran = mutableListOf<String>()
        gate.runWhenAvailable("backstop") { ran += "stale" }
        gate.runWhenAvailable("backstop") { ran += "fresh" }

        availability.unlock()

        assertEquals(listOf("fresh"), ran)
    }

    @Test
    fun `a failing deferred item does not prevent the others`() {
        val availability = FakeAvailability(available = false)
        val gate = ProtectedDataGate(availability)
        val ran = mutableListOf<String>()
        gate.runWhenAvailable("boom") { error("import failed") }
        gate.runWhenAvailable("ok") { ran += "ok" }

        availability.unlock()

        assertEquals(listOf("ok"), ran)
    }
}
