package app.snapsync.data.receiving

import app.snapsync.control.Verifies
import app.snapsync.data.RecordingSecureStore
import app.snapsync.data.backend.BASE
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.text
import app.snapsync.data.crypto.EncryptionSlots
import app.snapsync.data.crypto.EventKeyIds
import app.snapsync.data.crypto.EventKeyStore
import app.snapsync.data.crypto.FileNameCipher
import app.snapsync.data.crypto.ServedNames
import app.snapsync.data.membership.active
import app.snapsync.data.membership.sharedOver
import app.snapsync.data.sharing.ManifestRecord
import app.snapsync.data.sharing.declared
import app.snapsync.domain.asset.FileBinding
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.Encryption
import app.snapsync.domain.event.IdentifiedKey
import app.snapsync.domain.event.KeyToKeep
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.receiving.PushNotice
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.domain.time.Timestamp
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fakeCrypto
import app.snapsync.ports.HttpResult
import app.snapsync.ports.SecureStoreRead
import app.snapsync.testids.testAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The event's union over the backend (decision record `changes/incremental-union`): a whole read or a delta from a
 * position, each telling the backend why; every photo tagged with its source and given the download address this
 * build builds itself; the declared total netted, at the read, of what this device had declared; and a page the app
 * cannot go on from — or no page — is no read.
 */
class EventUnionTest {
    private val databases = DatabasesMock()
    private val record = ManifestRecord(sharedOver(databases))
    private var answer: HttpResult = page("[]", cursor = "0")
    private val transport = ScriptedTransport { answer }
    private val crypto = fakeCrypto()
    private val ids = EventKeyIds(crypto)
    private val keys = EventKeyStore(RecordingSecureStore(), ids)
    private val cipher = FileNameCipher(crypto)
    private val union = EventUnion(
        BackendGateway(exchangeOver(transport), ScriptedCredential("T1")),
        record,
        ServedNames(keys, cipher),
    )

    /** A plain event's membership — its names are read as served. */
    private val member = active(EVENT.value)

    private fun page(body: String, cursor: String = "7", declared: String? = null): HttpResult =
        json(
            200,
            body,
            mapOf("SnapSync-Cursor" to cursor) + (declared?.let { mapOf("SnapSync-Declared" to it) } ?: emptyMap()),
        )

    private fun asset(device: String = ANNA.value, asset: String = "A", filename: String = "IMG_1.MOV") =
        """{"deviceId":"$device","assetId":"$asset","creationDate":"$DURING","resources":[""" +
            """{"role":"live","contentType":"video/quicktime","key":"$asset-live.mov","filename":"$filename"}]}"""

