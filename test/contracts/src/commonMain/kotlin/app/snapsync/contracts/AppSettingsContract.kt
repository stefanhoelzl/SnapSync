package app.snapsync.contracts

import app.snapsync.ports.SystemUi
import kotlin.test.assertTrue

/** Whether the system can show this app's own Settings page. */
enum class AppSettingsState {
    /** A platform whose Settings page for this app the binding can see come to the front. */
    SHOWABLE,
}

/**
 * The port as a clause receives it: [ui], whose [SystemUi.openSettings] answers nothing, and an observation handle over
 * the system that shows the page — [settingsInFront], whether this app's Settings page is what the screen shows now —
 * and [leave], which returns the screen to where it was.
 */
class ShownSettings(
    val ui: SystemUi,
    val settingsInFront: suspend () -> Boolean,
    val leave: suspend () -> Unit,
)

/**
 * What [SystemUi.openSettings] promises (`docs/architecture.md` — this list IS the specification): the page where the
 * member changes the app's photo access comes to the front. It answers nothing, so the clause reads the screen. iOS
 * opens it by leaving the app, which takes the process away from whatever drives it, so its host is Android.
 */
object AppSettingsContract : Contract<AppSettingsState, ShownSettings>("AppSettings") {

    override val clauses = clauses {

        clause(
            "SHOWABLE_THE_APPS_SETTINGS_PAGE_COMES_TO_THE_FRONT",
            AppSettingsState.SHOWABLE,
            covers = cells { on<SystemUi>().answers(SystemUi::openSettings).returns() },
        ) { subject ->
            assertTrue(!subject.settingsInFront(), "the page is not showing before it is asked for")
            subject.ui.openSettings()
            try {
                awaitWithin { subject.settingsInFront() }
            } finally {
                subject.leave()
            }
        }
    }
}
