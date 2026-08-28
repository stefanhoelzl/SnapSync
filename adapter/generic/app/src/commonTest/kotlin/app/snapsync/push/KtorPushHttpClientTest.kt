package app.snapsync.push

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Ktor adapter behind the `PushHttpClient` port (its one policy consumer, `PushRegistration`,
 * lives in `:domain`'s `feature/push` and is tested there over a fake port; the status-code mapping is
 * this adapter's own contract). `EventNotifier` was the second consumer until the versioned device API
 * dropped the notify route — the manifest write is the announcement now.
 */
class KtorPushHttpClientTest {

    @Test
    fun put_maps_2xx_to_success() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Created) }
        val res = KtorPushHttpClient(HttpClient(engine)).put("https://e/devices/x", "{}")
        assertTrue(res.isSuccess)
    }

    @Test
    fun put_maps_non_2xx_to_failure() = runTest {
        val engine = MockEngine { respond("nope", HttpStatusCode.InternalServerError) }
        val res = KtorPushHttpClient(HttpClient(engine)).put("https://e/devices/x", "{}")
        assertTrue(res.isFailure)
    }

    @Test
    fun post_maps_2xx_to_success_and_non_2xx_to_failure() = runTest {
        val ok = MockEngine { respond("", HttpStatusCode.Accepted) }
        assertTrue(KtorPushHttpClient(HttpClient(ok)).post("https://e/events/x/notify").isSuccess)
        val bad = MockEngine { respond("nope", HttpStatusCode.BadGateway) }
        assertTrue(KtorPushHttpClient(HttpClient(bad)).post("https://e/events/x/notify").isFailure)
    }
}
