package app.snapsync.rig

import app.snapsync.levers.LeverRefused
import app.snapsync.levers.PlayedOsLevers
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A force-quit ends the app's process, so only a host that owns the process plays it: one that does not — a device's app
 * host — refuses it with the reason, in its advertisement as at the call, and the rest of the played OS stays honoured.
 */
class PlayedOsForceQuitTest {

    private val device = MockDevice()
    private val os = PlayedOs(device)
    private val world = MockWorld(
        device = device,
        mocked = MockedSystem.entries.toSet(),
        reach = null,
        reachRefusal = "no backend here",
        operatorRefusal = { "no backend here" },
        version = null,
        store = null,
        os = os,
        ownDeviceId = { device.ownDeviceId },
        joinedEventId = { null },
        controls = device.devControls.operator,
    )

    private fun playedOs() = world.servedServices(HostServices(entries = null, extensionHost = null))
        .single { it.service == PlayedOsLevers }

    @Test
    fun a_host_without_the_process_refuses_only_the_force_quit() = runTest {
        assertEquals(mapOf("played-os.forceQuit" to FORCE_QUIT_NEEDS_THE_PROCESS), playedOs().refused)
        assertFailsWith<LeverRefused> { os.forceQuit() }
    }

    @Test
    fun a_host_that_owns_the_process_honours_it() {
        os.process = object : AppProcess {
            override suspend fun die() = Unit

            override fun open() = Unit
        }
        assertEquals(emptyMap(), playedOs().refused)
    }
}
