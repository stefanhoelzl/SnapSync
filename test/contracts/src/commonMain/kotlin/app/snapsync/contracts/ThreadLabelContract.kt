@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package app.snapsync.contracts

import app.snapsync.ports.ThreadLabel
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The one state a per-thread slot has: none claimed yet on any thread. */
enum class ThreadLabelState {
    EMPTY,
}

/**
 * What the per-thread log label promises (`docs/patterns.md`, "Logging"): a label set on a thread is that thread's —
 * read back there, invisible to another thread — and swapping answers the label it replaced, so the runtime restores
 * a thread's label when a task it labelled ends.
 */
object ThreadLabelContract : Contract<ThreadLabelState, ThreadLabel>("ThreadLabel") {

    override val clauses = clauses {

        clause(
            "EMPTY_A_LABEL_IS_ITS_THREAD_S_UNTIL_SWAPPED_BACK",
            ThreadLabelState.EMPTY,
            covers = cells {
                on<ThreadLabel> {
                    answers(ThreadLabel::swap).returns()
                    answers(ThreadLabel::swap).with(null)
                    answers(ThreadLabel::current).returns()
                    answers(ThreadLabel::current).with(null)
                }
            },
        ) { label ->
            assertNull(label.current(), "nothing is labelled yet")
            assertNull(label.swap("OnHeartbeat"), "the first label replaces none")
            assertEquals("OnHeartbeat", label.current())
            val elsewhere = newSingleThreadContext("thread-label-contract").use { other ->
                withContext(other) { label.current() }
            }
            assertNull(elsewhere, "another thread does not see this thread's label")
            assertEquals("OnHeartbeat", label.swap(null), "a swap answers the label it replaced")
            assertNull(label.current())
        }
    }
}
