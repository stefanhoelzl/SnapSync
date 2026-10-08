package app.snapsync.feature.upload

import app.snapsync.feature.support.CapturingLogWriter
import app.snapsync.feature.support.configService
import app.snapsync.mock.inMemoryWake
import app.snapsync.model.CycleResult
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.GalleryAccess
import app.snapsync.model.TransferNetwork
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.services.upload.ExtensionRegistration
import app.snapsync.services.wake.Heartbeat
import app.snapsync.services.wake.WakeHold
import co.touchlab.kermit.Severity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wakes' hand-off to the tail (capability `sync-status`, "Each OS wake does its own work, then hands the rest to
 * one opportunistic tail"; `background-upload`, "The tail runner reimplements the OS scheduler") and the facts the
 * heartbeat's re-arm reads (`receiving-photos`, decision record `changes/timely-background-receiving`). Over a real
 * [TailRunner] whose units record what ran, a real [WakeHold] over a background time that records its holds, and the
 * real membership service.
 */
class TailWakesTest {

    /** Background time that records each hold's begin and end, in order. */
    private class Time : BackgroundTime {
        val log = mutableListOf<String>()
        override fun begin(label: String, onExpiry: () -> Unit): BackgroundTimeHold {
            log += "begin $label"
            return object : BackgroundTimeHold {
                override fun end() {
                    log += "end $label"
                }
            }
        }
    }

    /** The units, recording what ran; [failImport] makes the tail fail. */
    private class Units {
        val ran = mutableListOf<String>()
        var failImport = false
    }

