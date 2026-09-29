package app.snapsync.android.scene

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * The activity the member is looking at, if any — what an adapter needs to put a system surface in front of them (the
 * photo-permission dialog, the selection sheet). `null` while no activity is resumed: a process a worker started in the
 * background has none, and then nothing is asked (`Gallery.requestAccess`).
 *
 * [onResumed] runs every time an activity comes to the front — how an access change the member made in Settings is
 * picked up on return (capability `photo-access`). Registered once, in `Application.onCreate`.
 */
class ForegroundActivity(application: Application) {

    @Volatile
    var current: ComponentActivity? = null
        private set

    private val resumed = mutableListOf<() -> Unit>()

    /** Run [action] each time an activity is resumed, on the main thread. */
    fun onResumed(action: () -> Unit) {
        synchronized(resumed) { resumed += action }
    }

    init {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                current = activity as? ComponentActivity
                synchronized(resumed) { resumed.toList() }.forEach { it() }
            }

            override fun onActivityPaused(activity: Activity) {
                if (current === activity) current = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
