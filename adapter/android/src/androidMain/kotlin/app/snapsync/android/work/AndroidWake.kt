package app.snapsync.android.work

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import app.snapsync.annotations.Never
import app.snapsync.annotations.NeverAnswers
import app.snapsync.entry.BeforeListen
import app.snapsync.entry.HandlerSlot
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.ports.ScheduleResult
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import app.snapsync.ports.WakeId
import app.snapsync.ports.WakeNetwork
import app.snapsync.ports.WakeTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.toJavaDuration

/**
 * The Android [Wake]: each [WakeId] on the scheduler that keeps it when the app is swiped away.
 *
 * - [WakeId.Heartbeat] ([WakeTrigger.After]) is one unique WorkManager one-time work. It waits at least its delay, and
 *   for a network when it asks for one. Both its cadences are the same unique work, so a busy request replaces a
 *   pending idle one and vice versa; the cadence reaches WorkManager only as the delay (decision record
 *   `changes/timely-background-receiving`, D2).
 * - [WakeId.LibraryChanged] ([WakeTrigger.LibraryChange]) is this app's OWN JobScheduler job, run by [WakeJobService]:
 *   a content-URI trigger on the image and video collections of every volume, delivered no later than its maximum
 *   delay after the change. It is the counterpart of the upload extension being woken by a new photo.
 *
 * A wake for the other kind's trigger is refused: each id has one scheduler.
 *
 * **Why the library-change wake is not WorkManager's.** A swipe from the recent apps ends the process with exit reason
 * `REASON_USER_REQUESTED` and subreason `SUBREASON_REMOVE_TASK`, and leaves its jobs standing. When a new photo then
 * restarts the process, WorkManager 2.12.0's `ForceStopRunnable.isForceStopped` (API 30+) sees reason 10 and never
 * reads the subreason (androidx `main`, `ForceStopRunnable.java` ~251, unchanged). It concludes the app was
 * force-stopped and cancels and reschedules every WorkManager job, including the one that has just woken the app
 * ("cancelled while waiting for bind"). Nothing then uploads until the next change or the heartbeat (measured on the
 * API 36 emulator, 2026-11-07). That reschedule cancels only WorkManager's own jobs: its namespace
 * (`androidx.work.systemjobscheduler`) on API 34+, and default-namespace jobs whose service is its `SystemJobService`
 * (`SystemJobScheduler.cancelAllInAllNamespaces`, `getPendingJobs`). A job with a service of this app's own is never
 * touched. The heartbeat stays on WorkManager: a misread reschedules it, and a rescheduled heartbeat is still a
 * heartbeat.
 *
 * A request for a pending wake REPLACES it. The exception is a request from inside that wake's own run, where
 * replacing would stop the run asking: there it is APPENDED, to be delivered after this run ends.
 * - WorkManager: `APPEND_OR_REPLACE`.
 * - The library-change job alternates between two job ids ([LIBRARY_JOBS]), because JobScheduler stops a running job
 *   whose id is scheduled again. From inside one job's run, the request schedules the other id at once, so the system
 *   holds it, a process death included. If that job fires while the first still runs, its delivery waits for the
 *   first to end. A request while both have started asks nothing: the waiting one is the next delivery.
 *
 * Both entry ports ([WakeWorker], [WakeJobService]) live on the process's one composition. Each finds this adapter
 * through [registration], set when the composition [listen]s in `Application.onCreate`, which Android runs before any
 * worker or job. Each holds its run open until the core releases the wake's [Completion]. The run being stopped (the
 * worker's `onStopped`, the job's `onStopJob`, either way its coroutine cancelled) IS the operating system's expiry:
 * the completion's expiry action runs, and the core stops cooperatively.
 *
 * - **Force-stop.** Android cancels every job, both schedulers' alike, until the app is next opened. Then WorkManager
 *   reschedules the heartbeat, and the opening's re-arm requests the library-change wake again.
 * - **Reboot.** A content-URI job cannot be persisted, so a reboot drops the library-change wake. The heartbeat,
 *   which WorkManager re-registers at boot, re-arms it on its next run. Until then, a new photo waits for that run
 *   (a decision of this phase, 2026-11-08).
 */
@NeverAnswers(
    Never(member = "schedule", answer = "ScheduleResult.Unsupported"),
)
class AndroidWake(context: Context, private val log: Log = Log("wake")) : Wake {

