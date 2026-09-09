package app.snapsync.feature.upload

import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionRule
import app.snapsync.model.UploadMechanism
import app.snapsync.model.captureCutoff
import app.snapsync.ports.Clock
import app.snapsync.ports.DeviceFilesSource
import app.snapsync.ports.DeviceListingShapeException
import app.snapsync.ports.JoinedEventMarker
import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * The read-only foreground check (capability `event-rejoin-reconciliation`).
 *
 * Two of these tests assert **silence**, and they are the ones that earn the fault the right to ride at
 * `Error` at all: collected residue and a fetch that could not answer are the two states that look
 * exactly like data loss from the ledger's side and are not.
 */
class UploadLedgerAuditTest {

    private val deviceId = "11111111-1111-4111-8111-111111111111"
    private val inWindow = "2026-05-01T00:00:00Z"
    private val beforeWindow = "2025-01-01T00:00:00Z"

    /** The membership's policy: a capture-date floor, which is what the sweep's residue falls below. */
    private val policy = SelectionPolicy(listOf(SelectionRule.CaptureAfter(captureCutoff("2026-04-01T00:00:00Z"))))

    private class FakeFiles(var result: Result<List<String>> = Result.success(emptyList())) : DeviceFilesSource {
        var calls = 0
        /** Never returns, so `withTimeoutOrNull` is the only thing that ends the call. */
        var hang = false
        override suspend fun list(deviceId: String): Result<List<String>> {
            calls++
            if (hang) delay(Long.MAX_VALUE)
            return result
        }
    }

    private class FakeMarker(private var value: String? = null) : JoinedEventMarker {
        override fun read(): String? = value
        override fun set(eventId: String) { value = eventId }
        override fun clear() { value = null }
    }

