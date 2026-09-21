package app.snapsync.feature.upload

import app.snapsync.model.RegistrationOutcome
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.PendingResource
import app.snapsync.model.isDone
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
     * A ledger holding only what this class touches: `REQUESTED` rows and the demote that repairs them. The
     * demote is recorded into the shared [log], so its order against the registration calls is asserted.
     *
     * Local rather than `:adapter:generic:fake`'s honest double, because that module depends on `:domain`
     * and this test lives inside it. Everything unreached is `TODO()` rather than a quiet default — a fake
     * that silently answered a call this class was not supposed to make would hide exactly the regression
     * worth catching.
     */
    private class RequestedRowsLedger(private val log: MutableList<String>) : UnreachedLedgerStore() {
        private val rows = mutableMapOf<String, LedgerEntry>()

        fun requested(key: String) {
            rows[key] = LedgerEntry(key = key, assetId = key, state = LedgerState.REQUESTED, attempt = 0, eventId = "event")
        }

        override val changes: Flow<Unit> = emptyFlow()
        override suspend fun aggregates() = LedgerAggregates(
            pending = rows.values.count { it.state == LedgerState.REQUESTED },
            completed = rows.values.count { it.state == LedgerState.COMPLETED },
        )

        fun stateOf(key: String): LedgerState? = rows[key]?.state

        override suspend fun demoteRequested() {
            log += "demote"
            for (row in rows.entries) {
                if (row.value.state == LedgerState.REQUESTED) row.setValue(row.value.withState(LedgerState.FAILED))
            }
        }

        override suspend fun get(key: String): LedgerEntry? = rows[key]
        override suspend fun recordUnlessSettled(entry: LedgerEntry): Boolean {
            if (rows[entry.key]?.state?.isDone == true) return false
            rows[entry.key] = entry
            return true
        }
    }

    /**
     * Every member this mechanism must never reach, refusing loudly.
     *
     * Split out of [RequestedRowsLedger] rather than defaulted into it: the discipline above — no quiet
     * answers — is the point, and it is cheaper to keep when the refusals live in one place that a
     * subclass overrides only what it genuinely uses. A port that grows then costs one line here instead
     * of one line in every double.
     */
    private abstract class UnreachedLedgerStore : LedgerStore {
        override suspend fun entryForDestination(destinationPath: String): LedgerEntry? =
            TODO("not reached by this mechanism")
        override suspend fun pendingResources(): List<PendingResource> = TODO("not reached by this mechanism")
        override fun markTerminal(key: String, outcome: TerminalOutcome): Boolean = TODO("not reached by this mechanism")
        override suspend fun rowsNeedingJob(): List<LedgerEntry> = TODO()
        override suspend fun requestedKeys(): Set<String> = TODO("not reached by this mechanism")
        override suspend fun manifestRows(): List<LedgerEntry> = TODO("not reached by this mechanism")
        override suspend fun backfillManifestDetail(entry: LedgerEntry) = TODO("not reached by this mechanism")
        override suspend fun clear() = TODO("not reached by this mechanism")
        override suspend fun resetTo(entries: List<LedgerEntry>) = TODO("not reached by this mechanism")
        override suspend fun deleteKeys(keys: Collection<String>) = TODO("not reached by this mechanism")
        override suspend fun clearAbsenceMarks() = TODO("not reached by this mechanism")
        override suspend fun markAbsent(assetId: String) = TODO("not reached by this mechanism")
        override suspend fun markPresent(assetIds: Collection<String>) = TODO("not reached by this mechanism")
        override suspend fun backfillEventId(eventId: String) = TODO("not reached by this mechanism")
    }

    private fun mechanism(
        log: MutableList<String>,
        ledger: RequestedRowsLedger = RequestedRowsLedger(log),
        registry: RecordingRegistry = RecordingRegistry(log),
    ) = OsDrivenUploadMechanism(ledger, registry) to registry

    // ── The ritual ────────────────────────────────────────────────────────────────────────────────

    /**
     * `start()` is a **disable→enable toggle**, never a bare enable. The system's record survives app
     * delete/reinstall and reboot, so a record left by a prior or differently-signed build makes a bare
     * enable fail with `3202` — and the leading disable is what removes it.
     */
    @Test
    fun `start disables before it enables`() = runTest {
        val log = mutableListOf<String>()
        val (mechanism, _) = mechanism(log)
        mechanism.start()
        assertEquals(listOf("disable", "enable"), log.filter { it == "disable" || it == "enable" })
    }

    /**
     * The ordering the class's own KDoc records as a fixed defect: a fire-and-forget repair raced the
     * immediate re-enable and could reach the *re-enabled* extension's fresh rows. So the repair must sit
     * between the disable that orphans the rows and the enable that could record new ones, and be complete
     * before the enable, not merely started before it.
     */
    @Test
    fun `the REQUESTED demote runs between the disable and the re-enable`() = runTest {
        val log = mutableListOf<String>()
        val ledger = RequestedRowsLedger(log)
        ledger.requested("a.jpg")
        val (mechanism, _) = mechanism(log, ledger)
        mechanism.start()
        assertEquals(listOf("disable", "demote", "enable"), log, "the repair must sit inside the toggle, in order")
        assertEquals(LedgerState.FAILED, ledger.stateOf("a.jpg"), "the orphaned row must be demoted, not dropped")
    }

    /** A stale record is replaced rather than rejected: the disable finds one, the enable re-creates it. */
    @Test
    fun `a stale record is removed and replaced`() = runTest {
        val log = mutableListOf<String>()
        val registry = RecordingRegistry(log, registered = true)
        val (mechanism, _) = mechanism(log, registry = registry)
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
        val (mechanism, _) = mechanism(log, registry = registry)
        mechanism.start()
        assertTrue(!registry.isEnabled(), "a refused enable must not leave the app believing it registered")
    }

    // ── The repair belongs to the start ───────────────────────────────────────────────────────────

    /**
     * `stop()` is the disable **and nothing else** — on a leave and on a relinquish to the app-driven
     * mechanism alike. The rows the disable orphans are repaired by whichever mechanism starts next, the one
     * moment no other transfer can be carrying them; a repair here would reach rows a starting app-driven
     * mechanism owns. There is no narrower hand-off verb any more because there is nothing left to narrow.
     */
    @Test
    fun `stop deregisters and repairs nothing`() = runTest {
        val log = mutableListOf<String>()
        val ledger = RequestedRowsLedger(log)
        val (mechanism, registry) = mechanism(log, ledger)
        // Seeded AFTER the ritual, deliberately: `start()` repairs, so a row planted before it would be
        // demoted by the verb that is not under test.
        mechanism.start()
        ledger.requested("a.jpg")
        log.clear()
        mechanism.stop()
        assertTrue(!registry.isEnabled(), "stop must deregister")
        assertEquals(listOf("disable"), log, "stop must touch nothing but the registration")
        assertEquals(LedgerState.REQUESTED, ledger.stateOf("a.jpg"), "stop must leave the repair to the next start")
    }

    /** Every app-side kick is declined: the OS owns scheduling on this tier, and there is nothing to top up. */
    @Test
    fun `every app-side trigger is declined`() = runTest {
        val log = mutableListOf<String>()
        val (mechanism, _) = mechanism(log)
        log.clear()
        mechanism.onForeground()
        mechanism.onSilentPush("event")
        mechanism.onBackgroundTask()
        mechanism.onSelectionChanged()
        assertEquals(emptyList(), log, "an app-side trigger must touch neither the registration nor the ledger")
    }
}
