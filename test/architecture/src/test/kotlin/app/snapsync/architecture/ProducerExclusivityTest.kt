package app.snapsync.architecture

import app.snapsync.feature.upload.IdleUploadMechanism
import app.snapsync.feature.upload.RelinquishThenRun
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadMechanismRuntime
import app.snapsync.model.PermissionStatus
import app.snapsync.model.UploadMechanism
import app.snapsync.model.resolveUploadMechanism
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upload arm's invariants that the compiler cannot express (capability `architecture-guards`, "The
 * upload producers are never both started"; the behavioural half of `upload-lifecycle`'s "Exactly one
 * producer started per process").
 *
 * **This guard was retargeted, not retired.** "Both producers started" is a compile error again: the arm
 * holds one mechanism reference, so starting two has no expression. Exclusion moving back to the compiler
 * removed one failure mode and introduced two others, and the guard follows the risk:
 *
 * - **a resolver cell that cannot run on its OS.** Below iOS 26.1 `setUploadJobExtensionEnabled` does not
 *   exist, so a cell yielding the OS-driven mechanism there is not a wrong choice — it is an unrecognized
 *   selector and a dead process. That is strictly worse than the two-writer bug it replaced.
 * - **sequence bugs in an arm that now holds state.** `current` is new; the arm was stateless before.
 *
 * It drives the REAL [UploadArm] over the REAL resolver, with a factory mirroring the one `compose/`
 * builds — including the wrap that makes each mechanism relinquish what the other left behind. Wiring the
 * mechanisms bare would still pass every ordering assertion while testing nothing.
 */
class ProducerExclusivityTest {

    private class Recording(private val name: String, private val log: MutableList<String>) :
        UploadMechanismRuntime {
        override suspend fun start() { log += "$name.start" }
        override suspend fun stop() { log += "$name.stop" }
        override suspend fun onForeground() = Unit
        override suspend fun onSilentPush(eventId: String) = Unit
        override suspend fun onBackgroundTask() = Unit
        override suspend fun onSelectionChanged() = Unit
    }

    /** Every event the arm's callers can fire, each parameterized by the state it is fired under. */
    private enum class Event { PROVISION, PERMISSION_CHANGED, LEAVE }

    private val permissions = PermissionStatus.entries
    private val memberships = listOf(true, false, null)

    /** Mirrors `AppCore.uploadMechanisms`: both cells wrap, with deliberately asymmetric relinquish. */
    private fun factory(
        os: Recording,
        app: Recording,
        osSupported: Boolean,
    ): (UploadMechanism) -> UploadMechanismRuntime {
        val appHere = if (osSupported) RelinquishThenRun({ os.stop() }, app) else app
        val osHere = if (osSupported) RelinquishThenRun({ app.stop() }, os) else null
        return { kind ->
            when (kind) {
                UploadMechanism.PHOTOKIT -> osHere ?: appHere
                UploadMechanism.URL_SESSION -> appHere
                UploadMechanism.IDLE -> IdleUploadMechanism
            }
        }
    }

    @Test
    fun `no resolver cell yields a mechanism the OS cannot run`() {
        // The sharper of the two risks, and the one with no other guard in this module.
        for (permission in permissions) {
            for (override in listOf(null) + UploadMechanism.entries) {
                assertTrue(
                    resolveUploadMechanism(false, permission, override) != UploadMechanism.PHOTOKIT,
                    "os-driven mechanism resolved below 26.1: permission=$permission override=$override",
                )
            }
        }
    }

    /**
     * Every 3-event script over (event × permission × membership), for each OS — ~93k sequences —
     * asserting after every single verb that at most one mechanism is started, that a start is always
     * preceded by the other's stop within the same transition, and that whatever started is what
     * resolution yields for the state it started under.
     */
    @Test
    fun `no transition sequence leaves the wrong mechanism held or started`() = runTest {
        val steps = buildList {
            for (e in Event.entries) for (p in permissions) for (m in memberships) add(Triple(e, p, m))
        }
        for (osSupported in listOf(true, false)) {
            for (a in steps) for (b in steps) for (c in steps) {
                val log = mutableListOf<String>()
                val os = Recording("os", log)
                val app = Recording("app", log)
                var permission = PermissionStatus.NOT_DETERMINED
                var membership: Boolean? = null
                val arm = UploadArm(
                    resolve = { resolveUploadMechanism(osSupported, permission) },
                    mechanismFor = factory(os, app, osSupported),
                    membershipIncludesUpload = { membership },
                )
                val script = listOf(a, b, c)
                for ((event, p, m) in script) {
                    permission = p
                    membership = m
                    val before = log.size
                    when (event) {
                        Event.PROVISION -> arm.onProvision()
                        Event.PERMISSION_CHANGED -> arm.onPermissionChanged()
                        Event.LEAVE -> arm.onLeave()
                    }
                    assertStartedMatchesResolution(log, before, osSupported, p, script)
                }
                assertExclusive(log, script)
            }
        }
    }

    /** Whatever a transition started must be the mechanism resolution yields for the state it ran under. */
    private fun assertStartedMatchesResolution(
        log: List<String>,
        from: Int,
        osSupported: Boolean,
        permission: PermissionStatus,
        script: List<Triple<Event, PermissionStatus, Boolean?>>,
    ) {
        val started = log.drop(from).filter { it.endsWith(".start") }
        if (started.isEmpty()) return
        val expected = when (resolveUploadMechanism(osSupported, permission)) {
            UploadMechanism.PHOTOKIT -> if (osSupported) "os.start" else "app.start"
            UploadMechanism.URL_SESSION -> "app.start"
            UploadMechanism.IDLE -> null
        }
        assertEquals(
            listOf(expected), started,
            "a transition started something other than what resolution yields" +
                "\nosSupported=$osSupported permission=$permission\nscript=$script\nlog=$log",
        )
    }

    private fun assertExclusive(log: List<String>, script: List<Triple<Event, PermissionStatus, Boolean?>>) {
        val started = mutableSetOf<String>()
        for (verb in log) {
            val (who, what) = verb.split(".")
            if (what == "start") {
                started += who
                assertTrue(
                    started.size <= 1,
                    "BOTH mechanisms started — a second LedgerWriter over one ledger.\nscript=$script\nlog=$log",
                )
            } else {
                started -= who
            }
        }
    }
}
