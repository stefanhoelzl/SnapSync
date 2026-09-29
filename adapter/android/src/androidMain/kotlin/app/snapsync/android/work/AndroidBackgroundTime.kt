package app.snapsync.android.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import co.touchlab.kermit.Logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * The Android [BackgroundTime] (capability `background-upload`): "keep this process running while I finish, and tell
 * me when time is up", as WorkManager work.
 *
 * Each hold is an **expedited** one-time work ([HoldWorker]) that runs until the hold ends: a running worker keeps a
 * process the member just left — or one a worker started — from being cached and frozen mid-unit. Past the app's
 * expedited quota it runs as ordinary work. The worker being stopped IS the operating system's expiry: the hold's
 * `onExpiry` runs, once. A hold that cannot be requested is an immediate expiry, as the port allows.
 *
 * Holds live in this process's memory; a hold worker a previous process left behind finds no hold and ends at once.
 */
class AndroidBackgroundTime(context: Context, private val log: Logger = Logger.withTag("backgroundTime")) : BackgroundTime {

    private val appContext = context.applicationContext
    private val work = WorkManager.getInstance(appContext)
    private val next = AtomicLong()

    init {
        registered = this
    }

    override fun begin(label: String, onExpiry: () -> Unit): BackgroundTimeHold {
        val id = "hold.${next.incrementAndGet()}"
        val hold = Hold(id, label, onExpiry)
        holds[id] = hold
        try {
            val request = OneTimeWorkRequestBuilder<HoldWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(workDataOf(ID to id))
                .build()
            work.enqueueUniqueWork(id, ExistingWorkPolicy.KEEP, request)
        } catch (e: IllegalStateException) {
            log.w(e) { "$label: no hold could be requested — its time is up at once" }
            hold.expire()
        }
        return hold
    }

    /** The worker's run: hold it open until [id]'s hold ends; a stop is the hold's expiry. */
    internal suspend fun run(id: String) {
        val hold = holds[id] ?: return
        try {
            hold.ended.await()
        } catch (e: CancellationException) {
            log.i { "${hold.label}: the operating system stopped the hold — its time is up" }
            hold.expire()
            throw e
        }
    }

    private inner class Hold(val id: String, val label: String, private val onExpiry: () -> Unit) : BackgroundTimeHold {
        val ended = CompletableDeferred<Unit>()
        private val expired = AtomicBoolean(false)

        fun expire() {
            if (expired.compareAndSet(false, true)) onExpiry()
        }

        override fun end() {
            if (!ended.complete(Unit)) return
            holds.remove(id)
            work.cancelUniqueWork(id)
        }
    }

    internal companion object {
        const val ID = "hold"
        private val holds = ConcurrentHashMap<String, AndroidBackgroundTime.Hold>()

        @Volatile
        var registered: AndroidBackgroundTime? = null
    }
}

/**
 * The work a background-time hold runs as. On Android 11 an expedited work runs as a foreground service, which must
 * show a notification ([getForegroundInfo]); from Android 12 it does not.
 */
class HoldWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(AndroidBackgroundTime.ID) ?: return Result.success()
        AndroidBackgroundTime.registered?.run(id)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sharing photos", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Sharing photos")
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL = "snapsync.sharing"
        const val NOTIFICATION_ID = 1
    }
}
