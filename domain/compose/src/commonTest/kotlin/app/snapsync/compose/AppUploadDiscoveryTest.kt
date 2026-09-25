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
 * The app process's discovery binding (decision record `changes/own-work-per-wake`, D9) serves an unchanged
 * library from the walk memo: the external-change device check (task 7.4) is recorded in the `sync-ledger` spec.
 * Switching [APP_WALK_MEMO_USE] back to SHADOW fails this test on purpose — that revert should be deliberate.
 */
class AppUploadDiscoveryTest {

    @Test
    fun `the app binding serves an unchanged library without a second walk`() = runTest {
        assertEquals(WalkMemoUse.SERVE, APP_WALK_MEMO_USE)
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

        assertEquals(1, walks, "an unchanged library is answered from the memo")
    }
}
