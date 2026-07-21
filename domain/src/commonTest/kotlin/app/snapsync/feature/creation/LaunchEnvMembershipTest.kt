@file:OptIn(ExperimentalEncodingApi::class)

package app.snapsync.feature.creation

import app.snapsync.ports.CreateOutcome
import app.snapsync.ports.EventCreation
import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchEnvMembershipTest {

    private fun b64(json: String): String =
        Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT).encode(json.encodeToByteArray())

    /** A client that always mints, recording a "create" marker so create's ordering is observable. */
    private class FakeClient(private val events: MutableList<String>) : EventCreation {
        override suspend fun create(name: String, startsAt: String): CreateOutcome {
            events += "create"
            return CreateOutcome.Created(eventId = "11111111-1111-4111-8111-111111111111")
        }
    }

    private val silentLog = Logger(StaticConfig(logWriterList = emptyList()), tag = "test")

    private fun coordinator(events: MutableList<String>): LaunchEnvMembership =
        LaunchEnvMembership(
            headlessCreate = HeadlessCreate(FakeClient(events), silentLog, now = { "2026-07-14T18:00:00Z" }),
            log = silentLog,
            leave = { events += "leave" },
            ensureAttested = { events += "attest" },
        )

    @Test
    fun `all three triggers apply in leave then create then link order`() {
        val events = mutableListOf<String>()
        val opened = mutableListOf<String>()
        runTest {
            coordinator(events).run(
                leaveRequested = true,
                // autoJoin so create forwards a link through openUrl.
                createEvent = b64("""{"name":"Party","autoJoin":true}"""),
                eventLink = "https://x/join#raw",
                openUrl = { opened += it },
            )
        }
        // attest precedes create; leave precedes both; the create's synth link precedes the raw link.
        assertEquals(listOf("leave", "attest", "create"), events)
        assertEquals(2, opened.size)
        assertEquals("https://x/join#raw", opened.last()) // the raw event link is applied LAST
    }

    @Test
    fun `no triggers set does nothing`() {
        val events = mutableListOf<String>()
        val opened = mutableListOf<String>()
        runTest {
            coordinator(events).run(false, null, null) { opened += it }
        }
        assertEquals(emptyList(), events)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `leave-only leaves and does not create or open`() {
        val events = mutableListOf<String>()
        val opened = mutableListOf<String>()
        runTest {
            coordinator(events).run(leaveRequested = true, createEvent = null, eventLink = null) { opened += it }
        }
        assertEquals(listOf("leave"), events)
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `mint-only create attests and mints but opens no url`() {
        val events = mutableListOf<String>()
        val opened = mutableListOf<String>()
        runTest {
            coordinator(events).run(
                leaveRequested = false,
                createEvent = b64("""{"name":"Party"}"""), // no autoJoin
                eventLink = null,
                openUrl = { opened += it },
            )
        }
        assertEquals(listOf("attest", "create"), events)
        assertEquals(emptyList(), opened) // mint-only forwards no join link
    }

    @Test
    fun `a malformed create payload is skipped without attest or mint`() {
        val events = mutableListOf<String>()
        val opened = mutableListOf<String>()
        runTest {
            coordinator(events).run(
                leaveRequested = false,
                createEvent = "!!!not base64!!!",
                eventLink = null,
                openUrl = { opened += it },
            )
        }
        assertEquals(emptyList(), events) // decode failed before attest/create
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `event-link-only opens the raw url and does not create`() {
        val events = mutableListOf<String>()
        val opened = mutableListOf<String>()
        runTest {
            coordinator(events).run(false, null, "https://x/join#raw") { opened += it }
        }
        assertEquals(emptyList(), events) // no leave, no attest, no create
        assertEquals(listOf("https://x/join#raw"), opened)
    }
}
