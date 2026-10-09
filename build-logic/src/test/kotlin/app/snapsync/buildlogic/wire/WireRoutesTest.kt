package app.snapsync.buildlogic.wire

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The app's wire generator: it translates the repository's description, and refuses what it would have to guess. */
class WireRoutesTest {

    private val description = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { File(it, "api/openapi.yaml") }.first { it.isFile }.readText()

    private val generated = WireRoutes.generate(WireDescription.read(description))

    @Test
    fun `the repository's description yields the replies, the routes and the limits`() {
        assertEquals(setOf("Replies.kt", "Routes.kt", "Limits.kt"), generated.keys)
        assertTrue("sealed interface JoinEventReply" in generated.getValue("Replies.kt"))
        assertTrue("const val EVENT_NAME_MAX_LENGTH: Int = 100" in generated.getValue("Limits.kt"))
        assertTrue("""const val VERSION_HEADER: String = "x-snapsync-app-version"""" in generated.getValue("Limits.kt"))
    }

    @Test
    fun `a shared outcome is ONE type implementing every reply that answers with it`() {
        val replies = generated.getValue("Replies.kt")
        val notFound = replies.substringAfter("public data object EventNotFound").substringBefore("\n\n")
        listOf("ClientError", "GetEventReply", "JoinEventReply", "LeaveEventReply").forEach {
            assertTrue(it in notFound, "EventNotFound does not implement $it:\n$notFound")
        }
    }

    @Test
    fun `an operation whose security admits no token is open, every other one gated — the legacy optional reads too`() {
        val routes = generated.getValue("Routes.kt")
        assertTrue("fun request(): OpenRequest<ChallengeReply>" in routes)
        assertTrue("fun request(eventId: String): GatedRequest<GetEventReply>" in routes)
    }

    @Test
    fun `an OS-performed byte route gets a path, never a reply`() {
        val routes = generated.getValue("Routes.kt")
        assertTrue("object UploadResource" in routes)
        assertTrue("UploadResourceReply" !in generated.getValue("Replies.kt"))
    }

    @Test
    fun `a name outside the closed vocabulary is refused`() {
        val e = assertFailsWith<WireDescriptionException> {
            WireDescription.read(description.replaceFirst("x-snapsync-outcome:", "x-snapsync-outcomes:"))
        }
        assertTrue("x-snapsync-outcomes" in e.message!!)
    }

    @Test
    fun `a response that names no outcome is refused, naming the operation`() {
        val stripped = description.replaceFirst("          x-snapsync-outcome: Joined\n", "")
        val e = assertFailsWith<WireDescriptionException> { WireDescription.read(stripped) }
        assertTrue("joinEvent 200" in e.message!!, e.message)
    }

    @Test
    fun `a wire name becomes an identifier and a constant`() {
        assertEquals("snapSyncCursor", "SnapSync-Cursor".identifier())
        assertEquals("xSnapsyncFileKey", "x-snapsync-file-key".identifier())
        assertEquals("eventId", "eventId".identifier())
        assertEquals("X_SNAPSYNC_FILE_KEY", "x-snapsync-file-key".constant())
    }
}
