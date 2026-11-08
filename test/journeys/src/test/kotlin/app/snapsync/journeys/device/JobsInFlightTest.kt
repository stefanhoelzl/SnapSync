package app.snapsync.journeys.device

import kotlin.test.Test
import kotlin.test.assertEquals

/** What `dumpsys jobscheduler <app>` says is running or about to run (API 36, measured 2026-11-08). */
class JobsInFlightTest {
    private val app = "app.snapsync"

    @Test
    fun `nothing running and nothing pending reads zero`() {
        val dump = "Pending queue:\n  None\n\nActive jobs:\n  N/A\n\nIdle contexts (16):\n"
        assertEquals(0, JobsInFlight.count(dump, app))
    }

    @Test
    fun `a running job and a pending one are each counted`() {
        val dump = "Pending queue:\n" +
            "  #u0a219/7 from app.snapsync/androidx.work.impl.background.systemjob.SystemJobService\n" +
            "\nActive jobs:\n" +
            "  Slot #0(ID=3753221): 7dabe #u0a219/2147480001 app.snapsync/.android.work.WakeJobService\n" +
            "    Running for: +28ms, timeout at: +4m59s973ms\n" +
            "\nIdle contexts (16):\n"
        assertEquals(2, JobsInFlight.count(dump, app))
    }

    @Test
    fun `another app's job is not this app's`() {
        val dump = "Active jobs:\n  Slot #0(ID=1): ab #u0a10/1 com.other/.Job\n\nIdle contexts (1):\n"
        assertEquals(0, JobsInFlight.count(dump, app))
    }
}
