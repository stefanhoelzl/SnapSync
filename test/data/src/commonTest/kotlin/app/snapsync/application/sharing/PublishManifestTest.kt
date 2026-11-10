package app.snapsync.application.sharing

import app.snapsync.application.EntryPointCall
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.ok
import app.snapsync.data.backend.text
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.active
import app.snapsync.data.membership.enrolled
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.sharing.DURING
import app.snapsync.data.sharing.DiscoveryRead
import app.snapsync.data.sharing.Library
import app.snapsync.data.sharing.ManifestPublication
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.RecordRead
import app.snapsync.data.upload.OwnUploadRows
import app.snapsync.data.upload.WalkRecord
import app.snapsync.data.walkClock
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.MembershipRead
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.ManifestVersion
import app.snapsync.domain.sharing.PublishedDeclaration
import app.snapsync.domain.sharing.SettlingLook
import app.snapsync.domain.upload.FoundResource
import app.snapsync.domain.upload.RowDetail
import app.snapsync.domain.upload.UploadGate
import app.snapsync.domain.upload.UploadKey
import app.snapsync.format.TimestampText
import app.snapsync.levers.WriteKind
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.LibraryAssets
import app.snapsync.ports.HttpResult
import app.snapsync.testids.testDeviceId
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.Role
import app.snapsync.wire.runtime.WireJson
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Publishing this device's manifest (capabilities `photo-sharing`, `event-lifetime`; change
 * `settling-needs-a-real-look`): what it declares, when it settles the share, what it skips, and what each answer of
 * the backend leaves recorded — over the real stores, the real gateway and a scripted backend.
 *
 * The member's range is the fixture event's own: 14 July 18:00 to 21 July 18:00.
 */
@OptIn(EntryPointCall::class)
class PublishManifestTest {

