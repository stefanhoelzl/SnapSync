package app.snapsync.flow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **A trigger flow never outlives its own run** (law: `module-architecture`), from the behaviour side.
 *
 * The zone gate pins the *shape* — no `CoroutineScope`, no non-suspend `Unit` lambda — and the flow
 * transcriber pins the *grammar*. Neither can see whether `run()` actually waits, which is the property
 * the shells depend on: each one answers an OS completion handler when its flow returns, so a `run()`
 * that returns early makes that answer a false statement about work that had not started. Measured in
 * SNAPSYNC-6: `← onSilentPush (18ms)` against 41 s of real work.
 *
 * `SilentPush` is the subject because it is the one flow constructible from nothing — the others
 * need the full controller graph, whose fakes live in `:adapter:generic:fake`. The property is
 * structural and shared: it follows from `run()` being `suspend` with no scope to launch into, which
 * the zone gate holds for every flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowLifetimeTest {

    @Test
    fun `SilentPush run returns only after the fan out finishes`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var armFinished = false
        var returned = false

        val flow = SilentPush(
            reloadConfig = {},
            refreshAttestation = {},
            receivers = listOf { _ ->
                gate.await()
                armFinished = true
            },
        )
        val run = launch {
            flow.run(mapOf<Any?, Any?>("eventId" to "E"))
            returned = true
        }

        advanceUntilIdle()
        assertFalse(returned, "run() returned while its receiver was still working")

        gate.complete(Unit)
        run.join()
        assertTrue(armFinished)
        assertTrue(returned)
    }

    @Test
    fun `SilentPush awaits the attestation refresh before fanning out`() = runTest {
        // The fan-out's requests carry the token this renews. Firing them concurrently is how a request
        // went out holding the very credential the refresh was replacing.
        val order = mutableListOf<String>()
        SilentPush(
            reloadConfig = { order += "reload" },
            refreshAttestation = { order += "attest" },
            receivers = listOf { _ -> order += "fanout" },
        ).run(mapOf<Any?, Any?>("eventId" to "E"))

        assertTrue(order == listOf("reload", "attest", "fanout"), "unexpected order: $order")
    }

}