    private val work = WorkManager.getInstance(context.applicationContext)
    private val jobs = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, WakeJobService::class.java)
    private val running = ConcurrentHashMap.newKeySet<WakeId>()

    /** The library-change job ids Android has started and not yet seen end. */
    private val started = ConcurrentHashMap.newKeySet<Int>()

    /** One library-change delivery at a time: a job fired during another's run waits for it. */
    private val deliveries = Mutex()

    private val handlers = HandlerSlot<WakeHandlers>("Wake", BeforeListen.Dropped)

    @CompositionOnly
    override fun inbound(handlers: WakeHandlers) {
        this.handlers.set(handlers)
        registration.value = this
    }

    override suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult =
        run { request(id, trigger) }.also { log.unlabelled.i("wake.schedule(id=<id>) = <it>", "id" of id, "it" of it) }

    private suspend fun request(id: WakeId, trigger: WakeTrigger): ScheduleResult = try {
        when {
            id == WakeId.Heartbeat && trigger is WakeTrigger.After -> enqueue(id, trigger)
            id == WakeId.LibraryChanged && trigger is WakeTrigger.LibraryChange -> watch(trigger)
            else -> ScheduleResult.Refused("the $id wake is not carried on a ${trigger::class.simpleName} trigger")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Thrown by WorkManager's enqueue itself (an `IllegalStateException`), or the failure the operation reported
        // (its cause, which `await` rethrows unwrapped), or the job scheduler's refusal of a job over the app's quota.
        ScheduleResult.Refused("${e::class.simpleName}: ${e.message}")
    }

    private suspend fun enqueue(id: WakeId, trigger: WakeTrigger.After): ScheduleResult {
        val request = OneTimeWorkRequestBuilder<WakeWorker>()
            .setInputData(workDataOf(ID to id.name))
            .apply { constrain(trigger) }
            .build()
        val policy = if (id in running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        // Awaited: WorkManager enqueues asynchronously, so answering before it registered the request missed an
        // enqueue that failed later. `Scheduled` now means the platform holds the request.
        // Suspended for, never blocked on: the caller is often the main thread, and WorkManager runs its operations
        // one at a time. Behind a burst of other work, a blocking wait here froze the app into an ANR (SNAPSYNC-40).
        work.enqueueUniqueWork(nameOf(id), policy, request).await()
        return ScheduleResult.Scheduled
    }

    /** [trigger] as WorkManager's delay and constraints. */
    private fun OneTimeWorkRequest.Builder.constrain(trigger: WakeTrigger.After) {
        setInitialDelay(trigger.earliest.toJavaDuration())
        when (trigger.network) {
            WakeNetwork.NONE -> Unit
            WakeNetwork.ANY -> setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            // WorkManager's own unmetered constraint, which stays unsatisfied on
            // cellular and a metered Wi-Fi (measured on the API 36 emulator, 2026-10-03), and on a
            // temporarily unmetered carrier network too: JobScheduler matches it by `NOT_METERED`
            // alone (`AndroidNetworkMonitor`'s KDoc). It does not see Data Saver, so on an unmetered
            // Wi-Fi under it the heartbeat still runs; its run finds the uploads waiting and the
            // downloads parked, and re-arms. The photos travel at the first heartbeat after Data Saver
            // is off.
            WakeNetwork.UNRESTRICTED ->
                setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        }
    }

    /**
     * The library-change job: REPLACE from outside a run, the other id from inside one. A binder call, made off the
     * caller's thread, which is often the main one.
     */
    private suspend fun watch(trigger: WakeTrigger.LibraryChange): ScheduleResult {
        val refused = withContext(Dispatchers.IO) { arm(trigger) }
        return if (refused == null) ScheduleResult.Scheduled else ScheduleResult.Refused(refused)
    }

    /** Schedules the library-change job's next id; why the job scheduler refused it, or null. */
    private fun arm(trigger: WakeTrigger.LibraryChange): String? {
        forgetLegacyWatch()
        val now = started.toSet()
        val id = when (now.size) {
            0 -> LIBRARY_JOBS.first().also { jobs.cancel(LIBRARY_JOBS.last()) }
            1 -> LIBRARY_JOBS.first { it !in now }
            else -> return null
        }
        val result = jobs.schedule(libraryJob(id, trigger))
        return "the job scheduler refused the library-change job $id".takeIf { result != JobScheduler.RESULT_SUCCESS }
    }

    private fun libraryJob(id: Int, trigger: WakeTrigger.LibraryChange): JobInfo = JobInfo.Builder(id, service)
        .setExtras(PersistableBundle().apply { putString(ID, WakeId.LibraryChanged.name) })
        .addTriggerContentUri(collection(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)))
        .addTriggerContentUri(collection(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)))
        .setTriggerContentMaxDelay(trigger.maxDelay.inWholeMilliseconds)
        .build()

    private fun collection(uri: android.net.Uri) =
        JobInfo.TriggerContentUri(uri, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS)

    /**
     * Withdraws the WorkManager library-change request an older build left (`PENDING_CLEANUPS.md`, "The library-change
     * wake an older build left on WorkManager"), so it never fires beside the job. Until this build first arms the
     * job, the leftover stands as that build's watch.
     */
    private fun forgetLegacyWatch() {
        work.cancelUniqueWork(nameOf(WakeId.LibraryChanged))
    }

    override fun cancel(id: WakeId) = run {
        when (id) {
            WakeId.Heartbeat -> work.cancelUniqueWork(nameOf(id))
            WakeId.LibraryChanged -> {
                forgetLegacyWatch()
                LIBRARY_JOBS.forEach(jobs::cancel)
            }
        }
        Unit
    }.also { log.unlabelled.i("wake.cancel(id=<id>)", "id" of id) }

    /** A worker's or job's run: hand the wake to the core, and hold the run open until its completion is released. */
    internal suspend fun run(id: WakeId) = run body@{
        val registered = handlers.orNull("a $id wake") ?: return@body
        val done = CompletableDeferred<Unit>()
        running += id
        val run = registered.onWake.handle(id, WorkerCompletion(done))
        try {
            done.await()
        } catch (e: CancellationException) {
            log.unlabelled.i("the operating system stopped the <id> wake — its time is up", "id" of id)
            run.expire()
            throw e
        } finally {
            running -= id
        }
    }.also { log.unlabelled.i("wake.onTaskLaunched(id=<id>)", "id" of id) }

    /**
     * The library-change job [job]'s run: started from now on, so a request from inside a run arms the other id, and
     * delivered once no other library-change run is being delivered. [delivered] is set when it is.
     */
    internal suspend fun runJob(job: Int, id: WakeId, delivered: AtomicBoolean) {
        started += job
        try {
            deliveries.withLock {
                delivered.set(true)
                run(id)
            }
        } finally {
            started -= job
        }
    }

    internal companion object {
        const val ID = "wake"

        /**
         * The library-change job's two ids, used in turn. At the top of the id range: before API 34, WorkManager's
         * jobs share this app's default namespace, and [AndroidWorkConfiguration] keeps them below these.
         */
        val LIBRARY_JOBS = listOf(2_147_480_000, 2_147_480_001)

        /** The adapter the process's composition listened on last; an entry port waits for one while the process starts. */
        val registration = MutableStateFlow<AndroidWake?>(null)

        private const val COMPOSITION_WAIT_MILLIS = 10_000L

        fun nameOf(id: WakeId) = "wake.${id.name}"

        fun idOf(name: String?): WakeId? = WakeId.entries.firstOrNull { it.name == name }

        /**
         * An entry port's delivery: wait for the process's composition to listen on the [AndroidWake] (no composition,
         * such as a build that refused at start or a rig launch whose wake is mocked, runs nothing), then [block].
         */
        suspend fun deliver(block: suspend (AndroidWake) -> Unit) {
            val wake = withTimeoutOrNull(COMPOSITION_WAIT_MILLIS) { registration.filterNotNull().first() } ?: return
            // The wake's run is the runtime's, and contained there: a failure is reported where the run was started.
            block(wake)
        }
    }
}

