package app.snapsync.android.systemui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.model.Handoff
import app.snapsync.ports.SystemUi
import app.snapsync.model.runCatchingCancellable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android [SystemUi]: the system share sheet (a chooser over `ACTION_SEND`), `ACTION_VIEW` for a URL, and this
 * app's page in Settings (capability `photo-access`). Each is started from the activity in front when there is one,
 * and as a new task otherwise; a start the platform refuses — no app claims the URL — is a [Handoff.Refused].
 */
class AndroidSystemUi(
    context: Context,
    private val foreground: ForegroundActivity,
    private val log: Logger = Logger.withTag("systemUi"),
) : SystemUi {

    private val appContext = context.applicationContext

    override suspend fun share(text: String): Handoff {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        return start("share", Intent.createChooser(send, null))
    }

    override suspend fun openUrl(url: String): Handoff =
        start("openUrl", Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))

    override fun openSettings() {
        val settings = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", appContext.packageName, null))
        runCatchingCancellable { launch(settings) }.onFailure { log.w(it) { "openSettings: the platform refused" } }
    }

    private suspend fun start(name: String, intent: Intent): Handoff = withContext(Dispatchers.Main) {
        try {
            launch(intent)
            Handoff.Accepted
        } catch (e: ActivityNotFoundException) {
            Handoff.Refused("no app claims it: ${e.message}").also { log.i { "$name: $it" } }
        }
    }

    private fun launch(intent: Intent) {
        val activity = foreground.current
        if (activity != null) activity.startActivity(intent) else appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
