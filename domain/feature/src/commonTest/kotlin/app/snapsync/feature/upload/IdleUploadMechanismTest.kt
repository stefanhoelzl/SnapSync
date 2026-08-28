package app.snapsync.feature.upload

import kotlin.test.Test
import kotlinx.coroutines.test.runTest

/**
 * The mechanism that runs when no upload work may occur (capability `upload-lifecycle`, "A mechanism is
 * always resolved").
 *
 * **It is a mechanism, not an absence, and that is the whole point.** Every OS trigger carries a
 * completion handler the system waits on, and an unanswered handler costs the app its future background
 * wakes. Routing a trigger to a `null` mechanism strands one; this object declines every trigger **and
 * still returns**, so the entry point holding the receipt releases it normally.
 *
 * So the assertion is that each call *returns* — nothing thrown, nothing suspended forever. There is no
 * output to check, because declining is the entire behaviour; what is being pinned is that a future
 * trigger added to `UploadTriggers` cannot reach a mechanism that fails to answer, since the compiler
 * forces an implementation here and this test forces it to complete.
 */
class IdleUploadMechanismTest {

    @Test
    fun `every lifecycle verb and every trigger is declined and still answers`() = runTest {
        with(IdleUploadMechanism) {
            start()
            stop()
            onForeground()
            onSilentPush("EVENT")
            onBackgroundTask()
            onSelectionChanged()
        }
    }
}
