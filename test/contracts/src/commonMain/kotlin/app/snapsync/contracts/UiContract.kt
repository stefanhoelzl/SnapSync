package app.snapsync.contracts

import app.snapsync.model.UiIntent
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import kotlin.reflect.KClass
import kotlin.test.assertTrue

/** Whether the screen can be shown and touched. */
enum class UiStateForContract {
    /**
     * A screen the binding can bring up over the port's own entry and touch through every control a person can reach —
     * [UiUnderTest.tour], which shows the states the controls appear in and taps each, as a person would.
     */
    TOURABLE,
}

/**
 * The port as a clause receives it: [ui], and [tour] — the binding bringing the screen up through the adapter's own
 * entry (an activity's creation, the scene's first activation) and touching every control a person can reach, in the
 * states each appears in, which it shows through [Ui.show]. [react] is what the clause's handler does with an intent
 * beyond recording it: the composition that reduces it into the next state, so a tour can open a dialog and confirm it.
 */
class UiUnderTest(
    val ui: Ui,
    val react: (UiIntent) -> Unit,
    val tour: suspend () -> Unit,
)

/**
 * What the screen promises the composition (`docs/architecture.md`, "entry ports"): it reports itself live when it is
 * brought up, and every control a person can touch reaches the handler as the intent it means — none dropped, none
 * missing. What an intent DOES is the presentation's (`StatusContainerHost.onIntent`), tested beside it; which tap
 * means which intent is `statusActions`', click-tested in `:ui:screens`. This is what the adapter delivers.
 */
object UiContract : Contract<UiStateForContract, UiUnderTest>("Ui") {

    /** Every intent a person can make: what a tour of the whole screen must deliver. */
    val EVERY_INTENT: List<KClass<out UiIntent>> = listOf(
        UiIntent.CreateEvent::class,
        UiIntent.RequestPermission::class,
        UiIntent.ChoosePhotos::class,
        UiIntent.OpenSettings::class,
        UiIntent.LeaveEvent::class,
        UiIntent.ShareInvite::class,
        UiIntent.OpenAppStore::class,
        UiIntent.ConfirmLeaveOpen::class,
        UiIntent.ConfirmLeaveDismiss::class,
        UiIntent.RenameOpen::class,
        UiIntent.RenameDismiss::class,
        UiIntent.QrOpen::class,
        UiIntent.QrDismiss::class,
        UiIntent.ReportBugOpen::class,
        UiIntent.ReportRefusal::class,
        UiIntent.ReportBugDismiss::class,
        UiIntent.MenuOpen::class,
        UiIntent.MenuDismiss::class,
        UiIntent.MenuReportBug::class,
        UiIntent.MobileData::class,
        UiIntent.OpenLink::class,
        UiIntent.ReportNoticeDismiss::class,
        UiIntent.OpenReconfigure::class,
        UiIntent.CancelReconfigure::class,
        UiIntent.RenameEvent::class,
        UiIntent.RenameStatusConsumed::class,
        UiIntent.ConfirmStopSharing::class,
        UiIntent.KeepSharing::class,
        UiIntent.ShareOn::class,
        UiIntent.ReceiveOn::class,
        UiIntent.SaveToAlbum::class,
        UiIntent.RangePreset::class,
        UiIntent.RangeCustom::class,
        UiIntent.RetryLoad::class,
        UiIntent.ConfirmJoin::class,
        UiIntent.ConfirmSwitch::class,
        UiIntent.RetryJoin::class,
        UiIntent.CancelJoin::class,
        UiIntent.CancelSwitch::class,
        UiIntent.SendDiagnostics::class,
    )

