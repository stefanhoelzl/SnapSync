package app.snapsync.android.process

import app.snapsync.model.runCatchingCancellable
import android.content.Context
import android.os.UserManager
import app.snapsync.model.Availability
import app.snapsync.model.MemoryFootprint
import app.snapsync.ports.ProcessInfo

/**
 * The Android [ProcessInfo]: whether this user's credential-encrypted storage is unlocked — Android's counterpart of
 * iOS's protected data, unreadable from boot until the first unlock. The app is not direct-boot aware, so a process
 * of it normally starts only after that; the diagnostic dump reads it all the same, as on iOS.
 *
 * No memory footprint: it exists for MetricKit's reports, and Android composes no process-metric provider for it to
 * ride with — while the nearest Android figure (a PSS read) walks the process's memory maps on every call.
 */
class AndroidProcessInfo(context: Context) : ProcessInfo {
    private val users: UserManager = context.applicationContext.getSystemService(UserManager::class.java)

    override suspend fun protectedDataAvailable(): Availability =
        runCatchingCancellable { if (users.isUserUnlocked) Availability.AVAILABLE else Availability.UNAVAILABLE }
            .getOrDefault(Availability.UNKNOWN)

    override fun memoryFootprint(): MemoryFootprint? = null
}
