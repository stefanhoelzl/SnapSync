@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.android.scene

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import app.snapsync.android.dates.AndroidDateFormatting
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.UiContract
import app.snapsync.contracts.UiStateForContract
import app.snapsync.contracts.UiUnderTest
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.resolveDateLocale
import app.snapsync.tour.StatusScreenTour
import app.snapsync.ui.components.DateFormatter
import app.snapsync.ui.components.ScreenDates
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** The activity's UI test, which runs the whole contract: past `runTest`'s default minute on a software-rendering emulator. */
private val TOUR_TIMEOUT = 10.minutes

/** [AndroidUi] against the [UiContract]: the activity's pull of the screen ([AndroidUi.content]), toured on the device. */
class AndroidUiContractTest : StatusScreenTour() {

    override val ui = AndroidUi(
        dates = ScreenDates { tag ->
            val dates = AndroidDateFormatting()
            val device = dates.deviceLocale()
            DateFormatter(dates.formats(resolveDateLocale(tag, device.language, device.region))::format)
        },
        log = Logger.withTag("contract"),
    )

    override fun content() = ui.content()

    // As the screen suites do: the emulator's soft keyboard would otherwise shrink the form under a tap.
    override val host: @Composable (@Composable () -> Unit) -> Unit = { screen ->
        Box(Modifier.consumeWindowInsets(WindowInsets.ime)) { screen() }
    }

    private val binding = object : Binding<UiStateForContract, UiUnderTest> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidUi::class
        override val reaches = setOf(UiStateForContract.TOURABLE)
        override fun create(state: UiStateForContract, clauseId: String, log: CallLog): Entered<UiUnderTest> {
            val recorded = ui.recorded(log)
            port = recorded
            return Entered.Ready(
                UiUnderTest(ui = recorded, react = ::react, tour = ::runTour, shown = ::shownNow),
                dispose = ::retire,
            )
        }
    }

    @Test
    fun `the status screen satisfies the Ui contract`() =
        // The activity is launched before the clause runs, so the clause's own minute is spent on the tour alone.
        runAndroidComposeUiTest<ComponentActivity>(testTimeout = TOUR_TIMEOUT) {
            screenTest = this
            try {
                verify(UiContract, binding)
            } finally {
                screenTest = null
                retire()
            }
        }
}