    private val databases = DatabasesMock()
    private val shared = sharedOver(databases)
    private val membership = MembershipRepository(shared).apply { enter(enrolled()) }
    private val record = ManifestRecord(shared)
    private val library = Library(LibraryAssets.photo("A1", DURING))
    private val transport = ScriptedTransport { ok() }
    private val publish = PublishManifest(
        OwnUploadRows(shared),
        record,
        ManifestPublication(BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))),
        membership,
        library.wallClock,
    )

    init {
        WalkRecord(shared, walkClock).discover(
            active().event.id,
            listOf(
                FoundResource(
                    UploadKey.of(AssetId.parse("A1")!!, ResourceRole.PRIMARY, "IMG.JPG"),
                    RowDetail(CaptureDate.Dated(TimestampText.parse(DURING)), "image/jpeg", "IMG.JPG"),
                ),
                FoundResource(
                    UploadKey.of(AssetId.parse("A1")!!, ResourceRole.LIVE, "IMG.MOV"),
                    RowDetail(CaptureDate.Dated(TimestampText.parse(DURING)), "video/quicktime", "IMG.MOV"),
                ),
            ),
        )
    }

    private fun run(version: Long = 1) = UploadGate.Run(active(), ManifestVersion(version), testDeviceId("D"))

    private fun at(instant: String) {
        library.clock.now = Instant.parse(instant)
    }

    private suspend fun policy() = library.contribution().policy

    /** A look at the library taken now. */
    private suspend fun look(): SettlingLook =
        SettlingLook.Looked(assertIs<DiscoveryRead.Discovered>(library.own.discover(library.contribution())).discovery)

    private fun sent(): Manifest = WireJson.decodeFromString(Manifest.serializer(), transport.requests.last().body!!)

    private fun held(): RecordRead.Held = assertIs(record.read(active().event.id))

    @Test
    fun `it declares every admitted row with the pass's version, and records what the backend took`() = runTest {
        assertEquals(PublishOutcome.Published, publish(run(version = 3), policy(), look()))

        val manifest = sent()
        assertEquals(3, manifest.version)
        assertEquals(testDeviceId("D").value, manifest.deviceId)
        assertEquals(listOf("A1"), manifest.assets.map { it.assetId })
        val asset = manifest.assets.single()
        assertEquals(DURING, asset.creationDate)
        assertEquals(listOf(Role.PRIMARY, Role.LIVE), asset.resources.map { it.role })
        assertEquals(
            listOf(null to null, null to null),
            asset.resources.map { it.key to it.filename },
            "no key and no name: a photo's name travels with its bytes, sealed in an encrypted event",
        )
        assertFalse(manifest.`final`, "the range has not ended")
        assertEquals(ManifestVersion(3), held().version)
        assertEquals(1, held().assets)
        assertEquals(PublishedDeclaration.SHARING, held().declaration)
    }

    @Test
    fun `a manifest the record holds is not sent again — a higher version is`() = runTest {
        publish(run(), policy(), look())
        assertEquals(PublishOutcome.Unchanged, publish(run(), policy(), look()))
        assertEquals(1, transport.requests.size)

        assertEquals(PublishOutcome.Published, publish(run(version = 2), policy(), look()))
        assertEquals(2, transport.requests.size, "one version can name two snapshots: a higher one republishes")
    }

    @Test
    fun `a member who shares nothing declares nothing`() = runTest {
        val nothing = library.contribution(direction = Direction.DownloadOnly).policy
        publish(run(), nothing, SettlingLook.NothingToLook)
        assertEquals(emptyList(), sent().assets)
    }

    @Test
    fun `after the end a look taken since settles the share, and it is recorded so`() = runTest {
        at(AFTER_THE_END)
        assertEquals(PublishOutcome.Published, publish(run(), policy(), look()))
        assertTrue(sent().`final`)
        assertEquals(PublishedDeclaration.SETTLED, held().declaration)
    }

    @Test
    fun `after the end a look taken before it settles nothing`() = runTest {
        val before = look() // taken during the event
        at(AFTER_THE_END)
        publish(run(), policy(), before)
        assertFalse(sent().`final`)
    }

    @Test
    fun `after the end a look that could not be made settles nothing — and never takes a settlement back`() = runTest {
        at(AFTER_THE_END)
        publish(run(), policy(), SettlingLook.CouldNotLook)
        assertFalse(sent().`final`, "nothing was settled yet: the share is declared sharing")

        publish(run(version = 2), policy(), look())
        assertTrue(sent().`final`)
        assertEquals(PublishOutcome.KeptSettled, publish(run(version = 3), policy(), SettlingLook.CouldNotLook))
        assertEquals(2, transport.requests.size, "the unproven publish is not sent")
    }

    @Test
    fun `a member who shares nothing settles at the end with nothing to look at`() = runTest {
        at(AFTER_THE_END)
        val nothing = library.contribution(direction = Direction.DownloadOnly).policy
        publish(run(), nothing, SettlingLook.NothingToLook)
        assertTrue(sent().`final`)
    }

    @Test
    fun `the service's word that the photos were deleted is recorded and marks the membership`() = runTest {
        transport.answer = { json(410, CLOSED) }
        assertEquals(PublishOutcome.EventOver, publish(run(), policy(), look()))
        assertEquals(ManifestVersion(1), held().version, "re-sending could never change the answer")
        val read = assertIs<MembershipRead.Joined>(membership.read())
        assertIs<Membership.Deleted>(read.membership, "shown as over — never left on its own")
    }

    @Test
    fun `a set fixed before events stopped closing is recorded as published, marking nothing`() = runTest {
        transport.answer = { json(409, CLOSED) }
        assertEquals(PublishOutcome.Published, publish(run(), policy(), look()))
        assertEquals(ManifestVersion(1), held().version)
        assertIs<Membership.Active>(assertIs<MembershipRead.Joined>(membership.read()).membership)
    }

    @Test
    fun `a refusal or no answer records nothing — the next pass sends it again`() = runTest {
        val answers = listOf(text(409, "not a member"), text(502, "upstream"), HttpResult.Unreachable("offline"))
        for (answer in answers) {
            transport.answer = { answer }
            assertEquals(PublishOutcome.NotPublished, publish(run(), policy(), look()))
            assertEquals(RecordRead.None, record.read(active().event.id))
        }
        transport.answer = { ok() }
        assertEquals(PublishOutcome.Published, publish(run(), policy(), look()))
    }

    @Test
    fun `a record that cannot be read publishes nothing this pass`() = runTest {
        databases.lock()
        assertEquals(PublishOutcome.NotPublished, publish(run(), policy(), SettlingLook.CouldNotLook))
        assertEquals(emptyList(), transport.requests)
    }

    @Test
    fun `rows that cannot be read publish nothing this pass`() = runTest {
        val unreadable = DatabasesMock().also { it.lock() }
        val blind = PublishManifest(
            OwnUploadRows(sharedOver(unreadable)),
            record,
            ManifestPublication(BackendGateway(exchangeOver(transport), ScriptedCredential("T1"))),
            membership,
            library.wallClock,
        )
        assertEquals(PublishOutcome.NotPublished, blind(run(), policy(), SettlingLook.CouldNotLook))
        assertEquals(emptyList(), transport.requests)
    }

    @Test
    fun `a publish whose record was not written is sent once more`() = runTest {
        databases.fail("ledger.db", "uploadedManifest", WriteKind.INSERT)
        assertEquals(PublishOutcome.Published, publish(run(), policy(), look()))
        assertEquals(RecordRead.None, record.read(active().event.id))
        publish(run(), policy(), look())
        assertEquals(2, transport.requests.size)
    }

    private companion object {
        /** After the member's range ended, before the event's photos may be deleted. */
        const val AFTER_THE_END = "2026-07-22T09:00:00Z"
        const val CLOSED = """{"error":"closed"}"""
    }
}
