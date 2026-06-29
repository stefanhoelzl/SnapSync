package app.snapsync.status

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PendingManifestsSourceTest {

    @Test
    fun `a started-but-incomplete asset is in flight and its file is retained`() = runTest {
        val dir = FakeManifestDirectory(onDisk = mutableSetOf("A"))
        val completed = MutableCompletedAssetsSource(emptySet())
        val source = DirectoryPendingManifestsSource(dir, completed)

        source.refresh()

        assertEquals(setOf("A"), source.inFlight.value)
        assertTrue("A" in dir.onDisk, "an incomplete asset's manifest is not pruned")
    }

    @Test
    fun `an already-complete asset is excluded from in-flight and its file is pruned`() = runTest {
        val dir = FakeManifestDirectory(onDisk = mutableSetOf("A", "B"))
        val completed = MutableCompletedAssetsSource(setOf("A"))
        val source = DirectoryPendingManifestsSource(dir, completed)

        source.refresh()

        assertEquals(setOf("B"), source.inFlight.value, "complete assets are not in flight")
        assertEquals(setOf("B"), dir.onDisk, "the now-complete asset's manifest file is pruned")
    }

    @Test
    fun `refresh re-reads the on-disk set`() = runTest {
        val dir = FakeManifestDirectory(onDisk = mutableSetOf("A"))
        val completed = MutableCompletedAssetsSource(emptySet())
        val source = DirectoryPendingManifestsSource(dir, completed)

        source.refresh()
        assertEquals(setOf("A"), source.inFlight.value)

        dir.onDisk.add("B")
        source.refresh()
        assertEquals(setOf("A", "B"), source.inFlight.value)
    }
}

private class FakeManifestDirectory(val onDisk: MutableSet<String>) : ManifestDirectory {
    override suspend fun assetIds(): Set<String> = onDisk.toSet()
    override suspend fun prune(assetId: String) {
        onDisk.remove(assetId)
    }
}
