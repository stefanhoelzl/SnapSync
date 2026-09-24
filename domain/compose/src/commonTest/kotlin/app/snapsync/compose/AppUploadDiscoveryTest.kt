package app.snapsync.compose

import app.snapsync.feature.upload.WalkMemoUse
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.noContribution
import app.snapsync.ports.Discovery
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryChangeTokenRead
import app.snapsync.ports.PhotoGrantRead
import app.snapsync.ports.UploadDiscovery
import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * The app process's discovery binding (decision record `changes/own-work-per-wake`, D9): until the
 * external-change device check (task 7.4) is recorded in the `sync-ledger` spec, the memo SHALL NOT be relied on —
 * so the composed binding walks every time. Flipping [APP_WALK_MEMO_USE] fails this test on purpose: the flip is
 * made together with the recorded result, and this is where that is remembered.
 */
class AppUploadDiscoveryTest {

    @Test
    fun `the app binding does not serve walks until the external-change check is recorded`() = runTest {
        assertEquals(WalkMemoUse.SHADOW, APP_WALK_MEMO_USE)
        var walks = 0
        val walk = object : UploadDiscovery {
            override suspend fun discover(policy: SelectionPolicy): Discovery {
                walks++
                return Discovery(emptyList(), fullEnumeration = true)
            }

            override suspend fun resourcesFor(keys: Set<String>): List<Resource> = emptyList()
        }
        val unchanged = object : LibraryChangeToken {
            override fun sameLibraryAs(other: LibraryChangeToken) = true
        }
        val tokens = object : LibraryChangeTokenRead {
            override suspend fun current(): LibraryChangeToken = unchanged
        }
        val discovery = appUploadDiscovery(walk, tokens, PhotoGrantRead { PermissionStatus.GRANTED }, Logger.withTag("test"))

        discovery.discover(noContribution())
        discovery.discover(noContribution())

        assertEquals(2, walks, "an unchanged library still walks: the memo only shadows")
    }
}
