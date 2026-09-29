package app.snapsync.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * The one activity: the screen the process's composition shows ([SnapSyncRoot.ui]), and the event link it is opened
 * with — at its creation, or re-delivered to it running (single-top) — forwarded whole to the links adapter.
 */
class MainActivity : ComponentActivity() {
    private val root: SnapSyncRoot get() = (application as SnapSyncApplication).root

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(content = root.ui.content())
        root.links.deliverCreated(intent, restored = savedInstanceState != null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        root.links.deliverIntent("onNewIntent", intent)
    }
}
