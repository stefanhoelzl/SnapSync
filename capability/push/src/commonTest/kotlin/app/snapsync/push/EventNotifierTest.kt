package app.snapsync.push

import app.snapsync.ports.PushHttpClient

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class RecordingClient(private val result: Result<Unit> = Result.success(Unit)) : PushHttpClient {
    val posts = mutableListOf<String>()
    override suspend fun put(url: String, jsonBody: String): Result<Unit> = error("unused")
    override suspend fun post(url: String): Result<Unit> {
        posts.add(url)
        return result
    }
}

class EventNotifierTest {

    private val eventId = "7a3f9c21-0000-4000-8000-000000000001"

    @Test
    fun notify_posts_the_notify_route_with_no_body() = runTest {
        val client = RecordingClient()
        EventNotifier(client, "https://edge.example").notify(eventId)
        assertEquals(listOf("https://edge.example/events/$eventId/notify"), client.posts)
    }

    @Test
    fun trailing_slash_on_host_is_normalized() = runTest {
        val client = RecordingClient()
        EventNotifier(client, "https://edge.example/").notify(eventId)
        assertEquals("https://edge.example/events/$eventId/notify", client.posts.single())
    }

    @Test
    fun a_failed_notify_is_absorbed_not_thrown() = runTest {
        val client = RecordingClient(Result.failure(RuntimeException("boom")))
        // Must not throw — a failed notify is best-effort; recipients' foreground discovery backstops it.
        EventNotifier(client, "https://edge.example").notify(eventId)
        assertEquals(1, client.posts.size)
    }

    @Test
    fun notify_carries_no_token_or_payload() = runTest {
        // The seam's post takes only a URL — there is no body or auth argument to carry.
        val client = RecordingClient()
        EventNotifier(client, "https://edge.example").notify(eventId)
        assertTrue(client.posts.single().endsWith("/events/$eventId/notify"))
    }
}
