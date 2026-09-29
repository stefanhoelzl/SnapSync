package app.snapsync.android

import android.app.Application

/** The process: its one composition, built as it starts ([SnapSyncRoot]). */
class SnapSyncApplication : Application() {
    lateinit var root: SnapSyncRoot
        private set

    override fun onCreate() {
        super.onCreate()
        root = SnapSyncRoot(this)
        root.onLaunch()
    }
}
