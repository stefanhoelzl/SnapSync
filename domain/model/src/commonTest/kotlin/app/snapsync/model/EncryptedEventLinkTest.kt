package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An encrypted event's invite (the encrypted file format, `docs/architecture.md`): its key rides in the path form's
 * fragment as `k`, which no request carries, and a plain event's invite is byte-identical to what every installed build
 * reads. A key reaches no log or report.
 */
class EncryptedEventLinkTest {

    private val eventId = "7a3f9c21-0000-4000-8000-000000000001"
    private val key = ByteArray(32) { (it * 5).toByte() }
    private val text = encodeEventKey(key)

    @Test
    fun `an encrypted event's invite is the path form carrying its key`() {
        val url = encodeEventUrl(EventLinkPayload(eventId, key = text))
        assertEquals("$LINK_ORIGIN/join/$eventId#k=$text", url)
        val decoded = assertIs<ConfigDecodeResult.Success>(decodeEventUrl(url)).payload
        assertEquals(eventId, decoded.eventId)
        assertContentEquals(key, decodeEventKey(decoded.key!!))
    }

    @Test
    fun `a plain event's invite is unchanged and carries no key`() {
        val url = encodeEventUrl(EventLinkPayload(eventId))
        assertTrue(url.startsWith("$LINK_ORIGIN/join#v=3&d="), url)
        assertNull(assertIs<ConfigDecodeResult.Success>(decodeEventUrl(url)).payload.key)
        assertNull(assertIs<ConfigDecodeResult.Success>(decodeEventUrl("$LINK_ORIGIN/join/$eventId")).payload.key)
    }

    @Test
    fun `a k that is not a 32-byte key fails the link`() {
        for (bad in listOf(text.dropLast(1), "$text=", text + "A", "!!" + text.drop(2), "")) {
            assertIs<ConfigDecodeResult.Failure>(decodeEventUrl("$LINK_ORIGIN/join/$eventId#k=$bad"), bad)
        }
        assertNull(decodeEventKey(encodeEventKey(ByteArray(16))))
    }

    @Test
    fun `no key survives a log line or a report or the dump`() {
        val line = "onOpenUrl url=$LINK_ORIGIN/join/$eventId#k=$text&autoJoin=true"
        val scrubbed = redactEventKeys(line)
        assertTrue(text !in scrubbed && REDACTED_KEY in scrubbed, scrubbed)
        assertEquals("no key here", redactEventKeys("no key here"))
        val dump = diagnosticDumpEvent(
            DiagnosticDump(
                note = "k=$text? #k=$text",
                state = mapOf("invite" to "#k=$text"),
                ledger = emptyMap(),
                appLog = line,
                extensionLog = "",
            ),
        )
        val everything = dump.message + dump.contexts.values.flatMap { it.values }.joinToString()
        assertTrue(text !in everything, "the dump keeps ids, never keys")
        assertTrue(eventId in everything, "ids stay intact in the dump")
    }
}
