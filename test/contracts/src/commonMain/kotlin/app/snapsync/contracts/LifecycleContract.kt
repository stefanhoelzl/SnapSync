package app.snapsync.contracts

import app.snapsync.ports.Lifecycle
import app.snapsync.ports.LifecycleHandlers
import kotlin.test.assertEquals

/** Where the app's foreground life stands. */
enum class LifecycleState {
    /** An app whose active state the binding can move through its platform's own entries ([LifecycleUnderTest]). */
    MOVABLE,
}

/** The port, and the platform's two transitions as the binding plays them. */
class LifecycleUnderTest(
    val lifecycle: Lifecycle,
    val becomeActive: suspend () -> Unit,
    val resignActive: suspend () -> Unit,
)

/**
 * What the app's lifecycle promises the composition (`docs/architecture.md`, "entry ports"): each transition the
 * platform reports reaches the handler that listened, once, in the order it happened. What a transition RUNS is the
 * composition's, pinned by the rig tests over the JVM root; this is what the adapter delivers.
 */
object LifecycleContract : Contract<LifecycleState, LifecycleUnderTest>("Lifecycle") {

    override val clauses = clauses {

        clause(
            "MOVABLE_EACH_TRANSITION_REACHES_ITS_HANDLER",
            LifecycleState.MOVABLE,
            covers = cells {
                on<Lifecycle> {
                    answers(Lifecycle::listen).returns()
                    calls(LifecycleHandlers::onForeground)
                    calls(LifecycleHandlers::onBackground)
                }
            },
        ) { subject ->
            val seen = mutableListOf<String>()
            subject.lifecycle.listen(
                LifecycleHandlers(onForeground = {
                    seen += "foreground"
                }, onBackground = { seen += "background" }),
            )
            subject.becomeActive()
            awaitWithin { seen.isNotEmpty() }
            subject.resignActive()
            awaitWithin { seen.size >= 2 }
            assertEquals(listOf("foreground", "background"), seen, "each transition, once, in its order")
        }
    }
}
