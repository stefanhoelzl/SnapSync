package app.snapsync.membership

import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The iOS HTTP client the backend port runs over.
 *
 * **A transport failure propagates.** `HttpBackend` turns it into `Reply.Unreachable`, which every service above
 * it keeps apart from an answer — `DownloadController.reconcile` keeps last-good state on a failure by contract; a
 * swallowed error would instead present an empty union as the truth.
 *
 * No server is involved: the request is aimed at a closed local port, which refuses immediately.
 *
 * The credential's rules — read per call, a missing token still sent, a transport failure never a rejection — are
 * the authenticated backend's now, asserted in `:domain:services`' `CredentialedBackendTest` on every target.
 */
class DarwinHttpClientTest {

    private val refusedUrl = "http://127.0.0.1:1/api/v1/events"

    @Test
    fun `a transport failure propagates rather than being swallowed`() {
        val client = darwinHttpClient()

        assertFailsWith<Throwable> { runBlocking { client.get(refusedUrl) } }
    }
}
