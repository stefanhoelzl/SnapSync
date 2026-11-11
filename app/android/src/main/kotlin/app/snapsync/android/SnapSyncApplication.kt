package app.snapsync.android

import android.app.Application
import androidx.work.Configuration
import app.snapsync.android.work.AndroidWorkConfiguration

/**
 * The process: its one composition, built as it starts ([SnapSyncRoot]). It also hands WorkManager its configuration
 * ([AndroidWorkConfiguration]), which WorkManager reads on its first use because the manifest removes its initializer.
 */
class SnapSyncApplication : Application(), Configuration.Provider {
    lateinit var root: SnapSyncRoot
        private set

    override val workManagerConfiguration: Configuration
        get() = AndroidWorkConfiguration.configuration

    override fun onCreate() {
        super.onCreate()
        root = SnapSyncRoot(this)
        root.onLaunch()
    }
}