    @Test
    fun `a whole read serves every photo with the address this build builds, and the others' declared total`() =
        runTest {
            answer = page("[${asset()}]", cursor = "7", declared = "5")
            record.declared(EVENT, assets = 2)

            val read = assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Whole, UnionTrigger.FOREGROUND)).page

            assertEquals(7L, read.position)
            assertEquals(3L, read.othersDeclared, "what this device declared is not awaited")
            val photo = read.photos.single()
            assertEquals(ref("A"), photo.ref)
            assertEquals(captured(), photo.captured)
            val resource = photo.resources.single()
            assertEquals("A-live.mov", resource.key)
            assertEquals("live", resource.role)
            assertEquals("video/quicktime", resource.contentType)
            assertEquals("IMG_1.MOV", resource.originalFilename.value)
            assertEquals(true, resource.url.startsWith(BASE), resource.url)
            val request = transport.requests.single()
            assertEquals("foreground", request.headers["SnapSync-Trigger"])
            assertEquals(false, request.url.contains("cursor="), "from the start")
        }

    @Test
    fun `a read a push caused names the position it announced and when the app acted on it`() = runTest {
        answer = page("[]", cursor = "9")
        val acted = Timestamp.of(Instant.parse("2026-07-14T12:00:01Z"))

        union.read(member, UnionReadPlan.Since(4), UnionTrigger.PUSH, PushNotice(9, acted))

        val request = transport.requests.single()
        assertEquals("9", request.headers["x-snapsync-announced"])
        assertEquals("2026-07-14T12:00:01Z", request.headers["x-snapsync-notified"])
    }

    @Test
    fun `a delta reads from its position, and an unknown total or own count awaits nothing`() = runTest {
        answer = page("[]", cursor = "9")
        databases.lock() // the record cannot be read: no own count

        val read = assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Since(4), UnionTrigger.PUSH)).page

        assertNull(read.othersDeclared)
        assertEquals(true, transport.requests.single().url.contains("cursor=4"))
        answer = page("[]", declared = "5")
        assertNull(
            assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Whole, UnionTrigger.JOIN)).page.othersDeclared,
        )
    }

    @Test
    fun `a holding count reads every photo by ref and the position, needing no name - so no key either`() = runTest {
        answer = page("[${asset(asset = "A", filename = "no-name-it-could-open")},${asset(asset = "B")}]", cursor = "9")

        val held = assertIs<HeldRead.Counted>(union.held(EVENT, UnionTrigger.RECEIVED_CHECK)).union

        assertEquals(listOf(ref("A"), ref("B")), held.refs)
        assertEquals(9L, held.position)
        assertEquals("received-check", transport.requests.single().headers["SnapSync-Trigger"])
    }

    @Test
    fun `a holding count of a page it cannot read, or of no page, is none`() = runTest {
        for (unreadable in listOf(
            page("[]", cursor = "not-a-number"),
            page("[${asset(device = "not-a-device")}]"),
            text(404, "event not found"),
            text(502, "bad gateway"),
            HttpResult.Unreachable("offline"),
        )) {
            answer = unreadable
            assertIs<HeldRead.Unavailable>(union.held(EVENT, UnionTrigger.RECEIVED_CHECK), "$unreadable")
        }
    }

    @Test
    fun `a page the app cannot go on from, or no page, is no read`() = runTest {
        for (unreadable in listOf(
            page("[]", cursor = "not-a-number"),
            page("[${asset(device = "not-a-device")}]"),
            page("[${asset(asset = "no..id")}]"),
            text(404, "event not found"),
            text(502, "bad gateway"),
            HttpResult.Unreachable("offline"),
        )) {
            answer = unreadable
            assertIs<UnionRead.Unavailable>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE), "$unreadable")
        }
    }

    @Test
    fun `a page with a resource that names no sender filename is no read`() = runTest {
        // Every upload names its file, so the union never serves one without (change
        // `a-received-photo-always-has-a-sender-filename`): a photo with no name to arrive under is a page this build
        // cannot read, as a photo whose id is no id is.
        answer = page("[${asset(asset = "A")},${asset(asset = "B", filename = "")}]")
        assertIs<UnionRead.Unavailable>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE))

        answer = page("[${asset(asset = "A")},${asset(asset = "B")}]")
        val read = assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE))
        assertEquals(2, read.page.photos.size)
    }

    @Test
    fun `every trigger tells the backend its own name`() = runTest {
        val names = UnionTrigger.entries.map { trigger ->
            union.read(member, UnionReadPlan.Whole, trigger)
            transport.requests.last().headers["SnapSync-Trigger"]
        }
        assertEquals(
            listOf("push", "wake", "foreground", "join", "grant", "reconfigure", "leave-check", "received-check"),
            names,
        )
    }

    private val key = ids.mint()

    /** A member of the event encrypted under [key], which this device keeps. */
    private fun encrypted(): Membership.Active {
        val base = active(EVENT.value)
        keys.keep(KeyToKeep.Key(key), EVENT)
        return Membership.Active(base.event.copy(encryption = Encryption.Encrypted(key.id)), base.share)
    }

    /** [name], sealed as [asset]'s live resource on [ANNA] is in the event — under [under]. */
    private fun sealed(asset: String, name: String, under: IdentifiedKey = key, sealedFor: String = asset): String =
        cipher.sealFileName(under, FileBinding(EVENT, ANNA, testAssetId(sealedFor), ResourceRole.LIVE), name)

    @Test
    fun `an encrypted event's sealed names open with its key, and each key is derived from the opened name`() = runTest {
        val member = encrypted()
        answer = page("[${asset(asset = "A", filename = sealed("A", "IMG_1.MOV"))}]")

        val resource = assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE))
            .page.photos.single().resources.single()

        assertEquals("IMG_1.MOV", resource.originalFilename.value)
        assertEquals("A-live.mov", resource.key, "derived from the name, never read off the union")
    }

    // The app's part: a photo whose sealed name does not open under the event's key is left out of the page — never
    // planned, so never received — and every other photo of the page still is.
    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "The service keeps an encrypted event's photo names only encrypted",
        scenario = "A photo whose name does not open",
    )
    fun `a photo whose sealed name does not open is left out, and the rest of the page is read`() = runTest {
        val member = encrypted()
        val other = ids.mint()
        answer = page(
            "[${asset(asset = "A", filename = sealed("A", "IMG_1.MOV"))}," +
                "${asset(asset = "B", filename = sealed("B", "IMG_2.MOV", under = other))}," +
                "${asset(asset = "C", filename = sealed("C", "IMG_3.MOV", sealedFor = "A"))}," +
                "${asset(asset = "D", filename = "IMG_4.MOV")}]",
        )

        val read = assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE)).page

        assertEquals(
            listOf(ref("A")),
            read.photos.map { it.ref },
            "another key, another photo's record, a readable name",
        )
        assertEquals(7L, read.position, "the page is read past them")
    }

    @Test
    fun `a sealed name that opens to no file leaves its photo out`() = runTest {
        val member = encrypted()
        answer = page("[${asset(asset = "A", filename = sealed("A", ""))},${asset(asset = "B", filename = sealed("B", "IMG_2.MOV"))}]")

        val read = assertIs<UnionRead.Ok>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE)).page

        assertEquals(listOf(ref("B")), read.photos.map { it.ref })
    }

    @Test
    fun `an encrypted event whose key cannot be read now - a phone locked since boot - reads nothing`() = runTest {
        val member = encrypted()
        val locked = RecordingSecureStore(EncryptionSlots.EVENT_KEY to SecureStoreRead.Unavailable("locked"))
        val lockedUnion = EventUnion(
            BackendGateway(exchangeOver(transport), ScriptedCredential("T1")),
            record,
            ServedNames(EventKeyStore(locked, ids), cipher),
        )

        assertIs<UnionRead.Unavailable>(lockedUnion.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE))
        assertEquals(emptyList(), transport.requests)
    }

    @Test
    fun `an encrypted event whose key is not kept reads nothing, and is read again later`() = runTest {
        val base = active(EVENT.value)
        val member = Membership.Active(base.event.copy(encryption = Encryption.Encrypted(key.id)), base.share)

        assertIs<UnionRead.Unavailable>(union.read(member, UnionReadPlan.Whole, UnionTrigger.WAKE))
        assertEquals(emptyList(), transport.requests, "no page is asked for whose names cannot be read")
    }
}
