package app.snapsync.mock

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.DevControlsContract
import app.snapsync.contracts.DevControlsState
import app.snapsync.contracts.DevControlsUnderTest
import app.snapsync.contracts.Entered
import app.snapsync.contracts.ExtensionHostContract
import app.snapsync.contracts.ExtensionHostState
import app.snapsync.contracts.ExtensionHostUnderTest
import app.snapsync.contracts.LifecycleContract
import app.snapsync.contracts.LifecycleState
import app.snapsync.contracts.LifecycleUnderTest
import app.snapsync.contracts.LinksContract
import app.snapsync.contracts.LinksState
import app.snapsync.contracts.LinksUnderTest
import app.snapsync.contracts.PushNotificationsContract
import app.snapsync.contracts.PushOs
import app.snapsync.contracts.PushState
import app.snapsync.contracts.PushUnderTest
import app.snapsync.contracts.UiContract
import app.snapsync.contracts.UiStateForContract
import app.snapsync.contracts.UiUnderTest
import app.snapsync.contracts.currentHost
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.runEntry
import app.snapsync.contracts.verify
import app.snapsync.inbound.Completion
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.ProcessingAnswer
import app.snapsync.ports.LinkDelivery
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.datetime.LocalDate
import kotlin.test.Test

/**
 * The entry ports' mocks held to the contracts their real adapters pass (`docs/testing.md`, "Mocks"): each driven
 * through its operator face — the platform's side — exactly as a test of the composition drives it.
 */
class EntryMockContractBindingsTest {

