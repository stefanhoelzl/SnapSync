package app.snapsync.data.receiving

import app.snapsync.data.backend.BASE
import app.snapsync.data.backend.BackendGateway
import app.snapsync.data.backend.ScriptedCredential
import app.snapsync.data.backend.ScriptedTransport
import app.snapsync.data.backend.exchangeOver
import app.snapsync.data.backend.json
import app.snapsync.data.backend.text
import app.snapsync.domain.event.EventId
import app.snapsync.domain.receiving.UnionReadPlan
import app.snapsync.domain.receiving.UnionTrigger
import app.snapsync.ports.HttpResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The event's union over the backend (decision record `changes/incremental-union`): a whole read or a delta from a
 * position, each telling the backend why; every photo tagged with its source and given the download address this
 * build builds itself; the declared total netted, at the read, of what this device had declared; and a page the app
 * cannot go on from — or no page — is no read.
 */
class EventUnionTest {
    private var own: Int? = 0
    private var answer: HttpResult = page("[]", cursor = "0")
    private val transport = ScriptedTransport { answer }
    private val union = EventUnion(
        BackendGateway(exchangeOver(transport), ScriptedCredential("T1")),
        object : OwnDeclarations {
            override fun publishedCount(eventId: EventId): Int? = own
        },
    )

    private fun page(body: String, cursor: String = "7", declared: String? = null): HttpResult =
        json(
            200,
            body,
            mapOf("SnapSync-Cursor" to cursor) + (declared?.let { mapOf("SnapSync-Declared" to it) } ?: emptyMap()),
        )

    private fun asset(device: String = ANNA.value, asset: String = "A") =
        """{"deviceId":"$device","assetId":"$asset","creationDate":"$DURING","resources":[""" +
            """{"role":"live","contentType":"video/quicktime","key":"$asset-live.mov","filename":"IMG_1.MOV"}]}"""

    @Test
    fun `a whole read serves every photo with the address this build builds, and the others' declared total`() =
        runTest {
            answer = page("[${asset()}]", cursor = "7", declared = "5")
            own = 2

            val read = assertIs<UnionRead.Ok>(union.read(EVENT, UnionReadPlan.Whole, UnionTrigger.FOREGROUND)).page

            assertEquals(7L, read.position)
            assertEquals(3L, read.othersDeclared, "what this device declared is not awaited")
            val photo = read.photos.single()
            assertEquals(ref("A"), photo.ref)
            assertEquals(captured(), photo.captured)
            val resource = photo.resources.single()
            assertEquals("A-live.mov", resource.key)
            assertEquals("live", resource.role)
            assertEquals("video/quicktime", resource.contentType)
            assertEquals("IMG_1.MOV", resource.originalFilename)
            assertEquals(true, resource.url.startsWith(BASE), resource.url)
            val request = transport.requests.single()
            assertEquals("foreground", request.headers["SnapSync-Trigger"])
            assertEquals(false, request.url.contains("cursor="), "from the start")
        }

    @Test
    fun `a delta reads from its position, and an unknown total or own count awaits nothing`() = runTest {
        answer = page("[]", cursor = "9")
        own = null

        val read = assertIs<UnionRead.Ok>(union.read(EVENT, UnionReadPlan.Since(4), UnionTrigger.PUSH)).page

        assertNull(read.othersDeclared)
        assertEquals(true, transport.requests.single().url.contains("cursor=4"))
        answer = page("[]", declared = "5")
        assertNull(
            assertIs<UnionRead.Ok>(union.read(EVENT, UnionReadPlan.Whole, UnionTrigger.JOIN)).page.othersDeclared,
        )
    }

    @Test
    fun `an announced position already read reads nothing`() = runTest {
        assertIs<UnionRead.Unavailable>(union.read(EVENT, UnionReadPlan.AlreadyRead, UnionTrigger.PUSH))
        assertEquals(emptyList(), transport.requests)
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
            assertIs<UnionRead.Unavailable>(union.read(EVENT, UnionReadPlan.Whole, UnionTrigger.WAKE), "$unreadable")
        }
    }

    @Test
    fun `every trigger tells the backend its own name`() = runTest {
        val names = UnionTrigger.entries.map { trigger ->
            union.read(EVENT, UnionReadPlan.Whole, trigger)
            transport.requests.last().headers["SnapSync-Trigger"]
        }
        assertEquals(
            listOf("push", "wake", "foreground", "join", "grant", "reconfigure", "leave-check", "received-check"),
            names,
        )
    }
}
