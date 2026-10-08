package app.snapsync.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.snapsync.model.Arrow
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.status_sync_ongoing
import app.snapsync.ui.components.resources.status_sync_pending
import org.junit.Rule
import kotlin.test.Test

/** The syncing line's label (capability `photo-sharing`): either arrow in flight reads ongoing, none pending. */
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

    private fun setLine(status: AppSyncStatus) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                AppTheme(platformDates) { AppStatusLine(status) }
            }
        }
    }
}