    private class MovableClock(var millis: Long = 0L) : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(millis)
    }

    /** Records what was logged, so a severity can be asserted rather than a message. */
    private class Recorder : LogWriter() {
        val lines = mutableListOf<Pair<Severity, String>>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            lines += severity to message
        }
        fun logger() = Logger(loggerConfigInit(this), "UploadLedgerAuditTest")
        fun errors() = lines.filter { it.first == Severity.Error }.map { it.second }
    }

    private fun row(
        key: String,
        assetId: String = key.substringBefore('-'),
        state: LedgerState = LedgerState.COMPLETED,
        creationDate: String = inWindow,
        absent: Boolean = false,
    ) = LedgerEntry(
        key = key,
        assetId = assetId,
        state = state,
        attempt = 0,
        eventId = "E1",
        creationDate = creationDate,
        absent = absent,
    )

    private fun audit(
        files: DeviceFilesSource,
        ledger: InMemoryLedgerStore,
        marker: JoinedEventMarker = FakeMarker("E1"),
        clock: Clock = MovableClock(),
        log: Logger = Logger.withTag("UploadLedgerAuditTest"),
        mechanism: UploadMechanism = UploadMechanism.PHOTOKIT,
    ) = UploadLedgerAudit(
        files = files,
        ledger = ledger,
        marker = marker,
        deviceId = { deviceId },
        policy = { policy },
        mechanism = { mechanism },
        clock = clock,
        minInterval = 30.minutes,
        log = log,
    )

    private suspend fun ledgerWith(vararg rows: LedgerEntry) =
        InMemoryLedgerStore().also { store -> rows.forEach { store.put(it) } }

    @Test
    fun `a believed-landed row the listing lacks is reported at Error with counts and the mechanism`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"), row("B-primary.heic"))
        val files = FakeFiles(Result.success(listOf("A-primary.heic")))
        val recorder = Recorder()

        val findings = audit(files, ledger, log = recorder.logger())
            .check("E1")

        assertEquals(1, findings?.missing)
        assertEquals(2, findings?.believed)
        assertEquals(1, findings?.listed)
        val error = recorder.errors().single()
        assertTrue("1 of 2" in error, error)
        assertTrue("photokit" in error, error) // the tier the structural hypothesis names
    }

    @Test
    fun `the check writes nothing even when it finds a disagreement`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"), row("B-primary.heic"))
        val before = ledger.manifestRows().associateBy { it.key }
        val marker = FakeMarker("E1")

        audit(FakeFiles(Result.success(emptyList())), ledger, marker).check("E1")

        assertEquals(before, ledger.manifestRows().associateBy { it.key })
        assertEquals("E1", marker.read()) // the marker is untouched
    }

    @Test
    fun `an UPLOADED row is believed landed - the tier whose job carries no HTTP status`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic", state = LedgerState.UPLOADED))
        val recorder = Recorder()

        val findings = audit(FakeFiles(Result.success(emptyList())), ledger, log = recorder.logger()).check("E1")

        assertEquals(1, findings?.missing)
        assertEquals(1, recorder.errors().size)
    }

    @Test
    fun `a row that is not believed landed is outside the comparison set`() = runTest {
        val ledger = ledgerWith(
            row("A-primary.heic", state = LedgerState.DISCOVERED),
            row("B-primary.heic", state = LedgerState.REQUESTED),
            row("C-primary.heic", state = LedgerState.FAILED),
        )
        val recorder = Recorder()

        val findings = audit(FakeFiles(Result.success(emptyList())), ledger, log = recorder.logger()).check("E1")

        assertEquals(0, findings?.believed)
        assertEquals(0, findings?.missing)
        assertTrue(recorder.errors().isEmpty())
    }

    @Test
    fun `SILENCE - collected residue the policy no longer admits is not reported`() = runTest {
        // The sweep collects UNREFERENCED bytes; a row the current policy excludes is not declared by the
        // manifest, so this is ordinary cleanup, not data loss. Without the policy filter it would fire.
        val ledger = ledgerWith(
            row("OLD-primary.heic", creationDate = beforeWindow),
            row("A-primary.heic"),
        )
        val recorder = Recorder()

        val findings = audit(FakeFiles(Result.success(listOf("A-primary.heic"))), ledger, log = recorder.logger())
            .check("E1")

        assertEquals(0, findings?.missing)
        assertEquals(1, findings?.believed) // only the admitted row was compared
        assertTrue(recorder.errors().isEmpty())
    }

    @Test
    fun `SILENCE - a failed fetch reports nothing and does not consume the floor`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"))
        val files = FakeFiles(Result.failure(RuntimeException("offline")))
        val recorder = Recorder()
        val subject = audit(files, ledger, log = recorder.logger())

        assertNull(subject.check("E1"))
        assertTrue(recorder.errors().isEmpty())

        // The next foreground retries: nothing was learned, so nothing was spent.
        assertNull(subject.check("E1"))
        assertEquals(2, files.calls)
    }

    @Test
    fun `SILENCE - a timed-out fetch reports nothing`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"))
        val recorder = Recorder()

        assertNull(audit(FakeFiles().also { it.hang = true }, ledger, log = recorder.logger()).check("E1"))

        assertTrue(recorder.errors().isEmpty())
    }

    @Test
    fun `a listing this build cannot read is reported but never as the fault`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"))
        val files = FakeFiles(Result.failure(DeviceListingShapeException("v1 shape")))
        val recorder = Recorder()

        assertNull(audit(files, ledger, log = recorder.logger()).check("E1"))

        assertTrue("not understood" in recorder.errors().single())
    }

    @Test
    fun `no configured event skips silently and asks the backend nothing`() = runTest {
        val files = FakeFiles()
        val recorder = Recorder()

        assertNull(audit(files, ledgerWith(row("A-primary.heic")), log = recorder.logger()).check(null))

        assertEquals(0, files.calls)
        assertTrue(recorder.lines.isEmpty())
    }

    @Test
    fun `a pending rejoin - a marker mismatch - skips silently and asks the backend nothing`() = runTest {
        val files = FakeFiles()
        val recorder = Recorder()

        val findings = audit(files, ledgerWith(row("A-primary.heic")), FakeMarker("E0"), log = recorder.logger())
            .check("E1")

        assertNull(findings)
        assertEquals(0, files.calls)
        assertTrue(recorder.lines.isEmpty())
    }

    @Test
    fun `a listed resource with no ledger row is counted and not faulted`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"))
        val recorder = Recorder()

        val findings = audit(
            FakeFiles(Result.success(listOf("A-primary.heic", "Z-primary.heic"))),
            ledger,
            log = recorder.logger(),
        ).check("E1")

        assertEquals(1, findings?.unlisted)
        assertEquals(0, findings?.missing)
        assertTrue(recorder.errors().isEmpty())
    }

    @Test
    fun `a departed asset row is not counted as unknown - its bytes are still listed`() = runTest {
        // `manifestRows` excludes absent rows, so diffing against it would count every deleted photo
        // here. The point read behind the diff is what keeps this direction meaningful.
        val ledger = ledgerWith(row("A-primary.heic"), row("GONE-primary.heic", absent = true))

        val findings = audit(
            FakeFiles(Result.success(listOf("A-primary.heic", "GONE-primary.heic"))),
            ledger,
        ).check("E1")

        assertEquals(0, findings?.unlisted)
        assertEquals(0, findings?.missing) // an absent row is outside the comparison set too
    }

    @Test
    fun `the floor stops a second foreground refetching and lifts once the interval passes`() = runTest {
        val ledger = ledgerWith(row("A-primary.heic"))
        val files = FakeFiles(Result.success(listOf("A-primary.heic")))
        val clock = MovableClock(millis = 1_000L)
        val subject = audit(files, ledger, clock = clock)

        subject.check("E1")
        assertEquals(1, files.calls)

        assertNull(subject.check("E1")) // within the floor
        assertEquals(1, files.calls)

        clock.millis += 30.minutes.inWholeMilliseconds
        subject.check("E1")
        assertEquals(2, files.calls)
    }
}
