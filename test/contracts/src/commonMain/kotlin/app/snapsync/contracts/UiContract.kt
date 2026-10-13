@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.Ui
import app.snapsync.ports.UiHandlers
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlin.test.assertTrue

/** Whether the screen can be shown and touched. */
enum class UiStateForContract {
    /**
     * A screen the binding can bring up over the port's own entry and touch once — [UiUnderTest.tap], which shows a
     * state through [Ui.show] and taps one control on it, as a person would.
     */
    TOUCHABLE,
}

/**
 * The port as a clause receives it: [ui], and [tap] — the binding bringing the screen up through the adapter's own
 * entry (an activity's creation, the scene's first activation), showing it a state through [Ui.show], and tapping one
 * control a person can reach on it.
 */
class UiUnderTest(
    val ui: Ui<UiState, UiIntent>,
    val tap: suspend () -> Unit,
    /** The state the app shows now — what a screen brought up live renders first. */
    val shown: () -> UiState,
)

/**
 * What the screen promises the composition (`docs/architecture.md`, "entry ports"): it reports itself live when it is
 * brought up, renders what it is shown, and a tap on it reaches the handler as an intent. The intent is the
 * composition's vocabulary, carried through the adapter as it is — the port grid counts it as ONE cell
 * (`docs/testing.md`, "The port grid") — so one tap proves the crossing. Which tap means which intent is
 * `statusActions`', click-tested over every control in `:ui:screens` (`HostStatusActionsTest`); what an intent DOES is
 * the presentation's (`StatusContainerHost.onIntent`), tested beside it. This is what the adapter delivers.
 */
object UiContract : Contract<UiStateForContract, UiUnderTest>("Ui") {

    override val clauses = clauses {

        clause(
            "TOUCHABLE_A_TAP_REACHES_THE_HANDLER",
            UiStateForContract.TOUCHABLE,
            covers = cells {
                on<Ui<UiState, UiIntent>> {
                    answers(Ui<UiState, UiIntent>::inbound).returns()
                    answers(Ui<UiState, UiIntent>::show).returns()
                    calls(UiHandlers<UiState, UiIntent>::onLive)
                    calls(UiHandlers<UiState, UiIntent>::onIntent, UiIntent::class)
                }
            },
        ) { subject ->
            val delivered = mutableListOf<UiIntent>()
            var live = 0
            subject.ui.inbound(
                UiHandlers(
                    onIntent = inboundOf { delivered += it },
                    onLive = inboundOf {
                        live++
                        subject.shown()
                    },
                ),
            )
            subject.tap()
            assertTrue(live > 0, "a screen brought up reports itself live")
            assertTrue(delivered.isNotEmpty(), "a tap on the screen reaches the handler as an intent")
        }
    }
}