    override val clauses = clauses {

        clause(
            "TOURABLE_EVERY_CONTROL_REACHES_THE_HANDLER",
            UiStateForContract.TOURABLE,
            covers = cells {
                on<Ui> {
                    answers(Ui::listen).returns()
                    answers(Ui::show).returns()
                    calls(UiHandlers::onLive)
                    calls(UiHandlers::onIntent, UiIntent.CreateEvent::class)
                    calls(UiHandlers::onIntent, UiIntent.RequestPermission::class)
                    calls(UiHandlers::onIntent, UiIntent.ChoosePhotos::class)
                    calls(UiHandlers::onIntent, UiIntent.OpenSettings::class)
                    calls(UiHandlers::onIntent, UiIntent.LeaveEvent::class)
                    calls(UiHandlers::onIntent, UiIntent.ShareInvite::class)
                    calls(UiHandlers::onIntent, UiIntent.OpenAppStore::class)
                    calls(UiHandlers::onIntent, UiIntent.ConfirmLeaveOpen::class)
                    calls(UiHandlers::onIntent, UiIntent.ConfirmLeaveDismiss::class)
                    calls(UiHandlers::onIntent, UiIntent.RenameOpen::class)
                    calls(UiHandlers::onIntent, UiIntent.RenameDismiss::class)
                    calls(UiHandlers::onIntent, UiIntent.QrOpen::class)
                    calls(UiHandlers::onIntent, UiIntent.QrDismiss::class)
                    calls(UiHandlers::onIntent, UiIntent.ReportBugOpen::class)
                    calls(UiHandlers::onIntent, UiIntent.ReportRefusal::class)
                    calls(UiHandlers::onIntent, UiIntent.ReportBugDismiss::class)
                    calls(UiHandlers::onIntent, UiIntent.MenuOpen::class)
                    calls(UiHandlers::onIntent, UiIntent.MenuDismiss::class)
                    calls(UiHandlers::onIntent, UiIntent.MenuReportBug::class)
                    calls(UiHandlers::onIntent, UiIntent.MobileData::class)
                    calls(UiHandlers::onIntent, UiIntent.OpenLink::class)
                    calls(UiHandlers::onIntent, UiIntent.ReportNoticeDismiss::class)
                    calls(UiHandlers::onIntent, UiIntent.OpenReconfigure::class)
                    calls(UiHandlers::onIntent, UiIntent.CancelReconfigure::class)
                    calls(UiHandlers::onIntent, UiIntent.RenameEvent::class)
                    calls(UiHandlers::onIntent, UiIntent.RenameStatusConsumed::class)
                    calls(UiHandlers::onIntent, UiIntent.ConfirmStopSharing::class)
                    calls(UiHandlers::onIntent, UiIntent.KeepSharing::class)
                    calls(UiHandlers::onIntent, UiIntent.ShareOn::class)
                    calls(UiHandlers::onIntent, UiIntent.ReceiveOn::class)
                    calls(UiHandlers::onIntent, UiIntent.SaveToAlbum::class)
                    calls(UiHandlers::onIntent, UiIntent.RangePreset::class)
                    calls(UiHandlers::onIntent, UiIntent.RangeCustom::class)
                    calls(UiHandlers::onIntent, UiIntent.RetryLoad::class)
                    calls(UiHandlers::onIntent, UiIntent.ConfirmJoin::class)
                    calls(UiHandlers::onIntent, UiIntent.ConfirmSwitch::class)
                    calls(UiHandlers::onIntent, UiIntent.RetryJoin::class)
                    calls(UiHandlers::onIntent, UiIntent.CancelJoin::class)
                    calls(UiHandlers::onIntent, UiIntent.CancelSwitch::class)
                    calls(UiHandlers::onIntent, UiIntent.SendDiagnostics::class)
                }
            },
        ) { subject ->
            val delivered = mutableListOf<UiIntent>()
            var live = 0
            subject.ui.listen(
                UiHandlers(
                    onIntent = { intent ->
                        delivered += intent
                        subject.react(intent)
                    },
                    onLive = { live++ },
                ),
            )
            subject.tour()
            assertTrue(live > 0, "a screen brought up reports itself live")
            val missing = EVERY_INTENT.filter { kind -> delivered.none { kind.isInstance(it) } }
            assertTrue(
                missing.isEmpty(),
                "every control reaches the handler; never delivered: ${missing.map { it.simpleName }}",
            )
        }
    }
}
