package app.snapsync.data.upload

import app.snapsync.data.backend.json
import app.snapsync.data.backend.text
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.membership.active
import app.snapsync.data.sharing.DURING
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.Library
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.upload.UploadKey
import app.snapsync.domain.upload.UploadRow
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.ResourceKind
import app.snapsync.testids.testDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A walk's resources as the upload records them; a row read by its key; the manifest version; and what the backend
 * stores for this device, which settles an in-flight row its job never acknowledged.
 */
class WalkAndListingTest {

    private val world = SessionWorld()

    @Test
    fun `a read's resources are keyed under their role, with the capture date, name and type their rows record`() = runTest {
        val library = Library(
            LibraryAssets.photo("A1", DURING),
            LibraryAssets.livePhoto("A2", DURING),
            LibraryAssets.photo(
                "A3",
                DURING,
                resources = listOf(LibraryAssets.Resource(ResourceKind.VIDEO, "video/mp4", "V.MP4")),
            ),
        )
        val resources = OwnPhotoResources(library.own)
        val read = assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution()))

        val walked = resources.of(read, read.discovery.admitted)

        assertEquals(
            listOf(ResourceRole.PRIMARY, ResourceRole.PRIMARY, ResourceRole.LIVE, ResourceRole.PRIMARY),
            walked.map { it.found.key.role },
        )
        val first = walked.first().found
        assertEquals("A1", first.key.asset.value)
        assertIs<CaptureDate.Dated>(first.detail.captured)
        assertEquals("image/jpeg", first.detail.contentType)
    }

    @Test
    fun `a row's photo is found again, gone, or not readable now`() = runTest {
        val library = Library(LibraryAssets.photo("A1", DURING))
        val resources = OwnPhotoResources(library.own)
        val key = UploadKey.of(AssetId.parse("A1")!!, ResourceRole.PRIMARY, "IMG.JPG")

        assertIs<KeyLookup.Found>(resources.find(key))
        assertEquals(KeyLookup.Gone, resources.find(UploadKey.of(AssetId.parse("A1")!!, ResourceRole.LIVE, "IMG.MOV")))
        library.grant.value = LibraryGrant.DENIED
        assertEquals(KeyLookup.NotReadable, resources.find(key))

        library.grant.value = LibraryGrant.GRANTED
        val read = assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution()))
        library.grant.value = LibraryGrant.DENIED
        assertEquals(
            emptyList(),
            resources.of(read, read.discovery.admitted),
            "a library no longer readable yields none",
        )

        library.grant.value = LibraryGrant.GRANTED
        library.photos.value = emptyList()
        val gone = assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution()))
        assertEquals(emptyList(), resources.of(gone, gone.discovery.admitted))
    }

    @Test
    fun `a row is read by its key, and the manifest version advances with the rows`() {
        world.joined()
        assertEquals(RowRead.Absent, world.reads.row(world.event, world.key()))
        assertEquals(VersionRead.Read(ManifestVersion(1)), world.reads.manifestVersion(), "the join advanced it")

        world.discovered()

        assertIs<UploadRow.Discovered>(assertIs<RowRead.Found>(world.reads.row(world.event, world.key())).row)
        assertEquals(VersionRead.Read(ManifestVersion(2)), world.reads.manifestVersion())
        world.databases.lock()
        assertIs<RowRead.Unavailable>(world.reads.row(world.event, world.key()))
        assertIs<VersionRead.Unavailable>(world.reads.manifestVersion())
    }

    @Test
    fun `what the backend lists for this device settles its in-flight rows`() = runTest {
        world.joined()
        world.tier.create(world.event, world.discovered(), SessionWorld.From)
        world.transport.answer = {
            json(
                200,
                """[{"assetId":"A1","role":"primary","filename":"IMG.MOV"},{"assetId":"A9","role":"live","filename":"x.mov"},""" +
                    """{"assetId":"A8","role":"primary","filename":""}]""",
            )
        }
        val listing =
            DeviceListing(
                app.snapsync.data.backend.BackendGateway(
                    app.snapsync.data.backend.exchangeOver(world.transport),
                    app.snapsync.data.backend.ScriptedCredential("T1"),
                ),
                world.appIdentity,
                world.served,
            )

        val stored = assertIs<ListingRead.Listed>(listing.stored(active())).stored

        assertEquals(
            listOf(
                world.key(),
                UploadKey.of(AssetId.parse("A9")!!, ResourceRole.LIVE, "x.mov"),
                // A plain event's empty name keys its item as it always did: a `.bin`.
                UploadKey.of(AssetId.parse("A8")!!, ResourceRole.PRIMARY, ""),
            ),
            stored.map {
                it.key
            },
        )
        assertEquals(RowTransition.Transitioned, world.transitions.listed(world.event, stored.first()))
        assertIs<UploadRow.Completed>(world.row())
        assertIs<RowTransition.Superseded>(world.transitions.listed(world.event, stored.first()))
    }

    @Test
    fun `an encrypted event's listing keys each item by its opened name, and an item whose name does not open is none`() =
        runTest {
            world.joinedEncrypted()
            val sealed = world.names.sealFileName(
                world.eventKey,
                FileBinding(world.event, testDeviceId("D1"), AssetId.parse("A1")!!, ResourceRole.PRIMARY),
                "IMG.MOV",
            )
            val foreign = world.names.sealFileName(
                world.ids.mint(),
                FileBinding(world.event, testDeviceId("D1"), AssetId.parse("A2")!!, ResourceRole.PRIMARY),
                "IMG.MOV",
            )
            world.transport.answer = {
                json(
                    200,
                    """[{"assetId":"A1","role":"primary","filename":"$sealed"},""" +
                        """{"assetId":"A2","role":"primary","filename":"$foreign"}]""",
                )
            }
            val listing = DeviceListing(
                app.snapsync.data.backend.BackendGateway(
                    app.snapsync.data.backend.exchangeOver(world.transport),
                    app.snapsync.data.backend.ScriptedCredential("T1"),
                ),
                world.appIdentity,
                world.served,
            )

            val stored = assertIs<ListingRead.Listed>(listing.stored(world.joinedAs)).stored
            assertEquals(listOf(world.key()), stored.map { it.key }, "A2's record is another key's: no evidence")

            // A membership whose key this device does not keep lists nothing — and asks nothing.
            val asked = world.transport.calls.size
            val unkeyed = Membership.Active(
                world.joinedAs.event.copy(encryption = Encryption.Encrypted(world.ids.mint().id)),
                world.joinedAs.share,
            )
            assertIs<ListingRead.Unavailable>(listing.stored(unkeyed))
            assertEquals(asked, world.transport.calls.size)
        }

    @Test
    fun `a listing that cannot be asked or understood says so`() = runTest {
        val listing =
            DeviceListing(
                app.snapsync.data.backend.BackendGateway(
                    app.snapsync.data.backend.exchangeOver(world.transport),
                    app.snapsync.data.backend.ScriptedCredential("T1"),
                ),
                world.appIdentity,
                world.served,
            )
        listOf(
            text(400, "bad"),
            text(401, "unattested"),
            text(403, "not this device"),
            text(502, "down"),
            app.snapsync.ports.HttpResult.Response(503, mapOf("Retry-After" to "60"), ""),
            app.snapsync.data.backend.appTooOld("9.9"),
            text(418, "?"),
            json(200, """{"v1":"shape"}"""),
            app.snapsync.ports.HttpResult.Unreachable("offline"),
        ).forEach { answer ->
            world.transport.answer = { answer }
            assertIs<ListingRead.Unavailable>(listing.stored(active()), "$answer")
        }

        // A listing that decodes but names an asset this build cannot read will not heal: told apart, and none seeded.
        world.transport.answer = {
            json(200, """[{"assetId":"A1","role":"primary","filename":"x"},{"assetId":"bad id!","role":"live","filename":"x"}]""")
        }
        assertIs<ListingRead.Unreadable>(listing.stored(active()))

        world.appIdentity.read = DeviceIdRead.Unavailable("locked")
        assertEquals(ListingRead.Unavailable("locked"), listing.stored(active()))
    }
}
