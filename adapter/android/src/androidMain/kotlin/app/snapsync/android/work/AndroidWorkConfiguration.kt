package app.snapsync.android.work

import androidx.work.Configuration

/**
 * WorkManager's configuration for the process, handed to it by the app's `Application` (on-demand initialisation: the
 * app manifest removes WorkManager's startup initializer).
 *
 * It exists for one value: the job ids WorkManager may take stay below [AndroidWake.LIBRARY_JOBS]. Before API 34
 * WorkManager's jobs share this app's default JobScheduler namespace with the library-change job, and a shared id
 * would make one scheduler replace the other's job.
 */
object AndroidWorkConfiguration {
    val configuration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(0, AndroidWake.LIBRARY_JOBS.min() - 1)
            .build()
}
