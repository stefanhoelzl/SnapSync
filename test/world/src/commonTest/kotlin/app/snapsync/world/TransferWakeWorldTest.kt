package app.snapsync.world

import app.snapsync.ports.Completion
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * A transfer session's background events, as the `Upload` and `Download` event ports deliver them to the REAL
 * composition (capability `sync-status`, "OS completion handlers are released only after their work completes";
 * decision record `changes/own-work-per-wake`, D5). These were `PlatformEntriesContract` clauses while a session's
 * relaunch crossed the inbound port; it arrives through the transfer ports' handlers now (phase 11f), so their promises
 * are pinned here, over the world that composes the phone's graph. Which session an identifier names is the iOS
 * adapter's routing (`BackgroundSessions`).
 */
class TransferWakeWorldTest {

    private val joined = "22222222-2222-4222-8222-222222222222"

    /** A completion handler with no expiry of its own, recording whether [workDone] held at its first release. */
    private class OsCompletion(private val workDone: () -> Boolean) : Completion {
        var releases = 0
        var doneAtRelease: Boolean? = null

        override fun complete() {
            if (doneAtRelease == null) doneAtRelease = workDone()
            releases++
        }

        override fun onExpired(action: () -> Unit) = Unit
    }

    private suspend fun World.joinedWithAForeignPhoto(): World = apply {
        provision(joined)
        addForeignDevice("DEV-F", joined, listOf(World.foreignAsset("FQ")))
    }

    @Test
    fun upload_session_events_release_at_the_drain_then_run_the_tail() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = OsCompletion { w.appUpload.handbacks == 1 && w.operatorEngine.topUps == 0 }
        w.appUpload.handBack(completion)
        waitFor { completion.releases > 0 }
        settle()
        assertEquals(1, completion.releases, "released exactly once")
        assertEquals(true, completion.doneAtRelease, "at the session's drain report, before the tail")
        waitFor { w.operatorEngine.topUps > 0 }
        waitFor { w.backgroundTimeHolds.value.isEmpty() }
    }

    @Test
    fun download_session_events_are_adopted_and_bring_the_session_up() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        w.download.handBack(OsCompletion { true })
        assertTrue(w.downloadTransport != null, "the download session is brought up for its events")
        assertEquals(0, w.appUpload.handbacks, "and the app uploader's session is not handed them")
    }

    @Test
    fun a_drain_report_that_never_comes_releases_on_the_background_time_expiry() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        val completion = OsCompletion { true }
        w.download.handBack(completion)
        settle()
        assertEquals(0, completion.releases, "no clock of the app's own releases it")
        w.expireBackgroundTime()
        waitFor { completion.releases > 0 }
        settle()
        assertEquals(1, completion.releases, "released exactly once, on the operating system's expiry")
        waitFor { w.backgroundTimeHolds.value.isEmpty() }
        assertEquals(0, w.operatorEngine.topUps, "a wake whose time is up requests no tail")
    }

    @Test
    fun a_drain_report_releases_after_the_stagings_it_announced_are_recorded() = worldTest {
        val w = World(this).joinedWithAForeignPhoto()
        w.downloadController.reconcile(joined)
        val tags = w.download.inFlight().map { it.description }
        assertTrue(tags.isNotEmpty(), "precondition: the foreign photo is downloading")
        val completion = OsCompletion { w.downloadStore.stagings.size == tags.size }
        w.download.handBack(completion)
        tags.forEach { w.download.finish(it) }
        w.download.reportEventsDrained()
        waitFor { completion.releases > 0 }
        assertEquals(true, completion.doneAtRelease, "the stagings the wake delivered were recorded before the release")
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(10.seconds) {
        while (!condition()) delay(10)
    }

    /** A short pause, for asserting that something did NOT happen after a thing that did. */
    private suspend fun settle() = delay(200)
}
