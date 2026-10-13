@file:OptIn(ExperimentalTestApi::class)

package app.snapsync.scene

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
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
import app.snapsync.dates.IosDateFormatting
import app.snapsync.model.resolveDateLocale
import app.snapsync.tour.StatusScreenTour
import app.snapsync.ui.components.DateFormatter
import app.snapsync.ui.components.ScreenDates
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/** The whole contract is one tour: past `runComposeUiTest`'s default minute. */
private val TOUR_TIMEOUT = 10.minutes

/**
 * [IosUi] against the [UiContract] in the simulator's test executable: the live screen the scene's controller hosts
 * ([IosUi.liveContent]), toured in an offscreen Compose UI test. Which controller SwiftUI is handed — the placeholder or
 * a live one — is the scene rule's, held by `SceneModeTest`.
 */
class IosUiContractTest : StatusScreenTour() {

    override val ui = IosUi(
        record = SceneRecord(),
        dates = ScreenDates { tag ->
            val dates = IosDateFormatting()
            val device = dates.deviceLocale()
            DateFormatter(dates.formats(resolveDateLocale(tag, device.language, device.region))::format)
        },
        log = Logger.withTag("contract"),
    )

    override fun content() = ui.liveContent()

    private val binding = object : Binding<UiStateForContract, UiUnderTest> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosUi::class
        override val reaches = setOf(UiStateForContract.TOURABLE)
        override fun create(state: UiStateForContract, clauseId: String, log: CallLog): Entered<UiUnderTest> {
            val recorded = ui.recorded(log)
            port = recorded
            return Entered.Ready(UiUnderTest(ui = recorded, react = ::react, tour = ::runTour), dispose = ::retire)
        }
    }

    @Test
    fun `the status screen satisfies the Ui contract`() = runComposeUiTest(testTimeout = TOUR_TIMEOUT) {
        screenTest = this
        try {
            verify(UiContract, binding)
        } finally {
            screenTest = null
            retire()
        }
    }
}
