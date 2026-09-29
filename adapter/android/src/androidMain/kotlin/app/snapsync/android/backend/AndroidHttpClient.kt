package app.snapsync.android.backend

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout

/**
 * The per-request ceiling, as on iOS (`darwinHttpClient`, which carries the measurement): 5 s sits well above the
 * slowest real answer, so a request reported longer is a process that was not running, and a fast failure costs a
 * retry, never correctness.
 */
private const val REQUEST_TIMEOUT_MILLIS = 5_000L

/**
 * The Android HTTP client the backend port runs over: Ktor's OkHttp engine — the platform's TLS and certificate
 * store, HTTP/2 and connection pooling, the stack Android apps' background work is proven on.
 *
 * What this file owns is the engine and the timeout. The routes, the declared build version and the bearer header are
 * `HttpBackend`'s, identical on every platform; what an answer means is the authenticated backend's.
 */
fun androidHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) { requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS }
}
