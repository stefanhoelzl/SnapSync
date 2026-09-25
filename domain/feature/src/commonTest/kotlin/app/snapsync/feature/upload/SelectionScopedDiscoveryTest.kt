package app.snapsync.feature.upload

import app.snapsync.model.captureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.ports.Discovery
import app.snapsync.ports.UploadDiscovery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The read-discipline gate (capability `photo-access`): under a [SelectionScope.Scoped],
 * discovery consumes the snapshot with NO platform read and is authoritative (the selection is the gallery,
 * so de-selecting is deleting); under [SelectionScope.Unrestricted] it delegates unchanged; under
 * [SelectionScope.Unread] it refuses, because every answer it could give would delete rows.
 */
/** An admitting policy over [cutoff] — the shape the cycle hands the discovery. */
private suspend fun admitting(cutoff: String): SelectionPolicy =
    SelectionPolicy(selectionRulesFor(includesUpload = true, cutoff = captureCutoff(cutoff), ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }))

class SelectionScopedDiscoveryTest {

    private class RecordingDelegate : UploadDiscovery {
        var discoverCalls = 0
        var resolveCalls = 0
        override suspend fun discover(policy: SelectionPolicy): Discovery {
            discoverCalls++
            return Discovery(emptyList(), fullEnumeration = true)
        }
        override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
            resolveCalls++
            return emptyList()
        }
    }

    private fun resource(name: String) =
        Resource(filename = name, assetId = name, contentType = "image/jpeg", metadata = emptyMap(), data = Unit)

    @Test
    fun unrestricted_delegates_to_the_platform_walk() = runTest {
        val delegate = RecordingDelegate()
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Unrestricted }

        val discovery = scoped.discover(admitting("2026-01-01T00:00:00Z"))

        assertEquals(1, delegate.discoverCalls)
        assertTrue(discovery.fullEnumeration, "the platform walk's own answer crosses unchanged")
    }

    @Test
    fun scoped_returns_the_snapshot_without_any_platform_read() = runTest {
        val delegate = RecordingDelegate()
        val snapshot = listOf(resource("A"), resource("B"))
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Scoped(snapshot) }

        val discovery = scoped.discover(admitting("2026-01-01T00:00:00Z"))

        assertEquals(0, delegate.discoverCalls)
        assertEquals(
            snapshot.map { it.assetId },
            discovery.candidates.map { it.facts.assetId },
            "the snapshot crosses verbatim — wrapped as HELD candidates, nothing re-read",
        )
        // Under a partial grant the selection IS the gallery: a photo it no longer carries has left, so a read
        // snapshot drives ledger deletion exactly as a library walk does (`changes/selection-is-the-walk`, D1).
        assertTrue(discovery.fullEnumeration)
    }

    @Test
    fun a_read_empty_snapshot_is_authoritative() = runTest {
        // Everything de-selected: a real answer, read from the platform — every photo left the selection.
        val delegate = RecordingDelegate()
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Scoped(emptyList()) }

        val discovery = scoped.discover(admitting("2026-01-01T00:00:00Z"))

        assertEquals(0, delegate.discoverCalls)
        assertTrue(discovery.candidates.isEmpty())
        assertTrue(discovery.fullEnumeration)
    }

    @Test
    fun an_unread_scope_refuses_both_reads_and_never_answers_empty() = runTest {
        // Not yet read is not empty. An empty discovery would be authoritative and delete every row; an empty
        // resolution would delete every row that needs a job. Refusing fails one cycle; answering loses rows.
        val delegate = RecordingDelegate()
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Unread }

        assertFailsWith<IllegalStateException> { scoped.discover(admitting("2026-01-01T00:00:00Z")) }
        assertFailsWith<IllegalStateException> { scoped.resourcesFor(setOf("A")) }
        assertEquals(0, delegate.discoverCalls, "an unread scope must not fall through to a platform walk")
        assertEquals(0, delegate.resolveCalls, "an unread scope must not fall through to a platform read")
    }

    // ---- the ledger-driven resolve, under the same discipline (capability `photo-sharing`) ------------

    @Test
    fun unrestricted_resolve_delegates_to_the_platform() = runTest {
        val delegate = RecordingDelegate()
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Unrestricted }

        scoped.resourcesFor(setOf("A"))

        assertEquals(1, delegate.resolveCalls)
    }

    @Test
    fun scoped_resolve_answers_from_the_snapshot_without_any_platform_read() = runTest {
        // The snapshot is already in hand, so resolving a key from it costs nothing — and asking the
        // platform under a partial grant is exactly the read the discipline exists to avoid.
        val delegate = RecordingDelegate()
        val snapshot = listOf(resource("A"), resource("B"), resource("C"))
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Scoped(snapshot) }

        val resolved = scoped.resourcesFor(setOf("A", "C"))

        assertEquals(0, delegate.resolveCalls)
        assertEquals(listOf("A", "C"), resolved.map { it.filename })
    }

    @Test
    fun scoped_resolve_answers_nothing_for_a_key_outside_the_selection() = runTest {
        // The port's contract, and the honest answer: under `.limited` a photo outside the user's
        // selection is not this app's to upload, which is the SAME absence as an asset having left the
        // library — the caller stops asking for it either way. Resolving it from the platform instead
        // would upload a photo the user did not hand over.
        val delegate = RecordingDelegate()
        val scoped = SelectionScopedDiscovery(delegate) { SelectionScope.Scoped(listOf(resource("A"))) }

        val resolved = scoped.resourcesFor(setOf("A", "NOT-SELECTED"))

        assertEquals(0, delegate.resolveCalls, "an unselected key must not fall through to a platform read")
        assertEquals(listOf("A"), resolved.map { it.filename })
    }
}
