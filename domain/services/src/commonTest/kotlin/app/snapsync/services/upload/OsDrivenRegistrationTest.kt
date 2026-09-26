package app.snapsync.services.upload


import app.snapsync.model.RegistrationAnswer
import app.snapsync.model.RegistrationState
import app.snapsync.ports.ExtensionRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The OS-driven registration's **ritual** — the one thing it exists to get right, and which could not be tested
 * at all until this class left `:app:ios`.
 *
 * It defends against damage that is invisible when it happens and terminal afterwards: a bare enable against a
 * stale configuration record fails with `3202`, after which the OS never launches the extension and nothing
 * reports it. Before, the only way to exercise it was to contrive a physical device into that state.
 */
class OsDrivenRegistrationTest {

    /**
     * A registry that records the order of what it was asked, and can be made to refuse.
     *
     * Ordering is the point rather than a convenience: a bare enable is the hazard this class exists to avoid.
     */
    private class RecordingRegistry(
        private val log: MutableList<String>,
        var refuseWith: Pair<Boolean, RegistrationAnswer>? = null,
        var registered: Boolean = false,
    ) : ExtensionRegistry {
        override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
            log += if (enabled) "enable" else "disable"
            refuseWith?.takeIf { it.first == enabled }?.let { return it.second }
            val existed = registered
            registered = enabled
            return if (!enabled && !existed) {
                RegistrationAnswer.Answered(ok = false, domain = "PHPhotosErrorDomain", code = 3201)
            } else {
                RegistrationAnswer.Answered(ok = true, domain = null, code = null)
            }
        }

        override fun isEnabled(): RegistrationState =
            if (registered) RegistrationState.REGISTERED else RegistrationState.NOT_REGISTERED
    }

    private fun mechanism(
        log: MutableList<String>,
        registry: RecordingRegistry = RecordingRegistry(log),
    ) = OsDrivenRegistration(registry) to registry

    // ── The ritual ────────────────────────────────────────────────────────────────────────────────

    /**
     * `register()` is a **disable→enable toggle**, never a bare enable. The system's record survives app
     * delete/reinstall and reboot, so a record left by a prior or differently-signed build makes a bare
     * enable fail with `3202` — and the leading disable is what removes it.
     */
    @Test
    fun `register disables before it enables`() = runTest {
        val log = mutableListOf<String>()
        val (mechanism, _) = mechanism(log)
        mechanism.register()
        assertEquals(listOf("disable", "enable"), log.filter { it == "disable" || it == "enable" })
    }

    /** A stale record is replaced rather than rejected: the disable finds one, the enable re-creates it. */
    @Test
    fun `a stale record is removed and replaced`() = runTest {
        val log = mutableListOf<String>()
        val registry = RecordingRegistry(log, registered = true)
        val (mechanism, _) = mechanism(log, registry = registry)
        mechanism.register()
        assertTrue(registry.registered, "the ritual must leave a live registration behind")
    }

    /**
     * A refused enable is **not** followed by a claim that the extension was registered. This is the defect
     * the change removed: an unconditional `Info` line stood two milliseconds after an `Error` classifying
     * the very same call as failed, in the one capability whose stated failure mode is that "nothing else
     * will report it".
     */
    @Test
    fun `a refused enable leaves the registration absent and claims nothing`() = runTest {
        val log = mutableListOf<String>()
        val registry = RecordingRegistry(
            log,
            refuseWith = true to RegistrationAnswer.Answered(ok = false, domain = "PHPhotosErrorDomain", code = 3202L),
        )
        val (mechanism, _) = mechanism(log, registry = registry)
        mechanism.register()
        assertTrue(!registry.registered, "a refused enable must not leave the app believing it registered")
    }

    // ── Deregistration ────────────────────────────────────────────────────────────────────────────

    /**
     * Deregistration is the disable **and nothing else** — at a leave, or the rig's `extension=off`. Neither verb
     * touches the ledger any more: nothing orphans a row that needs repair, because a registration spans the whole
     * membership and a leave clears the ledger (decision record `changes/both-uploaders-active`).
     */
    @Test
    fun `deregister is the disable alone`() = runTest {
        val log = mutableListOf<String>()
        val (mechanism, registry) = mechanism(log)
        mechanism.register()
        log.clear()
        mechanism.deregister()
        assertTrue(!registry.registered, "deregister must deregister")
        assertEquals(listOf("disable"), log, "deregister must touch nothing but the registration")
    }

    // ── A platform without the mechanism ───────────────────────────────────────────────────────────

    /** Below iOS 26.1, on the JVM, on Android: the port answers `Unsupported`, and nothing reads as registered. */
    @Test
    fun `a platform without the extension registers nothing and reads no record`() = runTest {
        val unsupported = object : ExtensionRegistry {
            var writes = 0
            override suspend fun setEnabled(enabled: Boolean): RegistrationAnswer {
                writes++
                return RegistrationAnswer.Unsupported
            }

            override fun isEnabled() = RegistrationState.UNSUPPORTED
        }
        val mechanism = OsDrivenRegistration(unsupported)
        mechanism.register()
        mechanism.deregister()
        assertEquals(3, unsupported.writes, "the ritual still runs; the port answers it without the OS")
        assertEquals(null, mechanism.isRegistered())
    }
}
