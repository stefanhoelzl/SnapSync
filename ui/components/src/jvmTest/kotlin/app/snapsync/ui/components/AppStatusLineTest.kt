package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.status_sync_ongoing
import app.snapsync.ui.components.resources.status_sync_pending
import app.snapsync.ui.components.resources.status_waiting_photos
import app.snapsync.ui.components.resources.status_waiting_wifi
import app.snapsync.ui.state.Arrow
import org.junit.Rule
import kotlin.test.Test

/**
 * The syncing line's label (capability `sync-status`): either arrow in flight reads ongoing, whichever direction; held for
 * Wi-Fi reads so; only the others' photos left reads waiting for photos; any other still work reads pending.
 */
class AppStatusLineTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `photos only arriving read as ongoing`() {
        setLine(AppSyncStatus.Syncing(upload = Arrow.STATIC, download = Arrow.PULSING))
        rule.onNodeWithText(str(Res.string.status_sync_ongoing)).assertExists()
    }

    @Test
    fun `photos only waiting read as pending`() {
        setLine(AppSyncStatus.Syncing(upload = Arrow.STATIC, download = Arrow.STATIC))
        rule.onNodeWithText(str(Res.string.status_sync_pending)).assertExists()
    }

    @Test
    fun `photos only leaving read as ongoing too - never as arriving`() {
        setLine(AppSyncStatus.Syncing(upload = Arrow.PULSING, download = Arrow.HIDDEN))
        rule.onNodeWithText(str(Res.string.status_sync_ongoing)).assertExists()
    }

    @Test
    fun `only the others' photos left read as waiting for photos`() {
        setLine(AppSyncStatus.Syncing(upload = Arrow.HIDDEN, download = Arrow.STATIC, waitingForOthers = true))
        rule.onNodeWithText(str(Res.string.status_waiting_photos)).assertExists()
    }

    @Test
    fun `a running transfer outranks waiting for photos`() {
        setLine(AppSyncStatus.Syncing(upload = Arrow.HIDDEN, download = Arrow.PULSING, waitingForOthers = true))
        rule.onNodeWithText(str(Res.string.status_sync_ongoing)).assertExists()
    }

    @Test
    fun `waiting for Wi-Fi outranks waiting for photos`() {
        setLine(
            AppSyncStatus.Syncing(
                upload = Arrow.STATIC,
                download = Arrow.STATIC,
                waitingForWifi = true,
                waitingForOthers = true,
            ),
        )
        rule.onNodeWithText(str(Res.string.status_waiting_wifi)).assertExists()
    }

    private fun setLine(status: AppSyncStatus) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppTheme(platformDates) { AppStatusLine(status) }
            }
        }
    }
}
