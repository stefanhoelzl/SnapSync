package app.snapsync.android.scene

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.snapsync.model.PlatformEntry
import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers

/**
 * The Android [Lifecycle]: the PROCESS's resume ↔ the app became active, its pause ↔ it is leaving the active state —
 * `ProcessLifecycleOwner`, which folds every activity into one app-wide life, as iOS's `didBecomeActive` /
 * `willResignActive` are app-wide. An activity's own lifecycle would report a rotation or a second activity as the app
 * leaving; the process's does not.
 *
 * [listen] installs the observer — process-lifetime, never removed — and must run on the main thread, which is where
 * the root composes. A process a worker started in the background installs it too and simply never sees a resume.
 */
class AndroidLifecycle : Lifecycle {
    private var handlers: LifecycleHandlers? = null

    override fun listen(handlers: LifecycleHandlers) {
        this.handlers = handlers
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) = deliverForeground()

                override fun onPause(owner: LifecycleOwner) = deliverBackground()
            },
        )
    }

    /** The app became active — what the process's resume delivers. */
    @PlatformEntry
    fun deliverForeground() {
        handlers?.onForeground()
    }

    /** The app is leaving the active state — what the process's pause delivers. */
    @PlatformEntry
    fun deliverBackground() {
        handlers?.onBackground()
    }
}
