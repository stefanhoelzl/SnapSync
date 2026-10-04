package app.snapsync.android.work

import android.content.Context
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
import app.snapsync.model.BeforeListen
import app.snapsync.model.EntryScope
import app.snapsync.model.HandlerSlot
import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeNetwork
import app.snapsync.model.WakeTrigger
import app.snapsync.model.invocation
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Completion
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import co.touchlab.kermit.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Android [Wake] over WorkManager (capability `background-upload`): one unique one-time work per [WakeId].
 *
 * - [WakeTrigger.After] — the heartbeat — waits at least its delay, and for a network when it asks for one. Both its
 *   cadences are the same unique work, so a busy request replaces a pending idle one and vice versa; the cadence
 *   reaches WorkManager only as the delay (decision record `changes/timely-background-receiving`, D2).
 * - [WakeTrigger.LibraryChange] is a content-URI trigger on the image and video collections of every volume, delivered
 *   no later than its maximum delay after the change: WorkManager's counterpart of the upload extension being woken
 *   by a new photo. One-shot, like every wake here, so the core re-requests it after each tail.
 *
 * A request for a pending wake REPLACES it — except from inside that wake's own running worker, where replacing would
 * cancel the worker asking; there it is APPENDED, to wait for its trigger once this run ends.
 *
 * The worker ([WakeWorker]) is an entry port on the process's one composition: it finds this adapter through
 * [registered] — set when the composition [listen]s, in `Application.onCreate`, which Android runs before any worker —
 * and holds WorkManager's run open until the core releases the wake's [Completion]. The worker being stopped
 * (`onStopped`, its coroutine cancelled) IS the operating system's expiry: the completion's expiry action runs, and the
 * core stops cooperatively. A force-stop cancels every request until the app is next opened, when WorkManager
 * reschedules them.
 */
class AndroidWake(context: Context, private val log: Logger = Logger.withTag("wake")) : Wake {

    private val work = WorkManager.getInstance(context.applicationContext)
    private val running = ConcurrentHashMap.newKeySet<WakeId>()

    private val handlers = HandlerSlot<WakeHandlers>("Wake", BeforeListen.Dropped)

    override fun listen(handlers: WakeHandlers) {
        this.handlers.set(handlers)
        registration.value = this
    }

    override suspend fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult =
        log.invocation(EntryScope.None, "wake.schedule", params = "id=$id", result = { "$it" }) { enqueue(id, trigger) }

    private suspend fun enqueue(id: WakeId, trigger: WakeTrigger): ScheduleResult = try {
        val request = OneTimeWorkRequestBuilder<WakeWorker>()
            .setInputData(workDataOf(ID to id.name))
            .apply { constrain(trigger) }
            .build()
        val policy = if (id in running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        // Awaited: WorkManager enqueues asynchronously, and a content-URI trigger observes only once its job is
        // registered with the platform — so answering before that missed a change made right after, and an enqueue
        // that failed later was never reported. `Scheduled` now means the platform holds the request.
        // Suspended for, never blocked on: the caller is often the main thread, and WorkManager runs its operations
        // one at a time — behind a burst of other work, a blocking wait here froze the app into an ANR (SNAPSYNC-40).
        work.enqueueUniqueWork(nameOf(id), policy, request).await()
        ScheduleResult.Scheduled
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Thrown by the enqueue itself (an `IllegalStateException`), or the failure the operation reported — its
        // cause, which `await` rethrows unwrapped.
        ScheduleResult.Refused("${e::class.simpleName}: ${e.message}")
    }

    /** [trigger] as WorkManager's delay and constraints. */
    private fun OneTimeWorkRequest.Builder.constrain(trigger: WakeTrigger) {
        when (trigger) {
            is WakeTrigger.After -> {
                setInitialDelay(trigger.earliest.toJavaDuration())
                when (trigger.network) {
                    WakeNetwork.NONE -> Unit
                    WakeNetwork.ANY -> setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    // Capability `mobile-data`: WorkManager's own unmetered constraint, which stays unsatisfied on
                    // cellular and a metered Wi-Fi (measured on the API 36 emulator, 2026-10-03).
                    WakeNetwork.UNRESTRICTED ->
                        setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                }
            }
            is WakeTrigger.LibraryChange -> setConstraints(
                Constraints.Builder()
                    .addContentUriTrigger(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                    .addContentUriTrigger(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
                    .setTriggerContentMaxDelay(trigger.maxDelay.toJavaDuration())
                    .build(),
            )
        }
    }

    override fun cancel(id: WakeId) = log.invocation(EntryScope.None, "wake.cancel", params = "id=$id") {
        work.cancelUniqueWork(nameOf(id))
        Unit
    }

    /** The worker's run: hand the wake to the core, and hold the run open until its completion is released. */
    internal suspend fun run(id: WakeId) = log.invocation(EntryScope.None, "wake.onTaskLaunched", params = "id=$id") {
        val registered = handlers.orNull("a $id wake") ?: return@invocation
        val done = CompletableDeferred<Unit>()
        val completion = WorkerCompletion(done)
        running += id
        try {
            registered.onWake(id, completion)
            done.await()
        } catch (e: CancellationException) {
            log.i { "the operating system stopped the $id wake — its time is up" }
            completion.expire()
            throw e
        } finally {
            running -= id
        }
    }

    internal companion object {
        const val ID = "wake"

        /** The adapter the process's composition listened on last; a worker waits for one while the process starts. */
        val registration = MutableStateFlow<AndroidWake?>(null)

        fun nameOf(id: WakeId) = "wake.${id.name}"
    }
}

/**
 * A wake's [Completion]: released once, and expired once — when WorkManager stops the worker. The expiry action runs at
 * most once, whichever comes first, its registration or the stop, as `IosWake`'s does.
 */
private class WorkerCompletion(private val done: CompletableDeferred<Unit>) : Completion {
    private val expired = AtomicBoolean(false)
    private val action = AtomicReference<(() -> Unit)?>(null)
    private val actionRan = AtomicBoolean(false)

    override fun complete() {
        done.complete(Unit)
    }

    override fun onExpired(action: () -> Unit) {
        this.action.set(action)
        if (expired.get()) runOnce(action)
    }

    fun expire() {
        expired.set(true)
        action.get()?.let(::runOnce)
    }

    private fun runOnce(action: () -> Unit) {
        if (actionRan.compareAndSet(false, true)) action()
    }
}

/**
 * The work WorkManager runs for a [WakeId] — an entry port: it waits for the process's composition to listen on the
 * [AndroidWake] (no composition — a build that refused at start, or a rig launch whose wake is mocked — runs nothing),
 * then hands it the wake.
 */
class WakeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(AndroidWake.ID)?.let { name -> WakeId.entries.firstOrNull { it.name == name } }
            ?: return Result.success()
        val wake = withTimeoutOrNull(COMPOSITION_WAIT_MILLIS) { AndroidWake.registration.filterNotNull().first() }
            ?: return Result.success()
        runCatchingCancellable { wake.run(id) }.onFailure { Logger.withTag("wake").w(it) { "the $id wake failed" } }
        return Result.success()
    }

    private companion object {
        const val COMPOSITION_WAIT_MILLIS = 10_000L
    }
}
