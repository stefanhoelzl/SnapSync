package app.snapsync.feature.membership

import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.LedgerState
import app.snapsync.feature.support.LEDGER_EVENT
import app.snapsync.feature.support.OwnLibrary
import app.snapsync.feature.support.PublishedManifests
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.captureRange
import app.snapsync.mock.inMemoryDatabases
import app.snapsync.ports.DbOpen
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * The device manifest a pass publishes from its upload rows (capability `photo-sharing`): it DECLARES every row the
 * membership's policy admits, whatever its upload state, at the version the pass read — and a publish that fails
 * throws, for the pass's step to contain.
 */
class ManifestPublishTest {

    /** A member sharing from 2026-06-20 for 30 days — every [IN_RANGE] row lies inside it. */
    private suspend fun policy(): SelectionPolicy = OwnLibrary().contribution(
        captureRange("2026-06-20T00:00:00Z"),
    ).policy

    private val manifests = PublishedManifests()

    @Test
    fun `every admitted row is declared whatever its upload state, and one before the range is not`() = runTest {
        manifests.ledger.put(LEDGER_EVENT, "A-primary.heic", LedgerState.DISCOVERED, IN_RANGE)
        manifests.ledger.put(LEDGER_EVENT, "B-primary.heic", LedgerState.REQUESTED, IN_RANGE)
        manifests.ledger.put(LEDGER_EVENT, "C-primary.heic", LedgerState.COMPLETED, IN_RANGE)
        manifests.ledger.put(LEDGER_EVENT, "OLD-primary.heic", LedgerState.COMPLETED, "2025-01-01T00:00:00Z")

        val manifest = manifests.of(policy())

        assertEquals(listOf("A", "B", "C").map(::testAssetId), manifest?.assets?.map { it.assetId })
    }

    @Test
    fun `a bare row is not declared until a walk dates it`() = runTest {
        // A row the join-time load seeded from a stored-file listing has no capture date until a walk fills it. The
        // POLICY excludes it — an undated photo is in no range — so the one admission decides this like every other.
        manifests.ledger.put(LEDGER_EVENT, "A-primary.heic", LedgerState.COMPLETED, IN_RANGE)
        manifests.ledger.put(LEDGER_EVENT, "Z-primary.heic", LedgerState.COMPLETED)

        assertEquals(listOf(testAssetId("A")), manifests.of(policy())?.assets?.map { it.assetId })
    }

    @Test
    fun `the publish carries the version the pass read`() = runTest {
        manifests.ledger.put(LEDGER_EVENT, "A-primary.heic", LedgerState.COMPLETED, IN_RANGE)
        manifests.publish.publish(testEventId(LEDGER_EVENT), policy(), version = 42L)
        assertEquals(42L, manifests.published.single().version)
    }

    @Test
    fun `an event with no admitted rows publishes an empty manifest`() = runTest {
        assertEquals(emptyList(), manifests.of(policy())?.assets)
    }

    @Test
    fun `a failing publish throws for its step to contain, and rows that cannot be read publish nothing`() = runTest {
        manifests.ledger.put(LEDGER_EVENT, "A-primary.heic", LedgerState.COMPLETED, IN_RANGE)
        manifests.failing = true
        assertFailsWith<IllegalStateException> { manifests.of(policy()) }

        val locked = PublishedManifests(TestLedger(inMemoryDatabases(mapOf(LEDGER_DB_NAME to DbOpen.Failed("locked")))))
        assertNull(locked.of(policy()))
    }

    private companion object {
        const val IN_RANGE = "2026-06-27T10:00:00Z"
    }
}
