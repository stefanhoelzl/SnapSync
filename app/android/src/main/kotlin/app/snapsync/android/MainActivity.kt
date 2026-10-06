package app.snapsync.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * The one activity: the screen the process's composition shows ([SnapSyncRoot.ui]), and the event link it is opened
 * with — at its creation, or re-delivered to it running (single-top) — forwarded whole to the links adapter.
 */
class MainActivity : ComponentActivity() {
    private val root: SnapSyncRoot get() = (application as SnapSyncApplication).root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15+ draws every app edge to edge on its own; below it (down to minSdk 30) the platform paints the
        // bars in the theme's colours unless asked, so the screen's background would stop at a grey status bar.
        enableEdgeToEdge()
        setContent(content = root.ui.content())
        root.links.deliverCreated(intent, restored = savedInstanceState != null)
        root.onScreenCreated()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        root.links.deliverIntent("onNewIntent", intent)
    }
}
