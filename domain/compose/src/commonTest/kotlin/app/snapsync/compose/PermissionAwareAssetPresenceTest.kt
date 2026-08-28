package app.snapsync.compose

import app.snapsync.model.AssetPresence
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.ports.ImportedAssetPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * The grant decides **which source may answer**, and only a source that sees the whole library may say
 * [AssetPresence.ABSENT] (capability `photo-download`; capability `limited-photo-access`).
 *
 * The distinction under test is not *can we look* but **is a miss trustworthy**. A miss reported as
 * absence clears a live marker, imports a second copy, and orphans the first — the defect the download
 * capability exists to prevent. Its sibling [PermissionAwareCandidateSource] has carried a test since it
 * landed; this one did not, and the two decide the same question about two different reads.
 */
class PermissionAwareAssetPresenceTest {

    /** Counts queries, because "answered from the snapshot" and "did not look" are different claims. */
    private class RecordingLibrary(private val verdicts: Map<String, AssetPresence>) : ImportedAssetPresence {
        var queries = 0
        override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
            queries++
            return localIds.associateWith { verdicts[it] ?: AssetPresence.ABSENT }
        }
    }

    private fun snapshotOf(vararg ids: String) = ids.map {
        Resource(
            "$it-primary.jpg",
            it,
            "image/jpeg",
            mapOf(RESOURCE_META_CREATION_DATE to "2026-06-01T00:00:00Z"),
            Unit,
        )
    }

    private fun source(
        permission: PermissionStatus,
        library: RecordingLibrary = RecordingLibrary(emptyMap()),
        snapshot: List<Resource>? = null,
    ) = library to PermissionAwareAssetPresence(
        permission = MutableStateFlow(permission),
        library = library,
        selection = MutableStateFlow(snapshot),
    )

    @Test
    fun `GRANTED asks the library and both verdicts stand`() = runTest {
        val (library, source) = source(
            PermissionStatus.GRANTED,
            RecordingLibrary(mapOf("HERE" to AssetPresence.PRESENT, "GONE" to AssetPresence.ABSENT)),
        )
        assertEquals(
            mapOf("HERE" to AssetPresence.PRESENT, "GONE" to AssetPresence.ABSENT),
            source.presence(setOf("HERE", "GONE")),
        )
        assertEquals(1, library.queries, "a full grant is the only view that may be asked")
    }

    @Test
    fun `LIMITED answers from the snapshot and never queries the library`() = runTest {
        val (library, source) = source(PermissionStatus.LIMITED, snapshot = snapshotOf("S1"))
        assertEquals(mapOf("S1" to AssetPresence.PRESENT), source.presence(setOf("S1")))
        assertEquals(0, library.queries, "no library read under a partial grant")
    }

    @Test
    fun `a miss under LIMITED is UNKNOWN and never ABSENT`() = runTest {
        // THE LOAD-BEARING ASSERTION. An app-created asset joins the platform selection at creation time
        // only, so one created under a full grant is real but invisible after a downgrade (measured,
        // capability `limited-photo-access`). Reading that miss as ABSENT clears a live marker and
        // re-imports a photo the device already holds.
        val (_, source) = source(PermissionStatus.LIMITED, snapshot = snapshotOf("S1"))
        assertEquals(mapOf("MISSING" to AssetPresence.UNKNOWN), source.presence(setOf("MISSING")))
    }

    @Test
    fun `LIMITED before the first snapshot answers UNKNOWN for everything`() = runTest {
        // The honest gap between a grant turning partial and the first observer emission: nothing is
        // known to be selected, and we may not go looking. Every row simply waits.
        val (library, source) = source(PermissionStatus.LIMITED, snapshot = null)
        val verdicts = source.presence(setOf("A", "B"))
        assertEquals(mapOf("A" to AssetPresence.UNKNOWN, "B" to AssetPresence.UNKNOWN), verdicts)
        assertEquals(0, library.queries)
    }

    @Test
    fun `an unusable grant answers UNKNOWN and never queries the library`() = runTest {
        for (status in listOf(PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED)) {
            // A snapshot is present and still may not be trusted: without a usable grant a query returns
            // nothing for assets that exist, and an import cannot succeed anyway.
            val (library, source) = source(status, snapshot = snapshotOf("S1"))
            assertEquals(mapOf("S1" to AssetPresence.UNKNOWN), source.presence(setOf("S1")), "$status")
            assertEquals(0, library.queries, "$status never queries")
        }
    }

    @Test
    fun `every id asked about comes back with a verdict`() = runTest {
        // The port's contract: a missing entry and UNKNOWN mean the same thing to callers, and returning
        // the entry is the honest form. Asserted on the two arms that build the map themselves.
        for (status in listOf(PermissionStatus.LIMITED, PermissionStatus.DENIED)) {
            val (_, source) = source(status, snapshot = snapshotOf("S1"))
            val asked = setOf("S1", "S2", "S3")
            assertEquals(asked, source.presence(asked).keys, "$status answers every id it was asked")
            assertTrue(source.presence(asked).values.all { it != AssetPresence.ABSENT }, "$status never ABSENT")
        }
    }
}
