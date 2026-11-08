@file:OptIn(CompositionOnly::class)

package app.snapsync.android.work

import android.annotation.SuppressLint
import android.app.job.JobScheduler
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.workDataOf
import app.snapsync.android.gallery.MediaStoreSeeder
import app.snapsync.android.storage.context
import app.snapsync.contracts.grantedOf
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ScheduleResult
import app.snapsync.ports.WakeHandlers
import app.snapsync.ports.WakeId
import app.snapsync.ports.WakeNetwork
import app.snapsync.ports.WakeTrigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * The library-change wake as this app's own JobScheduler job (`AndroidWake`): the Android facts no shared contract
 * states. WorkManager's misread of a swipe as a force-stop leaves it standing, it is re-armed from inside its own run
 * without stopping that run, and it withdraws the WorkManager request an older build left.
 */
class AndroidLibraryWakeTest {

    private val jobs get() = context.getSystemService(JobScheduler::class.java)
    private val seeded = mutableSetOf<PlatformAssetId>()

    @AfterTest
    fun cleanUp() {
        AndroidWake(context).cancel(WakeId.LibraryChanged)
        MediaStoreSeeder.delete(seeded)
    }

    /** The library-change jobs the system holds for this app, by id. */
    private fun pendingJobs(): Set<Int> =
        jobs.allPendingJobs.map { it.id }.filter { it in AndroidWake.LIBRARY_JOBS }.toSet()

