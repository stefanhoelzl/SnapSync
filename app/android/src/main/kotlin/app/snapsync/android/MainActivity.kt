package app.snapsync.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** The one activity: the screen the process's composition shows ([SnapSyncRoot.ui]). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent(content = (application as SnapSyncApplication).root.ui.content())
    }
}
