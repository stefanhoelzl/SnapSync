package app.snapsync.feature.upload

import app.snapsync.model.RegistrationOutcome
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.PendingResource
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.UploadExtensionRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The OS-driven mechanism's **ritual and its repair** — the two things it exists to get right, and neither
 * of which could be tested at all until this class left `:app:ios`.
 *
 * Both defend against damage that is invisible when it happens and terminal afterwards. A bare enable
 * against a stale configuration record fails with `3202`, after which the OS never launches the extension
 * and nothing reports it. A disable wipes every in-flight upload job while leaving their ledger rows
 * `REQUESTED` forever, because no API can enumerate what vanished. Before, the only way to exercise either
 * was to contrive a physical device into the state it defends against.
 */
class OsDrivenUploadMechanismTest {

    /**
     * A registry that records the order of what it was asked, and can be made to refuse.
     *
     * Ordering is the point rather than a convenience: the whole hazard this class documents is a repair
     * racing the re-enable it precedes.
     */
    private class RecordingRegistry(
        private val log: MutableList<String>,
        var refuseWith: Pair<Boolean, RegistrationOutcome>? = null,
        var registered: Boolean = false,
    ) : UploadExtensionRegistry {
        override suspend fun setEnabled(enabled: Boolean): RegistrationOutcome {
            log += if (enabled) "enable" else "disable"
            refuseWith?.takeIf { it.first == enabled }?.let { return it.second }
            val existed = registered
            registered = enabled
            return if (!enabled && !existed) {
                RegistrationOutcome.NothingToDisable
            } else {
                RegistrationOutcome.Applied(enabled)
            }
        }

        override fun isEnabled(): Boolean = registered
    }


    /**
     * A ledger holding only what this class touches: `REQUESTED` rows and the clear that drops them.
     *
     * Local rather than `:adapter:generic:fake`'s honest double, because that module depends on `:domain`
     * and this test lives inside it. Everything unreached is `TODO()` rather than a quiet default — a fake
     * that silently answered a call this class was not supposed to make would hide exactly the regression
     * worth catching.
     */
    private class RequestedRowsLedger : LedgerStore {
        private val rows = mutableMapOf<String, LedgerEntry>()

        fun requested(key: String) {
            rows[key] = LedgerEntry(key = key, assetId = key, state = LedgerState.REQUESTED, attempt = 0, eventId = "event")
        }

        override val changes: Flow<Unit> = emptyFlow()
        override suspend fun aggregates() = LedgerAggregates(
            pending = rows.values.count { it.state == LedgerState.REQUESTED },
            completed = rows.values.count { it.state == LedgerState.COMPLETED },
        )

        override suspend fun clearRequested() {
            rows.values.removeAll { it.state == LedgerState.REQUESTED }
        }

        override suspend fun get(key: String): LedgerEntry? = rows[key]
        override suspend fun put(entry: LedgerEntry) { rows[entry.key] = entry }
        override suspend fun pendingResources(): List<PendingResource> = TODO("not reached by this mechanism")
        override fun markTerminal(key: String, state: LedgerState): Boolean = TODO("not reached by this mechanism")
        override suspend fun uploadedRows(): List<LedgerEntry> = TODO("not reached by this mechanism")
        override suspend fun promoteUploaded(key: String): Boolean = TODO("not reached by this mechanism")
        override suspend fun rowsNeedingJob(limit: Int): List<LedgerEntry> = TODO()

        override suspend fun requestedKeys(): Set<String> = TODO("not reached by this mechanism")
        override suspend fun completedManifestRows(): List<LedgerEntry> = TODO("not reached by this mechanism")
        override suspend fun backfillManifestDetail(entry: LedgerEntry) = TODO("not reached by this mechanism")
        override suspend fun clear() = TODO("not reached by this mechanism")
        override suspend fun resetTo(entries: List<LedgerEntry>) = TODO("not reached by this mechanism")
        override suspend fun markAbsent(assetId: String) = TODO("not reached by this mechanism")
        override suspend fun backfillEventId(eventId: String) = TODO("not reached by this mechanism")
    }

    private class RecordingCursor(private val log: MutableList<String>) : DiscoveryStore {
        var token: ByteArray? = byteArrayOf(1, 2, 3)
        override fun loadToken(): ByteArray? = token
        override fun saveToken(token: ByteArray) {
            this.token = token
        }

        override fun clearToken() {
            log += "clearCursor"
            token = null
        }
    }

    private fun mechanism(
        log: MutableList<String>,
        ledger: RequestedRowsLedger = RequestedRowsLedger(),
        registry: RecordingRegistry = RecordingRegistry(log),
        cursor: RecordingCursor = RecordingCursor(log),
    ) = Triple(OsDrivenUploadMechanism(ledger, registry, cursor), registry, cursor)

    // ── The ritual ────────────────────────────────────────────────────────────────────────────────

