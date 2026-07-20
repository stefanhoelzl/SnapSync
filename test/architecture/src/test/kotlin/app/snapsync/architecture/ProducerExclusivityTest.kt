package app.snapsync.architecture

import app.snapsync.feature.upload.ComposedProducers
import app.snapsync.feature.upload.UploadArm
import app.snapsync.feature.upload.UploadProducer
import app.snapsync.model.PermissionStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The exactly-one-started invariant (capability `architecture-guards`, "The upload producers are
 * never both started"; the behavioral half of `upload-lifecycle`'s "Exactly one producer started per
 * process").
 *
 * Two upload producers are COMPOSED on iOS ≥26.1 — the OS-driven extension (a `LedgerWriter` in the
 * extension process) and the app-driven pump (a `LedgerWriter` in the app process) — because the
 * mechanism the current permission requires is a **runtime** fact (the OS never invokes the extension
 * under `.limited`; measured, `ios-photokit-upload`), which no once-per-process construction decision
 * can express. The previous guarantee was structural (only one producer constructed); this guard is
 * its replacement: **no sequence of lifecycle transitions may ever have both started**, and every
 * mechanism switch stops the outgoing producer before starting the incoming one — the OS-driven
 * producer's `stop()` is what deregisters the extension, i.e. what actually prevents a second writer
 * over the one App-Group ledger (`sync-ledger`).
 *
 * The guard drives the REAL `UploadArm` (the single component allowed to start/stop producers) over
 * recording fakes, through every transition row of the lifecycle table under every permission, with
 * flips in both directions interleaved — exhaustively over a bounded script alphabet, not
 * hand-picked sequences.
 */
class ProducerExclusivityTest {

    private class Recording(private val name: String, private val log: MutableList<String>) : UploadProducer {
        override suspend fun start() { log += "$name.start" }
        override suspend fun stop() { log += "$name.stop" }
    }

    /** Every event the arm's callers can fire, each parameterized by the state it is fired under. */
    private enum class Event { PROVISION, PERMISSION_CHANGED, LEAVE }

    private val permissions = PermissionStatus.entries
    private val memberships = listOf(true, false, null)

    /**
     * Drive every 3-event script over (event × permission × membership) — ~19k sequences — asserting
     * after every single verb that at most one producer is started, and that a start is always
     * preceded by the other producer's stop within the same transition (stop-then-start).
     */
    @Test
    fun `no transition sequence ever has both producers started`() = runTest {
        val steps = buildList {
            for (e in Event.entries) for (p in permissions) for (m in memberships) add(Triple(e, p, m))
        }
        // 3-deep exhaustive scripts over the full alphabet.
        for (a in steps) for (b in steps) for (c in steps) {
            val log = mutableListOf<String>()
            var permission = PermissionStatus.NOT_DETERMINED
            var membership: Boolean? = null
            val arm = UploadArm(
                ComposedProducers(
                    osDriven = Recording("os", log),
                    appDriven = Recording("app", log),
                ),
                permission = { permission },
                membershipIncludesUpload = { membership },
            )
            for ((event, p, m) in listOf(a, b, c)) {
                permission = p
                membership = m
                when (event) {
                    Event.PROVISION -> arm.onProvision()
                    Event.PERMISSION_CHANGED -> arm.onPermissionChanged()
                    Event.LEAVE -> arm.onLeave()
                }
            }
            assertExclusive(log, script = listOf(a, b, c))
        }
    }

    private fun assertExclusive(log: List<String>, script: List<Triple<Event, PermissionStatus, Boolean?>>) {
        val started = mutableSetOf<String>()
        for (verb in log) {
            val (who, what) = verb.split(".")
            if (what == "start") {
                started += who
                assertTrue(
                    started.size <= 1,
                    "BOTH producers started — a second LedgerWriter over one ledger.\nscript=$script\nlog=$log",
                )
            } else {
                started -= who
            }
        }
    }
}
