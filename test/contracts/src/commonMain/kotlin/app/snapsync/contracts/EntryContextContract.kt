package app.snapsync.contracts

import app.snapsync.ports.EntryContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Whether the process keeps an ambient entry context at all. */
enum class EntryContextState {
    /** A process whose log lines carry no entry point (Android, the JVM): every claim is declined. */
    NO_CONTEXT,

    /** A process that tags its log lines with the entry point that drove them (iOS), with none claimed yet. */
    AMBIENT,
}

/**
 * What the ambient "what triggered this" seam promises (`docs/architecture.md`): the outermost claim wins, a nested
 * claim is declined and leaves it, and only the claim that set the context clears it.
 */
object EntryContextContract : Contract<EntryContextState, EntryContext>("EntryContext") {

    override val clauses = clauses {

        clause(
            "NO_CONTEXT_DECLINES_EVERY_CLAIM",
            EntryContextState.NO_CONTEXT,
            covers = cells {
                on<EntryContext> {
                    answers(EntryContext::enter).with(false)
                    answers(EntryContext::current).with(null)
                    answers(EntryContext::exit).returns()
                }
            },
        ) { context ->
            assertFalse(context.enter("onForeground"), "no context is kept, so no claim is established")
            assertNull(context.current())
            context.exit(owned = false)
            assertNull(context.current())
        }

        clause(
            "AMBIENT_THE_OUTERMOST_CLAIM_WINS_AND_ONLY_IT_CLEARS",
            EntryContextState.AMBIENT,
            covers = cells {
                on<EntryContext> {
                    answers(EntryContext::enter).with(true)
                    answers(EntryContext::enter).with(false)
                    answers(EntryContext::current).returns()
                    answers(EntryContext::current).with(null)
                    answers(EntryContext::exit).returns()
                }
            },
        ) { context ->
            assertNull(context.current(), "nothing is claimed yet")
            val outer = context.enter("onSilentPush")
            assertTrue(outer, "the first claim establishes the context")
            assertEquals("onSilentPush", context.current())
            val inner = context.enter("download.didComplete")
            assertFalse(inner, "a nested claim is declined")
            assertEquals("onSilentPush", context.current(), "and leaves the outer label")
            context.exit(inner)
            assertEquals("onSilentPush", context.current(), "a declined claim's exit clears nothing")
            context.exit(outer)
            assertNull(context.current(), "the establishing claim's exit clears it")
        }
    }
}