    private fun runner(units: Units) = TailRunner(
        importStaged = {
            units.ran += "import"
            if (units.failImport) error("import failed")
        },
        topUp = {
            units.ran += "topUp"
            CycleResult.COMPLETED
        },
        walkAndPublish = {
            units.ran += "walk"
            WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false)
        },
        walkPermitted = { true },
        mayCreate = { true },
        foregrounded = { false },
        refreshStatus = {},
        heartbeat = Heartbeat(inMemoryWake(), transferNetwork = { TransferNetwork.ANY }),
        importsRemain = { false },
        cadenceFacts = { CadenceFacts(false, false, false, false, false) },
        leftover = { "" },
    )

    private fun hold(time: Time, settled: MutableList<String> = mutableListOf()) =
        WakeHold("wake", time, stopTail = {}, log = CapturingLogWriter().logger(), settling = { settled += it })

    // ---- thenTail ---------------------------------------------------------------------------------------

    @Test
    fun `a full wake runs its tail then the end-of-wake step then ends its hold`() = runTest {
        val time = Time()
        val units = Units()
        val finished = mutableListOf<TailTrigger>()
        val settled = mutableListOf<String>()

        hold(time, settled).thenTail(TailTrigger.SILENT_PUSH, runner(units)) { finished += it }

        assertEquals(listOf("import", "topUp", "walk"), units.ran)
        assertEquals(listOf(TailTrigger.SILENT_PUSH), finished)
        assertEquals(listOf("wake"), settled)
        assertEquals(listOf("begin wake", "end wake"), time.log)
    }

    @Test
    fun `a narrow wake runs its tail but no end-of-wake step`() = runTest {
        for (trigger in listOf(TailTrigger.UPLOAD_COMPLETED, TailTrigger.DOWNLOAD_STAGED)) {
            val time = Time()
            val units = Units()
            val finished = mutableListOf<TailTrigger>()

            hold(time).thenTail(trigger, runner(units)) { finished += it }

            assertTrue(units.ran.isNotEmpty(), "$trigger: its tail ran")
            assertEquals(emptyList(), finished, "$trigger: too narrow a moment for the event's state")
            assertEquals(listOf("begin wake", "end wake"), time.log, "$trigger")
        }
    }

    @Test
    fun `a push for the active event joins the tail`() = runTest {
        val time = Time()
        val units = Units()
        val finished = mutableListOf<TailTrigger>()

        hold(time).thenTailWhen(joins = true, TailTrigger.SILENT_PUSH, runner(units)) { finished += it }

        assertEquals(listOf("import", "topUp", "walk"), units.ran)
        assertEquals(listOf(TailTrigger.SILENT_PUSH), finished)
        assertEquals(listOf("begin wake", "end wake"), time.log)
    }

    @Test
    fun `a push that joins no tail ends its hold at once`() = runTest {
        val time = Time()
        val units = Units()
        val finished = mutableListOf<TailTrigger>()
        val settled = mutableListOf<String>()

        hold(time, settled).thenTailWhen(joins = false, TailTrigger.SILENT_PUSH, runner(units)) { finished += it }

        assertEquals(emptyList(), units.ran)
        assertEquals(emptyList(), finished)
        assertEquals(emptyList(), settled, "no tail, so nothing settles before the end")
        assertEquals(listOf("begin wake", "end wake"), time.log)
    }

    // ---- heartbeatWake ----------------------------------------------------------------------------------

    @Test
    fun `a heartbeat runs its tail then the end-of-wake step then settles`() = runTest {
        val recorder = CapturingLogWriter()
        val units = Units()
        val order = mutableListOf<String>()

        heartbeatWake(
            label = "heartbeat",
            released = { false },
            runner = runner(units),
            finish = { order += "finish $it" },
            log = recorder.logger(),
            settling = { order += "settling" },
        )

        assertEquals(listOf("import", "topUp", "walk"), units.ran)
        assertEquals(listOf("finish HEARTBEAT", "settling"), order)
        assertFalse(Severity.Warn in recorder.severities)
    }

    @Test
    fun `a heartbeat whose time is already up does nothing at all`() = runTest {
        val units = Units()
        val order = mutableListOf<String>()

        heartbeatWake(
            label = "heartbeat",
            released = { true },
            runner = runner(units),
            finish = { order += "finish" },
            log = CapturingLogWriter().logger(),
            settling = { order += "settling" },
        )

        assertEquals(emptyList(), units.ran)
        assertEquals(emptyList(), order)
    }

    @Test
    fun `a heartbeat's failing tail and failing end-of-wake step are each contained and warned`() = runTest {
        val recorder = CapturingLogWriter()
        val units = Units().apply { failImport = true }
        var settled = false

        heartbeatWake(
            label = "heartbeat",
            released = { false },
            runner = runner(units),
            finish = { error("finish failed") },
            log = recorder.logger(),
            settling = { settled = true },
        )

        assertEquals(
            listOf(
                Severity.Warn to "heartbeat: its tail failed",
                Severity.Warn to "heartbeat: the end-of-wake step failed; the next wake runs it again",
            ),
            recorder.lines.filter { it.first == Severity.Warn },
        )
        assertTrue(settled, "settling runs after both failures")
    }

    // ---- cadenceFacts -----------------------------------------------------------------------------------

    private class Registration(private val registered: Boolean?) : ExtensionRegistration {
        var asked = 0
        override suspend fun register() = Unit
        override suspend fun deregister() = Unit
        override fun isRegistered(): Boolean? = registered.also { asked++ }
    }

    private fun event(direction: Direction = Direction.Both, endsAt: String = "2099-12-31T00:00:00Z") = EventConfig(
        "E",
        "E",
        captureCutoff("2026-01-01T00:00:00Z"),
        maxPhotoDate = captureCeiling(endsAt),
        endsAt = eventEnd(endsAt),
        deletesAt = deletesAt("2099-12-31T00:00:00Z"),
        direction = direction,
    )

    @Test
    fun `an unjoined device has no membership to share or end`() {
        val facts = cadenceFacts(configService(null), GalleryAccess.GRANTED, true, Registration(true))

        assertEquals(CadenceFacts(false, false, false, true, true), facts)
    }

    @Test
    fun `a joined sharer of an open event under a full grant`() {
        val facts = cadenceFacts(configService(event()), GalleryAccess.GRANTED, false, Registration(true))

        assertEquals(
            CadenceFacts(joined = true, ended = false, shares = true, fullGrant = true, osUploaderConfirmed = false),
            facts,
        )
    }

    @Test
    fun `an ended receive-only membership under a partial grant`() {
        // The test clock stands at 2026-06-15: an event that ended on 2026-06-01 has ended.
        val config = configService(event(Direction.DownloadOnly, endsAt = "2026-06-01T00:00:00Z"))

        val facts = cadenceFacts(config, GalleryAccess.LIMITED, false, Registration(null))

        assertEquals(
            CadenceFacts(joined = true, ended = true, shares = false, fullGrant = false, osUploaderConfirmed = false),
            facts,
        )
    }

    @Test
    fun `the OS uploader is confirmed only when it may be registered and the OS says it is`() {
        val config = configService(event())
        fun confirmed(registrable: Boolean, registration: Registration) =
            cadenceFacts(config, GalleryAccess.GRANTED, registrable, registration).osUploaderConfirmed

        assertTrue(confirmed(true, Registration(true)))
        assertFalse(confirmed(true, Registration(false)), "the OS says it is not")
        assertFalse(confirmed(true, Registration(null)), "the platform has no notion of it")
        val notAsked = Registration(true)
        assertFalse(confirmed(false, notAsked), "whether it happened says nothing when registering is not allowed")
        assertEquals(0, notAsked.asked, "nor is the OS asked")
    }
}