/** A wake's [Completion]: what the worker or job awaits before it ends. */
private class WorkerCompletion(private val done: CompletableDeferred<Unit>) : Completion {
    override fun complete() {
        done.complete(Unit)
    }
}

/** The work WorkManager runs for a [WakeId] (the heartbeat): an entry port that hands the wake to [AndroidWake]. */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = AndroidWake.idOf(inputData.getString(AndroidWake.ID)) ?: return Result.success()
        AndroidWake.deliver { it.run(id) }
        return Result.success()
    }
}

/**
 * The library-change job's service: an entry port that hands the wake to [AndroidWake] and holds the job until the
 * core releases it. `onStopJob` cancels the run, which is the wake's expiry. A job stopped before its delivery began
 * (while it waited for another run) asks to be rescheduled, because the run it waited on armed nothing in its place.
 */
class WakeJobService : JobService() {

    private class Running(val job: Job, val delivered: AtomicBoolean)

    private val runs = ConcurrentHashMap<Int, Running>()

    override fun onStartJob(params: JobParameters): Boolean {
        val id = AndroidWake.idOf(params.extras.getString(AndroidWake.ID)) ?: return false
        val delivered = AtomicBoolean(false)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                AndroidWake.deliver { it.runJob(params.jobId, id, delivered) }
            } finally {
                // Absent once `onStopJob` took it: the system has ended the job, and must not hear of it again.
                if (runs.remove(params.jobId) != null) jobFinished(params, false)
            }
        }
        runs[params.jobId] = Running(job, delivered)
        job.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val run = runs.remove(params.jobId) ?: return false
        run.job.cancel()
        return !run.delivered.get()
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
