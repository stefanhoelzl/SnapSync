package app.snapsync.data.sharing

import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.OTHER
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.store.StoreWrite
import app.snapsync.data.store.Stored
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.PublishedDeclaration
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.sharing.ShareDeclaration
import app.snapsync.domain.sharing.SharePhase
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The record of this device's last published manifest (capability `photo-sharing`): one row both processes read, for
 * one event at a time, that never moves to an older version of its event.
 */
class ManifestRecordTest {

    private val databases = DatabasesMock()
    private val shared = sharedOver(databases)
    private val record = ManifestRecord(shared)
    private val event = testEventId(EVENT)

    private fun recorded(
        version: Long,
        declared: ShareDeclaration = ShareDeclaration.Sharing,
        assets: Int = 0,
        of: String = EVENT,
    ): StoreWrite {
        val manifest = emptyManifest(version, declared)
        return record.record(testEventId(of), manifest.version, declared, assets, ManifestBody.of(manifest))
    }

    private val settled: ShareDeclaration =
        ShareDeclaration.of(SharePhase.Ended(at("2026-07-21T18:00:00Z")), SettlingLook.NothingToLook)

    @Test
    fun `nothing recorded is none`() {
        assertEquals(RecordRead.None, record.read(event))
    }

    @Test
    fun `a record holds the body sent, its version, what it declared and how many photos`() {
        assertEquals(StoreWrite.Written, recorded(4, settled, assets = 3))

        val held = assertIs<RecordRead.Held>(record.read(event))
        assertEquals(ManifestVersion(4), held.version)
        assertEquals(ManifestBody.of(emptyManifest(4, settled)).text, held.body)
        assertEquals(PublishedDeclaration.SETTLED, held.declaration)
        assertEquals(3, held.assets)
    }

    @Test
    fun `a record never moves to an older version of its event — another event replaces it`() {
        recorded(5, assets = 2)
        recorded(4, assets = 9)
        assertEquals(2, assertIs<RecordRead.Held>(record.read(event)).assets, "the older landed last: kept the newer")

        recorded(1, assets = 7, of = OTHER)
        assertEquals(RecordRead.None, record.read(event), "another event's record says nothing of this one")
        assertEquals(7, assertIs<RecordRead.Held>(record.read(testEventId(OTHER))).assets)
    }

    @Test
    fun `a row whose event column spells no id says nothing of any event`() {
        recorded(1)
        shared.execute {
            it.uploadedManifestQueries.recordUploaded(Stored.Invalid(""), 2, "{}", PublishedDeclaration.SHARING, 0)
        }
        assertEquals(RecordRead.None, record.read(event))
    }

    @Test
    fun `a record that cannot be read or written says so`() {
        assertEquals(RecordRead.None, record.read(event)) // the database exists
        databases.fail("ledger.db", "uploadedManifest", WriteKind.INSERT)
        assertIs<StoreWrite.Failed>(recorded(1))
        databases.lock()
        assertIs<RecordRead.Unavailable>(record.read(event))
    }
}