    private val lifecycle = object : Binding<LifecycleState, LifecycleUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = LifecycleMock.Face::class
        override val reaches = setOf(LifecycleState.MOVABLE)
        override fun create(state: LifecycleState, clauseId: String, log: CallLog): Entered<LifecycleUnderTest> {
            val mock = LifecycleMock()
            return Entered.Ready(
                LifecycleUnderTest(
                    mock.port().recorded(log),
                    becomeActive = { mock.operator.foreground() },
                    resignActive = { mock.operator.background() },
                ),
            )
        }
    }

    private val links = object : Binding<LinksState, LinksUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = LinksMock.Face::class
        override val reaches = setOf(
            LinksState.OPENABLE,
            LinksState.OPENED_WITH_NO_LINK,
            LinksState.OPENED_WITH_NO_URL,
            LinksState.INSTALLED_WITH_A_REFERRER,
        )
        override fun create(state: LinksState, clauseId: String, log: CallLog): Entered<LinksUnderTest> {
            val mock = LinksMock()
            return Entered.Ready(
                LinksUnderTest(
                    mock.port().recorded(log),
                    open = { url -> mock.operator.open(url) },
                    openNotALink = {
                        mock.operator.deliver(LinkDelivery.NotALink("onNewIntent", "android.intent.action.MAIN"))
                    },
                    openWithoutUrl = { mock.operator.deliver(LinkDelivery.NoUrl("onNewIntent")) },
                    startInstalled = { referrer -> mock.operator.startInstalled(referrer) },
                ),
            )
        }
    }

    private val push = object : Binding<PushState, PushUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = PushServiceMock.Face::class
        override val reaches = setOf(PushState.NO_PUSH_PROJECT, PushState.DELIVERING)
        override fun create(state: PushState, clauseId: String, log: CallLog): Entered<PushUnderTest> {
            val mock = PushServiceMock()
            if (state == PushState.NO_PUSH_PROJECT) mock.operator.refusesTokens = "no push project is configured"
            val os = PushOs(
                issueToken = { mock.operator.deliverToken(it) },
                deliver = { data ->
                    var released = false
                    mock.operator.deliverMessage(
                        HashMap<Any?, Any?>(data),
                        object : Completion {
                            override fun complete() {
                                released = true
                            }
                        },
                    )
                    released
                },
            )
            return Entered.Ready(PushUnderTest(mock.port().recorded(log), os.takeIf { state == PushState.DELIVERING }))
        }
    }

    private val devControls = object : Binding<DevControlsState, DevControlsUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = DevControlsMock.Face::class
        override val reaches = setOf(DevControlsState.SHIPPED, DevControlsState.SWITCHED)
        override fun create(state: DevControlsState, clauseId: String, log: CallLog): Entered<DevControlsUnderTest> {
            val mock = DevControlsMock()
            if (state == DevControlsState.SHIPPED) {
                mock.operator.encryptsNewEvents = false
                return Entered.Ready(DevControlsUnderTest(mock.port().recorded(log)))
            }
            mock.operator.pin = DevControlsContract.PINNED
            mock.operator.inviteLinkHints = InviteLinkHints.Honoured
            mock.operator.encryptsNewEvents = true
            mock.operator.networkGraceMillis = DevControlsContract.SHORT_GRACE_MILLIS
            return Entered.Ready(DevControlsUnderTest(mock.port().recorded(log)) { mock.operator.reset() })
        }
    }

    private val extensionHost = object : Binding<ExtensionHostState, ExtensionHostUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = ExtensionHostMock.Face::class
        override val reaches = setOf(ExtensionHostState.INVOKABLE)
        override fun create(
            state: ExtensionHostState,
            clauseId: String,
            log: CallLog,
        ): Entered<ExtensionHostUnderTest> {
            val mock = ExtensionHostMock()
            return Entered.Ready(
                ExtensionHostUnderTest(
                    mock.port().recorded(log),
                    invoke = {
                        var answered: ProcessingAnswer? = null
                        runEntry { answered = mock.operator.process(Dispatchers.Default) }
                        checkNotNull(answered).ordinal
                    },
                    end = { mock.operator.terminate() },
                    resultOf = { it.ordinal },
                ),
            )
        }
    }

    private val screen = object : Binding<UiStateForContract, UiUnderTest> {
        override val host = currentHost
        override val kind = BindingKind.Fake
        override val adapter = ScreenMock.Face::class
        override val reaches = setOf(UiStateForContract.TOURABLE)
        override fun create(state: UiStateForContract, clauseId: String, log: CallLog): Entered<UiUnderTest> {
            val mock = ScreenMock()
            val ui = mock.port().recorded(log)
            val creating = UiState(Layer.CreatingEvent)
            return Entered.Ready(
                UiUnderTest(
                    ui,
                    react = {},
                    tour = {
                        ui.show(creating)
                        mock.operator.live()
                        EVERY_INTENT.forEach { mock.operator.tap(it) }
                    },
                    shown = { creating },
                ),
            )
        }
    }

    @Test
    fun `the lifecycle mock satisfies the Lifecycle contract`() = verify(LifecycleContract, lifecycle)

    @Test
    fun `the links mock satisfies the Links contract`() = verify(LinksContract, links)

    @Test
    fun `the push service mock satisfies the PushNotifications contract`() = verify(PushNotificationsContract, push)

    @Test
    fun `the dev controls mock satisfies the DevControls contract`() = verify(DevControlsContract, devControls)

    @Test
    fun `the extension host mock satisfies the ExtensionHost contract`() = verify(ExtensionHostContract, extensionHost)

    @Test
    fun `the screen mock satisfies the Ui contract`() = verify(UiContract, screen)

    private companion object {
        private val DAY = LocalDate(2026, 10, 1)

        /** One of every intent a person can make — what the operator's taps play for a whole tour. */
        val EVERY_INTENT: List<UiIntent> = listOf(
            UiIntent.ConfirmCreate, UiIntent.CreateName("Anna’s Birthday"), UiIntent.RangeDay(DAY),
            UiIntent.RangeDragStart(DAY), UiIntent.RangeDragEnd(DAY), UiIntent.RangeSweep(DAY, DAY),
            UiIntent.RangeFromHour(9), UiIntent.RangeFromMinute(30), UiIntent.RangeUntilHour(18),
            UiIntent.RangeUntilMinute(0), UiIntent.RangeUntilMinuteDrag, UiIntent.RangePickerOpen,
            UiIntent.RangePickerConfirm, UiIntent.RangePickerDismiss, UiIntent.RequestPermission, UiIntent.ChoosePhotos,
            UiIntent.OpenSettings, UiIntent.LeaveEvent, UiIntent.ShareInvite, UiIntent.OpenAppStore,
            UiIntent.ConfirmLeaveOpen, UiIntent.ConfirmLeaveDismiss, UiIntent.RenameOpen, UiIntent.RenameDismiss,
            UiIntent.QrOpen, UiIntent.QrDismiss, UiIntent.ReportBugOpen,
            UiIntent.ReportRefusal(
                ScreenMessage.entries.first(),
            ),
            UiIntent.ReportBugDismiss, UiIntent.MenuOpen, UiIntent.MenuDismiss, UiIntent.MenuReportBug,
            UiIntent.MobileData(true), UiIntent.OpenLink(AppLink.entries.first()), UiIntent.ReportNoticeDismiss,
            UiIntent.OpenReconfigure, UiIntent.CancelReconfigure, UiIntent.RenameEvent("event", "name"),
            UiIntent.RenameStatusConsumed, UiIntent.ConfirmStopSharing, UiIntent.KeepSharing, UiIntent.ShareOn(true),
            UiIntent.ReceiveOn(true), UiIntent.SaveToAlbum(true), UiIntent.RangePreset(RangeChoice.entries.first()),
            UiIntent.RetryLoad, UiIntent.ConfirmJoin, UiIntent.ConfirmSwitch, UiIntent.RetryJoin, UiIntent.CancelJoin,
            UiIntent.CancelSwitch, UiIntent.SendDiagnostics("note", "screen"), UiIntent.KeepOpenFor(1),
            UiIntent.KeepOpenDismiss,
        )
    }
}
