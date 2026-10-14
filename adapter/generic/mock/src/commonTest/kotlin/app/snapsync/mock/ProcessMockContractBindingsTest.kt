package app.snapsync.mock

import app.snapsync.contracts.AppSettingsContract
import app.snapsync.contracts.AppSettingsState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.BuildInfoContract
import app.snapsync.contracts.BuildInfoState
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.ClockContract
import app.snapsync.contracts.ClockState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.LinkOpenerContract
import app.snapsync.contracts.LinkOpenerState
import app.snapsync.contracts.SharePresenterContract
import app.snapsync.contracts.SharePresenterState
import app.snapsync.contracts.ShownSettings
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.Platform
import app.snapsync.model.Store
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.Clock
import app.snapsync.ports.SystemUi
import kotlinx.datetime.TimeZone
import kotlin.test.Test

/**
 * What the platform tells a process about itself, and the UI it hands things to — as mocks, held to the contracts their
 * real adapters pass (`docs/testing.md`, "Mocks"). Each state is entered through the mock's constructor or operator
 * face, as a test of the composition enters it.
 */
class ProcessMockContractBindingsTest {

    private val buildInfo = object : Binding<BuildInfoState, BuildInfo> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = BuildInfoMock.Face::class
        override val reaches = setOf(
            BuildInfoState.REPORTING_AND_LISTED,
            BuildInfoState.BUNDLED,
            BuildInfoState.UNREPORTED_AND_UNLISTED,
            BuildInfoState.UNBUNDLED,
            BuildInfoState.ON_ANDROID,
            BuildInfoState.ON_IOS,
        )
        override fun create(state: BuildInfoState, clauseId: String, log: CallLog): Entered<BuildInfo> {
            val mock = when (state) {
                BuildInfoState.REPORTING_AND_LISTED -> BuildInfoMock(
                    dsn = "https://key@reporter.invalid/1",
                    store = StorePage(Store("App Store", "https://apps.apple.com/app/id0")),
                )
                BuildInfoState.BUNDLED -> BuildInfoMock(
                    processId = "app.snapsync",
                    buildNumber = "2000",
                    osVersion = "iOS 26.6",
                    deviceModel = "iPhone",
                    reporterEnvironment = "development",
                )
                BuildInfoState.ON_ANDROID -> BuildInfoMock(platform = Platform.ANDROID)
                else -> BuildInfoMock()
            }
            return Entered.Ready(mock.port().recorded(log))
        }
    }

    private val clock = object : Binding<ClockState, Clock> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = ClockMock.Face::class
        override val reaches = setOf(ClockState.RUNNING)
        override fun create(state: ClockState, clauseId: String, log: CallLog): Entered<Clock> {
            // A stopped clock, set to now by its operator — as a test that wants the wall clock sets it.
            val mock = ClockMock(kotlin.time.Clock.System.now(), TimeZone.of("Europe/Berlin"))
            return Entered.Ready(mock.port().recorded(log))
        }
    }

    private val linkOpener = object : Binding<LinkOpenerState, SystemUi> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = SystemUiMock.Face::class
        override val reaches = setOf(LinkOpenerState.CLAIMED, LinkOpenerState.UNCLAIMED)
        override fun create(state: LinkOpenerState, clauseId: String, log: CallLog): Entered<SystemUi> {
            val mock = SystemUiMock()
            if (state == LinkOpenerState.UNCLAIMED) mock.unclaimed = setOf(LinkOpenerContract.UNCLAIMED_URL)
            return Entered.Ready(mock.port().recorded(log))
        }
    }

    private val sharePresenter = object : Binding<SharePresenterState, SystemUi> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = SystemUiMock.Face::class
        override val reaches =
            setOf(SharePresenterState.PRESENTABLE, SharePresenterState.NO_WINDOW, SharePresenterState.NO_SHEET)
        override fun create(state: SharePresenterState, clauseId: String, log: CallLog): Entered<SystemUi> {
            val mock = SystemUiMock()
            mock.noWindow = state == SharePresenterState.NO_WINDOW
            mock.noSheet = state == SharePresenterState.NO_SHEET
            return Entered.Ready(mock.port().recorded(log))
        }
    }

    private val appSettings = object : Binding<AppSettingsState, ShownSettings> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = SystemUiMock.Face::class
        override val reaches = setOf(AppSettingsState.SHOWABLE)
        override fun create(state: AppSettingsState, clauseId: String, log: CallLog): Entered<ShownSettings> {
            val mock = SystemUiMock()
            return Entered.Ready(
                ShownSettings(
                    mock.port().recorded(log),
                    settingsInFront = { mock.settingsInFront.value },
                    leave = { mock.leaveSettings() },
                ),
            )
        }
    }

    @Test
    fun `the build-info mock satisfies the BuildInfo contract`() = verify(BuildInfoContract, buildInfo)

    @Test
    fun `the clock mock satisfies the Clock contract`() = verify(ClockContract, clock)

    @Test
    fun `the system-UI mock satisfies the LinkOpener contract`() = verify(LinkOpenerContract, linkOpener)

    @Test
    fun `the system-UI mock satisfies the SharePresenter contract`() = verify(SharePresenterContract, sharePresenter)

    @Test
    fun `the system-UI mock satisfies the AppSettings contract`() = verify(AppSettingsContract, appSettings)
}
