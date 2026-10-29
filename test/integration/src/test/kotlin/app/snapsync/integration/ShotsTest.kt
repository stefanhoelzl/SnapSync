package app.snapsync.integration

import app.snapsync.client.RigClient
import app.snapsync.levers.HostLevers
import app.snapsync.rig.JvmRigHost
import app.snapsync.rig.WorldEvents
import app.snapsync.rig.time.LoopTime
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every marketing screenshot's state is reached on the JVM host, on every build — so the scenarios `screenshots.yml`
 * captures from on a simulator cannot rot unnoticed between its dispatches. The host serves its events as a device host
 * does, so the scenario waits here exactly as it waits there.
 */
class ShotsTest {

    @Test
    fun the_create_shot_is_reached() = onJvmHost { reach(Shot.CREATE) { levers(HostLevers).relaunch() } }

    @Test
    fun the_joining_shot_is_reached() = onJvmHost { reach(Shot.JOINING) { levers(HostLevers).relaunch() } }

    @Test
    fun the_in_sync_shot_is_reached() = onJvmHost { reach(Shot.IN_SYNC) { levers(HostLevers).relaunch() } }

    /**
     * A fresh JVM host over mocks, serving its events from where its latest act began, on the wall clock's time — as the
     * device the scenario is captured on runs: the app's uploader paces its batches by its own timers, which a scenario
     * that moves no clock leaves to run.
     */
    private fun onJvmHost(body: suspend RigClient.() -> Unit) = runBlocking {
        val host = JvmRigHost.start(
            "mock",
            time = LoopTime.REAL,
            serving = WorldEvents.Serving.FROM_THE_LAST_ACT,
        )
        try {
            RigClient("http://127.0.0.1:${host.port}").use { it.body() }
            host.awaitQuiet()
            assertEquals(
                emptyList(),
                host.violations,
                "work reached the app from a thread the host's loop does not own",
            )
        } finally {
            host.close()
        }
    }
}