    private fun newPhoto() {
        seeded += MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, "2001-01-04T12:00:00Z", count = 1)
    }

    private fun watch(adapter: AndroidWake) = runBlocking { adapter.schedule(WakeId.LibraryChanged, NOW) }

    /**
     * Capability `delivery`, "Swiping the app away on Android keeps uploading". After a swipe, the new photo's trigger
     * restarts the process, and WorkManager's start misreads the swipe's exit as a force-stop and cancels and
     * reschedules its jobs before the woken job is bound. This plays that cancellation (`rescheduleEligibleWork`, the
     * very call `ForceStopRunnable` makes then) between the photo and the job's run: the job still runs.
     */
    @SuppressLint("RestrictedApi") // WorkManager's own force-stop handling, called as its start calls it.
    @Test
    fun `WorkManager’s force-stop handling leaves a woken library-change job to run`() {
        val woken = CompletableDeferred<WakeId>()
        val adapter = AndroidWake(context)
        adapter.inbound(
            WakeHandlers(
                grantedOf { id, completion ->
                    woken.complete(id)
                    completion.complete()
                },
            ),
        )
        assertEquals(ScheduleResult.Scheduled, watch(adapter))
        newPhoto()
        WorkManagerImpl.getInstance(context).rescheduleEligibleWork()
        assertEquals(setOf(AndroidWake.LIBRARY_JOBS.first()), pendingJobs(), "the misread cancelled the woken job")
        val id = runBlocking { withTimeoutOrNull(WAIT_MILLIS) { woken.await() } }
        assertEquals(WakeId.LibraryChanged, assertNotNull(id, "the new photo woke the app within a minute"))
    }

    /**
     * The core re-arms the library-change wake from inside its run. JobScheduler stops a running job whose id is
     * scheduled again, so the re-arm takes the other id: the run finishes, the re-arm waits for the next photo, and
     * that photo wakes the app again.
     */
    @Test
    fun `a library-change wake re-armed from inside its own run is not stopped by it and wakes the app again`() {
        val runs = Channel<Unit>(Channel.UNLIMITED)
        val expired = AtomicBoolean(false)
        val adapter = AndroidWake(context)
        adapter.inbound(
            WakeHandlers(
                grantedOf(expired = { expired.set(true) }) { _, completion ->
                    runBlocking { adapter.schedule(WakeId.LibraryChanged, NOW) }
                    Thread.sleep(REPLACE_WINDOW_MILLIS) // long enough for a reschedule of its own id to have stopped it
                    completion.complete()
                    runs.trySend(Unit)
                },
            ),
        )
        watch(adapter)
        newPhoto()
        assertNotNull(runBlocking { withTimeoutOrNull(WAIT_MILLIS) { runs.receive() } }, "a new photo woke the app")
        runBlocking { delay(REPLACE_WINDOW_MILLIS) } // the stop a reschedule of its own id would have caused lands
        assertEquals(false, expired.get(), "the running wake finished rather than being stopped by its own re-arm")
        assertEquals(setOf(AndroidWake.LIBRARY_JOBS.last()), pendingJobs(), "its re-arm waits under the other id")
        newPhoto()
        assertNotNull(runBlocking { withTimeoutOrNull(WAIT_MILLIS) { runs.receive() } }, "the re-arm woke it again")
        runBlocking { delay(REPLACE_WINDOW_MILLIS) }
        assertEquals(false, expired.get(), "the second run was not stopped by its re-arm either")
        assertEquals(setOf(AndroidWake.LIBRARY_JOBS.first()), pendingJobs(), "and its re-arm takes the first id back")
    }

    /** A request from outside a run replaces whichever id is pending, and a cancel withdraws both. */
    @Test
    fun `a library-change request replaces the pending one and a cancel withdraws it`() {
        val adapter = AndroidWake(context)
        watch(adapter)
        watch(adapter)
        assertEquals(setOf(AndroidWake.LIBRARY_JOBS.first()), pendingJobs(), "a repeated request never stacks")
        adapter.cancel(WakeId.LibraryChanged)
        assertEquals(emptySet(), pendingJobs(), "a cancel leaves no library-change job")
    }

    /** Each wake has one scheduler: a wake asked for on the other kind's trigger is refused, never misfiled. */
    @Test
    fun `a wake asked for on the other kind’s trigger is refused`() {
        val adapter = AndroidWake(context)
        runBlocking {
            assertIs<ScheduleResult.Refused>(
                adapter.schedule(WakeId.LibraryChanged, WakeTrigger.After(Duration.ZERO, network = WakeNetwork.NONE)),
            )
            assertIs<ScheduleResult.Refused>(adapter.schedule(WakeId.Heartbeat, NOW))
        }
        assertEquals(emptySet(), pendingJobs())
    }

    /**
     * `PENDING_CLEANUPS.md`, "The library-change wake an older build left on WorkManager": an older build's watch is a
     * unique WorkManager request. Arming the job withdraws it, so the two never both fire for one photo.
     */
    @Test
    fun `arming the library-change wake withdraws the WorkManager request an older build left`() {
        val work = WorkManager.getInstance(context)
        val name = AndroidWake.nameOf(WakeId.LibraryChanged)
        val older = OneTimeWorkRequestBuilder<WakeWorker>()
            .setInputData(workDataOf(AndroidWake.ID to WakeId.LibraryChanged.name))
            .setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                    .addContentUriTrigger(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                    .setTriggerContentMaxDelay(LEGACY_DELAY.toJavaDuration())
                    .build(),
            )
            .build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, older).result.get()
        suspend fun leftover() = withContext(Dispatchers.IO) {
            work.getWorkInfosForUniqueWork(name).get().count { !it.state.isFinished }
        }
        try {
            runBlocking { assertEquals(1, leftover(), "the older build's request stands before this build arms") }
            watch(AndroidWake(context))
            val gone = runBlocking { withTimeoutOrNull(WAIT_MILLIS) { while (leftover() > 0) delay(POLL_MILLIS) } }
            assertTrue(gone != null, "arming the job withdrew the older build's request")
        } finally {
            work.cancelUniqueWork(name).result.get()
        }
    }

    private companion object {
        /** A library change due within a second. */
        val NOW = WakeTrigger.LibraryChange(maxDelay = 1.seconds)

        /** The delay an older build asked for its library-change wake. */
        val LEGACY_DELAY = 60.seconds

        /** How long a wake the operating system owes is waited for: a library change is due within its 1 s delay. */
        const val WAIT_MILLIS = 60_000L

        /** Long enough for a reschedule of a running job's id to have stopped it, and for its re-arm to be recorded. */
        const val REPLACE_WINDOW_MILLIS = 1_000L

        const val POLL_MILLIS = 100L
    }
}
