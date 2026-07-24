package app.snapsync.compose

import app.snapsync.model.AssetFacts
import app.snapsync.model.Candidate
import app.snapsync.model.CaptureDate
import app.snapsync.model.PermissionStatus
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCutoff
import app.snapsync.ports.CandidateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

/**
 * The grant decides **where candidates come from**, and no consumer branches on it
 * (capability `limited-photo-access`, D10: *"the mode difference is one source impl, not a branch in the
 * policy or its consumers"*).
 *
 * That principle was already true of the policy and false of the consumers: the status total had two
 * entry points (`refresh` / `refreshFrom`) and the join preview a `when (permission)`, so each restated
 * the distinction — and it is the restatement, not the reading, that lets two paths drift. This pins that
 * the restatement is gone, and that the `LIMITED` path still never walks.
 */
class PermissionAwareCandidateSourceTest {

    private val policy = SelectionPolicy.from(
        includesUpload = true,
        cutoff = captureCutoff("2026-01-01T00:00:00Z"),
        ceiling = null,
    )

    /** Counts walks, because "counted from the snapshot" and "did not look" are different claims. */
    private class RecordingWalk(private val ids: List<String>) : CandidateSource {
        var walks = 0
        override suspend fun candidates(policy: SelectionPolicy): List<Candidate> {
            walks++
            return ids.map { id ->
                object : Candidate {
                    override val facts = AssetFacts(id, CaptureDate("2026-06-01T00:00:00Z"))
                    override suspend fun resources(): List<Resource> = emptyList()
                }
            }
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
        walk: RecordingWalk = RecordingWalk(listOf("W")),
        snapshot: List<Resource>? = null,
    ) = walk to PermissionAwareCandidateSource(
        permission = MutableStateFlow(permission),
        walk = walk,
        selection = MutableStateFlow(snapshot),
    )

    @Test
    fun `GRANTED walks the library`() = runTest {
        val (walk, source) = source(PermissionStatus.GRANTED)
        assertEquals(listOf("W"), source.candidates(policy).map { it.facts.assetId })
        assertEquals(1, walk.walks)
    }

    @Test
    fun `LIMITED reads the snapshot and never walks`() = runTest {
        // The load-bearing half. An autonomous library read under a partial grant queues iOS's
        // limited-access alert into an app-killing storm that survives process death — so a source that
        // merely *happened* to return the right ids while also walking would be a latent app-killer.
        val (walk, source) = source(PermissionStatus.LIMITED, snapshot = snapshotOf("S1", "S2"))
        assertEquals(listOf("S1", "S2"), source.candidates(policy).map { it.facts.assetId })
        assertEquals(0, walk.walks, "no autonomous library read under a partial grant")
    }

    @Test
    fun `LIMITED before the first snapshot yields nothing rather than walking`() = runTest {
        // The honest state between a grant turning partial and the first observer emission: there is
        // nothing selected that we know of, and we may not go looking for it.
        val (walk, source) = source(PermissionStatus.LIMITED, snapshot = null)
        assertTrue(source.candidates(policy).isEmpty())
        assertEquals(0, walk.walks)
    }

    @Test
    fun `an unusable grant yields nothing and never walks`() = runTest {
        for (status in listOf(PermissionStatus.DENIED, PermissionStatus.NOT_DETERMINED)) {
            val (walk, source) = source(status, snapshot = snapshotOf("S"))
            assertTrue(source.candidates(policy).isEmpty(), "$status yields no candidates")
            assertEquals(0, walk.walks, "$status never walks")
        }
    }

    @Test
    fun `the snapshot's candidates already carry their resources`() = runTest {
        // The snapshot arrives already read, WITH resources, from the sanctioned read points. Asking a
        // candidate for them must therefore issue nothing: a deferred read here would have to reach the
        // assets again later, off-flow, which is the measured storm (capability `limited-photo-access`).
        val (_, source) = source(PermissionStatus.LIMITED, snapshot = snapshotOf("S1"))
        val resources = source.candidates(policy).single().resources()
        assertEquals(listOf("S1-primary.jpg"), resources.map { it.filename })
    }
}
