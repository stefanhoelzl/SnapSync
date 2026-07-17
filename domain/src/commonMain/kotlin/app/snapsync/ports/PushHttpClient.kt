package app.snapsync.ports

/**
 * The minimal HTTP seam the push capability uses: `PUT` a JSON body (registration) and a bodyless
 * `POST` (the event notify, capability `event-notify-endpoint`). The real implementation
 * ([KtorPushHttpClient]) wraps the shared Ktor/Darwin client injected by the composition root; tests
 * inject a fake. Errors are returned as a failed [Result], never thrown.
 */
interface PushHttpClient {
    suspend fun put(url: String, jsonBody: String): Result<Unit>

    /** `POST` [url] with no request body (the notify endpoint takes no payload and no token). */
    suspend fun post(url: String): Result<Unit>
}
