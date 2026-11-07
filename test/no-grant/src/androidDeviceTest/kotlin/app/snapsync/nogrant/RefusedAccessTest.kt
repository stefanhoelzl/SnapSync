package app.snapsync.nogrant

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.control.Verifies
import app.snapsync.ports.LibraryGrant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A member who refused photo access is not shown Android's dialog a second time (capability `photo-access`): with an
 * activity in front — where a member who was never asked WOULD be asked — the permission adapter asks nothing and
 * answers the refusal. The contract's `REFUSED_A_REQUEST_CHANGES_NOTHING` runs with no screen in front, where asking
 * is impossible either way; this holds it where asking is possible.
 */
class RefusedAccessTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val record get() = context.getSharedPreferences(RECORD, Context.MODE_PRIVATE)

    @AfterTest
    fun clearRecord() {
        record.edit().clear().commit()
    }

    @Test
    // This adapter's part: a refusal is never asked about again. Taking the member to Settings instead is the core's
    // (`PhotoAccessIntegrationTest`).
    @Verifies(
        spec = "photo-access",
        requirement = "The system's access dialog is raised only by a deliberate tap",
        scenario = "A refused Android member is not asked twice",
    )
    fun `a refused member is asked nothing again with a screen in front to ask on`(): Unit = runBlocking {
        // What the member's "Don't allow" leaves: the adapter's record that it asked, and no grant.
        record.edit().putBoolean(ASKED, true).commit()
        val foreground = ForegroundActivity(context.applicationContext as Application)
        val permission = AndroidPhotoPermission(context, foreground)
        val activity = instrumentation.startActivitySync(
            Intent().setClassName(context, HOST_ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            instrumentation.waitForIdleSync()
            assertNotNull(foreground.current, "an activity is in front, so a request could raise the dialog")
            // A raised dialog would hold the answer until a person answered it: the timeout is the failure.
            assertEquals(LibraryGrant.DENIED, withTimeout(ANSWER_MILLIS) { permission.requestAccess() })
            assertEquals(LibraryGrant.DENIED, withTimeout(ANSWER_MILLIS) { permission.widenSelection() })
        } finally {
            activity.finish()
        }
    }

    private companion object {
        /** `AndroidPhotoPermission`'s record of having asked: the preferences file and the key its completion writes. */
        const val RECORD = "photo_permission"
        const val ASKED = "asked"

        /** The activity `ui-test-manifest` declares in this test APK. */
        const val HOST_ACTIVITY = "androidx.activity.ComponentActivity"
        const val ANSWER_MILLIS = 5_000L
    }
}