    /**
     * `start()` is a **disable→enable toggle**, never a bare enable. The system's record survives app
     * delete/reinstall and reboot, so a record left by a prior or differently-signed build makes a bare
     * enable fail with `3202` — and the leading disable is what removes it.
     */
    @Test
    fun `start disables before it enables`() = runTest {
        val log = mutableListOf<String>()
        val (mechanism, _, _) = mechanism(log)
        mechanism.start()
        assertEquals(listOf("disable", "enable"), log.filter { it == "disable" || it == "enable" })
    }

    /**
     * The ordering the class's own KDoc records as a fixed defect: a fire-and-forget clear raced the
     * immediate re-enable and could delete the *re-enabled* extension's fresh rows. So the repair must be
     * complete before the enable, not merely started before it.
     */
    @Test
    fun `the REQUESTED clear completes before the re-enable`() = runTest {
        val log = mutableListOf<String>()
        val ledger = RequestedRowsLedger()
        ledger.requested("a.jpg")
        val (mechanism, _, _) = mechanism(log, ledger)
        mechanism.start()
        assertTrue(
            log.indexOf("clearCursor") < log.indexOf("enable"),
            "the cursor reset must precede the re-enable, not race it: $log",
        )
        assertEquals(0, ledger.aggregates().pending, "the orphaned REQUESTED row survived the repair")
    }

    /** A stale record is replaced rather than rejected: the disable finds one, the enable re-creates it. */
    @Test
    fun `a stale record is removed and replaced`() = runTest {
        val log = mutableListOf<String>()
        val registry = RecordingRegistry(log, registered = true)
        val (mechanism, _, _) = mechanism(log, registry = registry)
        mechanism.start()
        assertTrue(registry.isEnabled(), "the ritual must leave a live registration behind")
    }

    /**
     * A refused enable is **not** followed by a claim that the extension was registered. This is the defect
     * the change removed: an unconditional `Info` line stood two milliseconds after an `Error` classifying
     * the very same call as failed, in the one capability whose stated failure mode is that "nothing else
     * will report it".
     */
    @Test
    fun `a refused enable leaves the registration absent and claims nothing`() = runTest {
        val log = mutableListOf<String>()
        val registry = RecordingRegistry(
            log,
            refuseWith = true to RegistrationOutcome.Failed(enabling = true, domain = "PHPhotosErrorDomain", code = 3202L),
        )
        val (mechanism, _, _) = mechanism(log, registry = registry)
        mechanism.start()
        assertTrue(!registry.isEnabled(), "a refused enable must not leave the app believing it registered")
    }

    // ── The repair ────────────────────────────────────────────────────────────────────────────────

    /**
     * `stop()` deregisters **and** repairs. Both clears exist because the OS disable wipes in-flight jobs:
     * `clearRequested` drops rows no API could otherwise resurface, and the cursor reset is what makes them
     * re-surface at all — `clearRequested` only makes the keys absent, and a settled cursor would scan
     * incrementally straight past them.
     */
    @Test
    fun `stop deregisters and repairs both halves`() = runTest {
        val log = mutableListOf<String>()
        val ledger = RequestedRowsLedger()
        ledger.requested("a.jpg")
        val (mechanism, registry, cursor) = mechanism(log, ledger)
        mechanism.start() // leave a live registration to stop
        log.clear()
        mechanism.stop()
        assertTrue(!registry.isEnabled(), "stop must deregister")
        assertEquals(null, cursor.loadToken(), "a settled cursor would never re-surface the cleared rows")
        assertEquals(0, ledger.aggregates().pending, "orphaned REQUESTED rows must not survive the disable")
    }

    /**
     * `deregister()` is the narrow verb the tier switch takes: deregister and **nothing else**. The repair
     * is ledger-wide and the cursor is shared, so applying it when handing off to the app-driven mechanism
     * would delete rows belonging to the mechanism about to start, and force it into a re-enumeration it
     * does not need.
     */
    @Test
    fun `deregister repairs nothing`() = runTest {
        val log = mutableListOf<String>()
        val ledger = RequestedRowsLedger()
        val (mechanism, registry, cursor) = mechanism(log, ledger)
        // Seeded AFTER the ritual, deliberately: `start()` runs `stop()` first, so a row planted before it
        // would be cleared by the repair under test rather than by the verb under test.
        mechanism.start()
        ledger.requested("a.jpg")
        cursor.saveToken(byteArrayOf(9))
        mechanism.deregister()
        assertTrue(!registry.isEnabled(), "deregister must still deregister")
        assertEquals(1, ledger.aggregates().pending, "deregister must not clear rows the next mechanism owns")
        assertTrue(cursor.loadToken() != null, "deregister must not force a full re-enumeration")
    }

    /** Every app-side kick is declined: the OS owns scheduling on this tier, and there is nothing to top up. */
    @Test
    fun `every app-side trigger is declined`() = runTest {
        val log = mutableListOf<String>()
        val (mechanism, _, _) = mechanism(log)
        log.clear()
        mechanism.onForeground()
        mechanism.onSilentPush("event")
        mechanism.onBackgroundTask()
        mechanism.onSelectionChanged()
        assertEquals(emptyList(), log, "an app-side trigger must touch neither the registration nor the cursor")
    }
}
